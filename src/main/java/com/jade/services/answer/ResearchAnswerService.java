package com.jade.services.answer;
import com.jade.api.*;
import java.util.*;
/** One-way orchestration: public evidence and answer text never re-enter the parser. */
public final class ResearchAnswerService implements AnswerService {
    private final KnowledgeAnswerProvider knowledge;
    private final WebSearchProvider search;
    public ResearchAnswerService(KnowledgeAnswerProvider knowledge, WebSearchProvider search) {
        this.knowledge = knowledge; this.search = search;
    }
    public AnswerResult answer(AnswerRequest request, CancellationToken token) throws ServiceException {
        return answer(request, token, ignored -> { });
    }
    public AnswerResult answer(AnswerRequest request, CancellationToken token, java.util.function.Consumer<String> progress) throws ServiceException {
        long start = System.nanoTime();
        var question = request.question(); var mode = QuestionRouter.mode(question);
        check(token);
        if (question.originalText().matches("(?s).*(/Users/|/home/|file://|[A-Za-z]:\\\\).*"))
            return result(question, mode, "Local paths cannot be sent to an answer provider.", AnswerStatus.FAILED, List.of(), "", "", start);
        if (knowledge == null)
            return result(question, mode, "Local intelligence is unavailable. Configure JADE_LOCAL_MODEL and start the local model runtime.", AnswerStatus.UNAVAILABLE, List.of(), "", "", start);
        if (mode == AnswerMode.WEB_RESEARCH && search == null)
            return result(question, mode, "Web research is unavailable because no search provider is configured.", AnswerStatus.UNAVAILABLE, List.of(), "", "", start);
        try {
            progress.accept(mode == AnswerMode.WEB_RESEARCH ? "Searching the web…" : "Thinking…");
            if (mode == AnswerMode.WEB_RESEARCH) {
                var researched = search.research(question, token);
                check(token);
                if (researched.isPresent()) {
                    var answer = researched.orElseThrow();
                    if (answer.sources().isEmpty())
                        return result(question, mode, "Web research returned no verified sources.", AnswerStatus.FAILED, List.of(), "", "", start);
                    return result(question, mode, answer.text(), AnswerStatus.ANSWERED, answer.sources(), answer.provider(), answer.model(), start);
                }
            }
            List<WebSearchProvider.Hit> hits = mode == AnswerMode.WEB_RESEARCH
                    ? bounded(search.search(QuestionRouter.query(question), token)) : List.of();
            check(token);
            if (mode == AnswerMode.WEB_RESEARCH && hits.isEmpty())
                return result(question, mode, "Web research returned no usable evidence.", AnswerStatus.UNAVAILABLE, List.of(), "", "", start);
            if (mode == AnswerMode.WEB_RESEARCH) progress.accept("Synthesizing sources…");
            var answer = knowledge.answer(new KnowledgeAnswerProvider.Request(question, hits), token);
            check(token);
            return result(question, mode, answer.text(), AnswerStatus.ANSWERED,
                    hits.stream().map(h -> new AnswerResult.Evidence(h.title(), h.uri().toString())).toList(), answer.provider(), answer.model(), start);
        } catch (ServiceException failure) {
            if (failure.error().code() == ErrorCode.CANCELLED) throw failure;
            return result(question, mode, ProviderHttp.isSafe(failure) ? failure.error().message() : "The answer provider request failed. Please try again later.", failure.error().code() == ErrorCode.MISSING_DEPENDENCY ? AnswerStatus.UNAVAILABLE : AnswerStatus.FAILED, List.of(), "", "", start);
        } catch (RuntimeException failure) {
            return result(question, mode, "The answer provider returned an invalid response.", AnswerStatus.FAILED, List.of(), "", "", start);
        }
    }
    private static List<WebSearchProvider.Hit> bounded(List<WebSearchProvider.Hit> supplied) {
        List<WebSearchProvider.Hit> hits = new ArrayList<>();
        int remaining = 12000;
        for (var hit : supplied) {
            int size = hit.title().length() + hit.uri().toString().length() + hit.snippet().length();
            if (hits.size() == 5 || size > remaining) break;
            hits.add(hit); remaining -= size;
        }
        return List.copyOf(hits);
    }
    static void check(CancellationToken token) throws ServiceException {
        if (token.isCancellationRequested() || Thread.currentThread().isInterrupted())
            throw new ServiceException(new StructuredError(ErrorCode.CANCELLED, "Answer request cancelled", Optional.empty()));
    }
    private static AnswerResult result(GeneralQuestion q, AnswerMode mode, String text, AnswerStatus status,
                                      List<AnswerResult.Evidence> sources, String provider, String model, long start) {
        return new AnswerResult(text, status, sources, !sources.isEmpty(), q.originalText(), mode, provider, model,
                (System.nanoTime() - start) / 1_000_000);
    }
}
