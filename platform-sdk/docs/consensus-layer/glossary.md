---
title: Glossary
kind: glossary
last_reviewed: TBD
---

# Glossary

The canonical, one-line home for vocabulary used across the consensus-layer KB,
and the disambiguator for overloaded terms (round / voting round / consensus round /
birth round; ancient / expired / stale; falling behind / fallen behind). Entries are
deliberately short: where a term needs more than a sentence or two, the entry points
at the concept file (`concepts/`) or architecture topic (`architecture/topics/`) that
holds the depth.

### Ancestor

Event *y* is an ancestor of *x* if *y* is *x*, a *Parent* of *x*, a parent of a parent, and
so on. Contrast *Descendant*.
See [concepts/hashgraph-dag.md](concepts/hashgraph-dag.md).

### Ancient

An event — or its round — is *ancient* when its birth round is below the current ancient
threshold; ancient events are dropped from the hashgraph DAG, though gossip may still
retain them briefly to serve peers a few rounds behind. Contrast *Expired* (discarded
everywhere) and *Stale* (a fate, not a lifecycle state).
See [concepts/event-lifecycle.md](concepts/event-lifecycle.md).

### Ancient threshold

The birth-round boundary, carried on the *Event window*, below which events are treated
as ancient; the more recent of the two birth-round thresholds (the other being the
*Expired threshold*).
See [concepts/event-lifecycle.md](concepts/event-lifecycle.md).

### Backpressure

The mechanism by which a slow consumer slows its producers. In production it is *soft*:
hard wire-level backpressure (`platform.wiring.hardBackpressureEnabled`) is off by
default, so the *Health monitor*'s unhealthy-duration signal drives throttling rather
than wires blocking at capacity.
See [architecture/topics/health-monitor-and-backpressure.md](architecture/topics/health-monitor-and-backpressure.md).

### Birth round

The round number a creator stamps on an event at creation — its *Pending consensus
round* — immutable thereafter. It drives ancient/expired filtering and selects the
*Roster* an event is validated against; it is not used by the algorithm to order events.
Contrast *Voting round* and *Consensus round*.
See [concepts/birth-round.md](concepts/birth-round.md).

### Branching

Byzantine equivocation (the paper calls it *forking*) in which one creator signs two
events, neither a self-ancestor of the other, with birth rounds within the non-ancient
window. Strong-seeing and the judge merge tolerate it without extra messages.
See [concepts/branching.md](concepts/branching.md).

### Broadcast

The push side of gossip: layered on the same peer connection as *Sync*, it pushes fresh
self-events to peers immediately. When broadcast is active, sync defers likely-duplicate
self-events to avoid sending them twice.
See [architecture/topics/gossip.md](architecture/topics/gossip.md).

### CGen

*Consensus generation* (`LocalConsensusGeneration`): orders the events that reach consensus
within a single *Consensus round* (ties broken by event hash). Assigned to those events identically on
every node, but temporary — cleared once that round is complete. Used only within the
hashgraph algorithm. Contrast *NGen* and *DeGen*; see *Generation*.
See [architecture/topics/hashgraph.md](architecture/topics/hashgraph.md).

### Child

The inverse of *Parent*: *x* is a child of *y* when *y* is a *Parent* of *x*.
See [concepts/hashgraph-dag.md](concepts/hashgraph-dag.md).

### Coin round

A periodic round in a fame *Election* (every `coinFreq` *Voting rounds*, default 12) where a voter
that does not strongly see a super-majority falls back to a pseudo-random bit derived
from the voter's `coin` field, guaranteeing the election eventually decides.
See [concepts/coin-rounds.md](concepts/coin-rounds.md).

### Consensus / Execution boundary

The interface across which Consensus hands decided rounds, pre-handle events, and stale
events to Execution and pulls transactions back. In current code it is a set of separate
callbacks (`onPreHandle`, `onHandleConsensusRound`, `getTransactionsForEvent`, …) rather
than a single API.
See [architecture/interfaces/consensus-execution-boundary.md](architecture/interfaces/consensus-execution-boundary.md).

### Consensus event

An event that has reached consensus, and so carries a *Consensus order* and
*Consensus timestamp*.
See [concepts/judges.md](concepts/judges.md).

### Consensus order

The total order over events the algorithm produces once a *Voting round* is decided, fixed by the
round's *Judges* and then split into per-transaction *Consensus timestamps*.
See [concepts/judges.md](concepts/judges.md).

### Consensus round

Two meanings. (a) The round in which an event reaches consensus order; set exactly once —
when the round's judges are all ancestors of the event — and never changed thereafter
(formerly *round-received*, still used in code but deprecated). Contrast *Voting round* and
*Birth round*. (b) `ConsensusRound`, the output object carrying a decided round's events (in
consensus order) plus that round's *Event window*.
See [concepts/rounds-and-witnesses.md](concepts/rounds-and-witnesses.md).

### Consensus snapshot

The serializable summary of the algorithm's position (round number, minimum-judge info,
judge IDs, monotonic consensus number, consensus timestamp) used to reload consensus at
restart or reconnect.
See [architecture/topics/hashgraph.md](architecture/topics/hashgraph.md).

### Consensus timestamp

The timestamp assigned to an event when it reaches consensus, derived from when the
judging nodes first received it and nudged forward so that consecutive transactions in
consensus order are strictly increasing.
See [architecture/topics/hashgraph.md](architecture/topics/hashgraph.md).

### Deduplication

The intake stage that discards events already seen — keyed on the `(descriptor, signature)`
pair — so the pipeline and hashgraph process each event once. A known descriptor arriving
with a new signature is kept, not dropped (a disparate signature).
See [architecture/topics/event-intake.md](architecture/topics/event-intake.md).

### DeGen

*Deterministic generation* (`DeGen`): drives *lastSee* for *Strongly seeing*. Assigned to the
descendants of the last round's judges, identically on every node, and recomputed whenever
hashgraph metadata is recalculated. Used only within the hashgraph algorithm. Contrast *NGen*
and *CGen*; see *Generation*.
See [architecture/topics/hashgraph.md](architecture/topics/hashgraph.md).

### Descendant

The inverse of *Ancestor*: *x* is a descendant of *y* when *y* is an *Ancestor* of *x*.
See [concepts/hashgraph-dag.md](concepts/hashgraph-dag.md).

### Election

The per-round fame vote on a witness. A *Voting round* is *decided* once every witness in it has a
fame verdict, at which point that round's *Judges* and consensus order are fixed.
See [concepts/voting.md](concepts/voting.md).

### Event

A signed record created by one node, that may contain transactions, information about its
parent events, and some other metadata. Events are the vertices of the hashgraph DAG.
See [concepts/hashgraph-dag.md](concepts/hashgraph-dag.md).

### Event creator

The component that decides when to author a *Self-event*, selects other-parents via the
*Tipset* algorithm, fills it with transactions pulled from Execution, and applies veto
rules (rate, platform status, health, sync lag, quiescence).
See [architecture/topics/event-creator.md](architecture/topics/event-creator.md).

### Event intake

The pipeline that hashes, validates, deduplicates, and orphan-buffers incoming events,
applies the per-stage ancient filter, and emits a topologically ordered stream.
See [architecture/topics/event-intake.md](architecture/topics/event-intake.md).

### Event lifecycle

The state sequence an admitted event passes through as the window advances: admitted →
ancient → expired.
See [concepts/event-lifecycle.md](concepts/event-lifecycle.md).

### Event window

The `EventWindow` record carrying the current *Ancient threshold*, *Expired threshold*,
and *Pending consensus round*; consulted across intake, the linker, gossip, and the
hashgraph to decide what is in-window.
See [concepts/event-lifecycle.md](concepts/event-lifecycle.md).

### Expired

An event past the *Expired threshold* (further in the past than ancient); discarded
everywhere, including by gossip. A peer that needs an expired event can no longer catch
up by gossip and must *Reconnect*. Contrast *Ancient* and *Stale*.
See [concepts/event-lifecycle.md](concepts/event-lifecycle.md).

### Expired threshold

The birth-round boundary below which events are expired; produced inside the hashgraph
and consumed at retention sites such as the gossip *Shadowgraph*.
See [concepts/event-lifecycle.md](concepts/event-lifecycle.md).

### Fallen behind

The condition of a node so far behind that the events it needs are already expired on its
peers, so gossip cannot close the gap and it must *Reconnect*. Contrast *Falling behind*,
which is still recoverable by gossip.
See [architecture/topics/reconnect.md](architecture/topics/reconnect.md).

### Falling behind

A node trailing its peers but still able to catch up through gossip; such a node may
self-suppress event creation to avoid authoring events that would go *Stale*. Contrast
*Fallen behind*.
See [architecture/topics/reasons-not-to-gossip.md](architecture/topics/reasons-not-to-gossip.md).

### Famous witness

A *Witness* voted *famous* by virtual voting. The famous witnesses of a *Voting round*, merged to
one per creator, are its *Judges*.
See [concepts/voting.md](concepts/voting.md).

### firstSee

`firstSee(x, m)` — the first *Witness* on *x*'s self-ancestor line in the *Voting round* of
`lastSee(x, m)` (the most recent event by creator *m* that is an ancestor of *x*). A
*see*-family helper used with *lastSee* and *seeThru* to compute *Strongly seeing*.
See [concepts/strongly-seeing.md](concepts/strongly-seeing.md).

### Freeze and upgrade

A coordinated network freeze at a chosen *Consensus round* that stops consensus so every node can
take a matching state and restart on new software.
See [architecture/topics/freeze-and-upgrade.md](architecture/topics/freeze-and-upgrade.md).

### Future event

An event whose *Birth round* exceeds the *Pending consensus round*; held in the future-event
buffer (`FutureEventBuffer`) until the window advances, rather than added to the hashgraph
or the event creator.
See [concepts/event-lifecycle.md](concepts/event-lifecycle.md).

### Future round

Any round beyond the *Pending consensus round*; events born in one are not yet admitted to
the hashgraph (see *Future event*).
See [concepts/event-lifecycle.md](concepts/event-lifecycle.md).

### Generation

A per-event count: one plus the maximum parent generation. The paper used a single
*deterministic* generation as the ancient horizon; current code uses *Birth round* for that
and keeps three separate generation counters instead, each calculated differently and used
for a different purpose — *nGen* (local, for topological ordering), *deGen* (deterministic,
for *Strongly seeing*), and *cGen* (deterministic, for consensus ordering within a
*Consensus round*).
See [concepts/birth-round.md](concepts/birth-round.md).

### Gossip

Peer-to-peer event exchange. Over each peer connection the consensus layer multiplexes a
*Sync* protocol and an optional *Broadcast* of self-events, governed by neighbor-discipline
rules for when not to gossip.
See [architecture/topics/gossip.md](architecture/topics/gossip.md).

### Hashgraph

The directed acyclic graph of events linked by *Self-parent* and *Other-parent* edges
that the algorithm runs over; "the hashgraph" is the in-memory non-ancient DAG.
See [concepts/hashgraph-dag.md](concepts/hashgraph-dag.md).

### Health monitor

The component that polls every *Scheduler*'s queue depth against capacity and publishes
the longest continuous unhealthy duration; a detector, not an enforcer — reaction sites
elsewhere decide what to do with the signal.
See [architecture/topics/health-monitor-and-backpressure.md](architecture/topics/health-monitor-and-backpressure.md).

### Invalid event

An event discardable on its own — bad signature, unparseable, or otherwise malformed —
independent of any other event. Contrast a *Branching* event, which is bad only relative to
its sibling.
See [architecture/topics/event-intake.md](architecture/topics/event-intake.md).

### ISS

An *inconsistent state signature*: a divergence detected when a node's per-round state
hash disagrees with peers' signatures, classified and met with a configured halt or
restart response.
See [architecture/topics/iss-detection.md](architecture/topics/iss-detection.md).

### Judge

The unique *Famous witness* for a creator in a decided *Voting round*; the judges of a *Voting round*
jointly fix the *Consensus round* (and thus consensus order) of every event that is an
ancestor of all of them. The paper terms these "unique famous witnesses."
See [concepts/judges.md](concepts/judges.md).

### lastSee

`lastSee(x, m)` — the most recent event by creator *m* that is an ancestor of *x*, or null if
none. A *see*-family helper underlying *firstSee* and *seeThru*.
See [concepts/strongly-seeing.md](concepts/strongly-seeing.md).

### Latest consensus round

The most recent round to reach consensus (`latestConsensusRound`, carried on the *Event
window*); one less than the *Pending consensus round*.
See [concepts/rounds-and-witnesses.md](concepts/rounds-and-witnesses.md).

### Min judge birth round

The smallest *Birth round* among a *Consensus round*'s *Judges*; stored per round as
`MinimumJudgeInfo`, it anchors the *Ancient threshold* and *Expired threshold*.
See [concepts/event-lifecycle.md](concepts/event-lifecycle.md).

### NGen

*Non-deterministic generation* (`NonDeterministicGeneration`): orders events into one valid
topological order and answers "higher in the hashgraph" comparisons. Assigned to every
non-orphan event, locally by each node, so it may differ between nodes; set once at intake and
then stable. Used throughout the consensus layer. Contrast the deterministic *DeGen* and
*CGen*; see *Generation*.
See [architecture/topics/event-intake.md](architecture/topics/event-intake.md).

### Orphan buffer

An event whose parents are not yet present is an *orphan*; the orphan buffer holds it
until each parent is either present or ancient, then releases it in topological order.
See [architecture/topics/event-intake.md](architecture/topics/event-intake.md).

### Other-child

The inverse of *Other-parent*: *x* is an other-child of *y* when *y* is *x*'s *Other-parent*.
See [concepts/hashgraph-dag.md](concepts/hashgraph-dag.md).

### Other-parent

A parent edge to an event by a different creator. The data model allows several; the number an event
uses is bounded by the `maxOtherParents` tunable (TUN-140). Contrast *Self-parent*.
See [concepts/hashgraph-dag.md](concepts/hashgraph-dag.md).

### Parent

An event *y* whose hash event *x* carries; either *x*'s *Self-parent* or its *Other-parent*.
See [concepts/hashgraph-dag.md](concepts/hashgraph-dag.md).

### PCES

The *preconsensus event stream*: an on-disk, topologically ordered log of every validated
event, written before the event is gossiped or fed to consensus, and replayed at restart
to rebuild the hashgraph.
See [architecture/topics/restart-and-pces.md](architecture/topics/restart-and-pces.md).

### Pending consensus round

The round a creator is currently working to decide — the latest decided round + 1. New
events take it as their *Birth round*; events with a higher birth round are future-buffered.
See [concepts/birth-round.md](concepts/birth-round.md).

### Platform status

The node's lifecycle state (e.g. `ACTIVE`, `CHECKING`, `FREEZING`), tracked by the status
state machine and consulted by rules such as whether to create events or gossip.
See [architecture/interfaces/consensus-execution-boundary.md](architecture/interfaces/consensus-execution-boundary.md).

### Prehandle

Execution's early, per-event pass over an event's transactions, before it reaches
consensus; an event sent to prehandle that later goes *Stale* must be reported back to
Execution so prehandle state can be reconciled.
See [concepts/stale-events.md](concepts/stale-events.md).

### Quiescence

An opt-in feature that pauses self-event creation when nothing is pending and no deadline
is near, and that constrains how preconsensus, consensus, and stale events are reported
across the Consensus / Execution boundary.
See [architecture/topics/quiescence.md](architecture/topics/quiescence.md).

### Reconnect

The recovery path for a *Fallen behind* node: it learns a recent *Signed state* from a
peer rather than catching up event-by-event through gossip.
See [architecture/topics/reconnect.md](architecture/topics/reconnect.md).

### Roster

The consensus-relevant subset of the address book (node IDs, signing keys, consensus
weights) for a given round; an event is validated against the roster for its *Birth round*.
Rosters can change on round boundaries — today only at upgrade.
See [concepts/birth-round.md](concepts/birth-round.md).

### Round

Events reach consensus in numbered batches called rounds. Several distinct round
quantities exist and must not be conflated: *Birth round* (stamped at creation),
*Voting round* (mutable, used for elections; formerly *round-created*), and
*Consensus round* (the round an event reaches consensus in; formerly *round-received*).
See [concepts/rounds-and-witnesses.md](concepts/rounds-and-witnesses.md).

### Round timestamp

The end-of-round timestamp (`ConsensusRound.getConsensusTimestamp`): normally the *Consensus
timestamp* of the last transaction of the round's last event, strictly greater than the
previous round's. Edge cases — a round with no events, or events with no transactions — fall
back to a derived value.
See [architecture/topics/hashgraph.md](architecture/topics/hashgraph.md).

### Round-created

Deprecated name for *Voting round*; still used in code but being phased out.
See [concepts/rounds-and-witnesses.md](concepts/rounds-and-witnesses.md).

### Round-received

Deprecated name for *Consensus round*; still used in code but being phased out.
See [concepts/rounds-and-witnesses.md](concepts/rounds-and-witnesses.md).

### Scheduler

A wiring primitive (`TaskScheduler`) that owns a queue, a threading policy (`SEQUENTIAL`,
`CONCURRENT`, `DIRECT`, …), and a primary output wire; components run as handlers on
schedulers.
See [architecture/topics/wiring-framework.md](architecture/topics/wiring-framework.md).

### Seeing

The paper's base relation, no longer used in the code: event *x* *sees* event *y* if *y* is
an ancestor of *x* and *x* cannot detect a branch by *y*'s creator on that path — so if a
creator branches, *x* sees none of the branched events. The code drops this branch test; its
*see*-family helpers (*lastSee*, *firstSee*, *seeThru*) instead resolve to the first event of
a branch.
See [concepts/strongly-seeing.md](concepts/strongly-seeing.md).

### seeThru

`seeThru(x, m, m2)` — the *Witness* by creator *m* that *x* sees *through* an intermediate
event by creator *m2*, i.e. `firstSee(lastSee(x, m2), m)`. *Strongly seeing* is computed by
counting the distinct *m2* that resolve to the same witness.
See [concepts/strongly-seeing.md](concepts/strongly-seeing.md).

### Self-ancestor

Event *y* is a self-ancestor of *x* if *y* is *x* or reachable from *x* through *Self-parent*
edges alone. Contrast *Self-descendant*.
See [concepts/hashgraph-dag.md](concepts/hashgraph-dag.md).

### Self-child

The inverse of *Self-parent*: *x* is a self-child of *y* when *y* is *x*'s *Self-parent*.
See [concepts/hashgraph-dag.md](concepts/hashgraph-dag.md).

### Self-descendant

The inverse of *Self-ancestor*: *x* is a self-descendant of *y* when *y* is a *Self-ancestor*
of *x*.
See [concepts/hashgraph-dag.md](concepts/hashgraph-dag.md).

### Self-event

An event a node creates itself; the creator tracks its latest self-event locally to use as
the next *Self-parent*.
See [architecture/topics/event-creator.md](architecture/topics/event-creator.md).

### Self-parent

The parent edge to the creator's own previous event; an honest creator's events form a
single chain under self-parent edges. Contrast *Other-parent*.
See [concepts/hashgraph-dag.md](concepts/hashgraph-dag.md).

### Shadowgraph

Gossip's window of events available for *Sync*, indexed for ancestor traversal and bounded
by the *Expired threshold*.
See [architecture/topics/gossip.md](architecture/topics/gossip.md).

### Signed state

A periodic snapshot of the application state for a *Consensus round*, hashed and signed by nodes; a
threshold of signatures makes it usable for *Reconnect* and for *ISS* detection.
See [architecture/topics/signed-state-management.md](architecture/topics/signed-state-management.md).

### Soldering

Connecting one component's output wire to another's input wire in the wiring model; the
per-edge handoff semantics are selected with `PUT`, `OFFER`, or `INJECT`.
See [architecture/topics/wiring-framework.md](architecture/topics/wiring-framework.md).

### Squelching

A *Scheduler*'s "drop new tasks" toggle, paired with `flush()` to drain a component's
backlog during operations such as reconnect.
See [architecture/topics/wiring-framework.md](architecture/topics/wiring-framework.md).

### Stale

A *fate*, not a lifecycle state: an event admitted to the hashgraph that aged past the
*Ancient threshold* without ever reaching consensus, so its transactions never gained
consensus order. Stale reports are local observations and are not deterministic across
nodes. Contrast *Ancient* and *Expired*.
See [concepts/stale-events.md](concepts/stale-events.md).

### Strongly seeing

Event *x* *strongly sees* witness *y* when a *Super-majority* of roster weight, spread
across distinct creators, lies on paths between *y* and *x*. The relation gates voting
round bumps and fame voting and is what keeps consensus safe under *Branching*.
See [concepts/strongly-seeing.md](concepts/strongly-seeing.md).

### Super-majority

More than two-thirds of total roster weight; the threshold for *Strongly seeing*, voting
round bumps, and fame *Election* decisions.
See [concepts/strongly-seeing.md](concepts/strongly-seeing.md).

### Sync

The three-phase gossip protocol in which two peers exchange event windows and *Tips*, then
send each other the events the other lacks; the primary catch-up mechanism. Contrast
*Broadcast*.
See [architecture/topics/gossip.md](architecture/topics/gossip.md).

### Tip

An event with no self-child yet — the frontier of a creator's chain. Tips are exchanged in
sync phase 1 and are the candidates the *Tipset* algorithm draws from.
See [architecture/topics/gossip.md](architecture/topics/gossip.md).

### Tipset

The algorithm the *Event creator* uses to choose an event's other-parent(s), favoring
parents that make the most progress toward consensus.
See [architecture/topics/event-creator.md](architecture/topics/event-creator.md).

### Transformer

A wiring primitive that reshapes data along a wire (filter, transform, list-split) without
a dedicated component.
See [architecture/topics/wiring-framework.md](architecture/topics/wiring-framework.md).

### Virtual voting

A message-free voting technique: each voter's vote is a deterministic function of the
hashgraph DAG rather than an exchanged message, so every node computes the same votes from
the same DAG.
See [concepts/voting.md](concepts/voting.md).

### Voter

A *Witness* that votes in the *Elections* of earlier *Voting rounds* (`votingWitness`): one round
later it casts a *first vote* (does it see the candidate?), and in later rounds a *counting
vote* (the tally over the voters it strongly sees).
See [concepts/voting.md](concepts/voting.md).

### Voting round

Formerly *round-created* (still used in code but deprecated). A mutable per-event round
number derived from an event's parents and the strong-seeing relation, used solely to
identify *Witnesses* and run *Elections*; it can be recalculated as earlier rounds decide.
Contrast *Consensus round* and *Birth round*.
See [concepts/rounds-and-witnesses.md](concepts/rounds-and-witnesses.md).

### Wire

A typed connection in the wiring model: an *output wire* is a component's source of data,
an *input wire* its entry point; wires are joined by *Soldering*.
See [architecture/topics/wiring-framework.md](architecture/topics/wiring-framework.md).

### Witness

The first event by a creator in a given *Voting round* (equivalently, an event whose
voting round exceeds its self-parent's, or which has no self-parent); only witnesses
vote, collect votes, and can become *Judges*.
See [concepts/rounds-and-witnesses.md](concepts/rounds-and-witnesses.md).
