# Hashgraph Consensus Glossary

The following terms are relevant to the Hashgraph consensus algorithm, which is the core algorithm running on mainnet nodes to put the transactions in consensus order and assign them consensus timestamps, before the transactions are handled.

## Conventions

- **Gloss** — short-form definition, one line. Verbatim source wording where the source's first sentence stands alone as a definition; minimally authored otherwise.
- **Detail** — longer prose definition, preserved verbatim from the source.
- **Formula** — formal expression extracted from the source.
- **Which / When / Consistency / Uses** — sub-fields used for event-field entries that carry per-field semantics in the source.
- **See also** — related glossary terms.
- **Concept** — link to the matching file under `concepts/`.
- **Delta-map** — link to the relevant delta-map topic when current-vs-proposed status applies.
- **Note** — editorial flag for source corrections or open questions.

Every entry has a `Gloss`. Entries whose source definition runs to multiple sentences (or carries a formula in prose) also have a `Detail` field carrying the full verbatim wording. Cross-reference paths assume the glossary lives at `consensus-layer/hashgraphGlossary.md`.

## Contents

- [Rounds](#rounds)
- [Fields of an event](#fields-of-an-event)
- [Events](#events)
- [Event relationships](#event-relationships)
- [Address books](#address-books)
- [Misc terminology](#misc-terminology)
- [Parameters](#parameters)

---

## Rounds

### Round
- **Gloss:** events reach consensus in batches, called `rounds`.
- **Detail:** events reach consensus in batches, called `rounds`. The first batch of events to reach consensus is round 1, the next is round 2, and so on. At any given moment, certain rounds have names, as defined below.
- **See also:** pending round, ancient round, future round, max consensus round, roster round, round timestamp
- **Concept:** [rounds-and-witnesses](concepts/rounds-and-witnesses.md)

### Pending round
- **Gloss:** the round number of the minimum round that hasn't yet reached consensus.
- **Detail:** the round number of the minimum round that hasn't yet reached consensus. This is the round whose judges are currently being determined. Those consensus calculations use the roster whose `rosterRound` is the current `pendingRound`. Any new event is created containing a `birthRound` field equal to the current `pendingRound`. All events currently in the hashgraph should have birth rounds in the range from `(minJudgeBirthRound - numRoundsNonAncient)` to `pendingRound`, inclusive, so they are neither `ancient` nor `future`.
- **See also:** round, ancient round, future round, min judge birth round, roster, roster round, birthRound, numRoundsNonAncient
- **Concept:** [rounds-and-witnesses](concepts/rounds-and-witnesses.md), [birth-round](concepts/birth-round.md)

### Min judge birth round
- **Gloss:** the minimum birth round of all the judges in round `pendingRound - 1`.
- **Detail:** the minimum birth round of all the judges in round `pendingRound - 1`. This is used to define which rounds are ancient and future, which controls which events can be in the hashgraph.
- **See also:** judge, pending round, ancient round, future round, birthRound, min non-ancient round, min non-expired round
- **Concept:** [birth-round](concepts/birth-round.md), [judges](concepts/judges.md)

### Ancient round
- **Gloss:** a round is `ancient` if its birth round is less than `(minJudgeBirthRound - numRoundsNonAncient)`.
- **Detail:** a round is `ancient` if its birth round is less than `(minJudgeBirthRound - numRoundsNonAncient)`. Events with ancient birth rounds are removed from the hashgraph (or treated by consensus as if they weren't in the hashgraph).
- **See also:** min judge birth round, numRoundsNonAncient, min non-ancient round, ancient event, future round
- **Concept:** [event-lifecycle](concepts/event-lifecycle.md), [birth-round](concepts/birth-round.md)

### Future round
- **Gloss:** any round greater than `pendingRound`. Events with birth rounds greater than this are not put in the hashgraph.
- **See also:** pending round, ancient round, future event
- **Concept:** [event-lifecycle](concepts/event-lifecycle.md), [birth-round](concepts/birth-round.md)

### Max consensus round
- **Gloss:** the round number for the maximum round that has reached consensus.
- **Formula:** `maxConsensusRound = pendingRound - 1`
- **See also:** pending round, round
- **Concept:** [rounds-and-witnesses](concepts/rounds-and-witnesses.md)

### Min non-ancient round
- **Gloss:** the min round that is not ancient
- **Formula:** `minNonAncientRound = minJudgeBirthRound - numRoundsNonAncient`
- **See also:** min judge birth round, numRoundsNonAncient, ancient round, ancient event
- **Concept:** [event-lifecycle](concepts/event-lifecycle.md), [birth-round](concepts/birth-round.md)

### Min non-expired round
- **Gloss:** the min round that is not expired
- **Formula:** `minNonExpiredRound = minJudgeBirthRound - numRoundsNonExpired`
- **See also:** min judge birth round, numRoundsNonExpired, expired event
- **Concept:** [event-lifecycle](concepts/event-lifecycle.md), [birth-round](concepts/birth-round.md)
- **Note:** the source v18 formula read `minNonExpiredRound = minJudgeBirthRound - numRoundsNonAncient`; corrected to `numRoundsNonExpired` per agreed cleanup.

### Max roster round
- **Gloss:** the maximum future round number for which there should be a roster in the queue
- **Formula:** `maxRosterRound = pendingRound + numRoundsFutureRoster`
- **See also:** pending round, numRoundsFutureRoster, roster, roster queue, roster round
- **Delta-map:** [hashgraph](delta-map/hashgraph.md)

### Roster round
- **Gloss:** any given roster (subset of an address book) is associated with a long `rosterRound`, which is its roster round number.
- **Detail:** Any given roster (subset of an address book) is associated with a long `rosterRound`, which is its roster round number. The `nodeID`s and consensus weights in a roster should be used when its `rosterRound` equals the pending round. A queue of rosters must be stored in state, one for each round number from `minNonAncientRound` to `maxRosterRound`, inclusive. Every time a round reaches consensus, the oldest roster can be removed. Any time the transactions in a round are handled, a new roster is added for the future. An event is only allowed to be in the hashgraph when its birth round is the roster round for some roster in the queue. And it must have been created by a node that is listed in that roster.  Of course, ancient, non-expired events may still be in memory, to gossip to nodes that are behind this node, but they don't count as being in this node's hashgraph. Events with birth rounds too far in the future should not be accepted during gossip. And a node should not send such events to another node, if it knows that node will just discard them for that reason. So conceptionally, there is a queue of rosters with one for each of the roster rounds. In implementation, since most rounds have identical rosters, this can be done with less memory by just having a queue of pointers, where multiple pointers point the same roster. Or it can even store the oldest roster, and store in what round it will change. But the effect is the same as a complete queue of rosters, in the appropriate range.
- **See also:** roster, roster queue, address book, min non-ancient round, max roster round, pending round, birthRound
- **Delta-map:** [hashgraph](delta-map/hashgraph.md)

### Round timestamp
- **Gloss:** the timestamp for a round is defined as the transaction timestamp of the last transaction in the last event that reached consensus in that round.
- **Detail:** the timestamp for a round is defined as the transaction timestamp of the last transaction in the last event that reached consensus in that round. So the state for that round reflects the effects of all transactions with a timestamp equal to or less than the round timestamp.
- **See also:** transaction timestamp, consensus event, consensusTimestamp

---

## Fields of an event

The fields in the first section below are created when the event is created, filled in by the creator node, signed by the creator node, and gossiped to all other nodes. The other sections are fields calculated by a node locally, about all of the events it receives or creates, and are not gossiped to other nodes.

The sections below list all the fields inside an event. In the first section, 4 of the fields make claims about a parent event: the creator and birth round of the self-parent and other-parent. If a malicious node creates an event, it is possible that it might make one or more incorrect claims, and so the claim won't match the creator or birth round of the actual parent. An event cannot be added to the hashgraph until all of its parents about which it made false claims have become ancient. In other words, liars can't be added to the hashgraph. But once a parent becomes ancient, it's as if that parent no longer exists, and so the false claim about it is no longer treated as a lie.

### Signed, immutable fields (gossiped, in the block stream)

#### transactions
- **Gloss:** the list of transactions (possibly empty)
- **See also:** transaction timestamp

#### birthRound
- **Gloss:** set to the creator's pending round at the moment of creation. Is signed and immutable.
- **See also:** pending round, ancient round, future round, selfParentBirthRound, otherParentBirthRound
- **Concept:** [birth-round](concepts/birth-round.md)

#### createdTimestamp
- **Gloss:** creator's claimed wall-clock time when it was created, must be later than self-parent.
- **See also:** selfParent, consensusTimestamp

#### selfParent
- **Gloss:** the hash of the self parent (or null if there is none, or the self-parent's birth round is ancient)
- **See also:** parent, self-parent, selfParentBirthRound, ancient event
- **Concept:** [hashgraph-dag](concepts/hashgraph-dag.md)

#### selfParentBirthRound
- **Gloss:** the claimed birth round of the self-parent.
- **See also:** selfParent, birthRound, self-parent
- **Concept:** [birth-round](concepts/birth-round.md), [hashgraph-dag](concepts/hashgraph-dag.md)

#### otherParent
- **Gloss:** the hash of the other parent (or null if there is none, or the other parent is ancient).
- **See also:** parent, other-parent, otherParentBirthRound, ancient event, maxOtherParents
- **Concept:** [hashgraph-dag](concepts/hashgraph-dag.md)

#### otherParentBirthRound
- **Gloss:** the claimed birth round of the other-parent.
- **See also:** otherParent, birthRound, other-parent
- **Concept:** [birth-round](concepts/birth-round.md), [hashgraph-dag](concepts/hashgraph-dag.md)

### Unsigned, immutable, streamed fields (not gossiped, in the block stream)

#### consensusRound
- **Gloss:** the min round in which this event was an ancestor of all judges.
- **See also:** judge, consensus event, ancestor, round
- **Concept:** [rounds-and-witnesses](concepts/rounds-and-witnesses.md), [judges](concepts/judges.md)

#### consensusTimestamp
- **Gloss:** the median timestamp of when it first reached each of the nodes that created judges in its consensus round.
- **Detail:** the median timestamp of when it first reached each of the nodes that created judges in its consensus round. This is adjusted by adding nanoseconds to ensure that in consensus order, each transaction is at least 1000 nanoseconds after the previous one. (That ensures they are unique and monotonically increasing, and there are big enough gaps so that synthetic transactions inserted between them can have timestamps that are unique).
- **See also:** judge, consensusRound, transaction timestamp, consensusOrder, round timestamp
- **Concept:** [judges](concepts/judges.md)

#### consensusOrder
- **Gloss:** an long `N` indicating this is the `N`th event in all of history, according to the calculated consensus order.
- **See also:** consensus event, consensusRound, consensusTimestamp
- **Concept:** [judges](concepts/judges.md)

### Unsigned, immutable, non-streamed fields (not gossiped, not in the block stream)

#### Non-Deterministic Generation (NGen)
- **Gloss:** non-deterministic per-event generation counter set when an event leaves the orphan buffer; may differ across nodes.
- **Which:** set for non-ancient events
- **Formula:** set to 1 plus the max NGen of its non-ancient parents (or set to 1, if there are none)
- **When:** set when an event leaves the orphan buffer, just before it is sent to PCES
- **Consistency:** an event can have different NGen on different nodes
- **Uses:** used to decide which events to recalculate for each round, and for the GUI, and in the tipset algorithm, and when there is a need to do a non-deterministic topological sort (such as in sync). Maybe this should also be used in metrics and in tests.
- **See also:** CGen, DGen, ancient event, orphan event, Preconsensus event stream (PCES) event, TipSet

#### Consensus Generation (CGen)
- **Gloss:** per-event consensus generation counter assigned during a round's consensus calculation; identical across all nodes.
- **Which:** set for the events that reached consensus in a given round
- **Formula:** set to 1 plus the max CGen of its parents that reached consensus in the same round (or set to 1, if there are none).
- **When:** set for each event that reaches consensus in a given round. As they are found (by depth-first search of the hashgraph), each event is assigned a generation after its parents are assigned.
- **Consistency:** an event will have the same CGen on all nodes
- **Uses:** used as one of the tie-breaking cases when sorting the events into consensus order for a round. Maybe it isn't used anywhere else.
- **See also:** NGen, DGen, consensus event, consensusOrder

### Unsigned, mutable fields (not gossiped, not in the block stream)

#### Deterministic Generation (DGen)
- **Gloss:** per-event deterministic generation counter recalculated each round alongside `votingRound`; identical across nodes for a given round but can change round to round.
- **Which:** set for all events that have positive voting rounds (not round 0 or round negative infinity)
- **Formula:** set to 1 plus the max DGen of its parents that have positive voting rounds (or set to 1, if there are none)
- **When:** set for each event at the same time its `votingRound` is set, during the recalculation that happens for each new consensus round
- **Consistency:** an event will have the same DGen on all nodes for a given round, though it can change for each round
- **Uses:** used in the lastSee() function for consensus. Maybe it isn't used anywhere else.
- **See also:** NGen, CGen, votingRound, see

#### votingRound
- **Gloss:** the round for this event, which determines whether it is a witness.
- **Detail:** the round for this event, which determines whether it is a witness. It is a witness if its voting round is different from its self-parent's voting round (or it has no self-parent). If it is a witness, then it might be eligible for election as a judge (if it's in the pending round), or it might be an initial voter for judge (if it's in the pending round plus 1), or it might be a voter and vote collector (if it's later than the pending round plus 1). Every time the processing of a new round starts, the voting round is recalculated for all events. It can be different than it was in the past because non-ancestors of the latest judges are defined to have a voting round of negative infinity (represented as a 0 in memory), and that can cause all their descendents to change their voting rounds. If a judge in the consensus round is not a descendant of any other judge in the consensus round, then it is guaranteed to have a voting round equal to the consensus round. In the fields listed here, this is the only one that is mutable. All the rest are immutable, and are signed when the event is created.
- **See also:** witness, judge, voter, initial voter, vote collector, election, pending round, DGen
- **Concept:** [voting](concepts/voting.md), [rounds-and-witnesses](concepts/rounds-and-witnesses.md), [judges](concepts/judges.md)

---

## Events

### Witness event
- **Gloss:** an event whose voting round is greater than its self-parent's voting round (or which doesn't have a self parent). Only witnesses can be judges or voters.
- **See also:** judge, voter, election, votingRound, selfParent
- **Concept:** [rounds-and-witnesses](concepts/rounds-and-witnesses.md)

### Judge event
- **Gloss:** an event that wins the election to be made a judge.
- **Detail:** an event that wins the election to be made a judge. It must be a witness, and it will have tended to have been gossiped to most of the other nodes quickly (otherwise it would have lost the election). An event reaches consensus when it is an ancestor of all judges in a given round. The minimum round where that happens is its consensus round. It's a math theorem that every round is guaranteed to have at least one judge, and a math conjecture that every round is guaranteed to have judges created by a supermajority of nodes (>2/3 of weight).
- **See also:** witness, election, consensus event, consensusRound, voter, ancestor
- **Concept:** [judges](concepts/judges.md), [voting](concepts/voting.md)

### Consensus event
- **Gloss:** an event that has reached consensus
- **See also:** consensusRound, consensusTimestamp, consensusOrder, judge

### Ancient event
- **Gloss:** an event whose birth round is less than `(minJudgeBirthRound - numRoundsNonAncient)`.
- **Detail:** an event whose birth round is less than `(minJudgeBirthRound - numRoundsNonAncient)`. The `minJudgeBirthRound` is the minimum birth round of all the judges in round `pendingRound` - 1. This event should not be in the hashgraph, though it may still be in memory in order to give to other nodes that are behind this one. During gossip, a node will discard any event that is ancient for it. So no event should be sent to a node if that event will be ancient for that node.
- **See also:** ancient round, min non-ancient round, min judge birth round, numRoundsNonAncient, expired event, stale event, future event
- **Concept:** [event-lifecycle](concepts/event-lifecycle.md), [birth-round](concepts/birth-round.md)

### Future event
- **Gloss:** an event whose birth round is greater than the `pendingRound`.
- **Detail:** an event whose birth round is greater than the `pendingRound`. These are not added to the hashgraph while they are still future.
- **See also:** future round, pending round, birthRound
- **Concept:** [event-lifecycle](concepts/event-lifecycle.md), [birth-round](concepts/birth-round.md)

### Expired event
- **Gloss:** an event whose birth round is less than `(pendingRound - numRoundsNonAncient)`.
- **Detail:** an event whose birth round is less than `(pendingRound - numRoundsNonAncient)`. Expired events should be removed from memory. So if a node is so far behind that the events it needs are expired for its neighbors, then it will not be able to catch up by gossip alone, so it will have to do a reconnect to catch up.
- **See also:** ancient event, min non-expired round, pending round, numRoundsNonExpired, numRoundsNonAncient
- **Concept:** [event-lifecycle](concepts/event-lifecycle.md)
- **Note:** the source formula uses `numRoundsNonAncient`; if "expired" is intended to be governed by `numRoundsNonExpired` (per min non-expired round), this may be a third instance of the same source typo. Preserved verbatim pending review.

### Stale event
- **Gloss:** an event that became ancient before reaching consensus.
- **Detail:** an event that became ancient before reaching consensus. It is guaranteed to never reach consensus, since only non-ancient events are allowed to reach consensus. If a node is active and keeping up with the network, it is very unlikely that any event it creates can ever go stale. An event it creates will typically spread by gossip until it reaches most of the other nodes within one `broadcast period`, which is about half of a round. So the only way its newly-created event could go stale is if it is so far behind the other nodes that its current consensus round is almost `numRoundsNonAncient` rounds behind them at the moment it creates an event. At which point, it is on the verge of becoming "fallen behind" and having to do a reconnect.
- **See also:** ancient event, consensus event, numRoundsNonAncient
- **Concept:** [stale-events](concepts/stale-events.md)

### Orphan event
- **Gloss:** an event whose non-ancient parents have not all been received yet, so it cannot be put into the hashgraph.
- **Detail:** if an event is received before one of its non-ancient parents is received, then it cannot be put into the hashgraph. It is an orphan. Orphan events can be either discarded, or put into an orphan buffer. It can then leave the orphan buffer when each of its parents is either present (and not, itself, and orphan), or is ancient.
- **See also:** parent, ancient event, NGen

### Invalid event
- **Gloss:** an event that can be immediately discarded because it has an invalid signature or it cannot be parsed, or it has some other error that is immediately visible, independent of any other events.
- **Detail:** an event that can be immediately discarded because it has an invalid signature or it cannot be parsed, or it has some other error that is immediately visible, independent of any other events. (An event can be "bad" in other senses, without being invalid, such as if it is a branch, or if it claims a parent has a birth round that differs from that parent's true birth round).
- **See also:** branch, parent, birthRound

### Voter event
- **Gloss:** an event that is currently acting as a voter in an election. It can be either an initial voter, or a vote collector.
- **See also:** election, initial voter, vote collector, witness, votingRound
- **Concept:** [voting](concepts/voting.md)

### Initial voter event
- **Gloss:** a witness with a voting round equal to `1 + pendingRound`.
- **Detail:** a witness with a voting round equal to `1 + pendingRound`. For each node, it votes for the witness created by that node in round pendingRound, or votes NULL if it cannot see any witness by that node in that round. (That's ordinary seeing, not strongly seeing).
- **See also:** voter, vote collector, witness, votingRound, pending round, see, strongly see
- **Concept:** [voting](concepts/voting.md)

### Vote collector event
- **Gloss:** a witness with a voting round greater than `1 + pendingRound`.
- **Detail:** a witness with a voting round greater than `1 + pendingRound`. It collects votes from all witnesses that it can strongly see in the previous voting round. For each node, it sets its vote for witness in the pending round to be the majority (or plurality) of the votes it collected. In case of a tie, it picks the witness with the least signture lexicographically. For a given node, the event it creates in one round might have a different vote than the one it creates in the previous round. So this is like a node virtually voting in many rounds, repeatedly changing its vote to match the majority of its peers. For any particular election (about whether a particular event is a judge), if a vote collector collects a supermajority of votes that agree, then it `decides` that vote, and that election is over.
- **See also:** voter, initial voter, witness, election, judge, strongly see, votingRound, pending round
- **Concept:** [voting](concepts/voting.md)

### Election event
- **Gloss:** a witness event in the pending round, which is currently being voted on for judge.
- **Detail:** a witness event in the pending round, which is currently being voted on for judge. The election is guaranteed to eventually be `decided` (with probability one), at which point that witness will either be declared to be a judge or not. There is a theorem that as a hashgraph grows, once a single witness is known for round `1 + pendingRound`, any witness in the pending round that is not yet in the hashgraph will be guaranteed to lose its election for judge. So any further witnesses added to that round later will not have actual elections calculated. They will just be instantly decided to not be judges. And there is a theorem that eventually (with probability one), each of the existing witnesses in the pending round will eventually have its election decided, so it will eventually be declared to either be a judge or not. When all such witnesses have been decided, then the complete set of judges will have been decided, so the round reaches consensus at that moment.
- **See also:** witness, judge, voter, initial voter, vote collector, pending round, useD12
- **Concept:** [voting](concepts/voting.md), [judges](concepts/judges.md)

### Preconsensus event stream (PCES) event
- **Gloss:** an event that has not yet reached consensus, which is written to storage so that, if the node restarts (either intentionally, or as a result of a crash), the node can read these events back into memory.
- **Detail:** an event that has not yet reached consensus, which is written to storage so that, if the node restarts (either intentionally, or as a result of a crash), the node can read these events back into memory. This helps it avoid accidentally creating an event that is a branch of one it created before the restart. The alternative is to gossip for a while after a restart, before creating any new events. But that approach can still fail if it doesn't gossip for long enough. And it will fail if all the nodes crash at the same moment, due to a software bug.
- **See also:** Pre-Consensus Event Stream (PCES), consensus event, branch

---

## Event relationships

### Parent(x,y)
- **Gloss:** a parent of `x` is `y`. So `x` contains the hash of `y`. The parent is either a self-parent or an other-parent. An event can have at most one self-parent.
- **See also:** child, SelfParent(x,y), OtherParent(x,y), selfParent, otherParent
- **Concept:** [hashgraph-dag](concepts/hashgraph-dag.md)

### SelfParent(x,y)
- **Gloss:** the self-parent of `x` is `y`; `x` contains the hash of `y`, and both have the same creator.
- **Detail:** the self-parent of `x` is `y`. So `x` contains the hash of `y`. Both `x` and `y` must have the same creator. The difference of birth rounds for `x` and `y` is at most `numRoundsNonAncient`. If it would have been greater, then `x` should simply be created with no self-parent.
- **See also:** parent, OtherParent(x,y), selfParent, selfParentBirthRound, numRoundsNonAncient
- **Concept:** [hashgraph-dag](concepts/hashgraph-dag.md)

### OtherParent(x,y)
- **Gloss:** the other-parent of `x` is `y`; `x` contains the hash of `y`, and `x` and `y` have different creators.
- **Detail:** the other-parent of `x` is `y`. So `x` contains the hash of `y`. The events `x` and `y` must have different creators. The difference of birth rounds for `x` and `y` is at most `numRoundsNonAncient`. If it would have been greater, then `x` should simply be created with no other-parent. Or with a different other-parent where the difference is smaller. (This will be changed to `otherParents` plural, when multiple other parents are implemented).
- **See also:** parent, SelfParent(x,y), otherParent, otherParentBirthRound, numRoundsNonAncient, maxOtherParents
- **Concept:** [hashgraph-dag](concepts/hashgraph-dag.md)
- **Delta-map:** [hashgraph](delta-map/hashgraph.md)

### Child(x,y)
- **Gloss:** a child of `x` is `y`. This means that `x` is a parent of `y`.
- **See also:** parent, SelfChild(x,y), OtherChild(x,y)
- **Concept:** [hashgraph-dag](concepts/hashgraph-dag.md)

### SelfChild(x,y)
- **Gloss:** a self-child of `x` is `y`. This means that `x` is a self-parent of `y`.
- **See also:** child, SelfParent(x,y), OtherChild(x,y)

### OtherChild(x,y)
- **Gloss:** an other-child of `x` is `y`. This means that `x` is an other-parent of `y`.
- **See also:** child, OtherParent(x,y), SelfChild(x,y)

### Ancestor(x,y)
- **Gloss:** an ancestor of `x` is `y`. This means that `y` is either `x`, or a parent of `x`, or a parent of a parent of `x`, etc.
- **See also:** Descendant(x,y), SelfAncestor(x,y), parent
- **Concept:** [hashgraph-dag](concepts/hashgraph-dag.md)

### Descendant(x,y)
- **Gloss:** a descendent of `x` is `y`. This means that `x` is a ancestor of `y`.
- **See also:** Ancestor(x,y), SelfDescendant(x,y), child

### SelfAncestor(x,y)
- **Gloss:** a self-ancestor of `x` is `y`. This means that `y` is either `x`, or a self-parent of `x`, or a self-parent of a self-parent of `x`, etc.
- **See also:** Ancestor(x,y), SelfDescendant(x,y), SelfParent(x,y)

### SelfDescendant(x,y)
- **Gloss:** a self-descendant of `x` is `y`. This means that `x` is a self-ancestor of `y`.
- **See also:** Descendant(x,y), SelfAncestor(x,y), SelfChild(x,y)

### See(x,y)
- **Gloss:** `x` can see `y` if `y` is an ancestor of `x` — and when there is branching, only the branch of `y` that `x`'s chain became aware of first.
- **Detail:** `x` can see `y`. If there is no branching, then this is the same as `y` being an ancestor of `x`. If there is branching, then it means that among all of `x` and its self-ancestors, `y` became an ancestor of that chain earlier than any branch of `y` became an ancestor.  In other words, you "see" your ancestors, and if there's a branch, then you "see" the side of the branch that you became aware of first, and you never "see" the other side of that branch, even after it becomes your ancestor.
- **See also:** StronglySee(x,y), Ancestor(x,y), SelfAncestor(x,y), Branch(x,y)
- **Concept:** [strongly-seeing](concepts/strongly-seeing.md), [branching](concepts/branching.md)

### StronglySee(x,y)
- **Gloss:** `x` strongly sees `y` when `x` sees events created by a supermajority of nodes (`>2/3` by weight), each of which can see `y`.
- **Detail:** `x` can strongly see `y`. This means that `x` can see events created by a supermajority of nodes (`>2/3` by weight), each of which can see `y`. In other words, there are paths from `x` to `y`, following parent pointers, that pass through a supermajority of creators. Because of the definition of "see", there will always be paths through the creator of `x` and through the creator of `y`.
- **See also:** See(x,y), parent, roster
- **Concept:** [strongly-seeing](concepts/strongly-seeing.md)

### Branch(x,y)
- **Gloss:** `x` and `y` form a branch when they have the same creator, neither is a self-ancestor of the other, and their birth rounds differ by at most `numRoundsNonAncient`.
- **Detail:** `x` and `y` form a branch if all three of the following conditions hold. The definition of seeing ensures that any given event can see at most one of `x` and `y`, if they form a branch. And for an honest node, if it creates an event that sees one of `x` or `y`, all of its self-descendents will continue to see that same `x` or `y`, and none of them will ever see the other one. The 3 requirements for them to constitute a branch are:
  - both are created by the same creator
  - neither is a self-ancestor of the other
  - the difference of the birth rounds is at most `numRoundsNonAncient` (for the value of `numRoundsNonAncient` defined in the roster whose `rosterRound` equals the max of their birth rounds)
- **See also:** See(x,y), SelfAncestor(x,y), numRoundsNonAncient, roster, roster round, birthRound, invalid event
- **Concept:** [branching](concepts/branching.md)

---

## Address books

### Address book
- **Gloss:** the current info for all nodes, stored as a file in the on-ledger file system, and therefore also in state.
- **Detail:** the current info for all nodes, stored as a file in the on-ledger file system, and therefore also in state. This includes public keys, IP addresses, node ID, proxies, consensus weight, and other info.
- **See also:** roster, roster queue
- **Delta-map:** [hashgraph](delta-map/hashgraph.md)

### Roster
- **Gloss:** a subset of the address book, with just the information needed for consensus.
- **Detail:** a subset of the address book, with just the information needed for consensus. This includes the nodeID, public key for signing events, consensus weight, and some cryptographic info related to TSS and state proofs. Each roster is associated with an unsigned long `rosterRound`. The roster with a given `rosterRound` number will be used while calculating which events reach consensus in round number `rosterRound`. So it is used when `rosterRound = pendingRound`. That calculation is started after round `rosterRound - 1` reaches consensus. When round `r` reaches consensus, all its transactions are handled, which might modify the address book, and the final address book at the end of handling is used to construct roster number `r + 1 + numRoundsFutureRoster`. In that case, the value of the setting `numRoundsFutureRoster` is the one in the roster for round `r`. If transactions are designed to change `numRoundsFutureRoster`, they can instantly reduce it by any amount, or they can increase it by at most 1 per round.
- **See also:** address book, roster queue, roster round, pending round, numRoundsFutureRoster
- **Delta-map:** [hashgraph](delta-map/hashgraph.md)

### Roster queue
- **Gloss:** a queue of rosters for every round from round number `minNonAncientRound` through `maxRosterRound`, inclusive.
- **Detail:** a queue of rosters for every round from round number `minNonAncientRound` through `maxRosterRound`, inclusive.  When the `pendingRound` reaches consensus, then `pendingRound`, `minNonAncientRound` and `maxRosterRound` will all increment by 1. At that point, the oldest roster is removed from the queue. And ideally, the roster for the new `maxRosterRound` would be added immediately. But it will actually be added slightly later, when the round that just reached consensus has been processed (handled). So the queue will sometimes be missing one or more of the latest rosters. That is ok. The hashgraph should only contain events that have created rounds from `minNonAncientRound` through `pendingRound`, inclusive. When each of those events is received during gossip, it will have its signature checked according to the public key associated with its creator's nodeID in the roster whose `roster number` matches its birth round. So it is OK for processing of rounds to fall behind the consensus of rounds, by `numRoundsFutureRoster` rounds, without any bad effects. But if enough rosters are missing from the queue so that the `pending round` roster is missing, then consensus will freeze until round number `pendingRound - numRoundsFutureRoster - 1` has been processed.  There's no particular problem with consensus freezing in that case, because there are already several rounds that have reached consensus and are waiting to be processed, so there is no harm in waiting until the event processing is ready for another one.
- **See also:** roster, address book, roster round, min non-ancient round, max roster round, pending round, numRoundsFutureRoster, birthRound
- **Delta-map:** [hashgraph](delta-map/hashgraph.md)

---

## Misc terminology

### Pre-Consensus Event Stream (PCES)
- **Gloss:** a stream to a node's hard drive of all events it created and all events it added to its hashgraph.
- **Detail:** a stream to a node's hard drive of all events it created and all events it added to its hashgraph. It is guaranteed to flush this stream (so the events actually reach the hard drive) after each time it creates an event (before gossiping it out), and after each time it reaches consensus on a round (before sending it to Services to handle its transactions).
- **See also:** Preconsensus event stream (PCES) event, consensus event

### TipSet
- **Gloss:** an algorithm (and class) that chooses which events should be the other parent(s) when a new event is created.
- **Detail:** an algorithm and a class. When a new event is created, the tipset algorithm chooses which events should be its other parent(s). It is designed to only include `tips` (events with no descendents yet that have been sent to the tipset algorithm). Events are sent to the tipset algorithm after they have been validated and are no longer in the orphan buffer or future buffer. The tipset algorithm immediately forwards them to consensus to add to the hashgraph. Currently, only 1 event can be an other parent for a given event, so it chooses the one that will make the most progress toward consensus. Or it will delay creating an event (briefly) if there are no parents that would be helpful. When we start to allow multiple other parents, then it might be set to do something like include the 5 most helpful parents, plus at most 5 random parents.
- **See also:** OtherParent(x,y), other-parent, orphan event, future event, maxOtherParents, NGen
- **Delta-map:** [hashgraph](delta-map/hashgraph.md)

### Transaction timestamp
- **Gloss:** Once an event is given a consensus timestamp, then each transaction inside it is given a unique timestamp.
- **Detail:** Once an event is given a consensus timestamp, then each transaction inside it is given a unique timestamp. The first transaction has the same timestamp as the event, and then it increments by 1000 nanoseconds for each transaction after it. If a timestamp of T is assigned to the last transaction, then the next event in consensus order must have a consensus timestamp of at least T plus 1000 nanoseconds. If its timestamp is less than T+1000ns, then it is set to T+1000ns as its `consensus timestamp`. This guarantees that event timestamps are always increasing, and that there is always at least 1000 nanoseconds between adjacent transactions in consensus order.
- **See also:** transactions, consensusTimestamp, consensus event, round timestamp

---

## Parameters

### numRoundsNonAncient
- **Gloss:** number of rounds to be ancient
- **See also:** ancient round, ancient event, min non-ancient round

### numRoundsNonExpired
- **Gloss:** number of rounds to be expired
- **See also:** expired event, min non-expired round
- **Note:** the source v18 glossary listed this parameter as `numRoundsNonAncient` (a duplicate of the entry above); corrected to `numRoundsNonExpired` per agreed cleanup.

### numRoundsFutureRoster
- **Gloss:** number of future rounds desired in the roster queue
- **See also:** roster queue, max roster round, roster round
- **Delta-map:** [hashgraph](delta-map/hashgraph.md)

### useD12
- **Gloss:** should the modified d12 algorithm be used? (currently `FALSE`)
- **See also:** election, voter, vote collector
- **Concept:** [coin-rounds](concepts/coin-rounds.md)

### maxOtherParents
- **Gloss:** max number of other parents allowed (currently 1)
- **See also:** OtherParent(x,y), other-parent, otherParent, TipSet
- **Concept:** [hashgraph-dag](concepts/hashgraph-dag.md)
- **Delta-map:** [hashgraph](delta-map/hashgraph.md)
