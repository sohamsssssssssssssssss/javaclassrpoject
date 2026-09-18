# JARVIS

JARVIS is a Java 21/JavaFX desktop assistant. Sprint 1 provides real typed commands for bounded PDF filename search, system status, configured macOS application launching and persistent SQLite history.

## Setup

Required to build: JDK 21+ and Maven 3.9+. The build compiles with `--release 21`. The final Sprint 1 gate used OpenJDK 25.0.2 and Maven 3.9.16 on macOS 27 ARM64. JavaFX 21 requires macOS 11+ or Linux with GTK 3; Windows is also supported by JavaFX, but JARVIS application launching is currently macOS-only.

Choose the search folder in the first-run window, or configure it before launch:

```sh
mvn -Djarvis.search.roots="/path/to/demo-folder" javafx:run
```

Multiple roots use the OS path separator (`:` on macOS/Linux, `;` on Windows). Optional settings:

```sh
mvn \
  -Djarvis.search.roots="/path/to/demo-folder" \
  -Djarvis.app.alias="my calculator" \
  -Djarvis.data.dir="/path/to/app-data" \
  javafx:run
```

Equivalent environment variables are `JARVIS_SEARCH_ROOTS`, `JARVIS_APP_ALIAS` and `JARVIS_DATA_DIR`. The custom alias maps only to the configured Calculator app; it is never interpreted as a command or executable path. Without a data override, history is stored in the platform user application-data directory.

## Build and distribution

```sh
mvn test
mvn -DskipTests package
java -jar target/jarvis.jar
```

On a JDK containing `jpackage`, create a platform-specific app image from the packaged jar. Use a clean input directory so build reports are not bundled:

```sh
mkdir -p target/jpackage-input target/release
cp target/jarvis.jar target/jpackage-input/jarvis.jar
jpackage --type app-image --name JARVIS --input target/jpackage-input \
  --main-jar jarvis.jar --main-class com.jarvis.app.JarvisLauncher --dest target/release
```

On macOS, launch the generated image with `open target/release/JARVIS.app`. App images are specific to the OS and architecture that created them.

## Supported commands

- `open calculator` / `open calc`
- `open "text editor"` / `open editor`
- `open "file manager"` / `open files`
- `find PDFs`
- `find PDF files`
- `find PDFs larger than 20 MB`
- `system status` / `status`
- `show history` / `history`

Search returns at most 50 matches and visits at most 10,000 regular files. It never follows symbolic links. MB means 1,048,576 bytes. Filenames and paths retain their original case.

Application launch is implemented on macOS using explicit `ProcessBuilder` argument lists. Windows/Linux return a visible unsupported-platform error. A successful outcome means the OS accepted the launch request; it does not claim the window was observed.

Not available this sprint: voice, speaker identity, context follow-ups, file mutation/undo, LLMs, automation, document-content search and workspace restore.
