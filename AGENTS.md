# JADE Working Agreement

## Project decisions

- Java 21 application logic; JavaFX and JavaFX CSS for the desktop UI.
- HTML, CSS and SQL are the only non-Java source exceptions. No scripts or external ML services.
- One Maven project. SQLite/JDBC persists history; OSHI may read system metrics.
- Never execute raw natural-language shell text. Missing dependencies, cancellation and unsupported platforms are visible failures.
- Voice identity will personalize greetings only; it never grants permissions. Audio feasibility is the next sprint.
- File mutation, voice, LLM, automation and workspace restore are out of sprint 1.

## Exclusive ownership

- **Codex/Soham:** `pom.xml`, `com.jade.api`, `com.jade.app`, `com.jade.core`, central docs and integration.
- **OpenCode:** `com.jade.ui`, `src/main/resources/ui`, matching UI tests, `docs/handoffs/opencode.md`.
- **Freebuff:** `com.jade.services`, `src/main/resources/db`, matching service tests, `docs/handoffs/freebuff.md`.
- Workers request API/build/central-doc changes in their handoff; they do not edit another owner's files.
- `docs/handoffs/codex.md` belongs to Codex in Stage B.

## Gates and commits

- Before handoff: `mvn test` and `mvn javafx:run`; record exact results and blockers.
- Tests never launch real applications or write outside temporary directories.
- UI updates run on the JavaFX thread; blocking work uses bounded executors; shutdown closes subscriptions, executors and repositories.
- Commit only authored paths. No reset, forced checkout, cleanup of others' files, push, or history rewrite.
- Commit messages: short imperative subject, one logical ownership-scoped change per commit.
