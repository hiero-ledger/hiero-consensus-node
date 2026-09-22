# Delta map

Per-topic record of how the current code aligns with the proposed future-state design in `../../proposals/consensus-layer/Consensus-Layer.md`. The KB treats current code as canonical; the delta map is where the future shape and the work it implies are tracked.

One file per architecture topic. Each file states the topic's status, then enumerates what aligns, what doesn't, and (where known) why. For topics the proposal does not name (`iss-detection`, `quiescence`), rows record implied deltas and proposal-staleness gaps, marked as such in the file. `sheriff.md` is the reverse case: a proposal-only module with no current code and hence no architecture topic yet. `consensus-boundary.md` is a further exception: it mirrors an architecture *interface* (`../architecture/interfaces/consensus-execution-boundary.md`), not a `topics/` file, and catalogues the Consensus/Execution public-API seam. The `wiring-framework` topic has no delta-map — the proposal disclaims the wiring framework as implementation detail, so its genuine seam deltas live in `consensus-boundary.md`.

- Entry format: see `FORMAT.md`.

## Status values

- `done` — current code matches the proposed design.
- `partial` — partially aligned; gaps remain.
- `not-started` — design exists; code work has not begun.
- `divergent` — current code intentionally or unintentionally diverges from the design.

A file's index status rolls up its rows: `divergent` if any row is divergent, else `partial` if statuses are mixed, else the uniform value.

## Index

|                Topic                 |   Status    |                                                                  Summary                                                                   |
|--------------------------------------|-------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| `consensus-boundary.md`              | not-started | The overarching Consensus/Execution seam — API/impl module pair, unified API surface, and the `initialize` / `destroy` instance lifecycle. |
| `gossip.md`                          | partial     | Extraction and sync behaviour done; Sheriff discipline not started.                                                                        |
| `event-intake.md`                    | partial     | Pipeline and persistence done (PCES permanently its own module); branch detection remains partial.                                         |
| `event-creator.md`                   | partial     | Tipset and transaction pull done; the public Consensus API surface is missing.                                                             |
| `hashgraph.md`                       | partial     | Split and birth-round handling done; the `nextRound` pull API not started.                                                                 |
| `health-monitor-and-backpressure.md` | partial     | Proposal's emergent backpressure (birth-round, tipset) done; `nextRound` not started.                                                      |
| `reasons-not-to-gossip.md`           | partial     | Persistence and fallen-behind done; Sheriff shunning and dynamic throttles not started.                                                    |
| `signed-state-management.md`         | not-started | Execution ownership not begun; state types extracted within the consensus layer.                                                           |
| `restart-and-pces.md`                | partial     | PCES done and permanently its own module by decision; restart and ISS ownership not yet moved.                                             |
| `freeze-and-upgrade.md`              | not-started | Proposal-silent; Execution ownership only implied. Orchestration still consensus-side; metadata handling predates the proposal.            |
| `reconnect.md`                       | not-started | Execution ownership not begun.                                                                                                             |
| `iss-detection.md`                   | not-started | Detection and response still platform-owned; the proposal implies they move with the state lifecycle.                                      |
| `quiescence.md`                      | divergent   | Postdates the proposal; the proposed public API has no quiescence operation.                                                               |
| `sheriff.md`                         | partial     | Proposal-only module; nothing exists beyond precursors already in code (sender attribution, branch detection).                             |
