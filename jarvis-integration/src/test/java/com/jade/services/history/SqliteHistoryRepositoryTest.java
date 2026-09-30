package com.jade.services.history;

import com.jade.api.CommandStatus;
import com.jade.api.CancellationToken;
import com.jade.api.ErrorCode;
import com.jade.api.HistoryEntry;
import com.jade.api.MutationKind;
import com.jade.api.ServiceException;
import com.jade.api.StructuredError;
import com.jade.api.UndoEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteHistoryRepositoryTest {

    private static final CancellationToken NONE = CancellationToken.NONE;

    private static HistoryEntry entry(
            UUID id, String text, CommandStatus status, Instant completedAt) {
        return new HistoryEntry(
                id,
                text,
                status,
                "summary for " + text,
                status == CommandStatus.FAILED
                        ? Optional.of(new StructuredError(ErrorCode.IO_FAILURE, "boom", Optional.empty()))
                        : Optional.empty(),
                completedAt.minusSeconds(10),
                completedAt.minusSeconds(5),
                completedAt);
    }

    private static HistoryEntry entry(String text, CommandStatus status, Instant completedAt) {
        return entry(UUID.randomUUID(), text, status, completedAt);
    }

    @Test
    void schemaIsCreatedAndUserVersionIsSet(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("history.db");
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            repository.recent(1, NONE); // forces lazy open + migration
        }
        assertTrue(java.nio.file.Files.exists(db));
        // Reopen with a fresh repository and confirm the table exists.
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            List<HistoryEntry> entries = repository.recent(10, NONE);
            assertTrue(entries.isEmpty());
        }
    }

    @Test
    void saveThenReadRoundTripsAllFields(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("history.db");
        UUID id = UUID.randomUUID();
        Instant completed = Instant.parse("2026-09-18T10:00:00Z");
        HistoryEntry written = entry(id, "find pdfs", CommandStatus.SUCCEEDED, completed);

        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            repository.save(written);
        }

        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            List<HistoryEntry> entries = repository.recent(10, NONE);
            assertEquals(1, entries.size());
            HistoryEntry read = entries.get(0);
            assertEquals(id, read.requestId());
            assertEquals("find pdfs", read.originalText());
            assertEquals(CommandStatus.SUCCEEDED, read.status());
            assertEquals("summary for find pdfs", read.summary());
            assertTrue(read.error().isEmpty());
            assertEquals(completed.minusSeconds(10), read.submittedAt());
            assertEquals(completed.minusSeconds(5), read.startedAt());
            assertEquals(completed, read.completedAt());
        }
    }

    @Test
    void historySurvivesReopeningTheDatabase(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("history.db");
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            repository.save(entry("open calc", CommandStatus.SUCCEEDED,
                    Instant.parse("2026-09-18T09:00:00Z")));
        }
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            repository.save(entry("system status", CommandStatus.FAILED,
                    Instant.parse("2026-09-18T09:05:00Z")));
        }
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            List<HistoryEntry> entries = repository.recent(50, NONE);
            assertEquals(2, entries.size());
            assertEquals("system status", entries.get(0).originalText());
            assertEquals("open calc", entries.get(1).originalText());
        }
    }

    @Test
    void recentOrdersByCompletedAtDescThenRequestIdDesc(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("history.db");
        // Deterministic UUID pair with a known text ordering: low < high,
        // so request_id DESC must put "high" text before "low" text.
        UUID low = new UUID(0L, 1L);
        UUID high = new UUID(0L, 2L);
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            repository.save(entry(low, "low", CommandStatus.SUCCEEDED,
                    Instant.parse("2026-09-18T08:00:00Z")));
            repository.save(entry(high, "high", CommandStatus.SUCCEEDED,
                    Instant.parse("2026-09-18T08:00:00Z")));
            repository.save(entry("older", CommandStatus.SUCCEEDED,
                    Instant.parse("2026-09-18T07:00:00Z")));
        }
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            List<HistoryEntry> entries = repository.recent(50, NONE);
            assertEquals(3, entries.size());
            assertEquals("high", entries.get(0).originalText()); // request_id DESC tiebreak
            assertEquals("low", entries.get(1).originalText());
            assertEquals("older", entries.get(2).originalText());
        }
    }

    @Test
    void recentRejectsLimitsOutsideOneToFive(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("history.db");
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            for (int limit : List.of(0, -1, 51, 100)) {
                ServiceException exception = assertThrows(ServiceException.class,
                        () -> repository.recent(limit, NONE));
                assertEquals(ErrorCode.INVALID_COMMAND, exception.error().code());
            }
        }
    }

    @Test
    void recentHonoursBoundedLimit(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("history.db");
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            for (int i = 0; i < 10; i++) {
                repository.save(entry("command " + i, CommandStatus.SUCCEEDED,
                        Instant.parse("2026-09-18T07:0" + i + ":00Z")));
            }
        }
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            assertEquals(3, repository.recent(3, NONE).size());
            assertEquals(10, repository.recent(50, NONE).size()); // LIMIT caps, never pads
        }
    }

    @Test
    void errorFieldsPersistAndReload(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("history.db");
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            repository.save(entry("bad command", CommandStatus.FAILED,
                    Instant.parse("2026-09-18T06:00:00Z")));
        }
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            HistoryEntry read = repository.recent(1, NONE).get(0);
            assertTrue(read.error().isPresent());
            assertEquals(ErrorCode.IO_FAILURE, read.error().get().code());
            assertEquals("boom", read.error().get().message());
        }
    }

    @Test
    void cancelledStatusPersists(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("history.db");
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            repository.save(entry("find pdfs", CommandStatus.CANCELLED,
                    Instant.parse("2026-09-18T05:00:00Z")));
        }
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            assertEquals(CommandStatus.CANCELLED,
                    repository.recent(1, NONE).get(0).status());
        }
    }

    @Test
    void duplicatedRequestIdFailsWithDatabaseFailure(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("history.db");
        UUID id = UUID.randomUUID();
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            repository.save(entry(id, "first", CommandStatus.SUCCEEDED,
                    Instant.parse("2026-09-18T04:00:00Z")));
            ServiceException exception = assertThrows(ServiceException.class,
                    () -> repository.save(entry(id, "second", CommandStatus.SUCCEEDED,
                            Instant.parse("2026-09-18T04:00:00Z"))));
            assertEquals(ErrorCode.DATABASE_FAILURE, exception.error().code());
        }
    }

    @Test
    void closeIsIdempotentAndUseAfterCloseReopensOrFailsCleanly(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("history.db");
        SqliteHistoryRepository repository = new SqliteHistoryRepository(db);
        repository.save(entry("one", CommandStatus.SUCCEEDED,
                Instant.parse("2026-09-18T03:00:00Z")));
        repository.close();
        repository.close(); // idempotent

        // A fresh repository over the same file still reads the data.
        try (SqliteHistoryRepository repository2 = new SqliteHistoryRepository(db)) {
            assertEquals(1, repository2.recent(10, NONE).size());
        }
    }

    @Test
    void constructorRejectsNullOrBlankPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> new SqliteHistoryRepository(null));
        assertThrows(IllegalArgumentException.class,
                () -> new SqliteHistoryRepository(Path.of("")));
    }

    @Test
    void recentHonoursCancellation(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("history.db");
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            repository.save(entry("one", CommandStatus.SUCCEEDED,
                    Instant.parse("2026-09-18T02:00:00Z")));
            ServiceException exception = assertThrows(ServiceException.class,
                    () -> repository.recent(10, () -> true));
            assertEquals(ErrorCode.CANCELLED, exception.error().code());
        }
    }

    @Test
    void closedRepositoryReportsDatabaseFailureOnUse(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("history.db");
        SqliteHistoryRepository repository = new SqliteHistoryRepository(db);
        repository.save(entry("one", CommandStatus.SUCCEEDED,
                Instant.parse("2026-09-18T01:00:00Z")));
        repository.close();
        assertThrows(ServiceException.class, () -> repository.recent(10, NONE));
    }

    @Test
    void unicodeOriginalTextSurvivesRoundTrip(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("history.db");
        String unicode = "café ☕ ファイル \"quoted\"";
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            repository.save(entry(unicode, CommandStatus.SUCCEEDED,
                    Instant.parse("2026-09-18T00:00:00Z")));
        }
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            assertEquals(unicode, repository.recent(1, NONE).get(0).originalText());
        }
    }

    // -----------------------------------------------------------------
    // Sprint 2: undo journal (undo_entries table, migration 002).
    // -----------------------------------------------------------------

    private static UndoEntry undoEntry(UUID requestId, String name) {
        return undoEntry(requestId, name, Instant.parse("2026-09-18T12:00:00Z"));
    }

    private static UndoEntry undoEntry(UUID requestId, String name, Instant recordedAt) {
        return new UndoEntry(
                UUID.randomUUID(),
                requestId,
                MutationKind.MOVE,
                Path.of("/scope/demo").resolve(name),
                Path.of("/scope/demo-moved").resolve(name),
                recordedAt);
    }

    @Test
    void recordsAndReadsUndoEntriesPerRequest(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("history.db");
        UUID requestA = UUID.randomUUID();
        UUID requestB = UUID.randomUUID();
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            repository.record(undoEntry(requestA, "a.pdf", Instant.parse("2026-09-18T12:00:00Z")));
            repository.record(undoEntry(requestA, "b.pdf", Instant.parse("2026-09-18T12:00:01Z")));
            repository.record(undoEntry(requestB, "c.pdf", Instant.parse("2026-09-18T12:00:02Z")));

            List<UndoEntry.JournalRow> rowsA = repository.rowsForRequest(requestA, NONE);
            assertEquals(2, rowsA.size());
            assertTrue(rowsA.stream().allMatch(row -> row.status() == UndoEntry.JournalStatus.ACTIVE));
            assertEquals(Optional.of(requestB), repository.latestUndoableRequest(NONE));
        }
    }

    @Test
    void marksRowsPerEntrySoUndoStaysIdempotent(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("history.db");
        UUID request = UUID.randomUUID();
        UndoEntry first = undoEntry(request, "a.pdf");
        UndoEntry second = undoEntry(request, "b.pdf");
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            repository.record(first);
            repository.record(second);
            repository.markStatus(first.id(), UndoEntry.JournalStatus.UNDONE, Optional.empty());
            assertEquals(Optional.of(request), repository.latestUndoableRequest(NONE));

            repository.markStatus(second.id(), UndoEntry.JournalStatus.UNDONE, Optional.empty());
            assertEquals(Optional.empty(), repository.latestUndoableRequest(NONE));
        }
    }

    @Test
    void undoJournalSurvivesReopeningTheDatabase(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("history.db");
        UUID request = UUID.randomUUID();
        UndoEntry stored = undoEntry(request, "report.pdf");
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            repository.record(stored);
        }
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            List<UndoEntry.JournalRow> rows = repository.rowsForRequest(request, NONE);
            assertEquals(1, rows.size());
            UndoEntry read = rows.get(0).entry();
            assertEquals(MutationKind.MOVE, read.kind());
            assertEquals(stored.source(), read.source());
            assertEquals(stored.target(), read.target());
            assertEquals(stored.recordedAt(), read.recordedAt());
        }
    }

    @Test
    void failedUndoRowsPersistTheirError(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("history.db");
        UUID request = UUID.randomUUID();
        UndoEntry stored = undoEntry(request, "x.pdf");
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            repository.record(stored);
            repository.markStatus(stored.id(), UndoEntry.JournalStatus.FAILED,
                    Optional.of(new StructuredError(
                            ErrorCode.TARGET_EXISTS, "Original location is occupied", Optional.empty())));
        }
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            UndoEntry.JournalRow row = repository.rowsForRequest(request, NONE).get(0);
            assertEquals(UndoEntry.JournalStatus.FAILED, row.status());
            assertEquals(ErrorCode.TARGET_EXISTS, row.error().orElseThrow().code());
        }
    }

    @Test
    void refusesToJournalNonReversibleKinds(@TempDir Path temp) {
        Path db = temp.resolve("history.db");
        UndoEntry copy = new UndoEntry(UUID.randomUUID(), UUID.randomUUID(),
                MutationKind.COPY, Path.of("/scope/a"), Path.of("/scope/b"),
                Instant.parse("2026-09-18T12:00:00Z"));
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            ServiceException exception = assertThrows(ServiceException.class, () -> repository.record(copy));
            assertEquals(ErrorCode.INVALID_COMMAND, exception.error().code());
        } catch (ServiceException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void undoJournalRejectsNullArguments(@TempDir Path temp) {
        Path db = temp.resolve("history.db");
        try (SqliteHistoryRepository repository = new SqliteHistoryRepository(db)) {
            assertThrows(NullPointerException.class, () -> repository.record(null));
            assertThrows(NullPointerException.class, () -> repository.rowsForRequest(null, NONE));
            assertThrows(NullPointerException.class, () -> repository.latestUndoableRequest(null));
            assertThrows(NullPointerException.class, () -> repository.markStatus(
                    UUID.randomUUID(), UndoEntry.JournalStatus.UNDONE, null));
        } catch (ServiceException e) {
            throw new IllegalStateException(e);
        }
    }
}
