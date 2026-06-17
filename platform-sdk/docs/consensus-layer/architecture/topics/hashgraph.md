---
type: architecture-topic
title: Hashgraph
last_reviewed: 2026-06-12
---

# Hashgraph

## Responsibilities

The hashgraph module owns the in-memory directed acyclic graph (DAG) of
non-ancient events and runs the consensus algorithm that turns a
topologically ordered stream of `PlatformEvent`s into an ordered stream
of `ConsensusRound`s carrying judges, a consensus timestamp, the
consensus roster for the round, and the `EventWindow` that defines the
ancient and expired thresholds going forward.

The module does not validate, deduplicate, or topologically order events
— those concerns live in
[`../topics/event-intake.md`](../topics/event-intake.md). It does not
durably persist events — that is the Pre-Consensus Event Stream
(see [`../topics/restart-and-pces.md`](../topics/restart-and-pces.md)).
It does not gossip — that is `consensus-gossip` (see [`../topics/gossip.md`](../topics/gossip.md). Once a round reaches
consensus, the round leaves the hashgraph; downstream signing, hashing,
and signature collection live in
[`../topics/signed-state-management.md`](../topics/signed-state-management.md).

The two boundaries that frame the module:

- **Upstream:** event intake delivers events on
  `consensus-hashgraph/.../HashgraphModule.java#eventInputWire` (input
  label `"persisted ordered events"`); events are required to arrive in
  valid topological order.
- **Downstream:** consensus rounds emerge on
  `consensus-hashgraph/.../HashgraphModule.java#consensusRoundOutputWire`,
  with side outputs for pre-consensus events and stale events.

## State

State is held in three layers, each in a distinct class.

**Wiring shell.**
[`consensus-hashgraph-impl/.../DefaultHashgraphModule.java`](../../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultHashgraphModule.java)
implements `HashgraphModule` as a wired component and exposes the input
and output wires. It does not hold algorithm state itself.

**Per-event driver.**
[`consensus-hashgraph-impl/.../DefaultConsensusEngine.java`](../../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java)
orchestrates one `addEvent` cycle. Its fields are the module's working
state at runtime:

- `linker: ConsensusLinker` — creates a wrapper that holds consensus metadata and attaches parent events to each incoming event (instead of a parent descriptor).
- `consensus: Consensus` (impl
  [`ConsensusImpl`](../../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java))
  — the core consensus algorithm that calculates round / witness / fame / judge state.
- `futureEventBuffer: FutureEventBuffer` — events whose birth round is
  still in the future.
- `freezeRoundController: FreezeRoundController` — cuts off rounds at
  the freeze boundary.
- `roundsNonAncient: int` — configuration constant from
  `ConsensusConfig`.

**DAG and algorithm state.**

- `ConsensusLinker` holds non-ancient events as `EventImpl` instances in
  two parallel structures:
  `parentDescriptorMap: SequenceMap<EventDescriptorWrapper, EventImpl>`
  keyed by `birthRound` for windowed retention, and
  `parentHashMap: Map<Hash, EventImpl>` for hash lookup
  (`ConsensusLinker.java#L45,L53`).
- `EventImpl` (extending `LinkedEvent<EventImpl>`) holds parent pointers
  plus the per-event memoized round, witness flag, judge flag,
  `DeGen`, `lastSee`, and `stronglySeeP` slots used by the algorithm.
- `ConsensusImpl` owns `ConsensusRounds` (per-round state including the
  current `RoundElections`), `lastConsensusTime` (the timestamp of the
  most recent consensus event, used to keep consensus timestamps
  strictly increasing across rounds), the running `numConsensus`
  counter, and the `pcesMode` flag set when the platform is replaying
  the pre-consensus event stream.
- `RoundElections` tracks witnesses voted on for a round and their
  decided-fame status, plus `minNGen` and `minBirthRound` for the
  judges.
- The future-event buffer
  [`consensus-utility/.../FutureEventBuffer.java`](../../../../consensus-utility/src/main/java/org/hiero/consensus/event/FutureEventBuffer.java)
  holds events whose birth round exceeds the current pending consensus
  round.

## Inputs and outputs

`HashgraphModule` exposes everything as wires; the rest of the platform
is wired against those.

**Inputs**

- `eventInputWire(): InputWire<PlatformEvent>` — the main event stream,
  expected in topological order
  (`HashgraphModule.java#eventInputWire`).
- `platformStatusInputWire(): InputWire<PlatformStatus>` — drives
  `ConsensusEngine.updatePlatformStatus`, which sets `pcesMode = true`
  on `Consensus` when the status is `REPLAYING_EVENTS`
  (`DefaultConsensusEngine.java#updatePlatformStatus`).
- `consensusSnapshotInputWire(): InputWire<ConsensusSnapshot>` —
  drives `ConsensusEngine.outOfBandSnapshotUpdate`, which clears the
  linker and the future-event buffer and reloads
  `Consensus.loadSnapshot(snapshot)` at restart and reconnect
  boundaries.

**Outputs**

- `consensusRoundOutputWire(): OutputWire<ConsensusRound>` — the rounds
  that have reached consensus.
- `preconsensusEventOutputWire(): OutputWire<PlatformEvent>` — events
  linked into the DAG that have not yet reached consensus.
- `staleEventOutputWire(): OutputWire<PlatformEvent>` — events that
  aged past the ancient threshold without reaching consensus
  (see [`../../concepts/stale-events.md`](../../concepts/stale-events.md)).

`ConsensusRound`
([`consensus-model/.../ConsensusRound.java`](../../../../consensus-model/src/main/java/org/hiero/consensus/model/hashgraph/ConsensusRound.java))
carries the consensus event list (in consensus order), the
`EventWindow` for the round (the new ancient and expired thresholds and
the next pending round), the consensus roster used to calculate the round, a
`ConsensusSnapshot` (round number, minimum-judge info, monotonic
consensus number, consensus timestamp, judge IDs), and a `pcesRound`
flag indicating whether the round was decided during PCES replay.

The three output streams correspond to the three fields of
`ConsensusEngineOutput`
(`consensusRounds`, `preConsensusEvents`, `staleEvents`) returned by
`DefaultConsensusEngine.addEvent`; the wired component fans them out
across the three output wires.

## Algorithm in current code

The hashgraph runs the algorithm one event at a time, driven by
`DefaultConsensusEngine.addEvent`. A single call may release several
buffered future events and emit several decided rounds.

**Per-event lifecycle.** `DefaultConsensusEngine.addEvent` (lines
~102–194) pushes the incoming event through this pipeline:

1. `freezeRoundController.isFrozen()` — once the freeze round has
   emitted, the event bypasses the consensus algorithm: it is run
   through `futureEventBuffer.addEvent` and, if it is not a future
   event, returned on the pre-consensus output (no consensus rounds,
   no stale events). Future events still buffer and yield an empty
   output. This lets the platform pre-handle post-freeze events (for
   example, to collect freeze-state signatures) without emitting any
   further rounds.
2. `futureEventBuffer.addEvent(event)` — returns `null` if the event's
   birth round is beyond the current pending consensus round (held for
   later release) or below the buffer's discard threshold.
3. `linker.linkEvent(event)` — drops the event if it is ancient;
   otherwise links it to its already-linked parents and returns an
   `EventImpl`.
4. `consensus.addEvent(linkedEvent)` — runs the round / witness / fame
   pipeline and returns zero or more `ConsensusRound`s.
5. If consensus advanced, `linker.setEventWindow(round.getEventWindow())`
   shifts the non-ancient window and returns the events that became
   ancient; non-consensus ancients are reported as stale (see
   [`../../concepts/stale-events.md`](../../concepts/stale-events.md)).
6. `futureEventBuffer.updateEventWindow(eventWindow)` releases any
   future events whose birth round is now eligible; they are appended
   to the work queue and each is fed back into step 3
   (`linker.linkEvent`), looping through steps 3–6 until the queue
   drains.
7. After the loop, `freezeRoundController.filterAndModify` truncates
   any rounds beyond the freeze boundary.

**Round computation (round created).** `ConsensusImpl.round` returns
`ROUND_FIRST` for events with no parents; otherwise, when the parents'
rounds differ, it inherits the maximum parent round; when the parents'
rounds agree at parent round `r`, it counts the witnesses in round `r`
that this event strongly sees (weighted by roster) and increments to
`r + 1` if a super-majority is reached. Events older than the latest
decided ancient threshold are marked `ROUND_NEGATIVE_INFINITY` and
skipped. The current code drives ancient-ness from birth round, not
generation; for the conceptual background see
[`../../concepts/rounds-and-witnesses.md`](../../concepts/rounds-and-witnesses.md).

**Witnesses.** `ConsensusImpl.witness` marks an event as a witness iff
its round is greater than `ROUND_NEGATIVE_INFINITY` and its round
exceeds its self-parent's round (or it has no self-parent).

**Strongly-seeing.** `ConsensusImpl.stronglySeeP` (line 1045) memoizes,
for an event `x` and member `m`, the canonical witness in the parent
round that `x` strongly sees through `m` — defined by a super-majority
of weight among intermediates that all see the same canonical witness.
`timedStronglySeeP` (line 871) wraps that with timing constraints used
during the round-creation walk. Both rest on
`lastSee(x, m)` (line 956), the most recent ancestor of `x` by
member `m`, memoized per event. Conceptual background:
[`../../concepts/strongly-seeing.md`](../../concepts/strongly-seeing.md).

**Fame voting.** When a witness is added to round `r + d`, it votes
on every undecided witness in earlier rounds via
`ConsensusImpl.voteInAllElections` (line 497). For round difference
`d == 1` the vote is the direct-visibility first vote; for `d > 1` the
vote is a counting vote (`getCountingVote`, line 578) that tallies
weighted YES/NO across witnesses of the previous round that the voter
strongly sees. When `isCoinRound(d)` returns true (line 613), the
counting vote falls back to a coin-bit derived from the voter's
`EventCore.coin` field (via `ConsensusUtils.coin`). Fame is decided
when a super-majority is reached on either side. See
[`../../concepts/voting.md`](../../concepts/voting.md) and
[`../../concepts/coin-rounds.md`](../../concepts/coin-rounds.md).

**Judges and round-decided.** When `RoundElections.isDecided()` is
true and the engine is no longer waiting for init judges,
`ConsensusImpl.roundDecided` (line 697) drives the rest:
`RoundElections.findAllJudges` collapses the famous witnesses to one
per creator (using a deterministic merge on branched creators) and
returns the judges sorted by creator id. The judges' hashes are XOR-ed
to produce a round-specific "whitening" byte string used during the
final tie-break.

**Consensus order within a round.** `ConsensusSorter.sort` orders the
events that just reached consensus by, in order:

1. preliminary consensus timestamp
   (`EventImpl.getPreliminaryConsensusTimestamp`),
2. extended median of received-times (the `recTimes` lists, walked
   outward from the median),
3. consensus generation `cGen` assigned per round by
   `LocalConsensusGeneration.assignCGen`, and
4. the whitened hash (XOR of the event's hash with the round's judge
   whitening).

`ConsensusSorter` is constructed once per decided round and discarded.
(`ConsensusSorter`'s class JavaDoc is stale and lists a different
key order; the implementation matches the four-step order above.)

**Round emission.** `roundDecided` packages the result into a
`ConsensusRound` carrying the consensus event list, an `EventWindow`
(with the decided round number, `decidedRound + 1` as the next
pending round, the new ancient threshold, and the new expired
threshold), and a `ConsensusSnapshot` (round, minimum-judge info,
the running `numConsensus` counter, the consensus timestamp, and the
judge IDs).

**Init-judge gate.** When `Consensus.loadSnapshot` is called at
restart or reconnect, `Consensus.waitingForInitJudges()` returns
`true` until the linker has supplied enough events to reconstruct the
snapshot's judges (handled by `InitJudges`). While that flag is set,
`DefaultConsensusEngine.addEvent` returns an empty output — the
just-added event is not yet classified as pre-consensus, because it
might already have been part of a previously decided round. The
flag is checked both before and after `consensus.addEvent`; the
post-add check is the transition point out of waiting. When the last
init judge arrives, the engine flushes any consensus events the
just-decided rounds produced and the queued pre-consensus events
into the output. Usually no rounds are decided in that same call,
but a major roster change can produce some — in which case those
consensus events are also reported as pre-consensus events so the
downstream view of pre-consensus output stays complete.

> **Note on the paper.** Round, witness, strongly-seeing, fame, and
> judge mean the same thing in the paper and in the code. The notable
> divergence is the ancient/expired horizon — the paper uses an event's
> deterministic generation where the current code uses `birthRound`; see
> [`../../concepts/birth-round.md`](../../concepts/birth-round.md) for that
> substitution. The
> implementation also carries a few quantities that do not appear in
> the paper — `NGen` (a locally-computed, non-deterministic generation
> used only for picking a topological order and for
> "higher in the hashgraph" comparisons; see `NonDeterministicGeneration`)
> and the `DeGen`/`cGen` family used inside the algorithm. Conceptual
> background lives in
> [`../../concepts/rounds-and-witnesses.md`](../../concepts/rounds-and-witnesses.md)
> and [`../../concepts/birth-round.md`](../../concepts/birth-round.md).

## Birth-round filtering

Birth round controls what enters the DAG and what stays in it (see
[`../../concepts/event-lifecycle.md`](../../concepts/event-lifecycle.md)
for the admitted → ancient → expired staircase that this section
gates).

**Future events.**
[`FutureEventBuffer`](../../../../consensus-utility/src/main/java/org/hiero/consensus/event/FutureEventBuffer.java),
configured with `FutureEventBufferingOption.PENDING_CONSENSUS_ROUND`,
holds events whose birth round exceeds
`EventWindow.getPendingConsensusRound()`. `addEvent` returns `null`
for such events; `updateEventWindow` releases events whose birth round
has become eligible after the window advanced. The buffer is
constructed in `DefaultConsensusEngine`'s constructor and exercised
on every event and every advanced round (`DefaultConsensusEngine.java`
lines ~81–82, ~110–115, ~186).

**Ancient drop.** `ConsensusLinker.linkEvent` short-circuits to `null`
when `EventWindow.isAncient(event)` is true — the event's birth round
is below the ancient threshold. `ConsensusLinker.setEventWindow` then
shifts `parentDescriptorMap`'s window to the new ancient threshold,
clears `parentHashMap` for evicted descriptors, calls `EventImpl.clear`
on each, and returns the list of events that just became ancient.
`DefaultConsensusEngine.addEvent` reports any of those that did not
reach consensus on the stale-events output (see
[`../../concepts/stale-events.md`](../../concepts/stale-events.md)).
Conceptual background:
[`../../concepts/birth-round.md`](../../concepts/birth-round.md).

## Cross-references

**Concepts.**

- [`../../concepts/hashgraph-dag.md`](../../concepts/hashgraph-dag.md)
- [`../../concepts/rounds-and-witnesses.md`](../../concepts/rounds-and-witnesses.md)
- [`../../concepts/strongly-seeing.md`](../../concepts/strongly-seeing.md)
- [`../../concepts/voting.md`](../../concepts/voting.md)
- [`../../concepts/coin-rounds.md`](../../concepts/coin-rounds.md)
- [`../../concepts/judges.md`](../../concepts/judges.md)
- [`../../concepts/birth-round.md`](../../concepts/birth-round.md)
- [`../../concepts/event-lifecycle.md`](../../concepts/event-lifecycle.md)
- [`../../concepts/stale-events.md`](../../concepts/stale-events.md)

**Invariants.** The consensus algorithm this module runs is where most of
the layer's invariants are established:

- INV-001 — voting round is monotonic along ancestry.
- INV-002 — consensus order is agreed by all nodes.
- INV-003 — consensus timestamp is agreed by all nodes.
- INV-004 — staleness is agreed by all nodes.
- INV-005 — every honest event eventually reaches consensus or becomes stale.
- INV-006 — every round eventually has at least one judge.
- INV-007 — all deciders of a round agree on its judge set.
- INV-008 — consensus, once reached, is permanent.
- INV-009 — a decided election never flips.
- INV-013 — the minimum non-ancient round never decreases (the ancient boundary only moves forward).
- INV-014 — every round has a supermajority of judges.
- INV-016 — consensus order is a strict total order with unique, gap-free ranks.

**Decisions.** [TBD: ADR-NNN once
[`../../decisions/`](../../decisions/) catalog populates.]

**Scenarios.** [TBD: SCN-NNN once
[`../../scenarios/`](../../scenarios/) catalog populates.]

**Sibling topics.**

- Upstream — [`../topics/event-intake.md`](../topics/event-intake.md)
- Downstream —
  [`../topics/signed-state-management.md`](../topics/signed-state-management.md)
- Lifecycle —
  [`../topics/restart-and-pces.md`](../topics/restart-and-pces.md),
  [`../topics/freeze-and-upgrade.md`](../topics/freeze-and-upgrade.md)

**Source proposal.**
[`../../../proposals/consensus-layer/Consensus-Layer.md`](../../../proposals/consensus-layer/Consensus-Layer.md).

## Future state

> **Future state.** Two proposal items touch this topic: the
> `nextRound(roster)` pull that would replace today's wired
> `consensusRoundOutputWire`, and the Sheriff module (absent from
> `consensus-hashgraph`, so a reader will not find it here). Both are
> described once, for the whole layer, in the
> [overview's Future state](../overview.md#future-state).
