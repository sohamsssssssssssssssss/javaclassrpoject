package com.jade.services.answer;
import com.jade.api.*;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;
class RemoteProviderTest {
    private static final String SECRET = "fake-test-secret";
    private KnowledgeAnswerProvider.Request request() { return new KnowledgeAnswerProvider.Request(new GeneralQuestion("what is recursion"),List.of()); }
    @Test void openAiRequestAndRedaction() throws Exception {
        var provider = new OpenAiKnowledgeProvider(SECRET,"test-model",(http,t) -> {
            assertEquals("https",http.uri().getScheme()); assertEquals("api.openai.com",http.uri().getHost());
            assertEquals("Bearer " + SECRET,http.headers().firstValue("Authorization").orElseThrow());
            assertTrue(http.timeout().isPresent());
            return ("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Answer " + SECRET + "\"}]}]}").getBytes(StandardCharsets.UTF_8);
        });
        var answer = provider.answer(request(),CancellationToken.NONE);
        assertFalse(answer.toString().contains(SECRET)); assertTrue(answer.text().contains("[redacted]"));
    }
    @Test void errorsNeverExposeRequestContextAndResponsesAreBounded() {
        for (ProviderHttp.Transport transport : List.<ProviderHttp.Transport>of(
                (http,t) -> { throw new IllegalStateException("Bearer " + SECRET); },
                (http,t) -> "not JSON".getBytes(),
                (http,t) -> new byte[ProviderHttp.MAX_BYTES + 1],
                (http,t) -> "{\"choices\":[{\"message\":{\"content\":\"\"}}]}".getBytes())) {
            var error = assertThrows(ServiceException.class,() -> new OpenAiKnowledgeProvider(SECRET,"test",transport).answer(request(),CancellationToken.NONE));
            assertFalse(error.toString().contains(SECRET)); assertNull(error.getCause());
        }
    }
    @Test void braveQueryAndSourcesAreBounded() throws Exception {
        var provider = new BraveWebSearchProvider(SECRET,(http,t) -> {
            assertEquals("api.search.brave.com",http.uri().getHost()); assertTrue(http.uri().getRawQuery().contains("count=5"));
            return "{\"web\":{\"results\":[{\"title\":\"Unsafe\",\"url\":\"file:///tmp/private\"},{\"title\":\"Java\",\"url\":\"https://example.org/a\",\"description\":\"public evidence\"}]}}".getBytes();
        });
        var hits = provider.search(new WebSearchProvider.Query("latest Java release",5),CancellationToken.NONE);
        assertEquals(1,hits.size()); assertEquals("https://example.org/a",hits.getFirst().uri().toString());
    }
    @Test void bodySubscriberCancelsBeforeUnboundedAccumulation() {
        var subscriber = new ProviderHttp.LimitedBody(); var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        subscriber.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() { cancelled.set(true); } });
        subscriber.onNext(List.of(ByteBuffer.allocate(ProviderHttp.MAX_BYTES + 1)));
        assertTrue(cancelled.get()); assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void transportRejectsErrorsRedirectsAndCancelsPendingHttp() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        for (int code : List.of(401, 429, 500)) server.createContext("/error" + code, exchange -> { exchange.sendResponseHeaders(code, -1); exchange.close(); });
        server.createContext("/timeout", exchange -> { try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } exchange.close(); });
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().set("Location", "file:///tmp/private"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.createContext("/slow", exchange -> { entered.countDown(); try { release.await(3,java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } exchange.close(); });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            for (String path : List.of("/error401", "/error429", "/error500", "/redirect"))
                assertThrows(ServiceException.class, () -> ProviderHttp.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path)).GET().build(),CancellationToken.NONE));
            assertEquals("The answer request timed out.", assertThrows(ServiceException.class,
                    () -> ProviderHttp.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/timeout")).timeout(java.time.Duration.ofSeconds(1)).GET().build(), CancellationToken.NONE)).getMessage());
            var cancellation = new java.util.concurrent.atomic.AtomicBoolean();
            var pending = java.util.concurrent.CompletableFuture.supplyAsync(() -> assertThrows(ServiceException.class,
                    () -> ProviderHttp.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/slow")).GET().build(), cancellation::get)));
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)); cancellation.set(true);
            assertEquals(ErrorCode.CANCELLED,pending.get(2,java.util.concurrent.TimeUnit.SECONDS).error().code());
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void cancelledAdaptersNeverCallTransport() {
        ProviderHttp.Transport transport = (http,t) -> { fail("Transport after cancellation"); return null; };
        assertThrows(ServiceException.class,() -> new OpenAiKnowledgeProvider(SECRET,"test",transport).answer(request(),() -> true));
        assertThrows(ServiceException.class,() -> new BraveWebSearchProvider(SECRET,transport).search(new WebSearchProvider.Query("Java",5),() -> true));
    }
}
