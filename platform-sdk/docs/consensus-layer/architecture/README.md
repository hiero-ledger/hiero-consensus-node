# Architecture

The topic-organized lens on the consensus layer. Each topic file describes the topic's responsibilities, state, contracts, and links to related concepts, invariants, decisions, and scenarios.

## Top-level files

- `overview.md` — high-level shape, adapted from `Consensus-Layer.md` for KB use (pending).

## Topics

One file per topic, in `topics/`. Twelve topics total.

|                 Topic File                  |              Topic              | Summary |
|---------------------------------------------|---------------------------------|---------|
| `topics/wiring-framework.md`                | Wiring framework                |         |
| `topics/gossip.md`                          | Gossip                          |         |
| `topics/event-intake.md`                    | Event intake                    |         |
| `topics/event-creator.md`                   | Event creator                   |         |
| `topics/hashgraph.md`                       | Hashgraph                       |         |
| `topics/health-monitor-and-backpressure.md` | Health monitor and backpressure |         |
| `topics/reasons-not-to-gossip.md`           | Reasons not to gossip           |         |
| `topics/signed-state-management.md`         | Signed state management         |         |
| `topics/iss-detection.md`                   | ISS detection                   |         |
| `topics/restart-and-pces.md`                | Restart and PCES                |         |
| `topics/freeze-and-upgrade.md`              | Freeze and upgrade              |         |
| `topics/reconnect.md`                       | Reconnect                       |         |

## Interfaces

Files in `interfaces/` describe public APIs that cross module boundaries.

|                Interface File                |                                                            Description                                                            |
|----------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `interfaces/consensus-execution-boundary.md` | The Consensus public API (`initialize`, `destroy`, `nextRound`, `onBehind`, `onPreHandleEvent`, `getTransactionsForEvent`, etc.). |
