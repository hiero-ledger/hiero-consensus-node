# Architecture

The topic-organized lens on the consensus layer. Eleven topics, plus an overview and the module-boundary APIs.

Where `../concepts/` defines foundational mental models and the catalog directories (`../invariants/`, `../rules/`, `../decisions/`, `../scenarios/`, `../heuristics/`) capture cross-cutting claims, this directory walks the consensus layer one topic at a time. Each topic file describes its responsibilities, state, contracts, and code anchors, and cross-references the catalog entries that touch it.

## Top-level files

- `overview.md` — high-level shape of the consensus layer, adapted from `../../proposals/consensus-layer/Consensus-Layer.md` for KB use (pending).

## Topics

One file per topic, in `topics/`. Twelve topics total.

|                 Topic file                  |              Topic              |                                                          Summary                                                          |
|---------------------------------------------|---------------------------------|---------------------------------------------------------------------------------------------------------------------------|---|
| `topics/wiring-framework.md`                | Wiring framework                | Component-and-wire infrastructure that schedules and connects the consensus components.                                   |
| `topics/gossip.md`                          | Gossip                          | Peer-to-peer exchange of events: RPC sync, fair sync selection, permits, simple broadcast.                                |
| `topics/event-intake.md`                    | Event intake                    | Pipeline for incoming events — deduplication, validation, signature checks, parent linking, orphan buffering, PCES write. |
| `topics/event-creator.md`                   | Event creator                   | Generates self-events from local transactions, subject to rate limits and gating conditions.                              |
| `topics/hashgraph.md`                       | Hashgraph                       | The consensus algorithm: rounds, witnesses, strongly-seeing, voting, coin rounds, judges.                                 |
| `topics/health-monitor-and-backpressure.md` | Health monitor and backpressure | Queue-depth observability and the feedback loops that throttle the system when components fall behind.                    |
| `topics/reasons-not-to-gossip.md`           | Reasons not to gossip           | Conditions under which a node abstains from gossip — falling behind, syncing, freezing, replaying.                        |
| `topics/signed-state-management.md`         | Signed state management         | Periodic state snapshots, signature collection, reference counting, on-disk format.                                       |
| `topics/restart-and-pces.md`                | Restart and PCES                | The Preconsensus Event Stream and the replay path that restores consensus position after a crash.                         |
| `topics/freeze-and-upgrade.md`              | Freeze and upgrade              | Coordinated network freeze for software upgrades, including the signature handoff to execution.                           |
| `topics/reconnect.md`                       | Reconnect                       | Recovery path for a node that has fallen far behind and cannot catch up via gossip.                                       |
| `topics/quiescence.md`                      | Quiescence                      | Opt-in feature affecting stale-events routing to Execution.                                                               |   |

## Interfaces

Files in `interfaces/` describe public APIs that cross module boundaries.

|                Interface file                |                                                            Description                                                            |
|----------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `interfaces/consensus-execution-boundary.md` | The Consensus public API (`initialize`, `destroy`, `nextRound`, `onBehind`, `onPreHandleEvent`, `getTransactionsForEvent`, etc.). |
