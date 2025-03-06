# Hashgraph Glossary

<a name="top"/>

- [Rounds](#rounds)
- [Fields of an event](#fields-of-an-event)
- [Events](#events)
- [Event relationships](#event-relationships)
- [Generations](#generations)
- [Address Books](#addressbooks)
- [Parameters](#parameters)

<a name="rounds"/>

## Rounds

- __Pending__ round - the round number of the earliest round that hasn't yet reached consensus. This is the round whose
  judges are currently being determined. Those consensus calculations use the roster whose roster round is the current
  `pendingRound`. Any new event is created containing a `birthRound` field equal to the current `pendingRound`. All
  events currently in the hashgraph should have birth rounds in the range from `pendingRound - roundsAncient` to
  `pendingRound`, inclusive.
- __Consensus__ round - the round number for the latest round that has reached consensus. <br>
    - `consensusRound = pendingRound - 1`
- __First non-ancient__ round - the oldest round that is not ancient<br>
    - `firstNonAncientRound = pendingRound - roundsAncient`
- __First non-expired__ round - the oldest round that is not expired<br>
    - `firstNonExpiredRound = pendingRound - roundsExpired`
- __Last roster__ round - Last future round for which there should be a roster in the queue<br>
    - `lastRosterRound = pendingRound + roundsRoster`
- __Roster__ round - Any given roster (subset of an address book) is associated with a long, which is its roster round
  number. The `nodeID`s and consensus weights in a roster should be used when that roster round number equals the
  pending round. A queue of rosters must be stored in state, one for each round number from `firstNonAncientRound` to
  `lastRosterRound`, inclusive. Every time a round reaches consensus, the oldest roster can be removed. Any time the
  transactions in a round are handled, a new roster is added for the future. An event is only allowed to be in the
  hashgraph when its birth round is the roster round for some roster in the queue. And it must have been created by a
  node that is listed in that roster. Of course, ancient, non-expired events may still be in memory, to gossip to nodes
  that are behind this node, but they don't count as being in this node's hashgraph. Events with birth rounds too far in
  the future should not be accepted during gossip. And a node should not send such events to another node, if it knows
  that node will just discard them for that reason.

  [top](#top)

<a name="fields-of-an-event"/>

## Fields of an event

- __birthRound__ - set to the creator's pending round at the moment of creation. Is signed and immutable.
- __votingRound__ - the round for this event, which determines whether it is a witness. It is a witness if its voting
  round is different from its self-parent's voting round (or it has no self-parent). If it is a witness, then it might
  be eligible for election as a judge (if it's in the pending round), or it might be an initial voter for judge (if it's
  in the pending round plus `d`), or it might be a voter and vote collector (if it's later than the pending round plus
  `d`). Every time the processing of a new round starts, the voting round is recalculated for all events. It can be
  different than it was in the past because non-ancestors of the latest judges are defined to have a voting round of
  negative infinity (represented as a 0 in memory), and that can cause all their descendents to change their voting
  rounds. If a judge in the consensus round is not a descendant of any other judge in the consensus round, then it is
  guaranteed to have a voting round equal to the consensus round. In the fields listed here, this is the only one that
  is mutable. All the rest are immutable, and are signed when the event is created.
- __consensusRound__ - the first round in which this event was an ancestor of all judges.
- __consensusTimestamp__ - the median timestamp of when it first reached each of the nodes that created judges in its
  consensus round. This is adjusted by adding nanoseconds to ensure that in consensus order, each transaction is at
  least 1000 nanoseconds after the previous one. (That ensures they are unique and monotonically increasing, and there
  are big enough gaps so that synthetic transactions inserted between them can have timestamps that are unique).
- __consensusOrder__ - an long `N` indicating this is the `N`th event in all of history, according to the calculated
  consensus order.
- __selfParent__ - the hash of the self parent (or null if there is none, or the self-parent's birth round is ancient)
- __selfParentCreator__ - the node that created the self-parent event. If this is incorrect, then it cannot be added to
  the hashgraph until the self-parent becomes ancient.
- __selfParentBirthRound__ - the claimed birth round of the self-parent. If this is incorrect, then it cannot be added
  to the hashgraph until the self-parent becomes ancient.
- __otherParent__ - the hash of the other-parent (or null if there is none, or the other-parent's birth round is
  ancient).
- __otherParentCreator__ - the node that created the other-parent event
- __otherParentBirthRound__ - the claimed birth round of the other-parent. If this is incorrect, then it cannot be added
  to the hashgraph until the other-parent becomes ancient.

  [top](#top)

<a name="events"/>

## Events

- __Judge__ event - an event that wins the election to be made a judge. It must be a witness, and it will have tended to
  have been gossiped to most of the other nodes quickly (otherwise it would have lost the election). An event reaches
  consensus when it is an ancestor of all judges in a given round. The first round where that happens is its consensus
  round. It's a math theorem that every round is guaranteed to have at least one judge, and a math conjecture that every
  round is guaranteed to have judges created by a supermajority of nodes (>2/3 of weight).
- __Consensus__ event - an event that has reached consensus
- __Ancient__ event - an event whose birth round is less than `(pendingRound - roundsAncient)`. This event should not be
  in the hashgraph, though it may still be in memory in order to give to other nodes that are behind this one. During
  gossip, a node will discard any event that is ancient for it. So no event should be sent to a node if that event will
  be ancient for that node.
- __Expired__ event - an event whose birth round is less than `(pendingRound - roundsExpired)`. Expired events should be
  removed from memory. So if a node is so far behind that the events it needs are expired for its neighbors, then it
  will not be able to catch up by gossip alone, so it will have to do a reconnect to catch up.
- __Witness__ event - an event whose voting round is greater than its self-parent's voting round (or which doesn't have
  a self parent). Only witnesses can be judges or voters.
- __Stale__ event - an event that became ancient before reaching consensus. It is guaranteed to never reach consensus,
  since only non-ancient events are allowed to reach consensus. If a node is active and keeping up with the network, it
  is very unlikely that any event it creates can ever go stale. An event it creates will typically spread by gossip
  until it reaches most of the other nodes within one `broadcast period`, which is about half of a round. So the only
  way its newly-created event could go stale is if it is so far behind the other nodes that its current consensus round
  is almost `roundsAncient` rounds behind them at the moment it creates an event. At which point, it is on the verge of
  becoming "fallen behind" and having to do a reconnect.
- __Orphan__ event - if an event is received before one of its non-ancient parents is received, then it cannot be put
  into the hashgraph. It is an orphan. Orphan events can be either discarded, or put into an orphan buffer. It can then
  leave the orphan buffer when each of its parents is either present (and not, itself, and orphan), or is ancient.
- __Invalid__ event - an event that can be immediately discarded because it has an invalid signature or it cannot be
  parsed, or it has some other error that is immediately visible, independent of any other events. (An event can be "
  bad" in other senses, without being invalid, such as if it is a branch, or if it claims a parent has a birth round
  that differs from that parent's true birth round).
- __Voter__ event - an event that is currently acting as a voter in an election. It can be either an initial voter, or a
  vote collector.
- __Initial voter__ event - a witness with a voting round equal to `1 + pendingRound`. For each node, it votes for the
  witness created by that node in round pendingRound, or votes NULL if it cannot see any witness by that node in that
  round. (That's ordinary seeing, not strongly seeing).
- __Vote collector__ event - a witness with a voting round greater than `1 + pendingRound`. It collects votes from all
  witnesses that it can strongly see in the previous voting round. For each node, it sets its vote for witness in the
  pending round to be the majority (or plurality) of the votes it collected. In case of tie, it picks the witness with
  the least signture lexicographically. For a given node, the event it creates in one round might have a different vote
  than the one it creates in the previous round. So this is like the a node virtually voting in many rounds, repeatedly
  changing its vote to match the majority of its peers. For any particular election (about whether a particular event is
  a judge), if a vote collector collects a supermajority of votes that agree, then it `decides` that vote, and that
  election is over.
- __Election__ event - a witness event in the pending round, which is currently being voted on for judge. The election
  is guaranteed to eventually be `decided` (with probability one), at which point that witness will either be declared
  to be a judge or not. There is a theorem that as a hashgraph grows, once a single witness is known for round
  `1 + pendingRound`, any witness in the pending round that is not yet in the hashgraph will be guaranteed to lose its
  election for judge. So any further witnesses added to that round later will not have actual elections calculated. They
  will just be instantly decided to not be judges. And there is a theorem that eventually (with probability one), each
  of the existing witnesses in the pending round will eventually have its election decided, so it will eventually be
  declared to either be a judge or not. When all such witnesses have been decided, then the complete set of judges will
  have been decided, so the round reaches consensus at that moment.
- __Preconsensus event stream__ event - an event that has not yet reached consensus, which is written to storage so
  that, if the node restarts (either intentionally, or as a result of a crash), the node can read these events back into
  memory. This helps it avoid accidentally creating an event that is a branch of one it created before the restart. The
  alternative is to gossip for a while after a restart, before creating any new events. But that approach can still fail
  if it doesn't gossip for long enough. And it will fail if all the nodes crash at the same moment, due to a software
  bug.

  [top](#top)

<a name="event-relationships"/>

## Event relationships

- __Parent__`(x,y)` - a parent of `x` is `y`. So `x` contains the hash of `y`. The parent is either a self-parent or an
  other-parent. An event can have at most one self-parent.
- __Self-parent__`(x,y)` - the self-parent of `x` is `y`. So `x` contains the hash of `y`. Both `x` and `y` must have
  the same creator. The difference of birth rounds for `x` and `y` is at most `roundsAncient`. If it would have been
  greater, then `x` should simply be created with no self-parent.
- __Other-parent__`(x,y)` - the other-parent of `x` is `y`. So `x` contains the hash of `y`. The events `x` and `y` must
  have different creators. The difference of birth rounds for `x` and `y` is at most `roundsAncient`. If it would have
  been greater, then `x` should simply be created with no other-parent. Or with a different other-parent where the
  difference is smaller.
- __Child__`(x,y)` - a child of `x` is `y`. This means that `x` is a parent of `y`.
- __Self-child__`(x,y)` - a self-child of `x` is `y`. This means that `x` is a self-parent of `y`.
- __Other-child__`(x,y)` - an other-child of `x` is `y`. This means that `x` is an other-parent of `y`.
- __Ancestor__`(x,y)` - an ancestor of `x` is `y`. This means that `y` is either `x`, or a parent of `x`, or a parent of
  a parent of `x`, etc.
- __Descendant__`(x,y)` - a descendent of `x` is `y`. This means that `x` is a ancestor of `y`.
- __Self-ancestor__`(x,y)` - a self-ancestor of `x` is `y`. This means that `y` is either `x`, or a self-parent of `x`,
  or a self-parent of a self-parent of `x`, etc.
- __Self-descendent__`(x,y)` - a self-descendant of `x` is `y`. This means that `x` is a self-ancestor of `y`.
- __See__`(x,y)` - `x` can see `y`. If there is no branching, then this is the same as `y` being an ancestor of `x`. If
  there is branching, then it means that among all of `x` and its self-ancestors, `y` became an ancestor of that chain
  earlier than any branch of `y` became an ancestor. In other words, you "see" your ancestors, and if there's a branch,
  then you "see" the side of the branch that you became aware of first, and you never "see" the other side of that
  branch, even after it becomes your ancestor.
- __Strongly see__ (x,y) - `x` can strongly see `y`. This means that `x` can see events created by a supermajority of
  nodes (`>2/3` by weight), each of which can see `y`. In other words, there are paths from `x` to `y`, following parent
  pointers, that pass through a supermajority of creators. Because of the definition of "see", there will always be
  paths through the creator of `x` and through the creator of `y`.
- __Branch__(x,y) - `x` and `y` form a branch if all three of the following conditions hold. The definition of seeing
  ensures that any given event can see at most one of `x` and `y`, if they form a branch. And for an honest node, if it
  creates an event that sees one of `x` or `y`, all of its self-descendents will continue to see that same `x` or `y`,
  and none of them will ever see the other one. The 3 requirements for them to constitute a branch are:
    - both are created by the same creator
    - neither is a self-ancestor of the other
    - the difference of the birth rounds is at most `roundsAncient` (for the value of `roundsAncient` defined in the
      roster whose roster round equals the max of their birth rounds)

  [top](#top)

<a name="generations"/>

## Generations

- __Local__ generation - an event is assigned this immutable number when it is added to the hashgraph. It is 1 plus the
  max of all the local generations of its non-ancient parents. If it has no non-ancient parents, then it has a local
  generation of 1. For a given event, this number can be different on different nodes. It can even be different on a
  single node, before and after a restart or reconnect. The purpose of this number is to make it easy to sort a set of
  events into topological order. (A topological order is any ordering of the events such that an event's parents always
  precede it in the order).
- __Consensus__ generation - an event is assigned this immutable number when it reaches consensus. It is 1 plus the max
  of all the consensus generations of its eligible parents, where where an event is eligible if it is not ancient and
  has not reached consensus in a previous round. If an event has no eligible parents, then its consensus generation is
  1. This is deterministic, so an event is guaranteed to have the same consensus generation on all nodes. This is used
  while finding the consensus order, since one step in the process is to break ties by consensus generation, thus
  ensuring the final order is a topological order. This is easily calculated during the calculation of which events have
  reached consensus in a given round. The latter is done by doing a depth-first search from each judge. It is easy to
  add a line of code to this search to do the calculation of consensus generation at the same time, with no additional
  searching at all.

  [top](#top)

<a name="addressbooks"/>

## Address Books

- __Address book__ - the current info for all nodes, stored as a file in the Hedera file system, and therefore also in
  state. This includes public keys, IP addresses, node ID, proxies, consensus weight, and other info.
- __Roster__ - a subset of the address book, with just the information needed for consensus. This includes the nodeID,
  public key for signing events, consensus weight, number of consensus shares, and some cryptographic info related to
  TSS and state proofs. Each roster is associated with an unsigned long `roster round`. The roster with a given
  `roster round` number will be used while calculating which events reach consensus in round number `rosterRound`. So it
  is used when `rosterRound = pendingRound`. That calculation is started after round `rosterRound - 1` reaches
  consensus. When round `r` reaches consensus, all its transactions are handled, which might modify the address book,
  and the final address book at the end of handling is used to construct roster number `r + 1 + roundsRoster`. In that
  case, the value of the setting `roundsRoster` is the one in the roster for round `r`. If transactions are designed to
  change `roundsRoster`, they can instantly reduce it by any amount, or they can increase it by at most 1 per round.
- __Roster queue__ - a queue of rosters for every round from round number `firstNonAncientRound` through
  `lastRosterRound`, inclusive. When the `pendingRound` reaches consensus, then `pendingRound`, `firstNonAncientRound`
  and `lastRosterRound` will all increment by 1. At that point, the oldest roster is removed from the queue. And
  ideally, the roster for the new `lastRosterRound` would be added immediately. But it will actually be added slightly
  later, when the round that just reached consensus has been processed (handled). So the queue will sometimes be missing
  one or more of the last rosters. That is ok. The hashgraph should only contain events that have created rounds from
  `firstNonAncientRound` through `pendingRound`, inclusive. When each of those events is received during gossip, it will
  have its signature checked according to the public key associated with its creator's nodeID in the roster whose
  `roster number` matches its birth round. So it is OK for processing of rounds to fall behind the consensus of rounds,
  by `roundsRoster` rounds, without any bad effects. But if enough rosters are missing from the queue so that the
  `pending round` roster is missing, then consensus will freeze until round number `pendingRound - roundsRoster - 1` has
  been processed. There's no particular problem with consensus freezing in that case, because there are already several
  rounds that have reached consensus and are waiting to be processed, so there is no harm in waiting until the event
  processing is ready for another one.

  [top](#top)

<a name="parameters"/>

## Parameters

- __roundsAncient__ - number of rounds to be ancient
- __roundsExpired__ - number of rounds to be expired
- __roundsRoster__ - number of future rounds desired in the roster queue
- __d__ = Number of rounds from witness being voted on to witness that is the initial vote (d=1 in current code)
- __maxOtherParents__ - max number of other parents allowed (currently 1)
- __d12__ - a boolean: should elections be redone with `d`=2 if `d`=1 didn’t give a supermajority of judges? (currently
  `FALSE`)

  [top](#top)
