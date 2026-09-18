# Freebuff Handoff — Sprint 1 Services

Date: 2026-09-18 · Worktree: `jarvis-freebuff-services` · Baseline: `b3094c5` (Codex contracts)

## Status: SERVICES READY (pending Codex integration gate)

## Changed files (all inside Freebuff ownership)

Implementation — `src/main/java/com/jarvis/services/`:

- `search/FileSystemFileSearchService.java` — implements `com.jarvis.api.FileSearchService`
- `app/DesktopAppService.java` — implements `com.jarvis.api.AppService`
- `app/AppCommandResolver.java` — OS adapter interface (maps logical app → explicit command list)
- `app/MacAppCommandResolver.java` / `app/WindowsAppCommandResolver.java` / `app/LinuxAppCommandResolver.java` — per-OS adapters
- `app/LaunchSpec.java` — explicit executable + argument list (immutable)
- `app/AppLauncher.java` / `app/ProcessAppLauncher.java` — process-start seam; default passes the list straight to `ProcessBuilder`
- `system/OshiSystemInfoService.java` — implements `com.jarvis.api.SystemInfoService` (+ nested `SystemMetricSource` seam)
- `system/OshiSystemMetricSource.java` — the only class touching OSHI
- `history/SqliteHistoryRepository.java` — implements `com.jarvis.api.HistoryRepository`

Resources — `src/main/resources/db/`:

- `001_history.sql` — exact frozen schema from docs/CONTRACTS.md (table, index, `PRAGMA user_version = 1`)

Tests — `src/test/java/com/jarvis/services/`:

- `search/FileSystemFileSearchServiceTest.java` (13 tests)
- `app/DesktopAppServiceTest.java` (9 tests)
- `system/OshiSystemInfoServiceTest.java` (6 tests)
- `history/SqliteHistoryRepositoryTest.java` (14 tests)

## Implemented factories (exact composition signatures from CONTRACTS.md)

- `new DesktopAppService(List<ConfiguredApp> apps)` ✓
- `new FileSystemFileSearchService(List<Path> roots)` ✓
- `new OshiSystemInfoService()` ✓
- `new SqliteHistoryRepository(Path databaseFile)` ✓

Each also offers an overload constructor for test injection (launcher/resolver, clock, metric source). Constructors validate configuration and do no blocking work; the SQLite database is opened and migrated lazily on first use under a lock.

## Implementation notes per service

### FileSearchService (`FileSystemFileSearchService`)
- Walks only constructor roots with `java.nio.file` (iterative DFS, per-root `DirectoryStream`), closing every stream via try-with-resources.
- Limits: returned matches ≤ `FileSearchQuery.maxResults` clamped to 50; visited regular files ≤ `scanLimit` clamped to 10,000. Result flags `resultLimitReached` / `scanLimitReached` report which bound stopped the scan.
- Symlinks are never followed (NOFOLLOW attribute reads + explicit skip with warning); entries resolving outside the root are skipped with warnings.
- Inaccessible entries/roots are skipped and reported via `warnings()` (bounded at 200 entries). A fully unscannable root set throws `ACCESS_DENIED`; cancellation throws `CANCELLED`. A complete empty search returns empty matches with no partial flags — distinct from partial/cancelled.
- Output ordering is deterministic: filename (ASCII case-insensitive), then path string, then path. Extension matching is ASCII case-insensitive on the final suffix; original filename case is preserved in `FileMatch.fileName()`/`path()`. One MB = 1,048,576 bytes (sizes are raw bytes; callers convert).
- Progress: `visitedFileProgress` fires every 250 visited files plus a final tail event.

### AppService (`DesktopAppService`)
- Launches only configured apps/aliases (id or alias lookup, ASCII case-insensitive). There is no API that accepts a command string — `sh -c`/`bash -c`/`cmd /c` and arbitrary user-derived lines are impossible by construction. `LaunchSpec` is an explicit executable + argument list passed verbatim to `ProcessBuilder` via the injectable `AppLauncher` seam.
- OS support: **macOS implemented** (`/usr/bin/open -a <App>` style: `open -a Calculator/TextEdit/Finder`, resolved by name via PATH — no personal absolute paths). Windows and Linux adapters throw `UNSUPPORTED_PLATFORM` clearly until implemented.
- Receipts report that the launch request was accepted (`ProcessBuilder.start()` returned / `open` accepted); window visibility is never claimed as verified.
- Failures: unknown app/alias → `UNKNOWN_APP` (REJECTED class), unsupported platform → `UNSUPPORTED_PLATFORM`, failed process start → `SERVICE_FAILURE` with cause.

### SystemInfoService (`OshiSystemInfoService`)
- OSHI 6.8.3 via a narrow `SystemMetricSource` seam (production: `OshiSystemMetricSource`). CPU load sampled between two OSHI tick reads 300 ms apart (bounded blocking work; gateway executor keeps it off the JavaFX thread).
- CPU/RAM values that OSHI cannot compute (negative/non-finite load, zero memory) surface as `Optional.empty()` — never fabricated. Blank OS fields become `"unknown"`. Probe failures → `SERVICE_FAILURE`.
- Note: the frozen `SystemSnapshot` record has no storage/battery fields; storage and battery metrics are therefore not exposed this sprint (record a contract extension request if wanted next sprint).

### HistoryRepository (`SqliteHistoryRepository`)
- Xerial SQLite JDBC; database file at the caller-supplied path (composition creates it under the writable application-data location, e.g. `user.home/Application Support/JARVIS` or `%APPDATA%/JARVIS`).
- Versioned migrations from classpath `/db/*.sql` gated on `PRAGMA user_version`, each applied in one transaction; currently migration 001 only. Pragmas: `busy_timeout=5000`, `journal_mode=WAL`, `synchronous=NORMAL`.
- All statements prepared; writes are single transactions; reads bounded `LIMIT ?` and ordered `completed_at DESC, request_id DESC` (valid limits 1..50, else `INVALID_COMMAND`). Enum names and `Instant.toString()` stored as TEXT.
- `close()` is idempotent and final: use-after-close fails with `DATABASE_FAILURE` instead of silently reopening. Duplicate `request_id` (PK violation) → `DATABASE_FAILURE`. Transactions are database-only — they never roll back OS actions.
- DB/runtime files stay out of Git via the existing `.gitignore` (`*.db`, `*.sqlite*`).

## Verification

Focused suites (`mvn -Dtest='FileSystemFileSearchServiceTest,DesktopAppServiceTest,OshiSystemInfoServiceTest,SqliteHistoryRepositoryTest' test`):

```
Tests run: 42, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

Re-verified independently on 2026-09-18 from a clean tree: 13 search + 9 app + 6 system + 14 history = 42/42 green (surefire XML reports confirm zero failures/errors).

`mvn javafx:run` (working-agreement gate): verified 2026-09-18 on macOS ARM64, JDK 25.0.2. JavaFX 21.0.10 mac-aarch64 natives resolved and loaded cleanly and the Stage A stub window stayed up; process terminated by the harness after ~25 s with no errors in the log. Command execution is not wired yet (composition is Codex's Stage B step), so the window shows the placeholder label only.

- `FileSystemFileSearchServiceTest` — 13/13: root validation, extension/size boundaries, `b.PDF` case semantics, deterministic ordering across two roots, complete-empty vs partial (`scanLimitReached`), result-limit flag, sprint-limit clamping, pre-cancel → `CANCELLED`, symlink file+dir not followed, symlink escape contained, progress cadence (250 + tail), unreadable root → `ACCESS_DENIED` (POSIX `setReadable(false)`).
- `DesktopAppServiceTest` — 9/9: recorded `LaunchSpec` equals `open -a Calculator`, all ids/aliases resolve (`calc`, `text editor`, `EDITOR`, `files`), unknown app → `UNKNOWN_APP`, pre-cancel → `CANCELLED`, Windows adapter → `UNSUPPORTED_PLATFORM`, launcher `IOException` → `SERVICE_FAILURE` with cause, `LaunchSpec` validation, resolver rejects unconfigured ids, `ProcessBuilder` receives the list verbatim (no shell wrapper anywhere).
- `OshiSystemInfoServiceTest` — 6/6: full snapshot passthrough, unavailable metrics stay empty (never fabricated/zero), blank OS fields → `unknown`, probe failure → `SERVICE_FAILURE`, pre-cancel → `CANCELLED`, default OSHI composition constructs.
- `SqliteHistoryRepositoryTest` — 14/14: schema + `user_version` setup, full-field roundtrip, **history survives reopen** (multiple cycles), `completed_at DESC, request_id DESC` ordering (deterministic UUIDs), limit bounds 0/−1/51 rejected as `INVALID_COMMAND`, bounded `LIMIT`, error columns persist, `CANCELLED` status persists, duplicate PK → `DATABASE_FAILURE`, idempotent close + use-after-close failure, null/blank path rejection, read cancellation, Unicode roundtrip.

No tests launch real applications; tests use JUnit `@TempDir` and disposable database files only. `mvn javafx:run` was verified separately (see Verification above); UI behaviour beyond the Stage A placeholder is OpenCode's scope.

## Environment / results caveats

- Local JDK is 25.0.2 with `maven.compiler.release=21` cross-compilation; Maven 3.9.16. Code compiles clean under release-21 semantics.
- SLF4J no-op warning from SQLite JDBC and a native-access warning on JDK 25 are benign.
- The unused-import JDK warning class is clean; compiler emits no warnings for the owned sources.

## Dependency requests

None. Pinned dependencies (JavaFX 21.0.10, SQLite JDBC 3.53.4.0, OSHI 6.8.3, JUnit 5.11.4) were sufficient. No `pom.xml` change requested.

## Limitations / integration notes

1. `FileSearchResult` carries no warning list; skipped-entry details live on `FileSystemFileSearchService.warnings()` (bounded). If the UI should show them, the gateway can read them after each search, or Codex can extend the frozen record next sprint.
2. `DesktopAppService` accepts any `ConfiguredApp` whose id maps in the OS adapter; ids outside `calculator`/`text-editor`/`file-manager` fail `UNSUPPORTED_PLATFORM` (sprint 1 scope).
3. Windows/Linux launching is deliberately unimplemented — clear `UNSUPPORTED_PLATFORM` failures, not guesses.
4. Storage/battery metrics need a `SystemSnapshot` extension (frozen record has no fields for them).
5. `search.roots` parsing (`${user.home}/Documents`, `${user.home}/Downloads`, `jarvis.search.roots` platform-path-separator list) is a composition concern for Codex: pass the resulting existing readable directories to `new FileSystemFileSearchService(List<Path> roots)`.
6. Configured apps for composition: `new ConfiguredApp("calculator","Calculator",Set.of("calculator","calc"))`, `new ConfiguredApp("text-editor","Text Editor",Set.of("text editor","editor"))`, `new ConfiguredApp("file-manager","File Manager",Set.of("file manager","files"))` (matches docs/COMMANDS.md).
7. **Request to Codex (`.gitignore`, not Freebuff-owned):** WAL mode creates `history.db-wal` and `history.db-shm` sidecars next to the database. The repo `.gitignore` covers `*.db` and `*.sqlite*` but not `*-wal`/`*-shm`, so a database placed in-repo could leak sidecar files into Git. Requested additions: `*-wal` and `*-shm`. Freebuff code itself never writes databases inside the repository.
8. History repository ownership note for the integrator: `SqliteHistoryRepository` performs no OS-action rollback — transactions are database-only (CONTRACTS.md lifecycle rule is honoured; e.g. a history-write failure after a successful app launch must never be reported as a failed launch).
