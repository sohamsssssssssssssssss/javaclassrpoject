# Project Decisions

- Sprint 1 only: typed commands, configured apps, bounded PDF search, system status and SQLite history.
- Java 21 is the compilation target. JavaFX 21.0.10 stays on the Java 21 LTS line.
- SQLite JDBC 3.53.4.0 bundles native SQLite libraries; OSHI 6.8.3 supplies metrics. These dependencies are not implemented in Java throughout.
- JavaFX, JDBC, OSHI and JUnit are the only dependencies. No DI framework: constructors receive interfaces directly.
- Search roots are explicit: select a folder in the first-run JavaFX setup or set `jarvis.search.roots`/`JARVIS_SEARCH_ROOTS` to a platform path-separator-delimited list. No developer-specific path is embedded.
- `jarvis.app.alias`/`JARVIS_APP_ALIAS` optionally adds one normalized alias for the configured Calculator app; it never supplies an executable or shell text.
- History defaults to the current user's platform application-data directory and can be relocated with `jarvis.data.dir`/`JARVIS_DATA_DIR`.
- Limits are fixed for sprint 1: 50 returned matches and 10,000 visited files per request. One MB is 1,048,576 bytes.
- Filename extension matching is Unicode-preserving and ASCII case-insensitive on the final filename suffix. Original path/name case is returned unchanged.
- Timestamps use `Instant` and persist as ISO-8601 UTC text.
- Audio feasibility (capture, offline STT/TTS candidates, device/permission failures and sample testing) is the immediate next sprint.
- Sprint 1 distribution is a host-specific `jpackage` app image. The verified artifact is macOS ARM64; Windows/Linux packages must be built and checked on those target platforms.

## Next sprint gate

Before voice integration, run a small Java-only feasibility check on the target laptop/architecture for microphone capture, Vosk speech recognition, one concrete TTS provider, and speaker enrollment/matching with Soham, Ved and unknown samples. Verify Java/native dependency compatibility and record every model's license and download size before integration. Wake-phrase recognition is not evidence of speaker identity.

## Verified dependency basis

- OpenJFX documents Maven platform-native resolution and JavaFX 21 requires macOS 11+/GTK 3. JavaFX 21.0.10 artifacts include macOS ARM64 natives.
- Xerial documents macOS ARM64 support and bundled native libraries for SQLite JDBC 3.53.4.0.
- OSHI 6.8.3 is the pinned 6.x release line; JUnit Jupiter 5.11.4 runs on Java 21.
