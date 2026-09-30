# Project Decisions

- Sprint 1 only: typed commands, configured apps, bounded PDF search, system status and SQLite history.
- Java 21 is the compilation target. JavaFX 21.0.10 stays on the Java 21 LTS line.
- SQLite JDBC 3.53.4.0 bundles native SQLite libraries; OSHI 6.8.3 supplies metrics. These dependencies are not implemented in Java throughout.
- JavaFX, JDBC, OSHI and JUnit are the only dependencies. No DI framework: constructors receive interfaces directly.
- Default search roots are runtime-derived `${user.home}/Documents` and `${user.home}/Downloads`; only existing readable directories are used. `jarvis.search.roots` may replace them with the platform path-separator-delimited explicit list.
- Limits are fixed for sprint 1: 50 returned matches and 10,000 visited files per request. One MB is 1,048,576 bytes.
- Filename extension matching is Unicode-preserving and ASCII case-insensitive on the final filename suffix. Original path/name case is returned unchanged.
- Timestamps use `Instant` and persist as ISO-8601 UTC text.
- Audio feasibility (capture, offline STT/TTS candidates, device/permission failures and sample testing) is the immediate next sprint.

## Verified dependency basis

- OpenJFX documents Maven platform-native resolution and JavaFX 21 requires macOS 11+/GTK 3. JavaFX 21.0.10 artifacts include macOS ARM64 natives.
- Xerial documents macOS ARM64 support and bundled native libraries for SQLite JDBC 3.53.4.0.
- OSHI 6.8.3 is the pinned 6.x release line; JUnit Jupiter 5.11.4 runs on Java 21.
