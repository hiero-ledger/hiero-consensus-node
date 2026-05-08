---

title: Hashgraph
kind: architecture-topic
last_reviewed: TBD
------------------

# Hashgraph

## Responsibilities

The hashgraph topic owns the in-memory directed acyclic graph (DAG) of
non-ancient events and runs the consensus algorithm that turns a
topologically ordered stream of `PlatformEvent`s into an ordered stream
of `ConsensusRound`s carrying judges, a consensus timestamp, the
consensus roster for the round, and the `EventWindow` that defines the
ancient and expired thresholds going forward.

The topic does not validate, deduplicate, or topologically order events
— those concerns live in
[`../topics/event-intake.md`](../topics/event-intake.md). It does not
durably persist events — that is the Pre-Consensus Event Stream
(see [`../topics/restart-and-pces.md`](../topics/restart-and-pces.md)).
It does not gossip — that is `consensus-gossip`. Once a round reaches
consensus, the round leaves the hashgraph; downstream signing, hashing,
and signature collection live in
[`../topics/signed-state-management.md`](../topics/signed-state-management.md).

The two boundaries that frame the topic:

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
orchestrates one `addEvent` cycle. Its fields are the topic's working
state at runtime:

- `linker: ConsensusLinker` — the DAG of non-ancient linked events.
- `consensus: Consensus` (impl
  [`ConsensusImpl`](../../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java))
  — round / witness / fame / judge state.
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
  current `RoundElections`), the
  `RosterLookup`, the `lastConsensusTime`, the running `numConsensus`
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
  aged past the ancient threshold without reaching consensus.

`ConsensusRound`
([`consensus-model/.../ConsensusRound.java`](../../../../consensus-model/src/main/java/org/hiero/consensus/model/hashgraph/ConsensusRound.java))
carries the consensus event list (in consensus order), the
`EventWindow` for the round (the new ancient and expired thresholds and
the next pending round), the consensus roster, a
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

1. `freezeRoundController.isFrozen()` — short-circuits to an empty
   output once the freeze round has emitted.
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
   ancient; non-consensus ancients are reported as stale.
6. `futureEventBuffer.updateEventWindow(eventWindow)` releases any
   future events whose birth round is now eligible; they are appended
   to the work queue and the loop iterates.
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
[`../concepts/rounds-and-witnesses.md`](../concepts/rounds-and-witnesses.md).

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
[`../concepts/strongly-seeing.md`](../concepts/strongly-seeing.md).

**Fame voting.** When a witness is added to round `r + d`, it votes
on every undecided witness in earlier rounds via
`ConsensusImpl.voteInAllElections` (line 497). For round difference
`d == 1` the vote is the direct-visibility first vote; for `d > 1` the
vote is a counting vote (`getCountingVote`, line 578) that tallies
weighted YES/NO across witnesses of the previous round that the voter
strongly sees. When `isCoinRound(d)` returns true (line 604), the
counting vote falls back to the middle-bit-of-signature pseudo-random
choice for that voter. Fame is decided when a super-majority is reached
on either side. See
[`../concepts/coin-rounds.md`](../concepts/coin-rounds.md).

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
[TBD: question for engineer — `ConsensusSorter`'s class JavaDoc lists
the order as "consensus timestamp → extended median → generation →
whitened signature", but its `compare` method JavaDoc separately
references "roundReceived" as the leading sort key. Round-received is
implicit because the sorter runs per decided round; is the four-step
order above the authoritative description today?]

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
might already have been part of a previously decided round. When the
last init judge arrives, the engine flushes any consensus events the
just-decided rounds produced and the queued pre-consensus events into
the output.
[TBD: question for engineer — the `waitingForInitJudges` flag is
checked both before and after `consensus.addEvent` in
`DefaultConsensusEngine.addEvent`. Is the post-add check intended to
be the only point at which we can transition out of waiting, or can a
single `addEvent` flip the flag in either direction in practice?]

> **Note on the paper.** The paper computes rounds against the event's
> non-deterministic generation (NGen); the current code uses event
> sequence numbers for parent matching and birth round for
> ancient-ness. Where the paper and the code use the same names
> (witness, strongly-seeing, fame, judge), the meanings line up; where
> they diverge in mechanics (NGen vs. birth round; the
> `DeGen`/`cGen` family of generations used inside the implementation
> only), the conceptual background lives in
> [`../concepts/rounds-and-witnesses.md`](../concepts/rounds-and-witnesses.md).

## Birth-round filtering

Birth round controls what enters the DAG and what stays in it.

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
reach consensus on the stale-events output. Conceptual background:
[`../concepts/birth-round.md`](../concepts/birth-round.md).

## Cross-references

**Concepts.**

- [`../concepts/hashgraph-dag.md`](../concepts/hashgraph-dag.md)
- [`../concepts/rounds-and-witnesses.md`](../concepts/rounds-and-witnesses.md)
- [`../concepts/strongly-seeing.md`](../concepts/strongly-seeing.md)
- [`../concepts/birth-round.md`](../concepts/birth-round.md)
- [`../concepts/coin-rounds.md`](../concepts/coin-rounds.md)
- [`../concepts/judges.md`](../concepts/judges.md)

**Invariants.** [TBD: INV-NNN once
[`../invariants.md`](../invariants.md) catalog populates.]

**Decisions.** [TBD: ADR-NNN once
[`../decisions/`](../decisions/) catalog populates.]

**Scenarios.** [TBD: SCN-NNN once
[`../scenarios/`](../scenarios/) catalog populates.]

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

> **Future state.** The proposal envisions a single Consensus public
> API on which Execution calls `nextRound(roster)` to pull each round,
> giving natural backpressure; today rounds flow over the wired
> `consensusRoundOutputWire` and the boundary is split across
> `ExecutionLayer` and `ConsensusStateEventHandler` in
> `swirlds-platform-core`. The proposal also introduces a separate
> Sheriff module that aggregates misbehavior reports; it is not present
> in the current `consensus-hashgraph` module or anywhere else in the
> code, and a reader looking for it inside the hashgraph topic will
> not find it.
