---
type: decision
id: ADR-008
title: Adopt a monotonic event sequence number as the local ordering key, retaining nGen for graph-height consumers
topics: [event-intake, event-creator, hashgraph, gossip]
related:
  invariants: []
  decisions: []
  scenarios: [SCN-002, SCN-003]
  heuristics: []
  rules: [RUL-005, RUL-006]
status: accepted
date: 2026-04-08
deciders:
  - Artur Biesiadowski (@abies)
  - Kelly Greco (@poulok)
  - Lazar Petrovic (@lpetrovic05)
curated_by: Michael Heinrichs (@netopyr)
provenance: hiero-consensus-node#24618
---

# ADR-008 — Adopt a monotonic event sequence number as the local ordering key, retaining nGen for graph-height consumers

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

### The reset breaks per-creator ordering monotonicity

The reset breaks the one property an *ordering* consumer relies on — **per-creator
monotonicity**, that a later event from a creator compares strictly greater than
that creator's earlier events. A creator's value may have climbed to, say, 50,
and then a genuinely *later* event arrives carrying `nGen = 1`, moving the value
**backward**. Any consumer that uses `nGen` as an ordering key inherits this.

#24618 assessed four such consumers as exposed: event creation (the tipset), the
consensus algorithm, sync, and `cGen`. That assessment held for two of them and
was corrected for the other two:

- **Event creation (the tipset) — genuinely exposed.** The advancement score and
  `ChildlessEventTracker` assume monotonic per-creator ordering. A slot dropping
  `50 → 1` registers as a regression, and the `existingEvent >= event` check in
  the childless tracker rejects the genuinely newer event — degrading event
  creation exactly when a node is trying to catch up.
- **Sync — genuinely exposed.** The send-list order is a topological ordering, so
  the reset perturbs it the same way.
- **Consensus and `cGen` — not exposed after all.** Migrating them showed neither
  actually depends on the reset: the consensus-relevant threshold needs graph
  *height* (not ordering), and `cGen` needs only a topological order of an
  *already-agreed* consensus set (either key suffices). The reset is benign for
  each, for different reasons — see [Limitations](#limitations).

Note what is **not** the problem: `nGen` being non-unique (events at the same
height share a value), or `nGen` folding in other-parents' heights. Neither
breaks per-creator monotonic ordering. The reset is the issue — a flaw in the
`nGen` concept itself, a graph height derived from currently-tracked parents.

Separately, the name "sequence" was already taken: `EventImpl.sequence`,
assigned by `Sequencer` in the order events are **added to consensus**, is used
only for metrics. Any new ordering field had to be disambiguated from it.

## Decision

**Use the orphan-buffer event sequence number as the canonical local ordering key
for consumers that need only a topological ordering, and retain `nGen` for the
consumers that need graph height.** The sequence number never resets, and *among
the events numbered since the buffer was last cleared* — node start, or a completed
reconnect — it is a valid topological ordering: a non-ancient parent leaves the
buffer before its child, so an ancestor's number is smaller. But it is a
release-order counter, **not a graph height**: a structurally-low event received
late gets a high number, and the ordering does not hold across a buffer clear on
reconnect — `clear()` does not reset the counter, so a re-ingested older event is
re-numbered above events released before the clear (see [Limitations](#limitations)). `nGen` — one plus the
maximum tracked-parent `nGen` — approximates graph height, so it is kept where a
consumer must know *how high in the hashgraph* an event sits, not merely which came
later.

The split, by consumer:

- **Sequence number** — event creation's advancement score and
  `ChildlessEventTracker`, and the sync send-list order. These need per-creator /
  topological monotonicity, which the sequence number provides without the `nGen`
  reset hazard below.
- **`nGen` (retained)** —
  - the **consensus-relevant threshold** (`ConsensusRounds.consensusRelevantNGen`,
    `isOlderThanDecidedRoundGeneration`; RUL-005), a graph-height frontier that
    must classify a structurally-below event as below on every node;
  - the **event creator's `lastSelfEvent`** recency check
    (`TipsetEventCreator.registerEvent`), which must not let a stale self-ancestor
    out-rank the latest self event;
  - the **`cGen`** topological sort (`LocalConsensusGeneration.assignCGen`), which
    needs only a valid topological order of a round's *already-agreed* consensus
    set — `nGen` or the sequence number both suffice, and it currently uses `nGen`.

- **Assignment.** `PlatformEvent` carries a `sequenceNumber`, defaulting to
  `UNASSIGNED_SEQUENCE_NUMBER = -1` and first assigned as `1`.
  `DefaultOrphanBuffer` holds a single `AtomicLong` and, in
  `eventIsNotAnOrphan(...)`, calls `getAndIncrement()` for each event it emits.
  Because the counter is bumped at the buffer's *exit* and never reads parent
  state, it never resets to 1 the way `nGen` does. Among the events numbered
  since the last clear — node start, or a completed reconnect — a creator's events
  are per-creator-monotonic (a self-parent leaves the buffer before its child).
  That monotonicity does **not** survive a reconnect: `clear()` empties the parent
  maps but leaves the `AtomicLong` untouched, so the same counter keeps climbing
  across the clear and a re-received event is re-numbered *upward* — above the
  number an earlier copy still carries. A stale ancestor can then out-rank a
  genuinely later event — the branching bug behind SCN-003, and the reason
  `lastSelfEvent` uses `nGen`. The scope is therefore *between clears*, not the
  buffer object's lifetime: the same `DefaultOrphanBuffer` and its counter persist
  across a reconnect.
- **Disambiguation.** The pre-existing consensus-side `EventImpl.sequence` is
  renamed `consensusSequence` (with `getConsensusSequence` /
  `setConsensusSequence`) so the intake-order sequence number and the
  consensus-order sequence are not confused.
- **Rollout and current state.** The replacement landed as independent,
  separately reviewable changes. The consensus and `cGen` stages were migrated and
  then **reverted**, because keying a height-sensitive comparison on a
  release-order counter broke consensus (SCN-002) and event creation (SCN-003):

  |                          Stage                           |                 Scope                  |      Tracking      |  State   |
  |----------------------------------------------------------|----------------------------------------|--------------------|----------|
  | Compute the sequence number in the orphan buffer         | `consensus-utility`, `consensus-model` | #24841 (PR #24937) | done     |
  | Event creation / tipset advancement                      | `consensus-event-creator-impl`         | #24991             | done     |
  | Sync send-list order                                     | `consensus-gossip-impl`                | #24843             | done     |
  | Consensus-relevant threshold                             | `consensus-hashgraph-impl`             | #24844, #26319     | reverted |
  | `cGen` topological sort                                  | `consensus-hashgraph-impl`             | #24883, #26319     | reverted |
  | Event creator `lastSelfEvent`                            | `consensus-event-creator-impl`         | #26376             | reverted |
  | Tools (GUI, CLI)                                         | `consensus-gui`, `swirlds-cli`         | #24885             | pending  |

  There is no `nGen`-removal stage: `nGen` is retained indefinitely for the
  height-sensitive consumers above.

## Limitations

The sequence number is **local to a node and non-deterministic across the
network** — it reflects this node's orphan-buffer release order, which depends
on gossip arrival order. It must never be used for anything that requires
cross-node agreement; it is only ever an input to local, best-effort decisions
(event creation, sync ordering) and local bookkeeping.

### Topological order vs. graph height — the sequence number is not a height

The sequence number and `nGen` are **not** interchangeable. Both are local and
vary in absolute value node to node. The difference:

- **Sequence number** — among events numbered since the buffer was last cleared
  (node start, or a completed reconnect), a valid topological order: a non-ancient
  parent is released, and numbered, before its child. Two gaps: it is a
  release-order counter, not a graph height (a structurally-low event received
  late gets a high number); and it does not hold across a buffer clear on
  reconnect — `clear()` does not reset the counter, so a re-ingested older event
  gets a *new, higher* number than the copy released before the clear (SCN-003).
  The scope is *between clears*, not the buffer object's lifetime: the same buffer
  and counter persist across a reconnect.
- **`nGen`** — approximates graph height (parent-derived). Its only defect is
  **one-directional**: the reset to 1 (when an event's parents are already ancient)
  can *under*-count an event's height, never over-count it.

Consumers that need only a topological order can use either; consumers that need
height must use `nGen`. Two incidents fixed the cases where the sequence number
was wrongly substituted for a height:

- **Consensus-relevant threshold (SCN-002, #26319).** The threshold is a
  graph-height frontier. Keyed on the sequence number, a structurally-low event
  ranked below the frontier on some nodes and above it on others, flipping a
  decided-round judge's metadata preservation during recalculation and diverging
  consensus — an ISS. Reverted to `nGen`.
- **Event creator `lastSelfEvent` (SCN-003, #26376).** After a fast reconnect a
  re-received self-ancestor got a higher *new* sequence number than the maintained
  latest self event and overwrote it, so the node built on an older self-parent — a
  branch. `nGen`'s one-directional error makes it safe: a graph-lower event can
  never present a higher `nGen`, so the "strictly greater" overwrite guard is
  never tripped by a stale ancestor. Reverted to `nGen`.

The `nGen` reset is benign in each retained case: for the threshold it only pushes
a below-frontier event further below; for `lastSelfEvent` the guard fires only on a
*higher* value and the reset only lowers; for `cGen` a reset event has all-ancient
parents, hence no in-set parents, so it is a root of the round's set and its low
value is correct.

## Consequences

### Positive

- **Eliminates the reset hazard for the migrated consumers.** Because the
  sequence number is assigned at the buffer's exit and never derived from parents,
  it cannot reset to 1. The "almost falling behind" reset that motivated #24618 is
  gone from event creation's advancement scoring and sync ordering.
- **A simpler ordering primitive where it fits.** A plain monotonic counter
  replaces a subtle "non-deterministic generation" for the consumers that need
  only ordering.
- **Decouples ordering from graph height where height is not needed.** Consumers
  that only ever needed "which event came later" (tipset advancement, sync) no
  longer depend on a value that also encodes DAG height — and, conversely, the
  exercise made explicit which consumers genuinely *do* need height (RUL-005,
  `lastSelfEvent`), documented in Limitations.

### Negative

- **The consensus algorithm and `lastSelfEvent` cannot move off `nGen`.** The
  migration's premise — that every consumer needs only *local* ordering — did not
  hold for consumers that need graph height. Substituting the sequence number
  caused an ISS (SCN-002) and a branch (SCN-003); both were reverted (#26319,
  #26376). `nGen` is retained indefinitely, so full removal is off the table.
- **Two ordering values coexist permanently.** `nGen` and `sequenceNumber` both
  remain in the codebase for good; a consumer that reads the wrong one, or
  compares the two, is a live hazard — no longer a transitional one.
- **Some uses of `nGen` are not pure ordering.** The GUI uses `nGen` as actual
  graph **height** to lay out the hashgraph vertically (`PictureMetadata`,
  `HashgraphPicture`). A sequence number is monotonic but is not a height, so that
  consumer (tools, #24885) needs its replacement confirmed case by case rather
  than a blind substitution.

### Neutral

- Three similarly named ordering fields coexist permanently — `nGen` (graph
  height), `sequenceNumber` (orphan-buffer exit order), and `consensusSequence`
  (consensus-add order). `nGen` is not removed.
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

### 2. Replace `nGen` with a monotonic event sequence number (selected, refined to a hybrid)

Selected, but not as a wholesale replacement: the consensus and event-creator
incidents (SCN-002, SCN-003) showed that consumers needing graph height must keep
`nGen`. The landed decision is the hybrid described under **Decision** above —
sequence number where a topological order suffices, `nGen` where height is needed.

## References

- `consensus-utility/.../DefaultOrphanBuffer.java` — `eventIsNotAnOrphan(...)`
  assigns the sequence number at the buffer's exit; `getMissingParents(...)`
  shows that an ancient parent is not "missing", which is why such an event is
  released and its `nGen` resets.
- `consensus-model/.../NonDeterministicGeneration.java` — `assignNGen`, the
  `max(parents) + 1` with `FIRST_GENERATION` fallback that produces the reset;
  retained (not deleted) because the height-sensitive consumers still need `nGen`.
- `consensus-model/.../PlatformEvent.java` — the `sequenceNumber` field,
  `UNASSIGNED_SEQUENCE_NUMBER`, and accessors, alongside the retained `nGen` field.
- `consensus-event-creator-impl/.../tipset/TipsetTracker.java`,
  `ChildlessEventTracker.java` — advancement scoring, migrated to the sequence
  number (#24991).
- `consensus-event-creator-impl/.../tipset/TipsetEventCreator.java` —
  `registerEvent` keys `lastSelfEvent` recency on `nGen`; reverted from the
  sequence number in #26376 (SCN-003).
- `consensus-hashgraph-impl/.../consensus/` — `ConsensusImpl`, `ConsensusRounds`,
  `RoundElections` (the consensus-relevant threshold) and `LocalConsensusGeneration`
  (the `cGen` sort): migrated to the sequence number under #24844 / #24883, then
  reverted to `nGen` in #26319 after the sequence-number threshold caused an ISS
  (SCN-002, RUL-005). `ConsensusSorter` orders by the resulting `cGen`, never
  `nGen`.
- `swirlds-cli/.../pcli/MinConsensusRelevantThresholdTest.java` — replays two
  nodes' PCES from genesis and asserts identical rounds; the regression guard for
  the reverted threshold (#26319, SCN-002).
- `consensus-otter-tests/.../otter/test/ReconnectTest.java` —
  `testSyntheticBottleneckReconnect`, the regression guard for the reverted
  `lastSelfEvent` key (#26376, SCN-003).
- `consensus-gossip-impl/.../shadowgraph/SyncUtils.java` — sorts the send list by
  `sequenceNumber` (#24843).
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
  [#24846](https://github.com/hiero-ledger/hiero-consensus-node/issues/24846)
  (the `nGen`-removal stage, now dropped),
  [#24883](https://github.com/hiero-ledger/hiero-consensus-node/issues/24883),
  [#24885](https://github.com/hiero-ledger/hiero-consensus-node/issues/24885),
  [#25482](https://github.com/hiero-ledger/hiero-consensus-node/issues/25482)
  (this ADR),
  [#26319](https://github.com/hiero-ledger/hiero-consensus-node/issues/26319)
  (revert of the consensus and `cGen` stages after the ISS, SCN-002), and
  [#26376](https://github.com/hiero-ledger/hiero-consensus-node/issues/26376)
  (revert of the `lastSelfEvent` key after the branch, SCN-003).

## Notes

- Timeline: the direction was set and approved via the design ticket #24618
  (closed 2026-04-08); the `date` above reflects that approval. Staged
  implementation followed: PR #24937 (2026-04-16) added the counter and renamed
  the consensus-side `sequence` to `consensusSequence`; PR #24991 (2026-04-30)
  migrated the tipset; sync (#24843) migrated the send-list sort. Consensus
  (#24844) and `cGen` (#24883) were migrated and then reverted to `nGen` in #26319
  (2026-07-14) after the sequence-number threshold caused an ISS; the event-creator
  `lastSelfEvent` change was reverted in #26376 (2026-07-15) after it caused
  branching. Tools (#24885) remain open.
- This entry fulfills #25482 ("Create ADR for replacing nGen with sequence
  number"). It superseded an earlier draft scoped to event creation only.
- 2026-07-17 — revised to the current decision: the migration is a hybrid, not a
  full `nGen` removal. Recorded that the consensus-relevant threshold, the
  event-creator `lastSelfEvent`, and `cGen` retain `nGen`; removed the
  `## Temporary Nature` section and the `nGen`-removal stage; added the
  graph-height-vs-topological-order distinction to `## Limitations`; retitled the
  ADR; linked SCN-002, SCN-003, and RUL-005. Dropped the stale `historical:`
  frontmatter pointer to `NonDeterministicGeneration.java` (no longer slated for
  removal). Corrected the topological-order scope from "within a single
  orphan-buffer lifetime" to "between clears": `DefaultOrphanBuffer.clear()`
  empties the parent maps but does not reset the `AtomicLong`, so the counter — and
  the buffer object — persist across a reconnect, and the property holds only among
  events numbered since the last clear (node start or a completed reconnect)
  — Kelly Greco (@poulok).
