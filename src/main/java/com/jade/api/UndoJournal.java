package com.jade.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for reversible file operations.
 *
 * <p>The journal stores one row per applied operation, keyed by the request
 * that produced it. Undo is per row: an operation that was already undone, or
 * whose inverse fails (for example a blocked target), is reported per row
 * instead of aborting the whole group, so repeating "undo that" stays safe and
 * idempotent.</p>
 */
public interface UndoJournal {
    /** Records one applied operation; rows start {@link UndoEntry.JournalStatus#ACTIVE}. */
    void record(UndoEntry entry) throws ServiceException;

    /**
     * Journal rows for one request, newest first. {@code limit} must be
     * between 1 and 50 (matching the history read contract).
     */
    List<UndoEntry.JournalRow> rowsForRequest(UUID requestId, CancellationToken cancellation)
            throws ServiceException;

    /** Marks one row undone or failed after its inverse operation was attempted. */
    void markStatus(UUID entryId, UndoEntry.JournalStatus status, Optional<StructuredError> error)
            throws ServiceException;

    /** The request whose journal rows would be reversed by "undo that". */
    Optional<UUID> latestUndoableRequest(CancellationToken cancellation) throws ServiceException;
}
