---
title: Signed state management
kind: architecture-topic
last_reviewed: TBD
---

# Signed state management

## Responsibilities

Signed-state management owns the production, signing, persistence, runtime
reservation, and reclamation of the per-round `SignedState` objects that
together form the platform's verifiable history. Each round's state is
captured immediately after consensus, accumulates signatures from peers
until quorum, optionally serializes to disk, and is eventually destroyed
once no live caller holds a reservation.

In scope:

- Producing a `SignedState` after each consensus round.
- Collecting state signatures from other nodes.
- Deciding when a round's state is persisted.
- Writing and reading the on-disk snapshot.
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
Thread-safe holder for a `SignedState` that internally manages reservations
on the value it holds. Used wherever multiple threads may swap, read, or
release references to a single signed state without coordinating manually.

### `StateWithHashComplexity`

Defined in
[`StateWithHashComplexity.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/eventhandling/StateWithHashComplexity.java).
Record that pairs a `ReservedSignedState` with an estimate of how
expensive hashing the state will be (measured in applied transactions,
minimum 1). The wiring graph carries this record from the
`TransactionHandler` through `SavedStateController` to `StateHasher`,
where the complexity figure feeds the scheduler's health monitor.

## Reservation discipline

When working with a `SignedState`, thread safety and proper reference-count
handling are non-negotiable.

**FAILURE TO USE STATE RESERVATIONS CORRECTLY IS ALMOST ALWAYS FATAL TO A NODE!**

While a state has a positive reference count, it will not be deleted. Once
all references on a state have been released, it becomes eligible for
asynchronous deletion and will eventually be destroyed. Once all
reservations on a state copy have been released, it is no longer
thread-safe to read, write, or otherwise interact with that copy.

1. If an object of type `SignedState` is passed into a method, it is never
   thread-safe to take a reference to the state and use it after the method
   returns.
   1. A `SignedState` passed directly into a method is only guaranteed to
      be valid until the method returns.
   2. If the state must outlive the call, take a fresh reservation:
      `ReservedSignedState ss = SignedState.reserve("reason")`.
   3. Avoid using the merkle reference-count API to force a state to remain
      in memory. Merkle reference counts should only be modified by the
      utilities designed to operate directly on merkle trees.
2. If an object of type `ReservedSignedState` is passed into a method, the
   method is responsible for guaranteeing that `ReservedSignedState.close()`
   is eventually called.
   1. Where possible, use a `ReservedSignedState` in a try-with-resources
      block.
   2. Avoid `@Nullable ReservedSignedState` parameters; prefer a non-null
      `ReservedSignedState` wrapped around a `null` value.
   3. Outside the wiring graph, prefer passing a `SignedState` into a
      method instead of a `ReservedSignedState`. Synchronous
      implementations rarely need to retain the state after returning,
      and if they do, taking a fresh reservation is cheap. Inside the
      wiring graph the reverse holds: state-bearing input wires take
      `ReservedSignedState` so the Reserver transformers
      (see [Reservation in the wiring graph](#reservation-in-the-wiring-graph))
      can mint and release one reservation per edge.
3. When creating a new `SignedState`, always ensure that an explicit
   reservation is held before passing it to other parts of the system —
   never rely on an implicit reference.
4. It is never thread-safe for threads to read from the same
   `ReservedSignedState` instance concurrently with another thread calling
   `ReservedSignedState.close()`.
   1. In multithreaded contexts, prefer creating a new
      `ReservedSignedState` for each thread that needs to read from the
      state.
   2. Alternatively, use `SignedStateReference` for thread-safe access and
      management of a `SignedState`.
5. When taking a reservation through any API that requires a reason, use a
   reason string unique enough that an engineer debugging a reference-count
   exception can locate the responsible call site by searching for the
   string.
6. Setting `state.debugStackTracesEnabled = true`
   ([`StateConfig.java:93`](../../../../consensus-state/src/main/java/org/hiero/consensus/state/config/StateConfig.java))
   captures stack traces alongside reservation events, which is useful for
   diagnosing reference-count exceptions. The setting has non-trivial
   performance impact and **must never be enabled in production**.

## Reservation in the wiring graph

The rules above are framed for code that holds a `ReservedSignedState`
on a single thread. When a state passes through the component
framework it also crosses scheduler queues, and every fan-out from one
wire to multiple listeners must mint one reservation per listener so
that the fastest consumer cannot release the last reservation while a
slower consumer is still waiting in queue. Three
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
The reader for the same on-disk format is
[`SignedStateFileReader`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/snapshot/SignedStateFileReader.java).

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
   blocks.
2. **Hash and locally sign.** `DefaultStateHasher#hashState`
   ([`DefaultStateHasher.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/hasher/DefaultStateHasher.java))
   forces computation of the merkle root, and
   `DefaultStateSigner#signState`
   ([`DefaultStateSigner.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/signer/DefaultStateSigner.java))
   produces a `StateSignatureTransaction` containing this node's
   signature over the hash. The transaction is submitted to Execution
   for inclusion in the gossiped event stream. (PCES-replay rounds are
   not signed.)
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

## ISS detection

Every hashed signed state and every `StateSignatureTransaction` is also
routed to `DefaultIssDetector`
([`DefaultIssDetector.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/iss/DefaultIssDetector.java)),
which validates that this node's locally-computed state hash agrees
with the consensus of peer signatures for the same round. A
disagreement produces an `IssNotification` — Inconsistent State
Signature — and falls into one of three categories: `SELF_ISS` (the
local hash is the outlier), `OTHER_ISS` (some peer is the outlier),
or `CATASTROPHIC_ISS` (no super-majority hash exists). ISS is a
serious failure; the detection algorithm, partition reporting, and
follow-on handling are described in
[iss-detection.md](iss-detection.md).

## Cross-references

- Topics:
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
