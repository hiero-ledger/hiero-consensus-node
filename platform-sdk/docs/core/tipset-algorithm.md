# Introduction

The tipset algorithm is a strategy for creating events that provides several key properties:

- partition resiliency, provided by stopping the creation of events when a quorum is lost
- improved consensus latency, provided by choosing hashgraph topologies that cause consensus to quickly advance
- automatic event creation throttles that limit event creation rates faster than what helps consensus

# Terms And Primitive Operations

## Tipset

A tipset is similar to a [vector clock](https://en.wikipedia.org/wiki/Vector_clock). It is an array of integer values.

For a particular network, there will be one entry in a tipset for each node in the address book.
The value of each entry corresponds to an event sequence number. The index in the tipset corresponds to
each node's index in the address book.

```
Example

For an address book with 4 nodes: A, B, C, and D

[1, 3, 5, 2]
       ^
       |
       Node C has a sequence number of 5 in this tipset
```

## Tipset of an Event

Each event in the hashgraph has a tipset.

To calculate the tipset of an event, iterate over all ancestors of the event (in this context,
an event is considered to be an ancestor to itself). From all of these ancestors, sort
by event creator. For each event creator, choose the event with the highest sequence number. The entry for that node
in the tipset is equal to the sequence number of that event. If no event was ever seen for that creator, the number will
be set to -1.

```
Example

Time starts at the bottom and moves forward as you go up the graph. Numbers for each event are sequence numbers.

 |      (7)      |       |
 |      /|       |       |
 |     / |       |       |
 |    /  |       |       |
 |   /   |       |       |
 |  /    |       |       |
 | /     |       |       |
(6)      |       |       |
 |\      |       |       |
 | \     |       |       |
 |  \    |       |       |
 |   \   |       |       |
 |    \  |       |       |
 |     \ |       |       |
 |      (5)      |      (4)
 |       |\      |      /|
 |       | \     |     / |
 |       |  \    |    /  |
 |       |   \   |   /   |
 |       |    \  |  /    |
 |       |     \ | /     |
(1)     (2)     (3)      |
 |       |       |       |
 |       |       |       |
 A       B       C       D

 The tipset of B's most recent event is [6, 7, 3, -1]

```

## Merging Tipsets

Although calculating an event tipset by iterating the graph is conceptually simple, in practice it's way too slow.
This section describes a faster algorithm.

Merging two tipsets is defined as taking two or more tipsets, for each event creator selecting the maximum sequence
number
across all tipsets being merged, and constructing the resulting tipset using those sequence numbers. Or to put it
another
way, just take the maximum for each element in the list.

```
Example

[1, 3, 5, 2] merged with [7, 3, 2, 11] == [7, 3, 5, 11]
```

# Tipset Advancement Score

A tipset advancement score between two tipsets `X` and `Y` is defined as the number of entries in tipset `Y` that are
strictly greater than the corresponding entry in tipset `X`.

```
Example

X = [1, 3, 5, 2]
Y = [7, 3, 4, 11]

For computing the advancement score between X and Y, look at each pair of sequence numbers:

1 vs 7: 7 > 1, so this is an advancement
3 vs 3: 3 == 3, so this is not an advancement
5 vs 4: 4 < 5, so this is not an advancement
2 vs 11: 11 > 2, so this is an advancement

The total advancement count from X to Y is therefore 2. Note that the amount an entry advances is not important,
only if it increased or did not increase.
```

# Weighted Tipset Advancement Score

A weighted tipset advancement score is very similar to a tipset advancement score. But instead of adding 1 for
each entry that has a greater value, add the amount of consensus weight held by that node.

```
Example

Suppose an address book has the following consensus weights: A = 5, B = 9, C = 11, D = 2

Find the weighed advancement score between X and Y.

X = [1, 3, 5, 2]
Y = [7, 3, 4, 11]

The entry for node `A` advances, add 5. The entry for `D` advances, add 2. Total weighted advancement score is 7 (5+2).
```

# Partial Weighted Tipset Advancement Score

This is similar to the weighed advancement score with one minor tweak. A partial weighted advancement score
is always calculated from the perspective of a particular node. When computing a partial advancement score,
ignore the advancement provided by the self sequence number.

```
Example

Suppose an address book has the following consensus weights: A = 5, B = 9, C = 11, D = 2

Find the partial weighted advancement score between X and Y from node A's perspective.

X = [1, 3, 5, 2]
Y = [7, 3, 4, 11]

The entry for node A advances, but we ignore it because A is ourselves.
The entry for D advances, add 2. Total weighted advancement score is 2.

----

Now, for the same tipsets, consider from B's perspective. A and D advance, yielding
a total weighted advancement score of 7 (5+2).
```

# Advancement Score Improvement Relative to the Snapshot

For each self event, we compute a special partial weighted advancement score. This score is taken relative to a
special tipset called the "snapshot". We then compare the advancement weight of this new event (relative to the
snapshot) to the advancement weight calculated for the previous event (also relative to the snapshot).
The difference between those two advancement weights, i.e. the improvement, is required to be strictly positive.
Nodes are not permitted to create events unless each new event improves on the advancement weight compared to
the previous event created.

The snapshot tipset is defined as follows.

The snapshot starts out empty, i.e. `[-1, -1, -1, ..., -1]` at genesis (in the current code, sequence numbers start at
1).
Periodically, the snapshot is updated to a more recent tipset.

Each time a node creates a new event, compare that new event's tipset to the snapshot tipset and find the partial
weighted advancement score of that new event.

There is a special threshold, called the snapshot threshold, that is used when deciding when the snapshot gets updated.
Whenever the total weighted advancement of a new event, relative to the snapshot, exceeds this threshold, the
snapshot is updated to equal the tipset of the new event.

The snapshot threshold for each node is slightly different, unless nodes have the same consensus weight.
To calculate the threshold, find the minimum weight required to have >2/3 of the total weight,
and then subtract the node's weight.

```
Example of computing snapshot advancement threshold

Suppose an address book has the following consensus weights: A = 5, B = 9, C = 11, D = 2. Compute the snapshot
advancement threshold for node A.

Total consensus weight is 27 (5+9+11+2). The amount of stake required to have >2/3 of the total weight is 19.
Subtract A's weight, and A's snapshot advancement threshold is 14 (19-5).
```

When a new event has a snapshot improvement score that is greater than or equal to the snapshot advancement threshold,
then the snapshot is updated to the tipset of that new event.

# Limiting Event Creation by Snapshot Improvement Score

Nodes are not permitted to create a new event unless one of the following two conditions are true:

1) this is the first event a node has ever created (i.e. this is genesis), or
2) the snapshot improvement score exceeds the snapshot improvement score of the previous event, or
3) the snapshot improvement score is greater than zero and this is the first event created since
we last updated the snapshot

By following this rule, this ensures that a given node stops creating new events in a finite amount of time if that
node no longer is in communication with a quorum of its peers.

```
Example

Address book weights: A = 5, B = 9, C = 11, D = 2
Consider from A's perspective. A's snapshot advancement threshold is 14.

Initially, the snapshot is [-1, -1, -1, -1].

A creates a genesis event with tipset [1, -1, -1, -1] (A's first event has sequence number 1 in such case, as it
hasn't seen any other events yet).
The snapshot improvement score is 0, which is ok since this is a genesis event.
(It will never again be ok to have an advancement score of 0.)

Next, A creates an event with self parent [1, -1, -1, -1] and other parent [1, 2, -1, -1]. The other parent has
a sequence number of 2. (Note: use of this particular tipset for the other parent is arbitrary
and was just chosen for this example.)

The tipset of the new event is [1, 2, -1, -1]. (Note that the sequence number for A is still 1, as sequence numbers
are assigned only after self event has gone through entire intake pipeline).
The partial weighted snapshot advancement score is 14, because we add self weight of 5 in any case to node B 9.

Next, A creates an event with self parent [3, 2, -1, -1] and other parent [1, 2, 5, 7] with a sequence number 5. The tipset
of the new event is [3, 2, 5, 7]. The partial weighted snapshot advancement score is 27, which exceeds the threshold
of 14. Therefore, the snapshot is updated to [3, 2, 5, 7].

```

# Optimizing Tipset Score

When it comes time to create a new event, if there are multiple other parents to choose from, select the other parent
that results in the greatest tipset score improvement.

# Selfishness Scores

Informally, selfishness is when the network decides (for whatever reason) not to use an event creator's events as other
parents very often. The node being ignored will then find that it is difficult or impossible for its events
to reach consensus.

A selfishness score is a numeric value that increases the more a node is getting ignored. The more time that passes
without putting a node's event into the ancestry of recent events, the higher the selfishness score is against that
node.

To compute the selfishness score against a node `X`, look at recent snapshot tipsets. Starting with the current
snapshot and going backwards towards older snapshots, count the number of snapshots that need to be iterated
over before an advancement in `X`'s sequence number is observed. That count is the selfishness score against `X`.

In order to prevent nodes from being ignored too badly, it is important to periodically choose an event from an
ignored node as an other parent, even if that choice of other parent does not improve the snapshot advancement
score much. The exact algorithm for this is a bit of a heuristic, and needs to be tuned on a variety of factors.
Failure to occasionally select an ignored node's events may cause some nodes to be unable to create events that
reach consensus.
