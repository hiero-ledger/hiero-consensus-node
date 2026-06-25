---
type: concept
title: Event lifecycle
last_reviewed: 2026-05-22
---

# Event lifecycle

## Definition

The event lifecycle is the sequence of states an admitted event
passes through as time advances:

- **Admitted.** The event is in the hashgraph DAG, still affecting
  and being affected by ongoing rounds.
- **Ancient.** The event is past the *ancient threshold*. It no
  longer affects ongoing consensus computation, but it may still be
  retained by other modules — in particular by gossip, which uses it
  to help peers catch up.
- **Expired.** The event is past the *expired threshold*. It is
  discarded entirely.

## Mechanics

```
[non-ancient] --(ancient threshold)--> [ancient, non-expired] --(expired threshold)--> [expired]
```

Both thresholds are birth-round values carried on `EventWindow`. The
expired threshold is more permissive (further in the past) than the
ancient threshold, so an event becomes ancient first and expired
later. In the interval between the two, the event no longer
influences this node's own consensus computation, but the node still
retains it so it can serve the event to peers that are
slightly behind and need it.

The two thresholds are consulted at distinct sites. The ancient
threshold gates what enters and stays in the hashgraph DAG and is
honoured by intake stages (deduplication, signature validation) and
by the linker. The expired threshold is produced inside the
hashgraph by `ConsensusRounds`, which keeps a `roundsExpired`-bounded
ring of per-round metadata (`MinimumJudgeInfo`) and reports the
oldest still-tracked round's minimum-judge birth round as the
expired threshold; that value is then consumed at event-retention
sites outside the hashgraph — notably the gossip shadowgraph — to
evict events that no peer can still need. A peer that has fallen far
enough behind to require an expired event can no longer catch up via
gossip and must instead recover by performing a reconnect (see
[`../architecture/topics/reconnect.md`](../architecture/topics/reconnect.md)).

## Example

A node has just decided round 100. Suppose `ancientThreshold = 90`
and `expiredThreshold = 80`.

- Birth round 95: **admitted**. Still in the non-ancient window
  (the set of events relevant to the consensus algorithm); still
  contributes to fame voting on undecided witnesses.
- Birth round 85: **ancient**. Removed from the hashgraph; still
  retained by the gossip shadowgraph, which can serve it to a peer
  who is a few rounds behind.
- Birth round 75: **expired**. Discarded everywhere.

## In current code

Both thresholds live on
[`EventWindow`](../../../consensus-model/src/main/java/org/hiero/consensus/model/hashgraph/EventWindow.java)
as the record fields `ancientThreshold` (line 17) and
`expiredThreshold` (line 18); `EventWindow.isAncient` tests
`event.getBirthRound() < ancientThreshold` (line 87).

The ancient threshold is honoured by the hashgraph linker
([`ConsensusLinker.linkEvent`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java))
and by intake stages such as
[`StandardEventDeduplicator.shiftWindow`](../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/deduplication/StandardEventDeduplicator.java).
The expired threshold is honoured at retention sites.
[`ConsensusRounds`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusRounds.java)
keeps a `minimumJudgeStorage` ring buffer of per-round
`MinimumJudgeInfo`; `getExpiredThreshold` (line 243) reports the
oldest still-tracked round's minimum-judge birth round, and the ring
is trimmed each time an election is decided —
`currentElectionDecided` calls
`minimumJudgeStorage.removeOlderThan(getFameDecidedBelow() - config.roundsExpired())`
(line 147) to drop newly-expired round metadata. The gossip
[`Shadowgraph`](../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/Shadowgraph.java)
consumes the same threshold to maintain its `oldestUnexpiredIndicator`
pointer (line 121) and to drive event eviction during sync
reservations (line 168).

Earlier code named the two thresholds `minGenNonAncient` and
`minGenNonExpired` and computed them against event generations;
current code uses birth round.

## Cross-references

- Architectural lens:
  [`../architecture/topics/hashgraph.md`](../architecture/topics/hashgraph.md),
  [`../architecture/topics/event-intake.md`](../architecture/topics/event-intake.md).
- Sibling concepts: [`birth-round.md`](birth-round.md),
  [`rounds-and-witnesses.md`](rounds-and-witnesses.md),
  [`stale-events.md`](stale-events.md).
- Invariants: INV-012 (the minimum non-ancient round never decreases — the ancient boundary only moves forward), INV-004 (a stale event is never ordered on any node).
- Glossary entry: [`../glossary.md`](../glossary.md).
