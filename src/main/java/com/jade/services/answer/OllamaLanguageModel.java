package com.jade.services.answer;
import com.jade.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;
/** Local Ollama protocol, never a cloud endpoint. No pull, shell, or tool requests. */
public final class OllamaLanguageModel implements LocalLanguageModel {
    private final URI endpoint;
    private final String model;
    private final ProviderHttp.Transport transport;
    public OllamaLanguageModel(URI endpoint, String model) { this(endpoint, model, ProviderHttp::send); }
    OllamaLanguageModel(URI endpoint, String model, ProviderHttp.Transport transport) {
        Objects.requireNonNull(endpoint); this.model = Objects.requireNonNull(model); this.transport = transport;
        if (!List.of("http", "https").contains(endpoint.getScheme()) || !List.of("127.0.0.1", "[::1]", "::1").contains(endpoint.getHost())
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !List.of("", "/").contains(endpoint.getPath())) throw new IllegalArgumentException("Local endpoint must use a literal loopback address");
        if (!model.matches("[A-Za-z0-9._:/-]{1,128}") || model.contains("/") || model.toLowerCase(Locale.ROOT).contains("cloud"))
            throw new IllegalArgumentException("Configure a local model name, not a cloud model");
        this.endpoint = endpoint.resolve("/api/generate");
    }
    public Response generate(Request request, CancellationToken token) throws ServiceException {
        ResearchAnswerService.check(token); long start = System.nanoTime();
        try {
            var json = new ObjectMapper();
            // Verify locally stored model metadata before sending a question; cloud aliases fail closed.
            var show = HttpRequest.newBuilder(endpoint.resolve("/api/show")).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("model", model, "verbose", false)))).build();
            byte[] metadata = transport.send(show, token);
            if (metadata.length > ProviderHttp.MAX_BYTES) throw new IllegalArgumentException();
            var info = json.readTree(metadata);
            if (info.has("remote_host") || info.has("remote_model") || info.path("model_info").path("general.architecture").asText().isBlank())
                throw ProviderHttp.localUnavailable();
            ResearchAnswerService.check(token);
            var body = Map.of("model", model, "system", request.systemInstruction(), "prompt", request.userPrompt(),
                    "stream", false, "options", Map.of("num_predict", request.maxOutputTokens(), "num_ctx", 8192));
            var http = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            byte[] bytes = transport.send(http, token);
            if (bytes.length > ProviderHttp.MAX_BYTES) throw new IllegalArgumentException();
            var response = json.readTree(bytes);
            if (!response.path("done").asBoolean() || !response.path("response").isTextual() || response.has("error")
                    || response.has("remote_host") || response.has("remote_model")) throw new IllegalArgumentException();
            String text = response.path("response").asText().strip();
            String usedModel = response.path("model").asText(model);
            if (usedModel.toLowerCase(Locale.ROOT).contains("cloud")) throw new IllegalArgumentException();
            if (text.length() > 11940 || response.path("done_reason").asText().equals("length")) {
                int end = Math.min(text.length(), 11940); int sentence = text.lastIndexOf('.', end-1);
                if (sentence > end/2) end = sentence+1;
                else if (end < text.length() && end > 0 && Character.isHighSurrogate(text.charAt(end-1))) end--;
                text = text.substring(0, end).stripTrailing() + "\n\n[Answer truncated]";
            }
            ResearchAnswerService.check(token);
            return new Response(text, usedModel, (System.nanoTime()-start)/1_000_000, AnswerStatus.ANSWERED);
        } catch (ServiceException failure) {
            if (failure.error().code() == ErrorCode.CANCELLED) throw failure;
            throw ProviderHttp.localUnavailable();
        } catch (Exception invalidResponse) { throw ProviderHttp.invalidResponse(); }
    }
}
