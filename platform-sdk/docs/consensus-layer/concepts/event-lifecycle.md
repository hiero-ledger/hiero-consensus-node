---

title: Event lifecycle
kind: concept
last_reviewed: TBD
------------------

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
[admitted] --(ancient threshold)--> [ancient] --(expired threshold)--> [expired]
```

Both thresholds are birth-round values carried on `EventWindow`. The
expired threshold is more permissive (further in the past) than the
ancient threshold, so an event becomes ancient first and expired
later. The interval between the two is the operational window during
which a node retains an event for catch-up purposes after it has
stopped influencing the node's own consensus computation.

The two thresholds are consulted at distinct sites. The ancient
threshold gates what enters and stays in the hashgraph DAG and is
honoured by intake stages (deduplication, signature validation) and
by the linker. The expired threshold gates retention in stores that
sit outside the hashgraph — the consensus-rounds buffer and the
gossip shadowgraph — which use it to evict events that no peer can
still need.

## Example

A node has just decided round 100. Suppose `ancientThreshold = 90`
and `expiredThreshold = 80`.

- Birth round 95: **admitted**. Still in the hashgraph window; still
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
The expired threshold is honoured at retention sites:
[`ConsensusRounds.getExpiredThreshold`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusRounds.java)
(line 243) and the gossip
[`Shadowgraph`](../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/Shadowgraph.java),
which uses it to maintain its `oldestUnexpiredIndicator` pointer
(line 121) and to drive event eviction during sync reservations
(line 168).

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
- Glossary entry: [`../../hashgraphGlossary.md`](../../hashgraphGlossary.md).
