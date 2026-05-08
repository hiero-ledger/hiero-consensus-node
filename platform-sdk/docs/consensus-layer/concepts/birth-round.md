---

title: Birth round
kind: concept
last_reviewed: TBD
------------------

# Birth round

## Definition

An event's *birth round* is a round number stamped on the event when
its creator builds it: the round the creator believes the network is
currently working on. Birth round is immutable per event and travels
with it through gossip, intake, and consensus.

## Mechanics

The hashgraph uses birth round to decide what enters and stays in the
DAG. Events whose birth round is below the current ancient threshold
are dropped (treated as ancient). Events whose birth round exceeds the
current pending consensus round are buffered and released later when
the window advances. Birth round is also the key the linker uses to
window-retain non-ancient events.

## Example

The pending consensus round is 100; the ancient threshold is 90. An
event with birth round 99 is admitted to the DAG. An event with birth
round 89 is dropped as ancient and reported as stale if it never
reached consensus. An event with birth round 105 sits in the future
buffer until the window advances past 105.

## In current code

Field accessor: `PlatformEvent.getBirthRound()` (line 266 of
[`PlatformEvent.java`](../../../consensus-model/src/main/java/org/hiero/consensus/model/event/PlatformEvent.java)).
Sentinel: `EventConstants.BIRTH_ROUND_UNDEFINED`
([`EventConstants.java`](../../../consensus-model/src/main/java/org/hiero/consensus/model/event/EventConstants.java)).
Ancient drop: `ConsensusLinker.linkEvent` calls
`EventWindow.isAncient`. Future buffering:
[`FutureEventBuffer`](../../../consensus-utility/src/main/java/org/hiero/consensus/event/FutureEventBuffer.java)
configured with `FutureEventBufferingOption.PENDING_CONSENSUS_ROUND`.

Birth round replaces an older generation-based ancient/expiry scheme;
the paper uses non-deterministic generation (NGen) to play the same
role.

## Cross-references

- Architectural lens:
  [`../architecture/topics/hashgraph.md#birth-round-filtering`](../architecture/topics/hashgraph.md#birth-round-filtering).
- Sibling concept:
  [`rounds-and-witnesses.md`](rounds-and-witnesses.md).
- Glossary entry: [`../glossary.md`](../glossary.md).
