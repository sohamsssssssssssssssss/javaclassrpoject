package com.jade.integration;

import com.jade.api.*;
import com.jade.core.DefaultCommandGateway;
import com.jade.services.app.DesktopAppService;
import com.jade.services.history.SqliteHistoryRepository;
import com.jade.services.search.FileSystemFileSearchService;
import com.jade.services.system.OshiSystemInfoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SprintOnePipelineTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void typedRequestsReachRealServicesAndPersistHistory() throws Exception {
        Path searchRoot = Files.createDirectory(temporaryDirectory.resolve("search"));
        Files.write(searchRoot.resolve("small.pdf"), new byte[64]);
        Files.write(searchRoot.resolve("large.PDF"), new byte[1_048_577]);
        Files.writeString(searchRoot.resolve("notes.txt"), "not a PDF");
        for (int index = 0; index < 50; index++) {
            Files.write(searchRoot.resolve("small-%02d.pdf".formatted(index)), new byte[64]);
        }
        Path database = temporaryDirectory.resolve("data/history.db");

        ExecutorService executor = new ThreadPoolExecutor(
                2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
        SqliteHistoryRepository history = new SqliteHistoryRepository(database);
        DefaultCommandGateway gateway = new DefaultCommandGateway(
                new DesktopAppService(List.of(
                        new ConfiguredApp("calculator", "Calculator", Set.of("calculator", "calc")),
                        new ConfiguredApp("text-editor", "Text Editor", Set.of("text editor", "editor")),
                        new ConfiguredApp("file-manager", "File Manager", Set.of("file manager", "files")))),
                new FileSystemFileSearchService(List.of(searchRoot)),
                new OshiSystemInfoService(),
                history,
                executor);
        try {
            FileSearchResult all = result(submit(gateway, "find PDFs"), FileSearchResult.class);
            assertEquals(50, all.matches().size());
            assertTrue(all.resultLimitReached());
            assertTrue(all.visitedFiles() <= 10_000);

            FileSearchResult large = result(
                    submit(gateway, "find PDFs larger than 1 MB"), FileSearchResult.class);
            assertEquals(List.of("large.PDF"), large.matches().stream().map(FileMatch::fileName).toList());

            assertEquals(CommandStatus.REJECTED, submit(gateway, "do something clever").status());
            CommandOutcome unavailable = submit(gateway, "open unavailable");
            assertEquals(CommandStatus.REJECTED, unavailable.status());
            assertEquals(ErrorCode.UNKNOWN_APP, unavailable.error().orElseThrow().code());

            SystemSnapshot snapshot = result(submit(gateway, "system status"), SystemSnapshot.class);
            assertFalse(snapshot.osName().isBlank());

            HistoryResult visibleHistory = result(submit(gateway, "show history"), HistoryResult.class);
            assertEquals(5, visibleHistory.entries().size());
        } finally {
            gateway.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            history.close();
        }

        try (SqliteHistoryRepository reopened = new SqliteHistoryRepository(database)) {
            List<HistoryEntry> persisted = reopened.recent(10, CancellationToken.NONE);
            assertEquals(6, persisted.size());
            assertTrue(persisted.stream().anyMatch(entry -> entry.status() == CommandStatus.REJECTED));
            assertTrue(persisted.stream().anyMatch(entry -> entry.originalText().equals("system status")));
        }
    }

    private static CommandOutcome submit(CommandGateway gateway, String text) throws Exception {
        CompletableFuture<CommandOutcome> completion = new CompletableFuture<>();
        gateway.submit(CommandRequest.create(text), ignored -> { }, completion::complete);
        return completion.get(5, TimeUnit.SECONDS);
    }

    private static <T extends CommandResult> T result(CommandOutcome outcome, Class<T> type) {
        assertEquals(CommandStatus.SUCCEEDED, outcome.status(), outcome.summary());
        return type.cast(outcome.result().orElseThrow());
    }
}
