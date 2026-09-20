package com.jade.core;

import com.jade.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DefaultCommandGatewayTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void executesEachRequestOnceAndRecordsUnknownApp() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        RecordingHistory history = new RecordingHistory();
        AppService apps = new AppService() {
            public List<ConfiguredApp> configuredApps() { return List.of(); }
            public AppLaunchReceipt launch(String id, CancellationToken token) throws ServiceException {
                launches.incrementAndGet();
                throw failure(ErrorCode.UNKNOWN_APP, "Unknown configured app");
            }
        };
        DefaultCommandGateway gateway = gateway(apps, history);

        CommandOutcome outcome = submit(gateway, "open mystery");

        assertEquals(CommandStatus.REJECTED, outcome.status());
        assertEquals(1, launches.get());
        assertEquals(1, history.entries.size());
        gateway.close();
    }

    @Test
    void preservesServiceFailureAndHistoryLoggingFailure() throws Exception {
        HistoryRepository brokenHistory = new HistoryRepository() {
            public void save(HistoryEntry entry) throws ServiceException { throw failure(ErrorCode.DATABASE_FAILURE, "DB offline"); }
            public List<HistoryEntry> recent(int limit, CancellationToken token) { return List.of(); }
            public void close() { }
        };
        AppService apps = new AppService() {
            public List<ConfiguredApp> configuredApps() { return List.of(); }
            public AppLaunchReceipt launch(String id, CancellationToken token) throws ServiceException {
                throw failure(ErrorCode.MISSING_DEPENDENCY, "Application unavailable");
            }
        };

        CommandOutcome outcome = submit(gateway(apps, brokenHistory), "open calc");

        assertEquals(CommandStatus.FAILED, outcome.status());
        assertEquals(ErrorCode.MISSING_DEPENDENCY, outcome.error().orElseThrow().code());
        assertTrue(outcome.summary().contains("history was not saved"));
    }

    @Test
    void cancellationIsVisible() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        FileSearchService search = (query, token, progress) -> {
            entered.countDown();
            while (!token.isCancellationRequested()) {
                Thread.onSpinWait();
            }
            throw failure(ErrorCode.CANCELLED, "Search cancelled");
        };
        DefaultCommandGateway gateway = new DefaultCommandGateway(
                unusedApps(), search, unusedSystem(), new RecordingHistory(), executor);
        CompletableFuture<CommandOutcome> completed = new CompletableFuture<>();
        CommandSubscription subscription = gateway.submit(
                CommandRequest.create("find PDFs"), ignored -> { }, completed::complete);
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        subscription.cancel();
        CommandOutcome outcome = completed.get(2, TimeUnit.SECONDS);

        assertEquals(CommandStatus.CANCELLED, outcome.status());
        gateway.close();
    }

    private DefaultCommandGateway gateway(AppService apps, HistoryRepository history) {
        return new DefaultCommandGateway(apps,
                (query, token, progress) -> new FileSearchResult(List.of(), 0, false, false),
                unusedSystem(), history, executor);
    }

    private static CommandOutcome submit(DefaultCommandGateway gateway, String text) throws Exception {
        CompletableFuture<CommandOutcome> completed = new CompletableFuture<>();
        gateway.submit(CommandRequest.create(text), ignored -> { }, completed::complete);
        return completed.get(2, TimeUnit.SECONDS);
    }

    private static AppService unusedApps() {
        return new AppService() {
            public List<ConfiguredApp> configuredApps() { return List.of(new ConfiguredApp("calculator", "Calculator", Set.of("calc"))); }
            public AppLaunchReceipt launch(String id, CancellationToken token) { return new AppLaunchReceipt(id, id, Instant.now()); }
        };
    }

    private static SystemInfoService unusedSystem() {
        return token -> new SystemSnapshot(Instant.now(), "test", "1", "test",
                OptionalDouble.empty(), OptionalLong.empty(), OptionalLong.empty());
    }

    private static ServiceException failure(ErrorCode code, String message) {
        return new ServiceException(new StructuredError(code, message, Optional.empty()));
    }

    private static final class RecordingHistory implements HistoryRepository {
        private final List<HistoryEntry> entries = new ArrayList<>();
        public void save(HistoryEntry entry) { entries.add(entry); }
        public List<HistoryEntry> recent(int limit, CancellationToken token) { return List.copyOf(entries); }
        public void close() { }
    }
}
