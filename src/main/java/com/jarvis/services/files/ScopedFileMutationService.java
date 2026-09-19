package com.jarvis.services.files;

import com.jarvis.api.CancellationToken;
import com.jarvis.api.ErrorCode;
import com.jarvis.api.FileMutationService;
import com.jarvis.api.MutationKind;
import com.jarvis.api.MutationReceipt;
import com.jarvis.api.OperationStatus;
import com.jarvis.api.RiskLevel;
import com.jarvis.api.ServiceException;
import com.jarvis.api.StructuredError;
import com.jarvis.api.UndoEntry;
import com.jarvis.api.UndoJournal;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * File mutation service confined to the configured scope roots.
 *
 * <p>Safety model (docs/PROJECT_DECISIONS.md, master plan §28/§31):</p>
 * <ul>
 *     <li>Every source and target path must resolve inside one of the
 *     configured roots; anything else fails with {@link ErrorCode#ACCESS_DENIED}
 *     before the filesystem is touched. Traversal via {@code ..} or symlinks
 *     is rejected: symlinks are never followed.</li>
 *     <li>Targets are never overwritten. An existing target fails with
 *     {@link ErrorCode#TARGET_EXISTS}.</li>
 *     <li>A directory is never moved or copied into its own descendant.</li>
 *     <li>Moves use {@link StandardCopyOption#ATOMIC_MOVE} when the
 *     filesystem supports it and fall back to a non-atomic move.</li>
 *     <li>Only genuinely reversible operations are journalled: moves and
 *     renames. Copies and folder creation are not undoable and are never
 *     recorded as such.</li>
 *     <li>JARVIS never deletes files; this service has no delete operation.</li>
 * </ul>
 *
 * <p>Undo journal writes and filesystem effects are intentionally not one
 * transaction: the journal records what happened on disk, and the receipt
 * reports per-entry truth. Journal write failures downgrade the entry to
 * FAILED so they are never silently swallowed.</p>
 */
public final class ScopedFileMutationService implements FileMutationService {
    private final List<Path> scopeRoots;
    private final UndoJournal undoJournal;
    private final Clock clock;
    private final Supplier<UUID> identifierSupplier;

    public ScopedFileMutationService(List<Path> scopeRoots, UndoJournal undoJournal) {
        this(scopeRoots, undoJournal, Clock.systemUTC(), UUID::randomUUID);
    }

    /** Test constructor: pinned clock and identifier supplier. */
    public ScopedFileMutationService(
            List<Path> scopeRoots,
            UndoJournal undoJournal,
            Clock clock,
            Supplier<UUID> identifierSupplier) {
        if (scopeRoots == null || scopeRoots.isEmpty()) {
            throw new IllegalArgumentException("at least one scope root is required");
        }
        this.scopeRoots = roots(scopeRoots);
        this.undoJournal = java.util.Objects.requireNonNull(undoJournal, "undoJournal");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.identifierSupplier = java.util.Objects.requireNonNull(identifierSupplier, "identifierSupplier");
    }

    public List<Path> scopeRoots() {
        return scopeRoots;
    }

    @Override
    public MutationReceipt.Entry apply(
            MutationKind kind,
            Path source,
            Path target,
            java.util.UUID requestId,
            CancellationToken cancellation) throws ServiceException {
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(target, "target");
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancellationRequested()) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.CANCELLED, "Operation cancelled before execution", Optional.empty()));
        }
        try {
            return switch (kind) {
                case MOVE -> moveOrRename(source, target, requestId, MutationKind.MOVE);
                case RENAME -> moveOrRename(source, target, requestId, MutationKind.RENAME);
                case COPY -> copy(source, target);
                case CREATE_FOLDER -> createFolder(target);
            };
        } catch (ServiceException e) {
            return failed(kind, source, target, e.error());
        } catch (RuntimeException e) {
            return failed(kind, source, target, new StructuredError(
                    ErrorCode.SERVICE_FAILURE,
                    "Unexpected mutation failure",
                    Optional.ofNullable(e.getMessage())));
        }
    }

    private MutationReceipt.Entry moveOrRename(Path source, Path target, java.util.UUID requestId, MutationKind kind)
            throws ServiceException {
        Path scopeSource = requireInScope(source, "source");
        Path scopeTarget = requireInScope(target, "target");
        if (scopeSource.equals(scopeTarget)) {
            throw failure(ErrorCode.INVALID_COMMAND, "Source and target are the same path", null);
        }
        BasicFileAttributes sourceAttributes = requireExistingRegularFileOrDirectory(scopeSource);
        requireFreeTarget(scopeTarget);
        if (sourceAttributes.isDirectory() && scopeTarget.startsWith(scopeSource)) {
            throw failure(ErrorCode.INVALID_COMMAND,
                    "A folder cannot be moved into itself", scopeSource.toString());
        }
        atomicMove(scopeSource, scopeTarget);
        return journal(requestId, kind, scopeSource, scopeTarget);
    }

    private MutationReceipt.Entry copy(Path source, Path target) throws ServiceException {
        Path scopeSource = requireInScope(source, "source");
        Path scopeTarget = requireInScope(target, "target");
        if (scopeSource.equals(scopeTarget)) {
            throw failure(ErrorCode.INVALID_COMMAND, "Source and target are the same path", null);
        }
        BasicFileAttributes sourceAttributes = requireExistingRegularFileOrDirectory(scopeSource);
        requireFreeTarget(scopeTarget);
        if (sourceAttributes.isDirectory() && scopeTarget.startsWith(scopeSource)) {
            throw failure(ErrorCode.INVALID_COMMAND,
                    "A folder cannot be copied into itself", scopeSource.toString());
        }
        try {
            if (sourceAttributes.isDirectory()) {
                copyTree(scopeSource, scopeTarget);
            } else {
                Files.copy(scopeSource, scopeTarget);
            }
        } catch (FileAlreadyExistsException e) {
            throw failure(ErrorCode.TARGET_EXISTS, "Target already exists", scopeTarget.toString());
        } catch (IOException | SecurityException e) {
            throw failure(ErrorCode.IO_FAILURE, "Could not copy file", describe(e));
        }
        // Copies duplicate data; removing the copy would change the user's
        // visible files, so copies are deliberately not journalled as undoable.
        return new MutationReceipt.Entry(
                OperationStatus.APPLIED, scopeSource, scopeTarget, RiskLevel.MEDIUM, Optional.empty());
    }

    private MutationReceipt.Entry createFolder(Path target) throws ServiceException {
        Path scopeTarget = requireInScope(target, "target");
        try {
            BasicFileAttributes existing = Files.readAttributes(
                    scopeTarget, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            throw failure(ErrorCode.TARGET_EXISTS,
                    existing.isDirectory() ? "Folder already exists" : "A file already exists with that name",
                    scopeTarget.toString());
        } catch (IOException readFailure) {
            // Expected when the target does not exist; creation proceeds.
        }
        try {
            Files.createDirectories(scopeTarget);
        } catch (IOException | SecurityException e) {
            throw failure(ErrorCode.IO_FAILURE, "Could not create folder", describe(e));
        }
        // Folder creation stays un-journalled: deleting the folder to undo it
        // would risk removing user content added after creation.
        return new MutationReceipt.Entry(
                OperationStatus.APPLIED, scopeTarget, scopeTarget, RiskLevel.MEDIUM, Optional.empty());
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var stream = Files.newDirectoryStream(source)) {
            Files.createDirectories(target);
            for (Path child : stream) {
                BasicFileAttributes attributes =
                        Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink()) {
                    continue;
                }
                if (attributes.isDirectory()) {
                    copyTree(child, target.resolve(child.getFileName().toString()));
                } else if (attributes.isRegularFile()) {
                    Files.copy(child, target.resolve(child.getFileName().toString()));
                }
            }
        }
    }

    private void atomicMove(Path source, Path target) throws ServiceException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(source, target);
            } catch (FileAlreadyExistsException exists) {
                throw failure(ErrorCode.TARGET_EXISTS, "Target already exists", target.toString());
            } catch (IOException | SecurityException fallbackFailure) {
                throw failure(ErrorCode.IO_FAILURE, "Could not move file", describe(fallbackFailure));
            }
        } catch (FileAlreadyExistsException e) {
            throw failure(ErrorCode.TARGET_EXISTS, "Target already exists", target.toString());
        } catch (IOException | SecurityException e) {
            throw failure(ErrorCode.IO_FAILURE, "Could not move file", describe(e));
        }
    }

    /**
     * Records a journalled operation after its filesystem effect succeeded.
     * A journal failure downgrades the entry to FAILED with the cause so the
     * user sees the operation happened but cannot be undone.
     */
    private MutationReceipt.Entry journal(java.util.UUID requestId, MutationKind kind, Path source, Path target) {
        try {
            undoJournal.record(new UndoEntry(
                    identifierSupplier.get(), requestId,
                    kind, source, target, Instant.now(clock)));
            return new MutationReceipt.Entry(OperationStatus.APPLIED, source, target, RiskLevel.HIGH, Optional.empty());
        } catch (ServiceException e) {
            return new MutationReceipt.Entry(
                    OperationStatus.FAILED, source, target, RiskLevel.HIGH, Optional.of(e.error()));
        }
    }

    private Path requireInScope(Path path, String description) throws ServiceException {
        Path normalized = path.toAbsolutePath().normalize();
        for (Path root : scopeRoots) {
            if (normalized.equals(root) || normalized.startsWith(root)) {
                return normalized;
            }
        }
        throw failure(ErrorCode.ACCESS_DENIED,
                description + " is outside the configured scope: " + normalized, null);
    }

    private static BasicFileAttributes requireExistingRegularFileOrDirectory(Path path) throws ServiceException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | SecurityException e) {
            throw failure(ErrorCode.IO_FAILURE, "Source does not exist or is not readable",
                    path + ": " + describe(e));
        }
        if (attributes.isSymbolicLink() || (!attributes.isRegularFile() && !attributes.isDirectory())) {
            throw failure(ErrorCode.ACCESS_DENIED, "Source must be a regular file or directory", path.toString());
        }
        return attributes;
    }

    private static void requireFreeTarget(Path target) throws ServiceException {
        try {
            Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            throw failure(ErrorCode.TARGET_EXISTS, "Target already exists", target.toString());
        } catch (ServiceException e) {
            throw e;
        } catch (IOException | SecurityException readFailure) {
            // Target does not exist: the desired state for a no-overwrite move.
        }
    }

    private static MutationReceipt.Entry failed(MutationKind kind, Path source, Path target, StructuredError error) {
        return new MutationReceipt.Entry(
                OperationStatus.FAILED, source, target, riskFor(kind), Optional.of(error));
    }

    private static RiskLevel riskFor(MutationKind kind) {
        return switch (kind) {
            case MOVE, RENAME -> RiskLevel.HIGH;
            case COPY, CREATE_FOLDER -> RiskLevel.MEDIUM;
        };
    }

    private static List<Path> roots(List<Path> scopeRoots) {
        List<Path> normalized = new ArrayList<>();
        for (Path root : scopeRoots) {
            if (root == null) {
                throw new IllegalArgumentException("scope roots must not be null");
            }
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("scope root is not an existing directory: " + root);
            }
            normalized.add(root.toAbsolutePath().normalize());
        }
        return List.copyOf(normalized);
    }

    private static ServiceException failure(ErrorCode code, String message, String detail) {
        return new ServiceException(new StructuredError(code, message, Optional.ofNullable(detail)));
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }
}
