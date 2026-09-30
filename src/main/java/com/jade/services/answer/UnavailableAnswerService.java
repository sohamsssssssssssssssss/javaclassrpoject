package com.jade.services.answer;

import com.jade.api.*;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Offline Loop 1 provider: proves routing without pretending to answer. */
public final class UnavailableAnswerService implements AnswerService {
    @Override
    public AnswerResult answer(AnswerRequest request, CancellationToken cancellation) throws ServiceException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancellationRequested()) {
            throw new ServiceException(new StructuredError(ErrorCode.CANCELLED,
                    "Question cancelled", Optional.empty()));
        }
        return new AnswerResult("Knowledge answering is not configured yet.",
                AnswerStatus.UNAVAILABLE, List.of(), false);
    }
}
