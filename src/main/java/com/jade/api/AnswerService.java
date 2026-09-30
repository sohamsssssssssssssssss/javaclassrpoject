package com.jade.api;

/** Knowledge-only seam. Receives no gateway, filesystem, project or execution capability. */
@FunctionalInterface
public interface AnswerService {
    AnswerResult answer(AnswerRequest request, CancellationToken cancellation) throws ServiceException;
    default AnswerResult answer(AnswerRequest request, CancellationToken cancellation,
                               java.util.function.Consumer<String> progress) throws ServiceException {
        return answer(request, cancellation);
    }
}
