# Project Decisions

- Sprint 1 only: typed commands, configured apps, bounded PDF search, system status and SQLite history.
- Sprint 2 adds: generalized bounded filename search (known extensions, size operators/units, date windows), create folder, move/copy/rename inside the configured scope with confirmation preview, SQLite-journalled undo of moves and renames, and per-session search-result selection ("move these files…", "rename the newest…").
- Audio feasibility research is complete (docs/AUDIO_FEASIBILITY.md): Vosk STT + Vosk SpeakerModel + native OS TTS behind a SpeechSynthesisService seam; the on-target verification checklist must pass before any voice integration.
- Java 21 is the compilation target. JavaFX 21.0.10 stays on the Java 21 LTS line.
- SQLite JDBC 3.53.4.0 bundles native SQLite libraries; OSHI 6.8.3 supplies metrics. These dependencies are not implemented in Java throughout.
- JavaFX, JDBC, OSHI and JUnit are the only dependencies. No DI framework: constructors receive interfaces directly.
- Search roots are explicit: select a folder in the first-run JavaFX setup or set `jade.search.roots`/`JADE_SEARCH_ROOTS` to a platform path-separator-delimited list. No developer-specific path is embedded.
- `jade.app.alias`/`JADE_APP_ALIAS` optionally adds one normalized alias for the configured Calculator app; it never supplies an executable or shell text.
- History defaults to the current user's platform application-data directory and can be relocated with `jade.data.dir`/`JADE_DATA_DIR`.
- Limits are fixed for sprint 1: 50 returned matches and 10,000 visited files per request. One MB is 1,048,576 bytes.
- Filename extension matching is Unicode-preserving and ASCII case-insensitive on the final filename suffix. Original path/name case is returned unchanged.
- Timestamps use `Instant` and persist as ISO-8601 UTC text.
- Sprint 2 file-mutation safety: sources and targets must resolve inside configured roots (no symlink following, no `..` traversal), targets are never overwritten (`TARGET_EXISTS`), directories are never moved/copied into their own descendants, and JADE has no delete operation at all.
- Risk levels per master plan §28: create/copy MEDIUM, move/rename HIGH, delete CRITICAL (unimplemented). Every mutation command asks once for its whole planned batch through the ConfirmationHandler seam; denial is a structured REJECTED outcome with no filesystem effect. With no handler configured, mutations are rejected CONFIRMATION_REQUIRED.
- Undo journalling covers genuinely reversible operations only: moves and renames (migration 002, per-row status so repeat undo is idempotent). Copies and folder creation are deliberately not undoable and are reported as such. Undo moves never overwrite an occupied original location.
- Cached search results are cleared after every mutation or undo so stale selections can never be replayed against a changed filesystem.
- Date phrases resolve against an injected Clock: "today" is a lower bound of local midnight; "yesterday"/"on <weekday>" are exactly that calendar day (inclusive start, exclusive end); "this week" starts Monday 00:00; "this month" the 1st 00:00.
- Audio feasibility (capture, offline STT/TTS candidates, device/permission failures and sample testing) is the immediate next sprint.
- Sprint 3 voice: Vosk is pinned to **0.3.38** — the 0.3.45 binding/native pair is mismatched on macOS (`vosk_recognizer_set_grm` missing from the bundled dylib). The darwin dylib is universal (x86_64+arm64), so 0.3.38 loads natively on Apple Silicon.
- Voice convergence rule: a spoken transcript enters the EXISTING gateway as a plain `CommandRequest`; voice never gets a second parser or a privileged path.
- Wake phrase "hello jade" is detected on the STT transcript (greeting word + wake word, known mishearings tolerated); no neural wake-word model. Speaker identity (Soham/Ved/Unknown, x-vector cosine ≥ 0.60 threshold) personalizes greetings only and never grants permissions.
- Vosk models are user-installed under `jade.audio.model.dir` / `jade.audio.spk.model.dir` and never committed. Without them, JADE runs normally with voice visibly unavailable.
- TTS is the platform adapter seam (`SpeechSynthesisService`); the shipped implementation is macOS `/usr/bin/say` with an explicit, unit-tested argument list. Other platforms fail visibly.
- Sprint 1 distribution is a host-specific `jpackage` app image. The verified artifact is macOS ARM64; Windows/Linux packages must be built and checked on those target platforms.

## Next sprint gate

Sprint 4 candidates: live-mic enrollment command, streaming partial transcripts in the UI, re-tuning the speaker threshold with real owners' voices, Windows SAPI TTS adapter behind the same seam, and packaging the voice stack into the jpackage image with the model-install step documented for end users.

## Sprint 4 file intelligence decisions (2026-09-19)

- The verified file/document service layer is ported into this module (`com.jade.services.files`), following the existing services-porting pattern; it stays the single source of extraction/search behavior.
- Mutations continue to flow exclusively through `ScopedFileMutationService` + confirmation; the ported `FileService` mutation methods are not routed, so no command can bypass the tested safety/undo layer.
- `list files` and `file info` reuse `FileSearchResult`; `list files` seeds the session context so selection commands compose. `file info` resolves one scope file by case-insensitive name and rejects missing/ambiguous names.
- Content search (`find <ext> files containing "text"`) runs over the last search result of the session through the injected `ContentSearchService`; the 100-document service limit is preserved, and per-document extraction failures are surfaced (`extractionFailed` + structured reason) instead of reading as zero matches.
- Tika 2.8.0 (`tika-core` + `tika-parsers-standard-package`) is the only new dependency; shaded-jar service files for Tika Parser/Detector are appended so parser discovery survives packaging.
- Secondary worktree `jarvis-freebuff-services` (its own Git repository, branch `freebuff/services`) produced an independent draft of the same file/document layer; per the freeze directive it is NOT merged. Its transferable findings are already embodied in this module's ported, tested code: `tika-core` alone ships no parsers (the standard parser package is required), zero-byte inputs are rejected before format detection, and rename destinations resolve against the source's own directory. Any future sync must go through this module's tests, not a blind copy.

## Sprint 4A conversational context decisions (2026-09-19)

- This is **bounded conversational file context**, not general conversational intelligence: the context is typed (previous `FileSearchQuery`, current result set, explicit selection, pending confirmation), in-memory, per-session, and owned by the gateway behind one state lock. No embeddings, no vector store, no transcript memory, no new schema.
- Refinement composes on the stored structured query — restated dimensions replace, the rest are inherited. The previous user sentence is never re-parsed.
- Newest/oldest ordering is deterministic: modification time first, highest absolute path string as tie-break — never filesystem iteration order.
- `it`/`the file` resolve only when exactly one referent exists (explicit selection, else sole result); otherwise the command fails honestly instead of guessing.
- Confirmation split: with a `ConfirmationHandler` (GUI dialog) the sprint-2 inline preview→decision→execute flow is unchanged; handler-less compositions now defer — the mutation command stores the exact typed preview + pre-resolved operations and returns a `FileMutationPreview` with no disk effect, and the typed `confirm` executes precisely those stored operations. `confirm` never re-parses text and never repeats.
- `undo that` is the existing `CommandPlan.Undo` — one journal, no second undo system.
- File opening is the `FileOpener` seam (`/usr/bin/open` on macOS, `xdg-open` on Linux, visible refusal elsewhere), always on a scope-checked, existence-checked path resolved by the gateway.

## Verified dependency basis

- OpenJFX documents Maven platform-native resolution and JavaFX 21 requires macOS 11+/GTK 3. JavaFX 21.0.10 artifacts include macOS ARM64 natives.
- Vosk 0.3.38 (Apache-2.0) bundles a universal darwin dylib verified with `lipo`/`nm` on this machine; JNA 5.7.0 comes transitively. Models: vosk-model-small-en-us-0.15 (~41 MB) and vosk-model-spk-0.4 (~14 MB), both Apache-2.0, downloaded from alphacephei.com and unpacked outside Git.
- Xerial documents macOS ARM64 support and bundled native libraries for SQLite JDBC 3.53.4.0.
- OSHI 6.8.3 is the pinned 6.x release line; JUnit Jupiter 5.11.4 runs on Java 21.
