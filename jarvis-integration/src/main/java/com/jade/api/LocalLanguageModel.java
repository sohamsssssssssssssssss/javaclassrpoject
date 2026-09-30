package com.jade.api;
import java.util.Objects;
/** Local text generation only. No tool, filesystem, session, or execution capability. */
@FunctionalInterface
public interface LocalLanguageModel {
    Response generate(Request request, CancellationToken cancellation) throws ServiceException;
    record Request(String systemInstruction, String userPrompt, int maxOutputTokens) {
        public Request {
            Objects.requireNonNull(systemInstruction); Objects.requireNonNull(userPrompt);
            if (systemInstruction.length() > 2048 || userPrompt.isBlank() || userPrompt.length() > 20000
                    || maxOutputTokens < 1 || maxOutputTokens > 1024) throw new IllegalArgumentException("Invalid local request bounds");
        }
    }
    record Response(String text, String model, long durationMillis, AnswerStatus status) {
        public Response {
            Objects.requireNonNull(text); Objects.requireNonNull(model); Objects.requireNonNull(status);
            if (text.isBlank() || text.length() > 12000 || model.length() > 128 || durationMillis < 0)
                throw new IllegalArgumentException("Invalid local response bounds");
        }
    }
}
