---
type: decision
id: ADR-008
title: Adopt a monotonic event sequence number as the local ordering key, replacing nGen
topics: [ event-intake, event-creator, hashgraph, gossip ]
related:
  invariants: [ INV-015 ]
  decisions: [ ADR-004 ]
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
  self-parent. Re-diagnosed, this consumer needs no ordering key at all: which of two
  self events is later is answerable from the hashgraph. The rework
  ([#26530](https://github.com/hiero-ledger/hiero-consensus-node/issues/26530))
  replaced the comparison with a structural test on the self-parent link, so the event
  creator reads neither `nGen` nor the sequence number (see [Why `lastSelfEvent` does
  not use the sequence number](#why-lastselfevent-does-not-use-the-sequence-number)).

Separately, the name "sequence" was already taken: `EventImpl.sequence`, assigned
by `Sequencer` in the order events are **added to consensus**, is used only for
metrics. The new field had to be disambiguated from it.

## Decision

**Adopt the orphan-buffer sequence number as the canonical local ordering key and
remove `nGen`.** Every consumer that still reads `nGen` moves to the sequence
number; once all have, `NonDeterministicGeneration` is deleted. The migration is
staged only by readiness — two conversions are gated on a prerequisite fix, the
other two are unblocked:

|           Consumer           |                Anchor                 | Current key |                    Prerequisite to convert                     |
|------------------------------|---------------------------------------|-------------|----------------------------------------------------------------|
| Consensus-relevant threshold | RUL-005                               | `nGen`      | #26529 (restores INV-015)                                      |
| `cGen` topological sort      | `LocalConsensusGeneration.assignCGen` | `nGen`      | none — a topological order of an already-agreed round suffices |
| Developer tools (GUI, CLI)   | `PictureMetadata`, `HashgraphPicture` | `nGen`      | none — a rendering choice, not an ordering requirement         |

Already migrated and stable: event creation's advancement scoring and
`ChildlessEventTracker` (#24991), and the sync send-list order (#24843).

One declared consumer left the migration instead of completing it: the event creator's
`lastSelfEvent` recency now uses no ordering key (#26530). That is one fewer conversion
to make, and one fewer place `nGen` has to be removed from.

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

### Why `lastSelfEvent` does not use the sequence number

The recency test in `TipsetEventCreator.registerEvent` is **structural**: the held
self event is replaced when the registered event is a child of it, compared by
self-parent descriptor, with two escapes for when nothing is held and when the held
event has gone ancient. No ordering key is read. The rule and its cases live in
[`../architecture/topics/event-creator.md`](../architecture/topics/event-creator.md#state);
what follows is why the sequence number is not among them.

The ancient escape is sound without a key: the registered event is non-ancient by the
method's entry guard, and birth round never decreases along ancestry (INV-011), so
every ancestor of an ancient event is itself ancient and the registered event cannot
be one. It is therefore always higher in the hashgraph than what it replaces.

This closes SCN-003 without an epoch argument. A self-ancestor of the held event,
re-delivered with a fresh higher sequence number after a reconnect cleared the orphan
buffer, cannot have the held event as its self-parent — that would be a cycle. It is
rejected on structure, whatever number the re-release gave it.

What the test costs is a dependence on delivery order: walking one link at a time
reaches the latest self event only if the links are offered in order. The orphan buffer
provides that ordering and the pipeline below it preserves it
([`../architecture/topics/event-intake.md`](../architecture/topics/event-intake.md#preserving-the-order-downstream)),
but nothing in the event creator enforces it, and a chain broken by a gap stalls
advancement until the held event goes ancient, during which the node builds on a stale
self-parent.

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
before the clear. A consumer that compares two events across a clear must establish
that they share an epoch by some other means. No current consumer does: the one that
would have had to, `lastSelfEvent`, stopped comparing ordering keys instead (SCN-003).

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

- **One conversion is gated on an external fix.** Until #26529 (threshold) lands, that
  consumer stays on `nGen`, so `nGen` and the
  sequence number coexist in the meantime and a consumer that reads the wrong one,
  or compares the two, is a live hazard.
- **The key could not serve one of its declared consumers.** `lastSelfEvent` needed to
  know which of two self events is higher in the hashgraph across a buffer clear, and
  no property of the sequence number supplies that. Read as a caution rather than a
  defect: a consumer that wants graph position rather than release order will not get
  it from this key, and the right response is to find the answer in the graph, not to
  dress the key up in a proxy for it.

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
  `registerEvent` advances `lastSelfEvent` by self-parent link and reads no ordering
  key (#26530, SCN-003). What it depends on instead is the intake pipeline's delivery
  order — see `architecture/topics/event-intake.md`.
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
  `consensus-hashgraph-impl/.../consensus/MinConsensusRelevantThresholdTest.java`
  (threshold, #26319, SCN-002), kept until #26529 lands;
  `consensus-otter-tests/.../otter/test/ReconnectTest.java`
  (`testSyntheticBottleneckReconnect`; `lastSelfEvent`, #26376, SCN-003); and
  `consensus-event-creator-impl/.../tipset/TipsetEventCreatorTests.java`, covering
  every path through the `lastSelfEvent` recency test — adopt because nothing is held
  (`firstSelfEventIsAdoptedWhenNoneIsHeld`), adopt because the held event is ancient
  (`ancientLastSelfEventIsReplaced`), adopt because the registered event is a child of
  the held one (`gossipedSelfEventDisplacesReplayedSelfEventWhenPcesWasIncomplete`, and
  `lastSelfEventUpdatedWhileRelearningThroughGossip` for a chain of them), and reject
  because it is not — whether it carries a different self parent
  (`selfEventThatIsNotAChildIsNotAdopted`, `lastSelfEventNotOverwritten`) or none at all
  (`selfEventWithNoSelfParentIsNotAdopted`). The unit guards cover the structural test,
  not the sequence number; they are listed here because this is where the conversion and
  its abandonment are recorded.
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
  (`lastSelfEvent` rework; landed, and removed the event creator from the migration).

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
- 2026-08-13 — the `lastSelfEvent` rework landed (#26530), and it took that consumer
  out of the migration rather than through it: the recency test is now structural and
  reads no ordering key. Removed the row from the migration table, added *Why
  `lastSelfEvent` does not use the sequence number*, recorded what the episode says
  about the key under Negative consequences, and pointed at the intake pipeline's
  delivery order as the property the new test depends on. The decision itself is
  unchanged — Kelly Greco (@poulok).
- 2026-08-13 — review follow-up. `event-creator.md` is now the canonical home for the
  adoption rule; *Why `lastSelfEvent` does not use the sequence number* keeps the
  rationale and links there rather than restating the cases. Corrected the
  `MinConsensusRelevantThresholdTest` path (`consensus-hashgraph-impl`, not
  `swirlds-cli`) and listed the guards by the path each covers — Kelly Greco (@poulok).
