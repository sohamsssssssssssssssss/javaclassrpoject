package com.jarvis.api;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public interface FileMutationService {
    /**
     * Applies a single planned file operation and records an undo journal row
     * for it under {@code requestId} when the operation is genuinely
     * reversible.
     *
     * <p>Targets are never overwritten: an existing target fails with
     * {@link ErrorCode#TARGET_EXISTS}. Operations outside the configured
     * scope fail with {@link ErrorCode#ACCESS_DENIED} before anything is
     * touched. JARVIS never deletes files.</p>
     */
    MutationReceipt.Entry apply(
            MutationKind kind,
            Path source,
            Path target,
            UUID requestId,
            CancellationToken cancellation) throws ServiceException;
}
