---
title: ISS detection
kind: architecture-topic
last_reviewed: TBD
---

# ISS detection

## Responsibilities

ISS detection owns the decision of whether this node's locally-computed
state hash for a given round agrees with the consensus of peer
signatures for that round. An **ISS** — Inconsistent State Signature —
is a serious failure: the node has diverged from the network or the
network has lost agreement among itself. The detector observes every
hashed `SignedState` and every peer `StateSignatureTransaction`,
classifies any disagreement, and emits an `IssNotification` that
downstream handling can act on.

In scope:

- Per-round collection of the local state hash and peer-reported hashes.
- Partitioning peer hashes by weight to find the consensus hash, when
  one exists.
- Classifying disagreements as `SELF_ISS`, `OTHER_ISS`, or
  `CATASTROPHIC_ISS`.
- Suppressing ISS detection for explicitly ignored rounds, for stale
  freeze-round signatures, and (optionally) for preconsensus replay.
- Recording the latest observed ISS round to a persistent scratchpad.
- Responding to ISS by halting or restarting, per configuration.

Out of scope (covered by sibling topics):

- Production, signing, persistence, and reclamation of signed states —
  see [signed-state-management.md](signed-state-management.md).
- Reconnect-side state transfer, which short-circuits past ISS rounds
  — see [reconnect.md](reconnect.md).

## Runtime types

### `IssDetector`

Interface at
[`IssDetector.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/iss/IssDetector.java).
Two input methods participate in detection:

- `handleState(ReservedSignedState)` — called once per hashed signed
  state. Records the local hash for that round and applies any peer
  signatures that arrived ahead of the state.
- `handleStateSignatureTransactions(Collection<ScopedSystemTransaction<StateSignatureTransaction>>)`
  — called for each batch of system transactions produced by the
  transaction handler. Each signature transaction reports the
  submitter's hash for some round.

Output: `List<IssNotification>` per call, possibly empty.

### `IssNotification`

Defined in
[
`IssNotification.java`](../../../../consensus-model/src/main/java/org/hiero/consensus/model/notification/IssNotification.java).
Carries a round number and an `IssType` (`SELF_ISS`, `OTHER_ISS`,
`CATASTROPHIC_ISS`).

### `RoundHashValidator`

Defined in
[
`RoundHashValidator.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/iss/internal/RoundHashValidator.java).
Per-round state machine. Buffers asynchronously-arriving evidence (the
local hash and per-peer reported hashes) until enough data is present
to decide, then exposes a `HashValidityStatus`
([
`HashValidityStatus.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/iss/internal/HashValidityStatus.java)):
`UNDECIDED`, `VALID`, `SELF_ISS`, `CATASTROPHIC_ISS`, `LACK_OF_DATA`,
or `CATASTROPHIC_LACK_OF_DATA`.

### `ConsensusHashFinder`

Defined in
[
`ConsensusHashFinder.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/iss/internal/ConsensusHashFinder.java).
Groups peer-reported hashes into weight-summed *partitions* keyed by
hash value. The consensus hash is the hash of the partition that
exceeds the `SUPER_MAJORITY` weight threshold; if no partition can
reach that threshold even with the remaining unreported weight, the
result is catastrophic.

### `IssHandler`

Interface at
[`IssHandler.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/iss/IssHandler.java)
with default implementation at
[
`DefaultIssHandler.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/iss/internal/DefaultIssHandler.java).
Reads each `IssNotification` and applies the configured response:
halt, force-restart, or no-op.

## Detection algorithm

`DefaultIssDetector` maintains a sliding window of `RoundHashValidator`
instances, sized by `consensusConfig.roundsNonAncient`. As each new
round's hashed state arrives:

1. The window shifts forward; any rounds that fall out are forced to
   a final `HashValidityStatus` by calling `outOfTime()`. Rounds that
   reach `CATASTROPHIC_ISS` or `CATASTROPHIC_LACK_OF_DATA` at this
   point emit a `CATASTROPHIC_ISS` notification; `LACK_OF_DATA` rounds
   are logged but do not produce a notification.
2. Any signature transactions for the new round that were buffered
   ahead of the state are now replayed against the round's validator.
3. The local state hash is reported to the validator via
   `reportSelfHash`. If the validator has accumulated enough peer
   evidence to decide and disagrees with the local hash, a `SELF_ISS`
   (or `CATASTROPHIC_ISS`) notification is produced.

For peer signatures, validation runs as soon as the transaction is
ingested *if* the round is already tracked. Signatures for rounds
ahead of the current window are buffered in `savedSignatures` until
the corresponding state arrives. Signatures for rounds behind the
window are silently dropped. Each peer signature reports the
submitter's hash; once a partition crosses `SUPER_MAJORITY` the
validator's status becomes `VALID` (if it matches the local hash) or
`SELF_ISS` (if not). When no partition can ever reach
`SUPER_MAJORITY`, the status becomes `CATASTROPHIC_ISS`.

### Classification

The `IssType` reported in the notification is derived from the
validator's final status:

- `VALID` with `hasDisagreement()` true → `OTHER_ISS` (some peer
  disagreed, but the consensus matched this node).
- `SELF_ISS` → `SELF_ISS` (this node is the outlier).
- `CATASTROPHIC_ISS` / `CATASTROPHIC_LACK_OF_DATA` → `CATASTROPHIC_ISS`
  (no super-majority hash exists).
- `LACK_OF_DATA` → no notification, but logged via a rate-limited
  warning.

### Suppression and special cases

- `ignoredRound` (constructor parameter, equal to
  `IssDetector.DO_NOT_IGNORE_ROUNDS = -1` when disabled): any round
  matching this value never produces a notification. Used for tests
  and for manual recovery scenarios.
- `ignorePreconsensusSignatures`: when true, peer signature
  transactions are ignored until `signalEndOfPreconsensusReplay()` has
  been called. This has the effect of discarding any state signature
  transactions in events that are replayed from PCES **Testing only** (set via the
  `pces.forceIgnorePcesSignatures` config flag); must not be enabled
  in production.
- `latestFreezeRound`: signature transactions whose `eventBirthRound`
  is at or below this round are dropped. In the current baseline,
  Execution modifies the state during migration when it is loaded from
  disk after a freeze-and-upgrade. That migration changes the state
  hash relative to the hash peers signed before the upgrade, so the
  pre-upgrade signatures no longer correspond to the post-migration
  state and must be ignored.

## ISS handling

`DefaultIssHandler#issObserved` consumes each `IssNotification` and
chooses a response based on three `StateConfig`
([`StateConfig.java`](../../../../consensus-state/src/main/java/org/hiero/consensus/state/config/StateConfig.java))
flags. All three default to `false`, so the production default is
"record a metric and move on" for every ISS type.

**Halt** (`haltOnAnyIss`, `haltOnCatastrophicIss`). When either flag
triggers, the handler flips a one-shot `halted` field and ignores every
subsequent ISS notification. The node stops doing work but the JVM
stays up and every in-memory structure — wiring queues, the hashgraph,
the signed-state holders, the live merkle tree — is preserved. This is
purposely a debugging aid: an engineer can attach a debugger or inspect
the heap of a frozen-in-place node. Both halt flags are **for debugging
only and must never be enabled in production** — `StateConfig`'s own
javadoc calls `haltOnAnyIss` on a running network "a very simple denial
of service attack."

**Automated self-ISS recovery** (`automatedSelfIssRecovery`). When
triggered, the handler invokes
`FatalErrorConsumer.fatalError("Self ISS", null, SystemExitCode.ISS)`,
which exits the JVM so the supervisor restarts the node. This is the
"turn it off and on again" recovery — the new process loads state from
disk and rejoins consensus. Only `SELF_ISS` triggers this path.

**The two mechanisms are mutually exclusive.** `selfIssObserved` checks
halt first and only consults recovery if halt is not configured
(`if (haltOnAnyIss) halt; else if (automatedSelfIssRecovery) restart`).
A node configured to halt never auto-recovers; a node configured to
auto-recover never halts. With all three flags off — the production
default — a `SELF_ISS` records a metric, writes the round to the
scratchpad, and the node keeps running.

|      ISS type      |    Default    | `haltOnAnyIss` | `automatedSelfIssRecovery` (self-ISS only) | `haltOnCatastrophicIss` |
|--------------------|---------------|----------------|--------------------------------------------|-------------------------|
| `OTHER_ISS`        | record metric | halt           | —                                          | —                       |
| `SELF_ISS`         | record metric | halt           | restart via `FatalErrorConsumer`           | —                       |
| `CATASTROPHIC_ISS` | record metric | halt           | —                                          | halt                    |

Once the handler has halted, subsequent ISS notifications of any type
are dropped — including `OTHER_ISS`, which never directly causes the
halt-bit to flip outside `haltOnAnyIss`.

For `SELF_ISS` and `CATASTROPHIC_ISS`, the round number is written to a
persistent `IssScratchpad` ([
`IssScratchpad.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/iss/IssScratchpad.java))
under key `LAST_ISS_ROUND`, monotonically increasing only. The
scratchpad survives restarts so an operator can observe the latest ISS
round even after an automated recovery.

## Wiring

In [
`PlatformWiring.java`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java)
the detector is wired as a Terminal consumer of the post-hasher
fan-out (taking a fresh reservation from
`postHasher_stateReserver`) and as a consumer of the transaction
handler's system-transaction stream. The detector's `getSplitOutput()`
is soldered to:

- `IssHandler::issObserved` — the response logic above.
- `PlatformMonitor::issNotification` — surfaces ISS to status tracking.
- `AppNotifier::sendIssNotification` — forwards to Execution-side
  application callbacks.

`IssDetector::overridingState` and `IssDetector::signalEndOfPreconsensusReplay`
are built but unsoldered in `buildUnsolderedWires`; they are invoked
directly by reconnect and replay paths rather than via wiring.

## Cross-references

- Topics:
  [signed-state-management.md](signed-state-management.md),
  [reconnect.md](reconnect.md),
  [restart-and-pces.md](restart-and-pces.md).
- Invariants: [TBD: INV-NNN once `invariants.md` catalog populates].
- Decisions: [TBD: ADR-NNN once `decisions/` catalog populates].
