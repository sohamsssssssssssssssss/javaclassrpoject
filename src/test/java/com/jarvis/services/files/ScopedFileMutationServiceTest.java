package com.jarvis.services.files;

import com.jarvis.api.CancellationToken;
import com.jarvis.api.ErrorCode;
import com.jarvis.api.MutationKind;
import com.jarvis.api.MutationReceipt;
import com.jarvis.api.OperationStatus;
import com.jarvis.api.RiskLevel;
import com.jarvis.api.ServiceException;
import com.jarvis.api.StructuredError;
import com.jarvis.api.UndoEntry;
import com.jarvis.api.UndoJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ScopedFileMutationServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-18T12:00:00Z"), ZoneId.of("UTC"));

    @TempDir
    Path temporaryDirectory;

    private Path scope;
    private RecordingJournal journal;
    private ScopedFileMutationService service;

    private void setUpService() throws IOException {
        scope = Files.createDirectory(temporaryDirectory.resolve("scope"));
        journal = new RecordingJournal();
        service = new ScopedFileMutationService(List.of(scope), journal, CLOCK, () -> UUID.randomUUID());
    }

    @Test
    void movesFileAtomicallyAndJournalsTheOperation() throws Exception {
        setUpService();
        Path source = Files.write(scope.resolve("report.pdf"), new byte[10]);
        Path target = scope.resolve("moved").resolve("report.pdf");
        Files.createDirectories(target.getParent());

        MutationReceipt.Entry entry = service.apply(
                MutationKind.MOVE, source, target, UUID.randomUUID(), CancellationToken.NONE);

        assertEquals(OperationStatus.APPLIED, entry.status());
        assertEquals(RiskLevel.HIGH, entry.risk());
        assertFalse(Files.exists(source));
        assertTrue(Files.exists(target));
        assertEquals(1, journal.recorded.size());
        assertEquals(MutationKind.MOVE, journal.recorded.getFirst().kind());
        assertEquals(target, journal.recorded.getFirst().target());
    }

    @Test
    void renamesWithinTheSameDirectory() throws Exception {
        setUpService();
        Path source = Files.write(scope.resolve("old.txt"), new byte[4]);

        MutationReceipt.Entry entry = service.apply(
                MutationKind.RENAME, source, scope.resolve("new.txt"),
                UUID.randomUUID(), CancellationToken.NONE);

        assertEquals(OperationStatus.APPLIED, entry.status());
        assertTrue(Files.exists(scope.resolve("new.txt")));
        assertFalse(Files.exists(source));
    }

    @Test
    void refusesToOverwriteAnExistingTarget() throws Exception {
        setUpService();
        Path source = Files.write(scope.resolve("a.txt"), new byte[4]);
        Files.write(scope.resolve("b.txt"), new byte[4]);

        MutationReceipt.Entry entry = service.apply(
                MutationKind.MOVE, source, scope.resolve("b.txt"), UUID.randomUUID(), CancellationToken.NONE);

        assertEquals(OperationStatus.FAILED, entry.status());
        assertEquals(ErrorCode.TARGET_EXISTS, entry.error().orElseThrow().code());
        assertTrue(Files.exists(source));
        assertTrue(scope.resolve("b.txt").toFile().length() == 4);
        assertTrue(journal.recorded.isEmpty());
    }

    @Test
    void rejectsPathsOutsideTheConfiguredScope() throws Exception {
        setUpService();
        Path inside = Files.write(scope.resolve("inside.txt"), new byte[4]);
        Path outside = temporaryDirectory.resolve("outside.txt");
        Files.write(outside, new byte[4]);

        MutationReceipt.Entry outbound = service.apply(
                MutationKind.MOVE, inside, outside, UUID.randomUUID(), CancellationToken.NONE);
        assertEquals(OperationStatus.FAILED, outbound.status());
        assertEquals(ErrorCode.ACCESS_DENIED, outbound.error().orElseThrow().code());

        MutationReceipt.Entry inbound = service.apply(
                MutationKind.MOVE, outside, scope.resolve("smuggled.txt"),
                UUID.randomUUID(), CancellationToken.NONE);
        assertEquals(OperationStatus.FAILED, inbound.status());
        assertEquals(ErrorCode.ACCESS_DENIED, inbound.error().orElseThrow().code());
        assertFalse(Files.exists(scope.resolve("smuggled.txt")));
    }

    @Test
    void refusesDirectoryMovedIntoItsOwnDescendant() throws Exception {
        setUpService();
        Path folder = Files.createDirectories(scope.resolve("parent").resolve("child"));

        MutationReceipt.Entry entry = service.apply(
                MutationKind.MOVE, folder.getParent(), folder.resolve("nested"),
                UUID.randomUUID(), CancellationToken.NONE);

        assertEquals(OperationStatus.FAILED, entry.status());
        assertEquals(ErrorCode.INVALID_COMMAND, entry.error().orElseThrow().code());
        assertTrue(Files.exists(folder.getParent()));
    }

    @Test
    void copiesFilesAndDirectoriesWithoutJournaling() throws Exception {
        setUpService();
        Path folder = Files.createDirectories(scope.resolve("docs"));
        Files.write(folder.resolve("a.txt"), new byte[4]);
        Files.write(folder.resolve("b.txt"), new byte[4]);

        MutationReceipt.Entry entry = service.apply(
                MutationKind.COPY, folder, scope.resolve("docs-copy"),
                UUID.randomUUID(), CancellationToken.NONE);

        assertEquals(OperationStatus.APPLIED, entry.status());
        assertEquals(RiskLevel.MEDIUM, entry.risk());
        assertTrue(Files.exists(scope.resolve("docs-copy").resolve("a.txt")));
        assertTrue(Files.exists(folder.resolve("a.txt")));
        assertTrue(journal.recorded.isEmpty(), "copies must not be journalled as undoable");
    }

    @Test
    void createsFolderAndRefusesDuplicates() throws Exception {
        setUpService();
        Path target = scope.resolve("College");

        MutationReceipt.Entry created = service.apply(
                MutationKind.CREATE_FOLDER, target, target, UUID.randomUUID(), CancellationToken.NONE);
        assertEquals(OperationStatus.APPLIED, created.status());
        assertTrue(Files.isDirectory(target));

        MutationReceipt.Entry duplicate = service.apply(
                MutationKind.CREATE_FOLDER, target, target, UUID.randomUUID(), CancellationToken.NONE);
        assertEquals(OperationStatus.FAILED, duplicate.status());
        assertEquals(ErrorCode.TARGET_EXISTS, duplicate.error().orElseThrow().code());
        assertTrue(journal.recorded.isEmpty());
    }

    @Test
    void missingSourceFailsWithoutSideEffects() throws Exception {
        setUpService();
        MutationReceipt.Entry entry = service.apply(
                MutationKind.MOVE, scope.resolve("ghost.txt"), scope.resolve("target.txt"),
                UUID.randomUUID(), CancellationToken.NONE);
        assertEquals(OperationStatus.FAILED, entry.status());
        assertFalse(Files.exists(scope.resolve("target.txt")));
    }

    @Test
    void journalFailureDowngradesTheEntryInsteadOfLying() throws Exception {
        setUpService();
        journal.failNext = true;
        Path source = Files.write(scope.resolve("j.txt"), new byte[4]);

        MutationReceipt.Entry entry = service.apply(
                MutationKind.MOVE, source, scope.resolve("j-moved.txt"),
                UUID.randomUUID(), CancellationToken.NONE);

        assertEquals(OperationStatus.FAILED, entry.status());
        assertTrue(Files.exists(scope.resolve("j-moved.txt")), "the move itself happened");
        assertEquals(ErrorCode.DATABASE_FAILURE, entry.error().orElseThrow().code());
    }

    @Test
    void rejectsInvalidConstructorArguments(@TempDir Path other) {
        assertThrows(IllegalArgumentException.class, () ->
                new ScopedFileMutationService(List.of(), new RecordingJournal()));
        assertThrows(IllegalArgumentException.class, () ->
                new ScopedFileMutationService(null, new RecordingJournal()));
        assertThrows(IllegalArgumentException.class, () ->
                new ScopedFileMutationService(List.of(other.resolve("missing")), new RecordingJournal()));
        assertThrows(NullPointerException.class, () ->
                new ScopedFileMutationService(List.of(other), null));
    }

    private static final class RecordingJournal implements UndoJournal {
        private final List<UndoEntry> recorded = new ArrayList<>();
        private boolean failNext;

        @Override
        public void record(UndoEntry entry) throws ServiceException {
            if (failNext) {
                failNext = false;
                throw new ServiceException(new StructuredError(
                        ErrorCode.DATABASE_FAILURE, "journal unavailable", Optional.empty()));
            }
            recorded.add(entry);
        }

        @Override
        public List<UndoEntry.JournalRow> rowsForRequest(UUID requestId, CancellationToken cancellation) {
            return List.of();
        }

        @Override
        public void markStatus(UUID entryId, UndoEntry.JournalStatus status, Optional<StructuredError> error) {
        }

        @Override
        public Optional<UUID> latestUndoableRequest(CancellationToken cancellation) {
            return Optional.empty();
        }
    }
}
