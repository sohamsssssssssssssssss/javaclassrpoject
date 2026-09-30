package com.jade.services.answer;
import com.jade.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
public final class BraveWebSearchProvider implements WebSearchProvider {
    private final String key;
    private final ProviderHttp.Transport transport;
    public BraveWebSearchProvider(String key) { this(key, ProviderHttp::send); }
    BraveWebSearchProvider(String key, ProviderHttp.Transport transport) { this.key = Objects.requireNonNull(key); this.transport = transport; if (key.isBlank()) throw new IllegalArgumentException("Missing search credential"); }
    public List<Hit> search(Query query, CancellationToken token) throws ServiceException {
        ResearchAnswerService.check(token);
        try {
            var uri = URI.create("https://api.search.brave.com/res/v1/web/search?q="
                    + URLEncoder.encode(query.text(), StandardCharsets.UTF_8) + "&count=" + query.maxResults());
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                    .header("X-Subscription-Token", key).header("Accept", "application/json").GET().build();
            byte[] bytes = transport.send(request, token);
            if (bytes.length > ProviderHttp.MAX_BYTES) throw new IllegalArgumentException();
            var results = new ObjectMapper().readTree(bytes).path("web").path("results");
            if (!results.isArray()) throw new IllegalArgumentException();
            List<Hit> hits = new ArrayList<>();
            for (var result : results) {
                if (hits.size() == query.maxResults()) break;
                try {
                    hits.add(new Hit(result.path("title").asText().replace(key, "[redacted]"),
                            URI.create(result.path("url").asText().replace(key, "REDACTED")),
                            result.path("description").asText().replace(key, "[redacted]")));
                } catch (IllegalArgumentException invalidHit) { /* Unsafe sources are not evidence. */ }
            }
            ResearchAnswerService.check(token); return List.copyOf(hits);
        } catch (ServiceException failure) {
            if (failure.error().code() == ErrorCode.CANCELLED) throw failure;
            throw ProviderHttp.failure();
        } catch (Exception ignored) { throw ProviderHttp.failure(); }
    }
}
