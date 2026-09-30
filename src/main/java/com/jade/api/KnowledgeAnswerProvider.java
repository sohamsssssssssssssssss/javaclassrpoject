package com.jade.api;
import java.util.List;
import java.util.Objects;
/** Text-only provider: no local context or action capabilities. */
@FunctionalInterface
public interface KnowledgeAnswerProvider {
    Answer answer(Request request, CancellationToken cancellation) throws ServiceException;
    record Request(GeneralQuestion question, List<WebSearchProvider.Hit> evidence) {
        public Request {
            Objects.requireNonNull(question); evidence = List.copyOf(evidence);
            if (evidence.size() > 5 || evidence.stream().mapToInt(h -> h.title().length() + h.uri().toString().length() + h.snippet().length()).sum() > 12000)
                throw new IllegalArgumentException("Evidence size exceeded");
        }
    }
    record Answer(String text, String provider, String model, List<AnswerResult.Evidence> sources) {
        public Answer(String text, String provider, String model) { this(text, provider, model, List.of()); }
        public Answer {
            Objects.requireNonNull(text); Objects.requireNonNull(provider); Objects.requireNonNull(model);
            sources = List.copyOf(sources);
            if (sources.size() > 5) throw new IllegalArgumentException("Too many sources");
            if (text.isBlank() || text.length() > 12000) throw new IllegalArgumentException("Invalid answer size");
        }
    }
}
