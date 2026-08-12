---
type: decision
id: ADR-008
title: Adopt a monotonic event sequence number as the local ordering key, replacing nGen
topics: [ event-intake, event-creator, hashgraph, gossip ]
related:
  invariants: [ INV-015 ]
  decisions: [ ]
  scenarios: [ SCN-002, SCN-003 ]
  heuristics: [ ]
  rules: [ RUL-005 ]
status: accepted
date: 2026-07-27
deciders:
  - Artur Biesiadowski (@abies)
  - Kelly Greco (@poulok)
  - Lazar Petrovic (@lpetrovic05)
curated_by: Michael Heinrichs (@netopyr)
provenance: hiero-consensus-node#24618
last_reviewed: TBD
---

# ADR-008 — Adopt a monotonic event sequence number as the local ordering key, replacing nGen

## Context

Each node assigns local ordering metadata to events as they leave the orphan
buffer — the point at which an event stops being an orphan because all of its
parents are present or have become ancient. In `DefaultOrphanBuffer`
(`consensus-utility`), `eventIsNotAnOrphan(...)` does this for every event it
emits, in the order it emits them.

Historically that value was the **non-deterministic generation** (`nGen`,
`NonDeterministicGeneration`), a graph-height number used as a local ordering key
by every consumer that must reason about how far along an event is.

### The `nGen` reset flaw

`nGen` is computed relative to the parents still tracked in the buffer's
`eventsWithParents` map, which holds only **non-ancient** events:

```
nGen = (no parent found in eventsWithParents) ? FIRST_GENERATION (1)
                                              : max(parent nGen) + 1
```

The buffer releases an event once it has no missing parents, and an ancient
parent does not count as missing. So an event whose parents have already gone
ancient is released with **`nGen = 1`**, as if it were genesis, even though it
sits high in the hashgraph — the failure in
[hiero-consensus-node#24618](https://github.com/hiero-ledger/hiero-consensus-node/issues/24618),
"NGen reset to 1 when node is almost falling behind during the sync." The reset
breaks the one property an *ordering* consumer relies on — **per-creator
monotonicity**: a value that has climbed to, say, 50 moves *backward* when a
genuinely later event arrives carrying `nGen = 1`.

### The sequence number, and two incidents that were misread

The **orphan-buffer sequence number** was introduced as a non-resetting local
ordering key: a single `AtomicLong` in `DefaultOrphanBuffer`, bumped at the
buffer's exit and never derived from parent state, so it cannot reset to 1. Among
the events numbered since the buffer was last cleared it is a valid topological
order — a non-ancient parent is released, and numbered, before its child.

Two consumers were migrated to the sequence number and then **reverted** after
incidents that were, at the time, read as proof that a sequence number cannot
serve them. Re-diagnosis showed otherwise — neither incident reflects a
fundamental need for a graph height:

- **Consensus-relevant threshold (RUL-005) → an ISS (SCN-002).** The threshold is
  the frontier below which `ConsensusImpl.round(x)` short-circuits an event to
  `ROUND_NEGATIVE_INFINITY`. Re-diagnosed, the divergence was **not** a property
  of the sequence number but a latent bug in `roundCreated` assignment
  ([#26529](https://github.com/hiero-ledger/hiero-consensus-node/issues/26529)): the
  no-parent branch of `round(x)`
  (`consensus-hashgraph-impl/.../consensus/ConsensusImpl.java`) assigns `ROUND_FIRST`
  to *any* event with no non-ancient parents, but that is right only when the pending
  round is 1 — a true genesis event. When the pending round is greater than 1, a
  no-parent event sits below a decided round — a non-descendant of its judges — and
  must be terminal (`ROUND_NEGATIVE_INFINITY`), not round 1; assigning it a real
  round violates INV-015. `nGen` masked the bug by always sorting such an event below
  the frontier (short-circuited to `ROUND_NEGATIVE_INFINITY`); the sequence number
  let it sort above the frontier and reach the branch. Fixing #26529 restores
  INV-015, after which the sequence number is safe here (see [Why the threshold is
  safe](#why-the-threshold-is-safe-on-the-sequence-number)).
- **Event creator `lastSelfEvent` → a branch (SCN-003).** After a fast
  reconnect a re-received self-ancestor got a higher *new* sequence number than
  the maintained latest self event and overwrote it, so the node built on an older
  self-parent. The `lastSelfEvent` tracking was reworked
  ([#26530](https://github.com/hiero-ledger/hiero-consensus-node/issues/26530)) so
  a re-received self-ancestor can never displace the latest self event, after
  which the sequence number is safe here too (see [Why `lastSelfEvent` is
  safe](#why-lastselfevent-is-safe-on-the-sequence-number)).

Separately, the name "sequence" was already taken: `EventImpl.sequence`, assigned
by `Sequencer` in the order events are **added to consensus**, is used only for
metrics. The new field had to be disambiguated from it.

## Decision

**Adopt the orphan-buffer sequence number as the canonical local ordering key and
remove `nGen`.** Every consumer that still reads `nGen` moves to the sequence
number; once all have, `NonDeterministicGeneration` is deleted. The migration is
staged only by readiness — one conversion is gated on a prerequisite fix, the
other two are unblocked:

|           Consumer           |                Anchor                 | Current key |                    Prerequisite to convert                     |
|------------------------------|---------------------------------------|-------------|----------------------------------------------------------------|
| Consensus-relevant threshold | RUL-005                               | `nGen`      | #26529 (restores INV-015)                                      |
| `cGen` topological sort      | `LocalConsensusGeneration.assignCGen` | `nGen`      | none — a topological order of an already-agreed round suffices |
| Developer tools (GUI, CLI)   | `PictureMetadata`, `HashgraphPicture` | `nGen`      | none — a rendering choice, not an ordering requirement         |

Already migrated and stable: event creation's advancement scoring and
`ChildlessEventTracker` (#24991), the sync send-list order (#24843), and the event
creator's `lastSelfEvent` recency (#26530).

**Assignment (current code).** `PlatformEvent` carries a `sequenceNumber`,
defaulting to `UNASSIGNED_SEQUENCE_NUMBER = -1` and first assigned as `1`.
`DefaultOrphanBuffer` holds a single `AtomicLong` and, in `eventIsNotAnOrphan(...)`,
calls `getAndIncrement()` for each event it emits.

**Disambiguation.** The pre-existing consensus-side `EventImpl.sequence` is renamed
`consensusSequence` (with `getConsensusSequence` / `setConsensusSequence`) so the
intake-order sequence number and the consensus-order sequence are not confused.

### Why the threshold is safe on the sequence number

The RUL-005 frontier is an **optimization** — a short cut so that `round(x)` need
not walk to the bottom of the graph. Its correctness does not require the key to
be a graph height. What must hold is INV-015: every event that is **not** a
descendant of any judge in the latest decided round is assigned a `roundCreated`
fixed by the decided round's bootstrap data — the same value on every node (the
constant `ROUND_NEGATIVE_INFINITY` in the current code). The sequence number fills the frontier's
role because a descendant of a judge always carries a **higher** sequence number
than that judge (a parent leaves the orphan buffer before its child):

- A descendant of a judge sorts **above** the frontier (the minimum judge key), so
  it is recalculated and gets a real round — correct.
- A non-descendant either sorts **below** the frontier (short-circuited straight to
  `ROUND_NEGATIVE_INFINITY`) or, sorting above it, inherits
  `ROUND_NEGATIVE_INFINITY` from its parents (all consensus events, and any event
  all of whose parents are `ROUND_NEGATIVE_INFINITY`, collapse to it) — also
  correct.

The only gap was a non-descendant with **no non-ancient parents** — nothing to
inherit terminal from, so it fell to the no-parent branch and was assigned
`ROUND_FIRST` as though the pending round were 1 (SCN-002, #26529). Closing that gap
— terminal, not round 1, whenever the pending round is greater than 1 — makes the
frontier correct on either key.

### Why `lastSelfEvent` is safe on the sequence number

The rework gates the comparison on `EventOrigin`
(`consensus-model/.../event/EventOrigin.java`), so two events are only ever ranked
by sequence number when both were numbered in the same orphan-buffer epoch
(`TipsetEventCreator.registerEvent`). Three cases, one per origin of the held
event:

- **`STORAGE`** — both events came from PCES replay. `STORAGE` is stamped only by
  `PcesFileIterator` (`consensus-pces-impl/.../common/PcesFileIterator.java`), and
  replay is flushed through the pipeline before gossip starts (RUL-002,
  `PcesReplayer.replayPces`), so replay is a single epoch and the highest sequence
  number is the graph-latest self event.
- **`RUNTIME`** — the node created the held event, so nothing observed can be
  higher in the graph. It is never displaced; `maybeCreateEvent` advances it
  directly.
- **`GOSSIP`** — reachable only when PCES was lost from disk, since every gossiped
  self event is persisted first and therefore replayed. The node relearns its own
  events through gossip during `OBSERVING` (ADR-004); the orphan buffer releases a
  parent before its child, so within the epoch the sequence number climbs to the
  last self event learned.

A self event returned by gossip is therefore never ranked against a replayed or
created one, which is what closes SCN-003: the re-received self-ancestor that
carried a higher post-clear sequence number is now rejected on origin before its
sequence number is consulted.

## Limitations

The sequence number is **local to a node and non-deterministic across the
network** — it reflects this node's orphan-buffer release order, which depends on
gossip arrival order. It must never be used for anything requiring cross-node
agreement; it is only ever an input to local, best-effort decisions and local
bookkeeping.

It is a valid topological order only **among the events numbered since the buffer
was last cleared** (node start, or a completed reconnect), and it is a release
order, **not** a graph height: a structurally-low event received late gets a high
number. In particular the per-creator monotonicity does **not** survive a buffer
clear — `clear()` (on reconnect) empties the parent maps but leaves the `AtomicLong`
untouched, so a re-ingested older event is re-numbered *above* the copy released
before the clear. A consumer that compares across a clear must establish that the
two events share an epoch by some other means; `lastSelfEvent` does so with
`EventOrigin`.

The `lastSelfEvent` conversion carries one residual, inherited from ADR-004 rather
than introduced here: a node that lost PCES from disk relearns its self events
through gossip, and if a reconnect clears the buffer mid-relearn, a re-numbered
self-ancestor can displace the held event. Disk-loss recovery is best-effort by
decision (ADR-004), so this is bounded by that decision, not by this one.

## Consequences

### Positive

- **Eliminates the reset hazard everywhere.** Because the sequence number is
  assigned at the buffer's exit and never derived from parents, it cannot reset to 1.
  Once conversion completes, the "almost falling behind" reset that motivated #24618 is gone from every consumer.
- **A single, simpler ordering primitive.** A plain monotonic counter replaces the
  subtle "non-deterministic generation" across the layer, and `nGen` retires.
- **Ends the two-value coexistence.** Removing `nGen` removes the standing hazard
  of a consumer reading the wrong one of two similarly named ordering values.

### Negative

- **One conversion is gated on an external fix.** Until #26529 (threshold) lands,
  that consumer stays on `nGen`, so `nGen` and the
  sequence number coexist in the meantime and a consumer that reads the wrong one,
  or compares the two, is a live hazard.
- **`lastSelfEvent` now depends on `EventOrigin` carrying epoch information.** The
  comparison is correct because `STORAGE` implies replay and replay is fenced off
  from gossip by the flush (RUL-002) — a non-local argument that nothing in the
  event creator enforces. A change to where an origin is stamped, or to the replay
  ordering, would break the guarantee without touching `TipsetEventCreator`.

### Neutral

- Until conversion completes, three similarly named ordering fields coexist —
  `nGen` (graph height), `sequenceNumber` (orphan-buffer exit order), and
  `consensusSequence` (consensus-add order). `consensusSequence` is unrelated and
  stays; `nGen` is on the way out.
- Like `nGen` before it, the sequence number is local and non-deterministic, so it
  must never feed cross-node agreement — misuse would be an ISS. That property is
  carried over, not introduced by this decision; nothing structural enforces it (the
  value is a plain `long`), so the guard is convention and the
  [Limitations](#limitations) above.
- The GUI can display an event's `nGen` value as an optional per-event label
  (`HashgraphPicture`, gated by the `writeNGen` option) for as long as `nGen`
  exists anywhere in the system; that display retires with `nGen` itself.
- Establishes the event sequence number as the default local ordering key for new
  code going forward.

## Alternatives Considered

### 1. Status quo — keep `nGen` as the ordering key

Leave every consumer on `nGen`, adding no new field (at most, patch the reset at
individual call sites).

**Rejected because:**

- The reset to 1 is a flaw in the `nGen` concept itself, so it resurfaces in every
  consumer that uses `nGen` for ordering.
- Patching the symptom per consumer multiplies fragile special-case code while
  leaving the root concept unsound. A single non-resetting primitive fixes the
  whole class of bug once and lets `nGen` be retired.

### 2. Permanently retain `nGen` for graph-height consumers

An interim reading of SCN-002 and SCN-003 concluded that consumers needing graph
height (the consensus threshold, `lastSelfEvent`) must keep `nGen` forever, and
that only the topological-order consumers move.

**Rejected because:**

- Retaining `nGen` indefinitely would leave two ordering primitives in the
  codebase for good and forfeit the simplification the migration was for.

### 3. Adopt the sequence number and remove `nGen`

See **Decision** above.

## References

- `consensus-utility/.../orphan/DefaultOrphanBuffer.java`:
  - `eventIsNotAnOrphan(...)` — stamps the sequence number (a single `AtomicLong`)
    as each event leaves the buffer.
  - `getMissingParents(...)` — treats an ancient parent as not missing, so an event
    whose parents have gone ancient is released with no tracked parents, resetting
    its `nGen` to 1.
- `consensus-model/.../NonDeterministicGeneration.java` — `assignNGen`, the
  `max(parents) + 1` with `FIRST_GENERATION` fallback that produces the reset; to
  be deleted once every consumer is converted.
- `consensus-model/.../PlatformEvent.java` — the `sequenceNumber` field,
  `UNASSIGNED_SEQUENCE_NUMBER`, and accessors.
- `consensus-event-creator-impl/.../tipset/TipsetTracker.java`,
  `ChildlessEventTracker.java` — advancement scoring, on the sequence number
  (#24991).
- `consensus-event-creator-impl/.../tipset/TipsetEventCreator.java` —
  `registerEvent` keys `lastSelfEvent` recency on the sequence number, gated on
  `EventOrigin` so the comparison never crosses an orphan-buffer epoch (#26530,
  SCN-003).
- `consensus-model/.../event/EventOrigin.java` — `GOSSIP` / `STORAGE` / `RUNTIME`,
  the epoch discriminator the `lastSelfEvent` comparison relies on.
- `consensus-pces-impl/.../common/PcesFileIterator.java` — stamps `STORAGE` on
  replayed events; the only producer of that origin.
- `consensus-hashgraph-impl/.../consensus/ConsensusImpl.java` — `round(x)` and its
  short-circuits; the no-parent branch that assigns `ROUND_FIRST` regardless of the
  pending round is the #26529 bug behind SCN-002. `ConsensusRounds`, `RoundElections` hold the threshold
  (RUL-005); `LocalConsensusGeneration` holds the `cGen` sort. `ConsensusSorter`
  orders by the resulting `cGen`, never `nGen`.
- `consensus-gossip-impl/.../shadowgraph/SyncUtils.java` — sorts the send list by
  `sequenceNumber` (#24843).
- `consensus-gui/.../hashgraph/util/PictureMetadata.java`, `HashgraphPicture.java`
  — read `nGen` for event rendering (`ypos`) and the optional `writeNGen` value
  label; a rendering choice, convertible to the sequence number, not a
  graph-height requirement.
- `consensus-hashgraph-impl/.../EventImpl.java`, `.../metrics/Sequencer.java` — the
  pre-existing consensus-order `sequence`, renamed `consensusSequence`.
- `docs/core/tipset-algorithm.md` — the tipset/vector-clock description, phrased in
  terms of sequence numbers.
- Regression guards:
  `swirlds-cli/.../pcli/MinConsensusRelevantThresholdTest.java` (threshold, #26319,
  SCN-002), kept until #26529 lands;
  `consensus-otter-tests/.../otter/test/ReconnectTest.java`
  (`testSyntheticBottleneckReconnect`; `lastSelfEvent`, #26376, SCN-003); and
  `consensus-event-creator-impl/.../tipset/TipsetEventCreatorTests.java`
  (`gossipedSelfEventDoesNotDisplaceReplayedSelfEvent`,
  `lastSelfEventUpdatedDuringPCESReplay`,
  `lastSelfEventUpdatedWhileRelearningThroughGossip`, `lastSelfEventNotOverwritten`
  — one per origin case of the `lastSelfEvent` comparison).
- Issues:
  [#24618](https://github.com/hiero-ledger/hiero-consensus-node/issues/24618)
  (umbrella rationale and staged plan),
  [#24841](https://github.com/hiero-ledger/hiero-consensus-node/issues/24841),
  [#24843](https://github.com/hiero-ledger/hiero-consensus-node/issues/24843),
  [#24844](https://github.com/hiero-ledger/hiero-consensus-node/issues/24844),
  [#24883](https://github.com/hiero-ledger/hiero-consensus-node/issues/24883),
  [#25482](https://github.com/hiero-ledger/hiero-consensus-node/issues/25482)
  (this ADR),
  [#26319](https://github.com/hiero-ledger/hiero-consensus-node/issues/26319)
  (interim revert of the threshold and `cGen` stages, SCN-002),
  [#26376](https://github.com/hiero-ledger/hiero-consensus-node/issues/26376)
  (interim revert of the `lastSelfEvent` stage, SCN-003),
  [#26529](https://github.com/hiero-ledger/hiero-consensus-node/issues/26529)
  (the `roundCreated` bug; prerequisite for the threshold conversion), and
  [#26530](https://github.com/hiero-ledger/hiero-consensus-node/issues/26530)
  (`lastSelfEvent` rework; the event-creator conversion, landed).

## Notes

- This entry fulfills #25482 ("Create ADR for replacing nGen with sequence
  number").
- 2026-07-17 — an interim revision narrowed the decision to a hybrid: `nGen`
  retained for graph-height consumers, sequence number only where a topological
  order sufficed — Kelly Greco (@poulok).
- 2026-07-27 — re-diagnosed the two reverts. SCN-002 was a latent `roundCreated`
  bug (#26529), not a sequence-number defect; SCN-003 is addressable by reworking
  `lastSelfEvent` (#26530). Revised the decision back to full `nGen` removal — all
  four remaining consumers convert to the sequence number, the threshold and
  `lastSelfEvent` gated on #26529 and #26530 respectively; retitled the ADR; added
  the threshold-safety argument and INV-015; corrected the GUI claim (`nGen` is a
  rendering choice and value label, not a required graph height) — Kelly Greco
  (@poulok).
- 2026-08-07 — the `lastSelfEvent` conversion landed (#26530). Moved that consumer
  out of the migration table into the already-migrated list; added the
  `lastSelfEvent` safety argument; recorded the `EventOrigin` dependency under
  Negative consequences and the disk-loss residual under Limitations. The decision
  itself is unchanged — one staged conversion completed — Kelly Greco (@poulok).
