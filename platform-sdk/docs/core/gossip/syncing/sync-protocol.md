# Sync Protocol Design Document

This document describes a proposal for how the sync protocol should work. The current code (2021-10-01) has a large
number of differences.

### Protocol

The following is the complete sync protocol:

```
//////////////// PHASE 1 ////////////////

Make an immutable snapshot of the current graph, and use it for everything below

tipList = all tips            // all of my tips. A "tip" is an event with no self-child
knownSet = empty set          // events that I know they already have
sendList = empt list          // events to be sent

do this
   send maxRoundGen, minGenNonAncient, minGenNonExpired, tipList
in parallel with this
   receive otherMaxRoundGen, otherMinGenNonAncient, otherMinGenNonExpired, otherTipHashList

if otherMaxRoundGen < minGenNonExpired
    abort the sync, and return
if maxRoundGen < otherMinGenNonExpired
    record that they consider me to have fallen behind
    abort the sync, and return

for each x in otherTipHashList
    if x is the hash of an event y in the graph
        add y to knownSet

//////////////// PHASE 2 ////////////////

do this
    for each x in otherTipHashList (in the order they were received)
        send the boolean (x is the hash of an event in the graph)
in parallel with this
    for each y in tipList (in the order they were sent)
        receive boolean b
        if b
            add y to knownSet

//////////////// PHASE 3 ////////////////

// add to knownSet all the ancestors of each known event
todoStack = stack containing all elements in knownSet (in an arbitrary order)
while todoStack is not empty
    pop x from todoStack
    push each parent of x onto todoStack (in an arbitrary order)
    if (x.generation >= otherMinGenNonAncient) AND (x not in knownSet)
        add x to knownSet

// tips might have changed since the beginning of the sync, get the latest
tipList = get latest tips
// set sendList to all ancestors of tips that are not known
todoStack = stack containing all elements in tipList (in an arbitrary order)
while todoStack is not empty
    pop x from todoStack
    push each parent of x onto todoStack (in an arbitrary order)
    if (x.generation >= otherMinGenNonAncient) AND (x not in knownSet)
        add x to knownSet //prune duplicate searches by acting as if this is known
        add x to sendList

sort sendList ascending by generation   // this will be in topological order
do this
    send sendList
in parallel with this
    receive otherSendList

add all of otherSendList to the queue of events to verify and add to the hashgraph
```

In this, `maxRoundGen` is the maximum round generation (the min judge generation in the latest consensus round)
, `minGenNonAncient` is the minimum generation of all the judges that are not ancient, and `minGenNonExpired` is the
minimum generation of all the judges that are not expired. All of these variables are clipped to always be 0 or greater.
If there are no events known, then all of them are set to 0. And similarly if there are no judges yet for a round, or if
an event has a generation of 0. In all those cases, the variables are set to 0. The
line `record that they consider me to have fallen behind` means to consider this as one vote that I have fallen behind.
It is described elsewhere how those votes are collected, and how, when a sufficient number are found, they trigger a
reconnect.

The immutable snapshot ensures that no events expire and disappear during the sync. It also ensures that
`maxRoundGen`, `minGenNonAncient`, and `minGenNonExpired` do not change during the sync. It's actually ok if it changes
during the sync by having additional events added, which can affect the booleans that are sent. But no events can be
removed, and the 3 generation variables must not change.

### Example

Suppose there is a complete hashgraph that looks like this, where events 1-6 and 18 are the tips (events with no
self-child):

<img src="sync-protocol-fig1.png" width="40%" />

Two nodes, Alice and Bob, each have a subset of that graph:

<img src="sync-protocol-fig2.png" width="75%" />

The two nodes will now sync. The following shows the above pseudocode broken into sections of pseudocode, each of which
is followed by an image of the state after completing that section.

```

//////////////// PHASE 1 ////////////////

Make an immutable snapshot of the current graph, and use it for everything below

tipList = all tips            // all of my tips. A "tip" is an event with no self-child
knownSet = empty set          // events that I know they already have
sendList = empt list          // events to be sent

```

<img src="sync-protocol-fig3.png" width="75%" />

```

do this
   send maxRoundGen, minGenNonAncient, minGenNonExpired, tipList
in parallel with this
   received otherMaxRoundGen, otherMinGenNonAncient, otherMinGenNonExpired, otherTipHashList

if otherMaxRoundGen < minGenNonExpired
    abort the sync, and return
if maxRoundGen < otherMinGenNonExpired
    record that they consider me to have fallen behind
    abort the sync, and return

for each x in otherTipHashList
    if x is the hash of an event y in the graph
        add y to knownSet

```

<img src="sync-protocol-fig4.png" width="75%" />

```

//////////////// PHASE 2 ////////////////

do this
    for each x in otherTipHashList (in the order they were received)
        send the boolean (x is the hash of an event in the graph)
in parallel with this
    for each y in tipList (in the order they were sent)
        receive boolean b
        if b
            add y to knownSet

```

<img src="sync-protocol-fig5.png" width="75%" />

```

//////////////// PHASE 3 ////////////////

// add to knownSet all the ancestors of each known event
todoStack = stack containing all elements in knownSet (in an arbitrary order)
while todoStack is not empty
    pop x from todoStack
    push each parent of x onto todoStack (in an arbitrary order)
    if (x.generation >= otherMinGenNonAncient) AND (x not in knownSet)
        add x to knownSet

```

<img src="sync-protocol-fig6.png" width="75%" />

```

// tips might have changed since the beginning of the sync, get the latest
tipList = get latest tips
// set sendList to all ancestors of tips that are not known
todoStack = stack containing all elements in tipList (in an arbitrary order)
while todoStack is not empty
    pop x from todoStack
    push each parent of x onto todoStack (in an arbitrary order)
    if (x.generation >= otherMinGenNonAncient) AND (x not in knownSet)
        add x to knownSet //prune duplicate searches by acting as if this is known
        add x to sendList

```

<img src="sync-protocol-fig7.png" width="75%" />

```

sort sendList ascending by generation   // this will be in topological order
do this
    send sendList
in parallel with this
    receive otherSendList

add all of otherSendList to the queue of events to verify and add to the hashgraph

```

<img src="sync-protocol-fig8.png" width="75%" />

### Tip update note

In phase 3, before creating the `sendList`, we update the tips in case they have changed since the last time we checked.
This is done in order to improve the `C2C` value (the amount of time it passes between event creation and that event
reaching consensus). This is especially important in high latency networks.

### Tip definition note

The definition of a tip must be an event with no self child. If a tip were defined as an event with no children (no self
or other child) and there are very few tips, and the tips for Alice and Bob were unknown to the other, each node would
send all non-ancient events in the hashgraph. Another example of when Alice and Bob would send all non-ancient events to
each other is in the case of a split fork graph as shown below.

<img src="sync-protocol-note.png" width="75%" />

### Tips increase explanation

It is possible that we have more tips than nodes even if there is no fork. Here is how:
Suppose we have an event 3 who has a self parent 2 who has a self parent 1. Once we receive 1, it becomes a tip. 2 is
created, but not gossiped out, because the creator is under load. Once it starts syncing again, 2 is ancient for the
other nodes, so they never receive it. They don't need to, because its a stale event. Now we receive 3 and it becomes a
tip, but 1 is still also a tip, because it has no descendants that we know of. So we end up with more tips then nodes.

### Filtering likely duplicate events

Concurrent synchronizations may result in multiple nodes transmitting the same event, leading to significant event
duplication. Although receivers filter out redundant events, this process consumes unnecessary bandwidth and processing
resources.

To mitigate this, a delay is introduced for sending certain events until they reach a designated age. In its most basic
configuration, self-events and their ancestors are transmitted immediately. In contrast, "other" events — those created
by different nodes, which are not ancestors of self events — are delayed for several seconds. This delay allows the
original creators to synchronize these events first, substantially reducing duplication. While not exhaustive — as
self-events eventually incorporate ancestors from various creators — this method remains more efficient than no
filtering and involves minimal overhead.

The introduction of primitive broadcast modifies this dynamic. Broadcast preemptively transmits all self-events,
significantly reducing the necessity of synchronizing them via the sync protocol. Synchronization remains essential
primarily after network disruptions. With broadcast enabled, self-events can be delayed significantly during
sync, as they will come over broadcast. However, node may not yet have received the ancestors from other creators,
as there is no guarantee they will be broadcast in time.
Missing ancestors would cause self-events to remain in the orphan buffer.

To address this, three levels of delays are implemented when broadcast is enabled. These levels categorize events as:
self-events, ancestors of self-events, and "other" events (non-ancestors of self-events). Ancestors of self-events are
assigned the shortest delay, while self-events and "other" events have longer delays. Timings are configurable based on
network conditions; a typical WAN configuration might include:

- self-events: 1 second delay
- ancestors of self-events: 250ms delay
- other events: 3 seconds delay

For such settings, if network latency (pings) remains below 250ms, the broadcast protocol can effectively replace the
sync protocol. This configuration maintains a low duplication rate while ensuring required ancestors are sent
aggressively enough to accommodate slower nodes. Furthermore, it serves as a robust fallback for significant network
disconnections. Broadcast remains disabled until the initial synchronization is complete, ensuring that newly connected
nodes receive all necessary data without delay to expedite recovery.

In diagram below, you can see example categorization of events. In such a case, magenta events would be sent most
aggressively, while green events would be sent less aggressively, and red events would be sent least aggressively.

<img src="ancestor-filtering.drawio.png" />
