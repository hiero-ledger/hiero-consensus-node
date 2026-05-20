# Quiescence — Design Analysis

This document describes the quiescence feature in the hiero-consensus-node: what it is for, the components that implement it and how it behaves at runtime. It is intended as a primer for engineers landing in the quiescence package for the first time.

## 1. Intent of Quiescence

Quiescence is an opt-in consensus-layer optimization for the Hiero/Hedera consensus node. When the network has no useful work to do — no user transactions in flight, no scheduled transactions about to fire, no upcoming stake-period or freeze deadline — participating nodes stop creating new events. They keep gossiping, so any inbound event or transaction can break the silence, but they no longer advance consensus time gratuitously.

The motivation is cost. A Hedera mainnet network rarely sees zero load, but private networks, test networks, and quiet periods on permissioned networks can sit idle for long stretches. Without quiescence each node still produces events purely to advance the hashgraph clock, burning CPU, network bandwidth, and (because every event ends up in the block stream) disk and block-node storage.

The feature is off by default (`quiescence.enabled=false`) and is intended as a switchable optimization.

## 2. The Three Commands

`org.hiero.consensus.model.quiescence.QuiescenceCommand` is the central vocabulary:

- **`DONT_QUIESCE`** — normal operation; the event creator produces events freely.
- **`QUIESCE`** — the event creator must not create new events. The node still gossips and processes incoming events; an inbound event/transaction may change the node's local quiescence status.
- **`BREAK_QUIESCENCE`** — an edge case: the whole network is silent and a single node receives a user transaction. That node has no fresh parents to build a normal event on, so it must create one with whatever parents it has to restart event flow. Once gossiped, peers will see the new event and switch back to `DONT_QUIESCE`.

These commands are produced by the application-layer `QuiescenceController` and pushed into the platform via `Platform.quiescenceCommand(...)`. Inside the platform they are fanned out to `PlatformMonitor` (telemetry) and to `EventCreationManager`, which in turn updates `QuiescenceRule` (event-creation gate) and the event creator itself (logic for `BREAK_QUIESCENCE` parent-selection).

## 3. Conditions That Drive State

`QuiescenceController.getQuiescenceStatus()` is the source of truth. Pseudocode:

```
if !config.enabled || pipelineTransactionCount < 0: return DONT_QUIESCE  // disabled
if pipelineTransactionCount > 0:                    return DONT_QUIESCE  // pipeline busy
if nextTct != null && (nextTct - tctDuration) < now: return DONT_QUIESCE // TCT close
if pendingTransactionCount() > 0:                   return BREAK_QUIESCENCE
if (now - lastActivityAt) < gracePeriod:            return DONT_QUIESCE  // within grace
return QUIESCE
```

Inputs:

- `pipelineTransactionCount` — relevant transactions that have entered `Hedera.onPreHandle` but have not yet been included in a fully-signed block. Maintained by the controller itself.
- `pendingTransactionCount` (a `LongSupplier` wired to `TxPipelineTracker.estimateTxPipelineCount`) — the number of transactions this node accepted at ingest but has not yet seen land in either a pre-handled event or a stale event.
- `nextTct` (Target Consensus Time) — the earliest "must-wake-up" instant the node knows about: next stake-period boundary, earliest scheduled-transaction second, or freeze time. Discovered by `TctProbe` and pushed in by `QuiescedHeartbeat`.
- `lastActivityAt` (a `Supplier<Instant>` wired to `TxPipelineTracker.lastActivityAt`) — wall-clock instant of the most recent observed transaction activity. Updated on `incrementPreFlight` / `incrementInFlight` (local ingest), on `QuiescenceController.onPreHandle` when relevant transactions are observed (cross-node), and on `platformStatusUpdate(ACTIVE)` / `platformStatusUpdate(RECONNECT_COMPLETE)` anchors. **Not** updated on `blockFullySigned` — blocks are produced every `blockPeriod` regardless of user traffic, so doing so would keep the grace period perpetually refreshed and prevent quiescence from ever firing.

"Relevant transaction" excludes `StateSignatureTransaction` and `HintsPartialSignature` (`QuiescenceUtils.isRelevantTransaction`), since those are pre-consensus housekeeping; we should not stay awake on their account.

### Grace period

Without the grace period, the controller transitions to `QUIESCE` the instant the pipeline drains to zero. The platform's event creator then stops producing events. When the next user transaction arrives — possibly seconds or minutes later — the platform wakes and creates a fresh event whose consensus timestamp tracks current wall-clock. **Consensus time jumps forward by the idle duration**, and any code path that runs against the freshly-advanced consensus time can mass-act on stale state. Concretely, `RecordCacheImpl.purgeExpiredReceiptEntries` (RecordCacheImpl.java:398) computes `earliestValidStart = consensusTimestamp - transactionMaxValidDuration` and bulk-expires any receipts whose `validStart` is before that threshold. After a quiescence wake-up, receipts that were submitted seconds before the sleep are now several minutes "in the past" relative to consensus time and get evicted, surfacing as `RECORD_NOT_FOUND` to any subsequent `GetTxnRecord` query.

The `gracePeriod` config knob (default `5s`) holds the controller in `DONT_QUIESCE` for that duration after the most recent observed activity. Short inter-transaction gaps no longer put the network to sleep; the time-jump-and-bulk-expiry pattern only fires after a real idle stretch.

The grace period is also the lever that lets bundles tune *whether* `QUIESCE` is ever actually entered in their environment. The concurrent HAPI subtasks (`hapiTestMisc`, `hapiTestMiscRecords`, `hapiTestTimeConsuming`, `hapiTestAtomicBatch`) raise it to `30s` so the controller never crosses into `QUIESCE` during the boot-up gap before parallel specs start submitting — once they do, continuous ingest activity keeps the grace check satisfied indefinitely. The Serial variants and the dedicated quiescence subtask keep the 5s default and exercise the wake-up cycle for real.

## 4. Components

|                  Class                  |                Layer                 |                                                                                       Role                                                                                        |
|-----------------------------------------|--------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `QuiescenceController`                  | `hedera-app/quiescence`              | Tracks pipeline counts, per-block trackers, and TCT; exposes `getQuiescenceStatus()`. Thread-safe.                                                                                |
| `QuiescenceBlockTracker`                | `hedera-app/quiescence`              | Per-block helper accumulating `relevantTransactionCount` and `maxConsensusTime`. Single-threaded.                                                                                 |
| `TxPipelineTracker`                     | `hedera-app/quiescence`              | Two `AtomicInteger`s (`preFlightCount`, `inFlightCount`) tracking transactions seen at ingest.                                                                                    |
| `QuiescenceUtils`                       | `hedera-app/quiescence`              | "Relevant transaction" filter; counter over iterators; throws `BadMetadataException` on missing/invalid `PreHandleResult` metadata.                                               |
| `QuiescedHeartbeat`                     | `hedera-app/quiescence`              | Singleton scheduled-executor task started when status first becomes `QUIESCE`. Each tick: `probe.findTct()`, push to controller, re-poll status, stop and emit if status changed. |
| `TctProbe`                              | `hedera-app/quiescence`              | Reads `BlockStreamInfo`, `FreezeService`, `ScheduleService`, staking config to compute the earliest TCT. Stateful across calls within a probe instance.                           |
| `QuiescenceConfig`                      | `hedera-config`                      | `enabled` (default false) and `tctDuration` (default 5s — gap before TCT when node must wake).                                                                                    |
| `QuiescenceCommand`                     | `consensus-model`                    | Enum (3 values).                                                                                                                                                                  |
| `QuiescenceRule`                        | `consensus-event-creator-impl/rules` | `EventCreationRule` that returns `false` from `isEventCreationPermitted()` iff command is `QUIESCE`.                                                                              |
| `Platform.quiescenceCommand(...)`       | `swirlds-platform-core`              | Entry point; documented as last-writer-wins under concurrent calls.                                                                                                               |
| `PlatformCoordinator.quiescenceCommand` | `swirlds-platform-core/wiring`       | Fan-out: `PlatformMonitor` + `EventCreatorModule.quiescenceCommandInputWire`.                                                                                                     |

Integration callbacks (caller → method):

- `IngestWorkflowImpl.submitTransaction` → `TxPipelineTracker.incrementPreFlight`/`decrementPreFlight`/`incrementInFlight`.
- `Hedera.onPreHandle` → `QuiescenceController.onPreHandle` and (for self events) `TxPipelineTracker.countLanded`.
- `Hedera.onStaleEvent` → `QuiescenceController.staleEvent` and (for self events) `TxPipelineTracker.countLanded`.
- `Hedera.newPlatformStatus` → `QuiescenceController.platformStatusUpdate` (only `RECONNECT_COMPLETE` triggers a state reset).
- `HandleWorkflow.handleConsensusRound` → `QuiescenceController.inProgressBlockTransaction` per consensus txn.
- `BlockRecordManagerImpl` (RECORDS stream mode) → `startingBlock`, `finishHandlingInProgressBlock`, `switchTracker`, `blockFullySigned`, and `maybeQuiesce` (the legacy poll site).
- `BlockStreamManagerImpl` (BLOCKS stream mode) → same controller calls plus an inline copy of `maybeQuiesce` logic in the block-signing callback.
- `QuiescedHeartbeat.heartbeat` → `TctProbe.findTct`, `QuiescenceController.setNextTargetConsensusTime`, `QuiescenceController.getQuiescenceStatus`, and (when transitioning out) `Platform.quiescenceCommand`.

## 5. Workflow — Life of a Quiet Network

1. Network is processing transactions normally; `lastQuiescenceCommand = DONT_QUIESCE` on the block-stream manager. Each ingested transaction increments `preFlight`/`inFlight`; pre-handle increments `pipelineTransactionCount`; `inProgressBlockTransaction` accumulates per-block; `blockFullySigned` subtracts the block's relevant count back out of `pipelineTransactionCount` and updates `nextTct` based on the block's `maxConsensusTime`.
2. Traffic drops. All in-flight transactions land, all blocks fully sign, `pipelineTransactionCount` returns to zero, `pendingTransactionCount` returns to zero. On the next block-signing callback `getQuiescenceStatus()` returns `QUIESCE`. `BlockStreamManagerImpl` CAS-updates `lastQuiescenceCommand` from `DONT_QUIESCE` to `QUIESCE`, emits the command to the platform, and starts `QuiescedHeartbeat`.
3. The platform routes `QUIESCE` to `PlatformMonitor` and to `EventCreationManager`. `QuiescenceRule` flips to "no events." Event creation stops.
4. `QuiescedHeartbeat` ticks at `blockStream.quiescedHeartbeatInterval`. Each tick `TctProbe.findTct()` examines state and returns the earliest of: next stake-period start, an upcoming scheduled-transaction second within the probe window, or the freeze time. The result is pushed to the controller. The controller's status is re-queried.
5. While the network is genuinely idle, status remains `QUIESCE` and the heartbeat keeps ticking. As long as the TCT-tctDuration window has not opened, the controller returns `QUIESCE`.
6. **Exit path A — local transaction arrives.** A user submits a transaction to this node. `pendingTransactionCount` becomes positive. The next time the controller is queried (by the heartbeat tick, or by `maybeQuiesce` if a block signing fires) it returns `BREAK_QUIESCENCE`. The heartbeat emits `BREAK_QUIESCENCE` to the platform and stops itself. The event creator produces one quiescence-breaking event. Gossip propagates it; peers' incoming transactions update their pipeline counts and they too leave QUIESCE.
7. **Exit path B — TCT window opens.** The heartbeat tick observes `now > tct - tctDuration` and returns `DONT_QUIESCE`. The heartbeat emits `DONT_QUIESCE` and stops itself.
8. **Exit path C — `RECONNECT_COMPLETE`.** `Hedera.newPlatformStatus` invokes `controller.platformStatusUpdate(RECONNECT_COMPLETE)`, which zeroes `pipelineTransactionCount` and clears `blockTrackers`. The controller will re-derive state from incoming events.
9. **Failure mode — `disableQuiescence`.** Any unexpected exception, a duplicate `blockFinalized`, a missing block tracker, a `BadMetadataException`, or a negative pipeline count sets `pipelineTransactionCount` to `Long.MIN_VALUE/2`. `isDisabled()` then returns true forever (until process restart or, after the fix, until the next `RECONNECT_COMPLETE`); the controller behaves as if quiescence were turned off.

## 6. Test Coverage

**Unit tests** (`hedera-node/hedera-app/src/test/java/com/hedera/node/app/quiescence/`):

- `QuiescenceControllerTest` — covers transitions, signature filtering (state sig + hints sig ignored), stale events, the TCT threshold including consensus-time-advances-past-TCT clearing, platform status `RECONNECT_COMPLETE` reset, break-quiescence with pending transactions, and the various `switchTracker` / `finishHandlingInProgressBlock` / `inProgressBlockTransaction` shapes. Notably, **the EPOCH-as-deadline behavior is not negative-tested**; tests pass because they set `nextTct` to an explicit non-EPOCH future time via `setNextTargetConsensusTime`.
- `QuiescedHeartbeatTest` — uses an injected `ScheduledExecutorService` and captures the runnable, then drives it manually. Covers start/stop/shutdown idempotency, the probe→controller→platform happy path, and a thorough set of exception paths (probe throws, controller throws, platform throws).
- `TctProbeTest` — exhaustive on TCT-source priority. **Encodes the EPOCH-as-deadline behavior** as expected (`findTctUsesEpochWhenFreezeTimeIsNull`, `findTctWithFreezeTimeAndNoStakePeriod`). These assertions are *backwards*: they should be checking that the probe returns null in those scenarios.
- `TxPipelineTrackerTest` — preflight/inflight counter mechanics.

**Platform-layer tests** (`platform-sdk/consensus-otter-tests/`):

- `testOtter/.../QuiescenceTest` — full Otter integration test on a 4-node network. Sends `QUIESCE` to all nodes, asserts no rounds advance, sends `BREAK_QUIESCENCE` to one node, asserts a "Created quiescence breaking event" log line on that node, sends `DONT_QUIESCE`, asserts ≥20 more rounds advance. Continuous assertions check no error logs and no reconnect attempts.
- `testIntegration/.../QuiescenceTest` — a placeholder integration test (Turtle + Container) that exercises the command paths without observing semantic effects. Self-described as "just a temporary test until quiescence is implemented."

**HAPI system test** — `QuiesceThenMixedOpsRestartTest`
