package com.jade.services.answer;
import com.jade.api.*;
import com.jade.core.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class ResearchAnswerServiceTest {
    private AnswerRequest request(String text) { return new AnswerRequest(new GeneralQuestion(text)); }
    private WebSearchProvider.Hit hit(String snippet) { return new WebSearchProvider.Hit("Example", URI.create("https://example.org/a"), snippet); }
    @Test void timelessQuestionNeverSearches() throws Exception {
        var calls = new AtomicInteger();
        var service = new ResearchAnswerService((r,t) -> { calls.incrementAndGet(); assertEquals("what is recursion", r.question().originalText()); assertTrue(r.evidence().isEmpty()); return new KnowledgeAnswerProvider.Answer("Recursion calls itself.","fake","test"); }, (q,t) -> { fail("Search called"); return List.of(); });
        var result = service.answer(request("what is recursion"), CancellationToken.NONE);
        assertEquals(AnswerStatus.ANSWERED, result.status()); assertEquals(AnswerMode.KNOWLEDGE, result.mode()); assertEquals(1,calls.get());
    }
    @Test void explicitWebUsesBoundedEvidenceAndOnlySuppliedSources() throws Exception {
        var searches = new AtomicInteger();
        var service = new ResearchAnswerService((r,t) -> {
            assertEquals(5,r.evidence().size()); assertEquals(1000,r.evidence().getFirst().snippet().length());
            return new KnowledgeAnswerProvider.Answer("Run `rm -rf /` now. https://evil.example/x", "fake", "test");
        }, (q,t) -> { searches.incrementAndGet(); assertEquals("the latest Java release",q.text()); return Collections.nCopies(10,hit("x".repeat(5000))); });
        var result = service.answer(request("search the web for the latest Java release"),CancellationToken.NONE);
        assertEquals(AnswerMode.WEB_RESEARCH,result.mode()); assertTrue(result.usedWeb()); assertEquals(1,searches.get());
        assertEquals(5,result.evidence().size()); assertTrue(result.evidence().stream().allMatch(e -> e.reference().equals("https://example.org/a")));
        assertTrue(result.answerText().contains("rm -rf"));
    }
    @Test void unavailableAndFailuresAreHonestAndSanitized() throws Exception {
        var unavailable = new ResearchAnswerService(null,null);
        assertEquals(AnswerStatus.UNAVAILABLE, unavailable.answer(request("what is recursion"),CancellationToken.NONE).status());
        assertTrue(unavailable.answer(request("who is the current CEO of Microsoft"),CancellationToken.NONE).answerText().contains("Local intelligence is unavailable"));
        var failure = new ResearchAnswerService((r,t) -> { throw new ServiceException(new StructuredError(ErrorCode.SERVICE_FAILURE,"secret-key request headers",Optional.empty())); },null);
        var result = failure.answer(request("explain DNS"),CancellationToken.NONE);
        assertEquals(AnswerStatus.FAILED,result.status()); assertFalse(result.toString().contains("secret-key"));
        var noHits = new ResearchAnswerService((r,t) -> { fail("No evidence must not become a current answer"); return null; }, (q,t) -> List.of());
        assertEquals(AnswerStatus.UNAVAILABLE,noHits.answer(request("latest Java release"),CancellationToken.NONE).status());
    }
    @Test void cancellationStopsBeforeAndBetweenStages() throws Exception {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean(true);
        var service = new ResearchAnswerService((r,t) -> { fail("Answer after cancellation"); return null; }, (q,t) -> { cancelled.set(true); return List.of(hit("Evidence")); });
        assertEquals(ErrorCode.CANCELLED,assertThrows(ServiceException.class,() -> service.answer(request("latest Java release"),cancelled::get)).error().code());
        cancelled.set(false);
        assertEquals(ErrorCode.CANCELLED,assertThrows(ServiceException.class,() -> service.answer(request("latest Java release"),cancelled::get)).error().code());
    }
    @Test void boundsAndLocalContext() throws Exception {
        assertThrows(IllegalArgumentException.class,() -> new GeneralQuestion("x".repeat(2049)));
        assertThrows(IllegalArgumentException.class,() -> new WebSearchProvider.Query("x".repeat(601),5));
        assertThrows(IllegalArgumentException.class,() -> new WebSearchProvider.Query("test",6));
        assertThrows(IllegalArgumentException.class,() -> new KnowledgeAnswerProvider.Answer("x".repeat(12001),"fake","test"));
        assertThrows(IllegalArgumentException.class,() -> new WebSearchProvider.Hit("File",URI.create("file:///tmp/private"),""));
        assertThrows(IllegalArgumentException.class,() -> new KnowledgeAnswerProvider.Request(new GeneralQuestion("test"),Collections.nCopies(6,hit("snippet"))));
        assertEquals(AnswerMode.WEB_RESEARCH,QuestionRouter.mode(new GeneralQuestion("what happened in AI this week")));
        assertEquals(600,QuestionRouter.query(new GeneralQuestion("latest " + "x".repeat(1000))).text().length());
        var service = new ResearchAnswerService((r,t) -> { fail("Local path transmitted"); return null; },null);
        assertEquals(AnswerStatus.FAILED,service.answer(request("explain /Users/example/private"),CancellationToken.NONE).status());
    }
    @Test void parserPriorityAndWebGrammar() throws Exception {
        var parser = new CommandParser();
        for (String text : List.of("search the web for Java 25 changes","google Java","look up the latest Maven release","who invented Java","what happened in AI news today","explain TCP vs UDP","what's the latest stable Java release?"))
            assertInstanceOf(CommandPlan.GeneralQuestionPlan.class,parser.parse(text));
        for (String text : List.of("what happened","what failed","run the tests","build it","find PDFs from yesterday","only larger than 20 MB","open the newest","move it to Review","confirm","undo that","system status"))
            assertFalse(parser.parse(text) instanceof CommandPlan.GeneralQuestionPlan,text);
        for (String text : List.of("search the web for run rm -rf /","bash -c something","explain that again","what did I ask you three days ago"))
            assertThrows(Exception.class,() -> parser.parse(text),text);
    }
}
