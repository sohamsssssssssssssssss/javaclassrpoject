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
- `find PDFs`, `find pdf files`, `find txt files`, `find jpgs` (known extensions)
- `find PDFs larger than 20 MB`, `find zip files smaller than 1 GB`
- `find pdfs from yesterday`, `find txt files from today`, `find pdfs from this week`, `find pdfs from this month`, `find pdfs from saturday`
- `create folder called College`
- `move these files to Review` / `copy these files to Review` (acts on the last search result)
- `rename the newest to summary`
- `undo` (reverses the last move/rename command)
- `system status` / `status`
- `show history` / `history`
- `list files` (top-level files of the scope; usable as a selection)
- `file info <name>` / `what is <name>` (top-level scope files, case-insensitive)
- `find <ext> files containing "text"` (document-content search over the last search of the session; TXT, PDF, DOCX and other Tika-supported formats; at most 100 documents)

Search returns at most 50 matches and visits at most 10,000 regular files. It never follows symbolic links. MB means 1,048,576 bytes. Filenames and paths retain their original case.

File mutations are confined to the configured scope folder: JARVIS never follows symlinks, never overwrites an existing target, never moves a folder into itself, and has no delete capability. Every move/copy/rename/create command shows a preview of exactly which files will be affected and asks for confirmation before anything changes; cancelling is always safe. Moves and renames can be undone with `undo`; copies and folder creations deliberately cannot.

Application launch is implemented on macOS using explicit `ProcessBuilder` argument lists. Windows/Linux return a visible unsupported-platform error. A successful outcome means the OS accepted the launch request; it does not claim the window was observed.

Voice input (optional Vosk model, see `docs/AUDIO_FEASIBILITY.md`), speaker-identified greetings and document-content search (Apache Tika) are available as of sprints 3–4; spoken commands use exactly the same grammar as typed commands. Still not available: multi-step planning, LLMs, automation, file deletion and workspace restore.
