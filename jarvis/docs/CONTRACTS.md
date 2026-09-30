# Frozen Sprint 1 Contracts

`com.jarvis.api` is JavaFX-free. Constructors receive interfaces; no service locator, global singleton or reflection-based DI is allowed.

## Composition

Final integration creates these worker implementations, then injects them into `com.jarvis.core.DefaultCommandGateway`:

```java
new DesktopAppService(List<ConfiguredApp> apps)
new FileSystemFileSearchService(List<Path> roots)
new OshiSystemInfoService()
new SqliteHistoryRepository(Path databaseFile)
new DefaultCommandGateway(appService, fileSearchService, systemInfoService,
    historyRepository, workerExecutor)
```

Implementation classes live under `com.jarvis.services`; `DefaultCommandGateway` lives under `com.jarvis.core`. The executor is bounded and owned/closed by application composition. Service constructors validate configuration and do no blocking work.

`DesktopAppService` maps logical IDs to argument lists passed directly to `ProcessBuilder`; it never accepts or invokes a shell string. `FileSystemFileSearchService` uses only its constructor roots, returns at most 50 matches, stops after visiting 10,000 regular files, does not follow symbolic links, and reports which bound stopped the scan. Permission/I/O failures produce a structured failure rather than fabricated success.

## SQLite migration 001

Freebuff owns the migration resource `src/main/resources/db/001_history.sql` with exactly this schema:

```sql
CREATE TABLE IF NOT EXISTS command_history (
    request_id TEXT PRIMARY KEY NOT NULL,
    original_text TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('SUCCEEDED','FAILED','CANCELLED','REJECTED')),
    summary TEXT NOT NULL,
    error_code TEXT,
    error_message TEXT,
    submitted_at TEXT NOT NULL,
    started_at TEXT NOT NULL,
    completed_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_command_history_completed
    ON command_history(completed_at DESC);
PRAGMA user_version = 1;
```

Repository writes are single transactions. `recent(limit, token)` orders by `completed_at DESC`, then `request_id DESC`; valid limits are 1..50. Store enum names and `Instant.toString()`. A history-write failure is attached/surfaced separately and never replaces the command's original error.

## Lifecycle and outcomes

- `CommandGateway.submit` accepts one request once, invokes completion exactly once, and returns immediately.
- Closing/cancelling a subscription requests cooperative cancellation; it never reports success after cancellation wins.
- Progress callbacks are ordered per request. UI code marshals callbacks onto the JavaFX thread.
- `CommandStatus`: `SUCCEEDED`, `FAILED`, `CANCELLED`, `REJECTED`. Parse/unknown-app failures are `REJECTED`; dependency/platform/service failures are `FAILED`.
- History stores every terminal outcome when possible, including failures and cancellation.
- See `docs/COMMANDS.md` and `docs/PROJECT_DECISIONS.md` for frozen parsing/search semantics.
