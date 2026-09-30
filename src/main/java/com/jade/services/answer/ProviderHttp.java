package com.jade.services.answer;
import com.jade.api.*;
import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
/** Fixed HTTPS adapters share bounded transport; redirects are never followed. */
final class ProviderHttp {
    static final int MAX_BYTES = 262144;
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new java.net.ProxySelector() {
                public List<java.net.Proxy> select(java.net.URI uri) { return List.of(java.net.Proxy.NO_PROXY); }
                public void connectFailed(java.net.URI uri, java.net.SocketAddress address, java.io.IOException failure) { }
            }).build();
    @FunctionalInterface interface Transport {
        byte[] send(HttpRequest request, CancellationToken token) throws ServiceException;
    }
    static byte[] send(HttpRequest request, CancellationToken token) throws ServiceException {
        ResearchAnswerService.check(token);
        var future = CLIENT.sendAsync(request, info -> new LimitedBody());
        long seconds = Math.min(60, Math.max(1, request.timeout().orElse(Duration.ofSeconds(30)).toSeconds()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        try {
            while (true) {
                ResearchAnswerService.check(token);
                if (System.nanoTime() > deadline) throw new TimeoutException();
                try {
                    var response = future.get(50, TimeUnit.MILLISECONDS);
                    if (response.statusCode() < 200 || response.statusCode() >= 300) throw statusFailure(response.statusCode());
                    return response.body();
                } catch (TimeoutException pending) {
                    if (System.nanoTime() > deadline) throw pending;
                }
            }
        } catch (ServiceException cancellation) { throw cancellation;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); ResearchAnswerService.check(token); throw failure();
        } catch (TimeoutException timeout) { throw failure("The answer request timed out.");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof HttpTimeoutException) throw failure("The answer request timed out.");
            throw failure("JADE could not reach the answer provider.");
        } catch (Exception ignored) { throw failure("JADE could not reach the answer provider.");
        } finally { if (!future.isDone()) future.cancel(true); }
    }
    private static final Set<String> SAFE_MESSAGES = Set.of("Remote provider request failed", "The answer request timed out.",
            "JADE could not reach the answer provider.", "JADE could not authenticate with the configured answer provider.",
            "The answer provider temporarily rate-limited the request.", "The answer provider is temporarily unavailable.",
            "The answer provider returned an invalid response.", "Web research returned no verified sources.",
            "Local intelligence is unavailable. Start the configured local runtime and ensure the model is installed.",
            "No usable public evidence is available. Broad web search is not configured; use a supported official URL or configure optional search.");
    static boolean isSafe(ServiceException error) { return SAFE_MESSAGES.contains(error.error().message()); }
    static ServiceException statusFailure(int status) {
        return failure(switch (status) {
            case 401, 403 -> "JADE could not authenticate with the configured answer provider.";
            case 429 -> "The answer provider temporarily rate-limited the request.";
            default -> status >= 500 ? "The answer provider is temporarily unavailable." : "Remote provider request failed";
        });
    }
    static ServiceException localUnavailable() {
        return new ServiceException(new StructuredError(ErrorCode.MISSING_DEPENDENCY,
                "Local intelligence is unavailable. Start the configured local runtime and ensure the model is installed.", Optional.empty()));
    }
    static ServiceException retrievalUnavailable() {
        return new ServiceException(new StructuredError(ErrorCode.MISSING_DEPENDENCY,
                "No usable public evidence is available. Broad web search is not configured; use a supported official URL or configure optional search.", Optional.empty()));
    }
    static ServiceException invalidResponse() { return failure("The answer provider returned an invalid response."); }
    static ServiceException failure() { return failure("Remote provider request failed"); }
    static ServiceException failure(String message) {
        // No remote error bodies, headers, credentials, or exception causes escape this boundary.
        return new ServiceException(new StructuredError(ErrorCode.SERVICE_FAILURE, message, Optional.empty()));
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> done = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return done; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                    subscription.cancel(); done.completeExceptionally(new IllegalStateException("Response size exceeded")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable ignored) { done.completeExceptionally(new IllegalStateException("Response failed")); }
        public void onComplete() { done.complete(bytes.toByteArray()); }
    }
}
