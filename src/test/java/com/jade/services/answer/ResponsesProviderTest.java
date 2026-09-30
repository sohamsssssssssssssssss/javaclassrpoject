package com.jade.services.answer;
import com.jade.api.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class ResponsesProviderTest {
    private static final String KEY = "fake-response-test-secret";
    private static final String MODEL = "injected-test-model";
    private final ObjectMapper json = new ObjectMapper();
    private KnowledgeAnswerProvider.Request request() { return new KnowledgeAnswerProvider.Request(new GeneralQuestion("what is recursion?"),List.of()); }
    private byte[] response(String text) throws Exception {
        return json.writeValueAsBytes(Map.of("status","completed","model","returned-model","output",List.of(Map.of("type","message","role","assistant","content",List.of(Map.of("type","output_text","text",text))))));
    }
    private JsonNode body(java.net.http.HttpRequest http) throws Exception {
        var done = new CompletableFuture<byte[]>(); var out = new java.io.ByteArrayOutputStream();
        http.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { byte[] bytes=new byte[buffer.remaining()]; buffer.get(bytes); out.writeBytes(bytes); }
            public void onError(Throwable error) { done.completeExceptionally(error); }
            public void onComplete() { done.complete(out.toByteArray()); }
        });
        return json.readTree(done.get(2,TimeUnit.SECONDS));
    }
    @Test void normalSerializationContainsOnlyQuestionAndFixedInstructions() throws Exception {
        var provider = new OpenAiKnowledgeProvider(KEY,MODEL,(http,t) -> {
            try {
                assertEquals("https://api.openai.com/v1/responses",http.uri().toString());
                var sent=body(http); assertEquals("what is recursion?",sent.path("input").asText());
                assertEquals(MODEL,sent.path("model").asText()); assertFalse(sent.has("tools")); assertFalse(sent.path("store").asBoolean());
                Set<String> fields=new HashSet<>(); sent.fieldNames().forEachRemaining(fields::add);
                assertEquals(Set.of("model","instructions","input","store","max_output_tokens"),fields);
                String serialized=sent.toString();
                for (String privateValue : List.of(KEY,"/Users/","selectedFile","searchScope","history","projectSource","OPENAI_API_KEY","environment")) assertFalse(serialized.contains(privateValue),privateValue);
                return response("Recursion is self-reference.");
            } catch (Exception e) { throw new AssertionError(e); }
        });
        var result=provider.answer(request(),CancellationToken.NONE);
        assertEquals("returned-model",result.model()); assertEquals("Recursion is self-reference.",result.text()); assertTrue(result.sources().isEmpty());
    }
    @Test void researchUsesOneRequiredToolAndVerifiedDeduplicatedSources() throws Exception {
        var calls=new AtomicInteger();
        var provider=new OpenAiKnowledgeProvider(KEY,MODEL,(http,t) -> {
            try {
                calls.incrementAndGet(); var sent=body(http);
                assertEquals("web_search",sent.path("tools").path(0).path("type").asText());
                assertEquals("required",sent.path("tool_choice").asText()); assertEquals(1,sent.path("max_tool_calls").asInt());
                return ("{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\"},{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Current answer. https://invented.example/ignored\",\"annotations\":[{\"type\":\"url_citation\",\"title\":\"OpenJDK\",\"url\":\"https://example.org/java\"},{\"type\":\"url_citation\",\"title\":\"Duplicate\",\"url\":\"https://example.org/java#section\"},{\"type\":\"url_citation\",\"title\":\"Unsafe\",\"url\":\"file:///tmp/private\"}]}]},{\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"sources\":[{\"title\":\"Second source\",\"url\":\"https://example.org/second\"}]}}]}").getBytes(StandardCharsets.UTF_8);
            } catch (Exception e) { throw new AssertionError(e); }
        });
        var service=new ResearchAnswerService(provider,provider);
        var result=service.answer(new AnswerRequest(new GeneralQuestion("what is the latest stable Java release?")),CancellationToken.NONE);
        assertEquals(1,calls.get()); assertEquals(AnswerStatus.ANSWERED,result.status()); assertEquals(AnswerMode.WEB_RESEARCH,result.mode());
        assertEquals(List.of("https://example.org/java","https://example.org/second"),result.evidence().stream().map(AnswerResult.Evidence::reference).toList());
    }
    @Test void parsesAllAssistantTextAndIgnoresOtherOutput() throws Exception {
        byte[] bytes="{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"arguments\":\"rm -rf /\"},{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"First paragraph.\"},{\"type\":\"output_text\",\"text\":\"Second paragraph.\"}]}]}".getBytes();
        assertEquals("First paragraph.\n\nSecond paragraph.",new OpenAiKnowledgeProvider(KEY,MODEL,(h,t)->bytes).answer(request(),CancellationToken.NONE).text());
    }
    @Test void malformedEmptyAndFailedResponsesAreHonest() {
        for(String body:List.of("{","{}","{\"status\":\"failed\",\"error\":{\"message\":\""+KEY+"\"}}","{\"status\":\"completed\",\"output\":[]}")) {
            var error=assertThrows(ServiceException.class,()->new OpenAiKnowledgeProvider(KEY,MODEL,(h,t)->body.getBytes()).answer(request(),CancellationToken.NONE));
            assertFalse(error.toString().contains(KEY)); assertNull(error.getCause());
        }
    }
    @Test void oversizedAndIncompleteAnswersAreTruthfullyTruncated() throws Exception {
        String large="A complete sentence. ".repeat(1000);
        var result=new OpenAiKnowledgeProvider(KEY,MODEL,(h,t)-> { try {return response(large);}catch(Exception e){throw new AssertionError(e);} }).answer(request(),CancellationToken.NONE);
        assertTrue(result.text().length()<=12000); assertTrue(result.text().endsWith("[Answer truncated]"));
        byte[] incomplete="{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Partial sentence\"}]}]}".getBytes();
        assertTrue(new OpenAiKnowledgeProvider(KEY,MODEL,(h,t)->incomplete).answer(request(),CancellationToken.NONE).text().endsWith("[Answer truncated]"));
    }
    @Test void missingKeyAndUnsupportedConfigurationNeverStartNetwork() throws Exception {
        for (String key : Arrays.asList(null,""," ")) {
            var result=AnswerConfiguration.configured(key,"openai",MODEL,"","").answer(new AnswerRequest(request().question()),CancellationToken.NONE);
            assertEquals(AnswerStatus.UNAVAILABLE,result.status()); assertFalse(result.answerText().contains("OPENAI_API_KEY"));
        }
        assertEquals(AnswerStatus.UNAVAILABLE,AnswerConfiguration.configured(KEY,"unsupported",MODEL,"","").answer(new AnswerRequest(request().question()),CancellationToken.NONE).status());
    }
    @Test void knownErrorsAreUsefulAndUnknownErrorsAreSanitized() throws Exception {
        for (int status:List.of(401,429,500)) {
            var service=new ResearchAnswerService(new OpenAiKnowledgeProvider(KEY,MODEL,(h,t)->{throw ProviderHttp.statusFailure(status);}),null);
            var result=service.answer(new AnswerRequest(request().question()),CancellationToken.NONE);
            assertEquals(AnswerStatus.FAILED,result.status()); assertEquals(ProviderHttp.statusFailure(status).error().message(),result.answerText()); assertFalse(result.toString().contains(KEY));
        }
        var timeout=new OpenAiKnowledgeProvider(KEY,MODEL,(h,t)->{throw ProviderHttp.failure("The answer request timed out.");});
        assertEquals("The answer request timed out.",assertThrows(ServiceException.class,()->timeout.answer(request(),CancellationToken.NONE)).getMessage());
    }
    @Test void researchWithoutSearchMetadataCannotPretendFreshness() throws Exception {
        var provider=new OpenAiKnowledgeProvider(KEY,MODEL,(h,t)-> {try{return response("Current answer from memory.");}catch(Exception e){throw new AssertionError(e);}});
        var result=new ResearchAnswerService(provider,provider).answer(new AnswerRequest(new GeneralQuestion("latest Java release")),CancellationToken.NONE);
        assertEquals(AnswerStatus.FAILED,result.status()); assertTrue(result.evidence().isEmpty());
    }
    @Test void progressIsContextualAndCancellationStopsBeforeSynthesis() throws Exception {
        List<String> progress=new ArrayList<>(); var cancel=new AtomicBoolean();
        var service=new ResearchAnswerService((r,t)->{fail("Synthesis after cancellation");return null;},(q,t)->{cancel.set(true);return List.of();});
        assertThrows(ServiceException.class,()->service.answer(new AnswerRequest(new GeneralQuestion("latest Java release")),cancel::get,progress::add));
        assertEquals(List.of("Searching the web…"),progress);
    }
}
