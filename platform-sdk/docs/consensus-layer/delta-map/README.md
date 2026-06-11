# Delta map

Per-topic record of how the current code aligns with the proposed future-state design in `../../proposals/consensus-layer/Consensus-Layer.md`. The KB treats current code as canonical; the delta map is where the future shape and the work it implies are tracked.

One file per architecture topic. Each file states the topic's status, then enumerates what aligns, what doesn't, and (where known) why. For topics the proposal does not name (`iss-detection`, `quiescence`), rows record implied deltas and proposal-staleness gaps, marked as such in the file. `sheriff.md` is the reverse case: a proposal-only module with no current code and hence no architecture topic yet.

- Entry format: see `FORMAT.md`.

## Status values

- `done` — current code matches the proposed design.
- `partial` — partially aligned; gaps remain.
- `not-started` — design exists; code work has not begun.
- `divergent` — current code intentionally or unintentionally diverges from the design.

A file's index status rolls up its rows: `divergent` if any row is divergent, else `partial` if statuses are mixed, else the uniform value.

## Index

|                Topic                 |   Status    | Summary |
|--------------------------------------|-------------|---------|
| `wiring-framework.md`                | partial     | Framework internals match the proposal; the pulled module boundary has not begun. |
| `gossip.md`                          | partial     | Extraction and sync behaviour done; Sheriff discipline not started. |
| `event-intake.md`                    | partial     | Pipeline done; persistence and branch detection sit outside the intake module. |
| `event-creator.md`                   | partial     | Tipset and transaction pull done; the public Consensus API surface is missing. |
| `hashgraph.md`                       | partial     | Split and birth-round handling done; the `nextRound` pull API not started. |
| `health-monitor-and-backpressure.md` | partial     | Wire-level monitoring done; the module-level overlay not started. |
| `reasons-not-to-gossip.md`           | partial     | Existing gating rules in place; Sheriff verdicts not started. |
| `signed-state-management.md`         | divergent   | Lifecycle still platform-owned; state types moved consensus-side, not execution-side. |
| `restart-and-pces.md`                | divergent   | PCES done; restart ownership unchanged and ISS recovery conflicts with the proposed split. |
| `freeze-and-upgrade.md`              | not-started | Freeze orchestration still consensus-side; only metadata handling is execution-side, and that predates the proposal. |
| `reconnect.md`                       | divergent   | Tracks the reconnect-refactor proposal inside the consensus layer, not the proposed execution ownership. |
| `iss-detection.md`                   | divergent   | Detection and response still platform-owned; the proposal implies they move with the state lifecycle. |
| `quiescence.md`                      | divergent   | Postdates the proposal; the proposed public API has no quiescence operation. |
| `sheriff.md`                         | partial     | Proposal-only module; nothing exists beyond precursors already in code (sender attribution, branch detection). |
