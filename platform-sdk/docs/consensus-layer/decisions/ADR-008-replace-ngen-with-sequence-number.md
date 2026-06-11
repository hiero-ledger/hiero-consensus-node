---
id: ADR-008
title: Replace nGen with a monotonic event sequence number and remove nGen
topics: [event-intake, event-creation, hashgraph, gossip]
related:
  invariants: []
  decisions: []
  scenarios: []
  heuristics: []
  rules: []
status: accepted
date: 2026-04-08
deciders:
  - Artur Biesiadowski (@abies)
  - Kelly Greco (@poulok)
  - Lazar Petrovic (@lpetrovic05)
curated_by: Michael Heinrichs (@netopyr)
provenance: hiero-consensus-node#24618
---

# ADR-008 — Replace nGen with a monotonic event sequence number and remove nGen

## Context

Each node assigns local ordering metadata to events as they pass through the
orphan buffer — the point at which an event stops being an orphan because all of
its parents are present or have become ancient. In `DefaultOrphanBuffer`
(`consensus-utility`), `eventIsNotAnOrphan(...)` does this for every event it
emits, in the order it emits them.

Historically that ordering value has been the **non-deterministic generation**
(`nGen`, `NonDeterministicGeneration`), a graph-height number used as a local
ordering key by every component that needs to reason about "how far along" an
event is: event creation (the tipset algorithm), the consensus algorithm,
gossip/sync, the consensus generation (`cGen`) bookkeeping, and developer tools
(GUI, CLI).

### How `nGen` is computed, and how it can reset

`nGen` is computed relative to the parents that are still tracked in the orphan
buffer's `eventsWithParents` map — which holds only **non-ancient** events:

```
nGen = (no parent found in eventsWithParents) ? FIRST_GENERATION (1)
                                              : max(parent nGen) + 1
```

The orphan buffer releases an event once it has **no missing parents**, and an
**ancient parent does not count as missing** (`getMissingParents` skips parents
for which `eventWindow.isAncient(parent)` is true). So an event whose parents
have already gone ancient is released immediately — but by then those parents
have been dropped from `eventsWithParents`. The loop finds no parents,
`maxParentNGen` stays undefined, and the event is stamped **`nGen = 1`**, as if
it were a genesis event, even though it sits high in the hashgraph.

This is the failure described in
[hiero-consensus-node#24618](https://github.com/hiero-ledger/hiero-consensus-node/issues/24618),
"NGen reset to 1 when node is almost falling behind during the sync": when a
node is almost falling behind, the event window can advance past an event's
parents while the event itself is still non-ancient, and that event's `nGen`
**resets to 1**.

### The reset hurts every ordering consumer, not just the tipset

The reset breaks the one property these consumers rely on — **per-creator
monotonicity**, that a later event from a creator compares strictly greater than
that creator's earlier events. A creator's value may have climbed to, say, 50,
and then a genuinely *later* event arrives carrying `nGen = 1`, moving the value
**backward**. The fallout is broad:

- **Event creation (the tipset).** The advancement score and
  `ChildlessEventTracker` assume monotonic per-creator ordering. A slot dropping
  `50 → 1` registers as a regression, and the `existingEvent >= event` check in
  the childless tracker rejects the genuinely newer event — degrading event
  creation exactly when a node is trying to catch up.
- **Consensus, sync, and cGen.** The same ordering assumption underlies the
  consensus algorithm, the order in which sync sends events, and `cGen`
  handling. Per #24618 these are all exposed to the reset.

Note what is **not** the problem: `nGen` being non-unique (events at the same
height share a value), or `nGen` folding in other-parents' heights. Neither
breaks per-creator monotonic ordering. The reset is the issue — and it is a flaw
in the `nGen` concept itself (a graph height derived from currently-tracked
parents), so every consumer that uses `nGen` as an ordering key inherits it.

Separately, the name "sequence" was already taken: `EventImpl.sequence`,
assigned by `Sequencer` in the order events are **added to consensus**, is used
only for metrics. Any new ordering field had to be disambiguated from it.

## Decision

**Adopt a dedicated, ever-increasing event sequence number as the canonical
local event-ordering primitive, migrate every `nGen` consumer to it, and remove
`nGen` entirely once the migration is complete.** `nGen` is retained only as a
transitional measure while its consumers are moved over; no new code should take
a dependency on it.

- **Assignment.** `PlatformEvent` carries a `sequenceNumber`, defaulting to
  `UNASSIGNED_SEQUENCE_NUMBER = -1` and first assigned as `1`.
  `DefaultOrphanBuffer` holds a single `AtomicLong` and, in
  `eventIsNotAnOrphan(...)`, calls `getAndIncrement()` for each event it emits.
  Because the counter is bumped at the buffer's *exit* and never reads parent
  state, it never resets: a given creator's own events receive strictly
  increasing — though not contiguous — numbers even when their parents have gone
  ancient, and every event receives a value distinct from every other. (A
  creator's events stay per-creator-monotonic because a self-parent always
  leaves the buffer before its child.)
- **Disambiguation.** The pre-existing consensus-side `EventImpl.sequence` is
  renamed `consensusSequence` (with `getConsensusSequence` /
  `setConsensusSequence`) so the intake-order sequence number and the
  consensus-order sequence are not confused.
- **Staged rollout.** The replacement lands as independent, separately reviewable
  changes, so the sensitive consumers (consensus, sync) move one at a time with
  their own testing rather than in one large switch:

  | Stage | Scope | Tracking | State |
  |-------|-------|----------|-------|
  | Compute the sequence number in the orphan buffer | `consensus-utility`, `consensus-model` | #24841 (PR #24937) | done |
  | Event creation / tipset | `consensus-event-creator-impl` | #24991 | done |
  | Consensus algorithm | `consensus-hashgraph-impl` | #24844 | pending |
  | Sync | `consensus-gossip-impl` | #24843 | pending |
  | `cGen` handling | `consensus-hashgraph-impl` | #24883 | pending |
  | Tools (GUI, CLI) | `consensus-gui`, `swirlds-cli` | #24885 | pending |
  | Remove `nGen` from the orphan buffer and `PlatformEvent` | `consensus-utility`, `consensus-model` | #24846 | pending |

  The final stage (#24846) deletes `assignNGen`, the `nGen` field, and its
  accessors, completing the removal.

## Temporary Nature

The retention of `nGen` is temporary. It remains only until every consumer in
the staged rollout has migrated to the sequence number; the closing stage
(#24846) removes `nGen` from the orphan buffer and `PlatformEvent`. Until then,
`nGen` and `sequenceNumber` coexist by design, and `nGen` must be treated as
deprecated — read by the not-yet-migrated consumers, written by no new ones.

## Limitations

The sequence number is **local to a node and non-deterministic across the
network** — it reflects this node's orphan-buffer release order, which depends
on gossip arrival order. Like `nGen`, it must never be used for anything that
requires cross-node agreement; it is only ever an input to local, best-effort
decisions (event creation, sync ordering) and local bookkeeping.

## Consequences

### Positive

- **Eliminates the reset hazard everywhere.** Because the sequence number is
  assigned at the buffer's exit and never derived from parents, it cannot reset
  to 1. Once every consumer is migrated, the "almost falling behind" reset that
  motivated #24618 is gone from event creation, consensus, sync, and `cGen`
  alike — not just the tipset.
- **A simpler ordering primitive.** A plain monotonic counter replaces a subtle
  "non-deterministic generation," reducing the conceptual surface engineers must
  hold. After removal, the orphan buffer and `PlatformEvent` shed the `assignNGen`
  computation and the `nGen` field entirely.
- **Decouples ordering from graph height.** Components that only ever needed
  "which event came later" no longer depend on a value that also encodes DAG
  height.

### Negative

- **A large, cross-cutting migration touching the most sensitive code.** The
  consensus algorithm and the wire-adjacent sync path both depend on `nGen`;
  moving them carries more risk than the tipset change and must be staged and
  tested carefully. The migration is spread across several PRs and is not yet
  complete.
- **An extended interim where two ordering values coexist.** Until #24846 lands,
  some consumers read `nGen` and others read `sequenceNumber`; a half-migrated
  consumer, or one that compares the two, is a live hazard during the rollout.
- **Some uses of `nGen` are not pure ordering.** The GUI uses `nGen` as actual
  graph **height** to lay out the hashgraph vertically (`PictureMetadata`,
  `HashgraphPicture`), and `cGen` has its own semantics. A sequence number is
  monotonic but is not a height (siblings get different numbers), so those
  consumers need their replacement value confirmed case by case rather than a
  blind substitution — which is why `cGen` (#24883) and tools (#24885) are
  separate stages.

### Neutral

- During the migration three similarly named ordering fields coexist — `nGen`
  (graph height), `sequenceNumber` (orphan-buffer exit order), and
  `consensusSequence` (consensus-add order). After `nGen` removal only
  `sequenceNumber` and `consensusSequence` remain.
- Self events still do not advance their own tipset slot
  (`TipsetTracker.addSelfEvent`) — self advancement never counts toward the
  score, and a freshly created self event has no number yet. This behaviour
  predates and is unaffected by this decision.
- Establishes the event sequence number as the default local ordering key for
  new code going forward.

## Alternatives Considered

### 1. Status quo — keep `nGen` as the ordering key

Leave every consumer on `nGen`, adding no new field (at most, patch the reset at
individual call sites).

**Rejected because:**

- The reset to 1 when an event's parents have already gone ancient — the
  "almost falling behind" case — is a flaw in the `nGen` concept itself, so it
  resurfaces in every consumer that uses `nGen` for ordering: event creation,
  consensus, sync, and `cGen`.
- Patching the symptom per consumer multiplies fragile special-case code while
  leaving the root concept unsound. A single non-resetting primitive fixes the
  whole class of bug once and lets `nGen` be retired.

### 2. Replace `nGen` with a monotonic event sequence number (selected)

See **Decision** above.

## References

- `consensus-utility/.../DefaultOrphanBuffer.java` — `eventIsNotAnOrphan(...)`
  assigns the sequence number at the buffer's exit; `getMissingParents(...)`
  shows that an ancient parent is not "missing", which is why such an event is
  released and its `nGen` resets.
- `consensus-model/.../NonDeterministicGeneration.java` — `assignNGen`, the
  `max(parents) + 1` with `FIRST_GENERATION` fallback that produces the reset;
  deleted in the final stage.
- `consensus-model/.../PlatformEvent.java` — the `sequenceNumber` field,
  `UNASSIGNED_SEQUENCE_NUMBER`, and accessors (and the `nGen` field to be
  removed).
- `consensus-event-creator-impl/.../tipset/TipsetTracker.java`,
  `ChildlessEventTracker.java` — the first consumer migrated (#24991).
- `consensus-hashgraph-impl/.../consensus/` — `ConsensusImpl`, `ConsensusRounds`,
  `RoundElections`, `ConsensusSorter`, `LocalConsensusGeneration`: the consensus
  and `cGen` consumers still on `nGen` (#24844, #24883).
- `consensus-gossip-impl/.../shadowgraph/SyncUtils.java` — sorts the send list by
  `nGen` (#24843).
- `consensus-gui/.../hashgraph/util/PictureMetadata.java`,
  `HashgraphPicture.java` — use `nGen` as graph height for layout (#24885).
- `consensus-hashgraph-impl/.../EventImpl.java`, `.../metrics/Sequencer.java` —
  the pre-existing consensus-order `sequence`, renamed `consensusSequence`.
- `docs/core/tipset-algorithm.md` — the tipset/vector-clock description, updated
  to phrase entries as sequence numbers.
- Issues:
  [#24618](https://github.com/hiero-ledger/hiero-consensus-node/issues/24618)
  (umbrella rationale and staged plan, approved as the design ticket),
  [#24841](https://github.com/hiero-ledger/hiero-consensus-node/issues/24841),
  [#24843](https://github.com/hiero-ledger/hiero-consensus-node/issues/24843),
  [#24844](https://github.com/hiero-ledger/hiero-consensus-node/issues/24844),
  [#24846](https://github.com/hiero-ledger/hiero-consensus-node/issues/24846),
  [#24883](https://github.com/hiero-ledger/hiero-consensus-node/issues/24883),
  [#24885](https://github.com/hiero-ledger/hiero-consensus-node/issues/24885),
  and [#25482](https://github.com/hiero-ledger/hiero-consensus-node/issues/25482)
  (this ADR).

## Notes

- Timeline: the direction was set and approved via the design ticket #24618
  (closed 2026-04-08); the decision date above reflects that approval. Staged
  implementation followed: PR #24937 (2026-04-16) added the counter and renamed
  the consensus-side `sequence` to `consensusSequence`; PR #24991 (2026-04-30)
  migrated the tipset. Consensus (#24844), sync (#24843), `cGen` (#24883), tools
  (#24885), and the final `nGen` removal (#24846) remain open at the time of
  writing.
- This entry fulfills #25482 ("Create ADR for replacing nGen with sequence
  number"). It supersedes an earlier draft scoped to event creation only; the
  scope was broadened to the full `nGen` removal.
