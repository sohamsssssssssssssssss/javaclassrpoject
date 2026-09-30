# Codex Core Handoff

## Integration note

The registered `codex/core` worktree was clean at bootstrap `b3094c5` and contained no handoff or implementation commit. The integration lead supplied the missing core directly on `integration/sprint1`; no absent commit is claimed.

## Paths

- `src/main/java/com/jarvis/core/`: quote-aware tokenizer, frozen grammar parser, typed plans and sequential asynchronous gateway.
- `src/test/java/com/jarvis/core/`: parser, execution-count, cancellation and failure-preservation tests.

## Behaviour

- Exact sprint grammar only; no shell/file-target guessing.
- 50 result and 10,000 visited-file limits; MB means 1,048,576 bytes.
- Bounded execution is supplied by composition. Completion is emitted once and terminal outcomes are persisted.
- A history-write failure is added to the summary without replacing the command's original error/status.

Final Maven results and the integration commit SHA are recorded by the integration handoff/status.
