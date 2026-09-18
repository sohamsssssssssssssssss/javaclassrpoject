# Status

## Sprint 1 integration

- Branch/worktree: `integration/sprint1` / `jarvis-integration`.
- Typed parser, sequential gateway, JavaFX UI, real services and SQLite history are composed.
- Search configuration is user-selected; no personal path is committed.
- macOS application launching is implemented. Windows/Linux launching is visibly unsupported.
- Integrated commits, in order: core `bc87bff`, UI `8a033f9` (from `e7c75b7`), services `3400ce6` and handoff `74c0802` (from `264964c` and `13d9410`). The bootstrap `b3094c5` appears once.
- Final gate on 2026-09-18: `mvn clean package` passed 50 tests with zero failures, errors or skips and produced `target/jarvis.jar`.
- The real integration test covers the 50-result PDF bound, the 10,000-file configured scan ceiling, size filtering, unknown input, an unavailable app, live system status, and SQLite history persistence across reopen. It uses only JUnit temporary directories and does not launch applications.
- `jpackage` 25.0.2 produced the ARM64 macOS image at `target/release/JARVIS.app`. Its Mach-O launcher and bundled JavaFX UI were launched on macOS 27; the window was visibly observed with a temporary search scope.
- A temporary Java-only GUI smoke harness submitted `system status` through `CommandUI` into the real gateway/OSHI service and observed accurate OS, architecture, CPU and memory labels. macOS denied synthetic keystrokes, so no manual app-launch command was performed or claimed.
- Verified toolchain: OpenJDK 25.0.2, Maven 3.9.16, Java 21 release target, macOS 27.0 ARM64. JDK 21+ and Maven 3.9+ remain the documented build minimum.
- Voice and speaker identity are not implemented; their Java-only feasibility gate is next.
