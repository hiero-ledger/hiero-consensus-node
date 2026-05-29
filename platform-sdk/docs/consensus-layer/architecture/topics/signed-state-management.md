---
title: Signed state management
kind: architecture-topic
last_reviewed: TBD
---

# Signed state management

## Responsibilities

Signed-state management owns the production, signing, persistence, runtime
reservation, and reclamation of the per-round `SignedState` objects that
form the ledger's history. Each round's state is
captured immediately after consensus, accumulates signatures from peers
until quorum, optionally serializes to disk, and is eventually destroyed
once no live caller holds a reservation.

In scope:

- Producing a `SignedState` at every block boundary and at the freeze round.
- Loading the starting `SignedState` from disk at startup.
- Collecting state signatures from other nodes.
- Deciding when a round's state is persisted.
- Writing and reading the on-disk snapshot.
- Exposing the latest immutable state and the latest complete signed state
  to consumers outside the module.
- Reservation / reference-counting for in-memory access.
- Asynchronous deletion of unreserved states.

Out of scope (covered by sibling topics):

- PCES replay procedure — see [restart-and-pces.md](restart-and-pces.md).
- Reconnect-side state transfer — see [reconnect.md](reconnect.md).
- Freeze procedure mechanics — see [freeze-and-upgrade.md](freeze-and-upgrade.md).

## Runtime types

### `SignedState`

Defined in
[`SignedState.java`](../../../../consensus-state/src/main/java/org/hiero/consensus/state/signed/SignedState.java).
Holds a round's `State`, hash, `SigSet`, and freeze flag, and exposes
`reserve(reason)` to obtain a `ReservedSignedState`. When its reference
count reaches zero, an internal callback enqueues the state for
asynchronous deletion.

### `ReservedSignedState`

Defined in
[`ReservedSignedState.java`](../../../../consensus-state/src/main/java/org/hiero/consensus/state/signed/ReservedSignedState.java).
An auto-closeable wrapper carrying a single reservation on a `SignedState`.
`close()` releases the reservation; while at least one reservation is
outstanding the underlying state is guaranteed not to be destroyed. Use
inside a try-with-resources block whenever possible.

### `SignedStateReference`

Defined in
[`SignedStateReference.java`](../../../../consensus-state/src/main/java/org/hiero/consensus/state/signed/SignedStateReference.java).
Effectively an `AtomicReference<SignedState>` with reservation
bookkeeping: `set(state, reason)` closes the previous reservation and
takes a new one on the incoming value, and `getAndReserve(reason)`
returns a fresh `ReservedSignedState` for the caller to close.
Currently used only by `RecoveryPlatform` in `swirlds-cli`, the offline
recovery tool. In-node holders use the two nexus classes described
under [Latest-state exposure](#latest-state-exposure) instead.

### `StateWithHashComplexity`

Defined in
[`StateWithHashComplexity.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/eventhandling/StateWithHashComplexity.java).
Record that pairs a `ReservedSignedState` with an estimate of how
expensive hashing the state will be (measured in applied transactions,
minimum 1). The wiring graph carries this record from the
`TransactionHandler` through `SavedStateController` to `StateHasher`,
where the complexity figure feeds the scheduler's health monitor.

## Lifecycle

A signed state passes through six phases.

1. **Create.** Only consensus rounds that close a block, plus the
   freeze round, produce a `SignedState`.
   `DefaultTransactionHandler#handleConsensusRound`
   ([`DefaultTransactionHandler.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/eventhandling/DefaultTransactionHandler.java))
   applies each round's transactions to the mutable state and then
   calls `ConsensusStateEventHandler#onSealConsensusRound`, which
   returns whether the round aligns with the end of a block. If it
   does — or if this is the freeze round — the handler takes an
   immutable copy of the state and constructs a fresh `SignedState`.
   Other rounds yield no `SignedState`; their transaction count is
   accumulated into the next boundary round's hash-complexity estimate.
   Restricting state creation to block boundaries ensures that every
   persisted snapshot is a point Execution can cleanly restart from
   and that the hashes published into the block stream cover whole
   blocks. The first `SignedState` a network ever produces — the
   genesis state — follows this same lifecycle but is marked with
   `FIRST_ROUND_AFTER_GENESIS` so that it is always written to disk.
2. **Hash and locally sign.** `DefaultStateHasher#hashState`
   ([`DefaultStateHasher.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/hasher/DefaultStateHasher.java))
   forces computation of the merkle root, and
   `DefaultStateSigner#signState`
   ([`DefaultStateSigner.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/signer/DefaultStateSigner.java))
   produces a `StateSignatureTransaction` containing this node's
   signature over the hash. The transaction is submitted to Execution
   for inclusion in the gossiped event stream. (PCES-replay rounds are
   not signed again — they reached consensus before the restart and
   their original signatures are already in this node's PCES log, so
   re-signing would only duplicate what replay is about to surface.)
3. **Collect peer signatures.**
   `DefaultStateSignatureCollector#addSignature`
   ([`DefaultStateSignatureCollector.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/signed/DefaultStateSignatureCollector.java))
   accumulates signatures from `StateSignatureTransaction` payloads sent
   by peers, adding them to the state's `SigSet` until the
   signing-weight threshold is reached. Collection is best-effort, not
   guaranteed: states that have not yet collected a threshold of
   signatures are parked in the collector's `incompleteStates` map
   and purged once they fall behind
   `lastStateRound - stateConfig.roundsToKeepForSigning + 1` (default
   26 rounds). A purged state still flows downstream — if it was
   marked for saving (step 4) it is written to disk with an incomplete
   `SigSet`, and `DefaultStateSnapshotManager` logs the shortfall via
   `InsufficientSignaturesPayload` and increments
   `totalUnsignedDiskStates`. Freeze states bypass the parking step:
   they are expected to lack quorum and are emitted immediately on
   arrival at the collector.
4. **Decide to save.** `DefaultSavedStateController#shouldSaveToDisk`
   ([`DefaultSavedStateController.java:111`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/components/DefaultSavedStateController.java))
   marks freeze states for saving unconditionally; for non-freeze rounds
   it tests whether the round's consensus timestamp crosses a
   `stateConfig.saveStatePeriod` boundary (read at line 116; the period
   crossing is computed at lines 133-134). When saving is selected, the
   controller calls `signedState.markAsStateToSave(reason)` (line 92).
   The `reason` is one of `FREEZE_STATE`, `FIRST_ROUND_AFTER_GENESIS`,
   or `PERIODIC_SNAPSHOT`
   ([`StateToDiskReason.java`](../../../../consensus-state/src/main/java/org/hiero/consensus/state/snapshot/StateToDiskReason.java));
   on reconnect, `SavedStateController#reconnectStateReceived` applies
   `RECONNECT` to the incoming state instead. (The enum contains
   additional values — `ISS`, `FATAL_ERROR`, `PCES_RECOVERY_COMPLETE`
   — that have no current production caller.)
5. **Write.** `SignedStateFileWriter#writeSignedStateToDisk` writes inside
   `executeAndRename`. After the state files are written, it copies PCES
   files into the round directory by calling
   `pcesModule.copyPcesFilesRetryOnFailure`
   ([`SignedStateFileWriter.java:303`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/snapshot/SignedStateFileWriter.java)).
6. **Reclaim.** `DefaultStateGarbageCollector#heartbeat`
   ([`DefaultStateGarbageCollector.java`](../../../../consensus-state/src/main/java/org/hiero/consensus/state/signed/DefaultStateGarbageCollector.java))
   destroys states whose reservation count has reached zero, off the hot
   path.

The freeze-state branch of step 4 is part of the freeze procedure
described in [freeze-and-upgrade.md](freeze-and-upgrade.md). The
`RECONNECT` reason set in `reconnectStateReceived` connects to the flow
described in [reconnect.md](reconnect.md). Those flows are not
reproduced here.

## On-disk layout

`SignedStateFileWriter.writeSignedStateToDisk`
([`SignedStateFileWriter.java:362`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/snapshot/SignedStateFileWriter.java))
is the entry point used whenever a signed state is persisted — periodic
snapshot, freeze state, or state dump. The writer computes the round
directory via
[`SignedStateFilePath`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/snapshot/SignedStateFilePath.java):

```
<savedStateDirectory>/<mainClassName>/<selfId>/<swirldName>/<round>/
```

The whole round directory is built under a temporary path and moved into
place via `executeAndRename`
([`SignedStateFileWriter.java:386`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/snapshot/SignedStateFileWriter.java)),
so readers never observe a half-built directory; on a mid-write crash the
temporary tree is orphaned without affecting the live `saved/…/<round>/`
hierarchy.

A complete round directory contains:

- `stateMetadata.txt` — human-readable key/value file written by
  [`SavedStateMetadata`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/snapshot/SavedStateMetadata.java).
- `hashInfo.txt` — mnemonic of the state hash, diagnostic only.
- `currentRoster.json` — the active `Roster` as PBJ JSON.
- `consensusSnapshot.json` — the round's `ConsensusSnapshot` as PBJ JSON.
- `signatureSet.pbj` — the `SigSet` as PBJ binary.
- `settingsUsed.txt` — effective configuration dump.
- `data/` — the state snapshot files.
- A PCES sub-tree of event files needed to replay state from this round.

Files inside `data/` and the PCES sub-tree are **hard-linked** from the
live working directory rather than byte-copied, keeping snapshots cheap and
preserving the immutable view even if compaction later removes the
originating files.

For the full schema, file-by-file format, and field-by-field detail, see
[signed-state-snapshot-spec.md](../../../core/signed-state-snapshot-spec.md).

### Reading

[`SignedStateFileReader.readState`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/snapshot/SignedStateFileReader.java)
is the canonical reader for the on-disk format. In production it is
reached through
[`StartupStateUtils.loadStateFile`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/signed/StartupStateUtils.java),
which enumerates the round directories under
`<savedStateDirectory>/<mainClassName>/<selfId>/<swirldName>/` via
`SignedStateFilePath.getSavedStateFiles()` and walks them newest-first,
returning the first state that deserializes successfully. If no on-disk
state is found, a null-reservation is returned and the node starts from
genesis. When `stateConfig.deleteInvalidStateFiles` is set, an unparseable
round directory is moved into the recycle bin and the loader continues to
the next-newest candidate; otherwise the node fails to start.

## Latest-state exposure

Two `SignedStateNexus`-derived holders sit on the boundary of this module
and expose an in-memory `SignedState` to consumers outside it. Each holds
the most recent state matching a different criterion:

- **`LatestImmutableStateNexus`**
  ([interface](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/nexus/SignedStateNexus.java),
  implemented by
  [`LockFreeStateNexus`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/nexus/LockFreeStateNexus.java))
  holds the latest immutable state produced by the transaction handler.
  Execution reads from it to prehandle incoming transactions against an
  up-to-date state without blocking the handle thread.
- **`LatestCompleteStateNexus`**
  ([interface](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/nexus/LatestCompleteStateNexus.java),
  implemented by
  [`DefaultLatestCompleteStateNexus`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/nexus/DefaultLatestCompleteStateNexus.java))
  holds the latest signed state that has collected at least the
  signing-weight threshold of signatures. Reconnect teachers serve this
  state to learners. The holder is cleared in two cases:
  `updateEventWindow` drops the state when it ages past
  `latestConsensusRound - stateConfig.roundsToKeepForSigning + 1` — i.e.
  when this node has not reached signing quorum on a newer state within
  the window — and `updatePlatformStatus` drops it when status becomes
  `FREEZING`. A stale or pre-freeze state is never offered to a learner.

Both follow the Holder pattern from [Reservation in the wiring
graph](#reservation-in-the-wiring-graph): each setter releases the
previous reservation and takes a new one, and `getState(reason)` returns a
fresh reservation that the caller must close.

## Reservation discipline

The lifetime of every in-memory `SignedState` is governed by reference
counting and asynchronous garbage collection. The property — *a state
must remain reserved as long as any consumer can still access it* — its
application across direct calls, wiring fan-out, holders, and concurrent
reads, and the use-after-free failure mode it guards against are
captured in [RUL-001](../../rules/RUL-001-signed-state-reservations.md).

Two related operational cautions sit outside that rule:

- **Merkle reference-count API.** Do not use the merkle reference-count
  API to force a `SignedState` to remain in memory. Merkle reference
  counts should only be modified by utilities designed to operate
  directly on merkle trees; use `SignedState.reserve(reason)` instead.
- **Debug stack traces.** Setting `state.debugStackTracesEnabled = true`
  ([`StateConfig.java:93`](../../../../consensus-state/src/main/java/org/hiero/consensus/state/config/StateConfig.java))
  captures stack traces alongside reservation events, which is useful
  for diagnosing reference-count exceptions. The setting has non-trivial
  performance impact and **must never be enabled in production**.

## Reservation in the wiring graph

RUL-001 is straightforward to follow for code that holds a
`ReservedSignedState` on a single thread. When a state passes through
the component framework it also crosses scheduler queues, and every
fan-out from one wire to multiple listeners must mint one reservation
per listener so that the fastest consumer cannot release the last
reservation while a slower consumer is still waiting in queue. Three
`AdvancedTransformation` implementations in
`com.swirlds.platform.wiring` enforce this:

- [`SignedStateReserver`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/SignedStateReserver.java)
  — fans out a `ReservedSignedState`.
- [`StateWithHashComplexityReserver`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/StateWithHashComplexityReserver.java)
  — fans out a `StateWithHashComplexity` (used between
  `TransactionHandler` and `SavedStateController`).
- [`StateWithHashComplexityToStateReserver`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/StateWithHashComplexityToStateReserver.java)
  — unwraps a `StateWithHashComplexity` to a `ReservedSignedState`
  while fanning out (feeds `SignedStateNexus` and
  `StateGarbageCollector`).

All three share the same contract:

- `transform()` runs once per downstream listener and returns a
  freshly reserved `ReservedSignedState` whose `reason` is the
  reserver's `name`.
- `inputCleanup()` runs once after every listener has received its
  copy and releases the upstream reservation.
- `outputCleanup()` releases a per-listener reservation if the
  destination declines it (offer soldering).

The per-listener reservation is minted *before* the work item lands in
the downstream scheduler's queue, so a state cannot become eligible
for deletion while a task sits in queue. A state's reservation count
reaches zero only after every wired consumer has actually run and
released its reservation.

### Component patterns

Within
[`PlatformWiring.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java)
every consumer of a state-bearing wire follows one of three patterns:

- **Terminal.** Wraps the input in `try (reservedState)` and produces a
  non-state output (a transaction, a notification, an ISS list, or a
  raw `SignedState` reference held without a reservation). Sites:
  `HashLogger::logHashes`, `StateSigner::signState`,
  `IssDetector::handleState`, `StateGarbageCollector::registerState`,
  `StateHashedNotification::from`.
- **Pipeline-middle.** Returns the same `ReservedSignedState` (or the
  same `StateWithHashComplexity`) without closing it. The next
  Reserver's `inputCleanup()` releases the reservation after every
  downstream listener has minted its own. Sites:
  `SavedStateController::markSavedState`, `StateHasher::hashState`.
- **Holder.** Stores the reservation in a field and closes the
  previous one when a newer one arrives, or on `clear()` / status
  change. Sites: `LockFreeStateNexus`,
  `DefaultLatestCompleteStateNexus`, and the `incompleteStates` map
  inside `DefaultStateSignatureCollector` (which parks states until
  they collect enough signatures or age out, at which point the
  reservation flows on as part of the collector's list output).

`StateSnapshotManager::saveStateTask` is the only consumer that
transfers ownership to a helper:
`SignedStateFileWriter#writeSignedStateToDisk` takes the reservation
and releases it (early for async snapshots, after the write for
synchronous ones), with a defensive `try { ... } finally { if
(!rs.isClosed()) rs.close(); }` covering early returns and errors.

## ISS detection

Every hashed signed state and every `StateSignatureTransaction` is also
routed to `DefaultIssDetector`, which checks whether this node's local
hash agrees with the consensus of peer signatures for the same round.
The detection algorithm, classification, suppression rules, and handler
behavior are covered in [iss-detection.md](iss-detection.md).

## Cross-references

- Topics:
  [iss-detection.md](iss-detection.md),
  [restart-and-pces.md](restart-and-pces.md),
  [reconnect.md](reconnect.md),
  [freeze-and-upgrade.md](freeze-and-upgrade.md).
- Interface:
  [consensus-execution-boundary.md](../interfaces/consensus-execution-boundary.md).
- Source docs:
  [signed-state-snapshot-spec.md](../../../core/signed-state-snapshot-spec.md),
  [signed-state-use.md](../../../core/signed-state-use.md).
- Invariants: [TBD: INV-NNN once `invariants.md` catalog populates].
- Decisions: [TBD: ADR-NNN once `decisions/` catalog populates].

## Future state

The proposal at
[`Consensus-Layer.md`](../../../proposals/consensus-layer/Consensus-Layer.md)
places signed-state lifecycle entirely under Execution. Current code
reflects a partial move: the runtime types (`SignedState`,
`ReservedSignedState`, `SignedStateReference`, `StateToDiskReason`,
`DefaultStateGarbageCollector`, `StateConfig`) live in the
`consensus-state` module under the `org.hiero.consensus.state` package.
File I/O and snapshot orchestration (`SignedStateFileWriter` /
`SignedStateFileReader`, `SignedStateFilePath`, `SavedStateMetadata`,
`StateDumpRequest`, `DefaultStateSignatureCollector`,
`DefaultSavedStateController`) remain in `swirlds-platform-core` under
`com.swirlds.platform.state.snapshot` and
`com.swirlds.platform.components`.
