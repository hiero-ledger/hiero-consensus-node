---

title: Stale events
kind: concept
last_reviewed: TBD
------------------

# Stale events

## Definition

A *stale event* in current code is an event that was admitted to the
hashgraph DAG and then aged past the ancient threshold without ever
reaching consensus. Stale is a *fate*, not one of the
[event lifecycle](event-lifecycle.md) states: the lifecycle describes
states an admitted event passes through, and stale is the specific
outcome of an admitted event whose lifecycle ended in *ancient*
without consensus.

The fate matters mostly for self-events. Their transactions never
became part of the consensus order, so the application can resubmit
them on a fresh self-event. Stale reports for events authored by
other creators are informational; the local node does not own those
transactions.

## Mechanics

When the ancient threshold advances, the linker unlinks events that
just became ancient and returns the list. The hashgraph engine
partitions that list into events that reached consensus (no further
action) and events that did not. The latter are reported as stale on
the stale-events output wire; an application-layer consumer
registered with the platform builder receives them and may resubmit
their transactions.

Events that arrive at intake already past the ancient threshold are
silently dropped at validation or linking — they never enter the
hashgraph, so they never become stale in the sense above. In current
code *stale* specifically denotes the post-admission outcome, not
pre-admission rejection.

## Example

Node A creates self-event `s` at birth round 50. `s` enters the
hashgraph and contributes to ongoing rounds, but A is briefly
partitioned before any round in which `s` would have judged decides.
The network proceeds without A; eventually the ancient threshold
advances past 50. The linker unlinks `s` as ancient. Because `s`
never reached consensus the engine emits `s` on the stale-events
output. A's stale-event callback receives `s` and resubmits its
transactions on a new self-event.

## In current code

Stale events are produced by
[`DefaultConsensusEngine.addEvent`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java)
(lines ~122, 180–185, 192–193): non-consensus events returned by
`ConsensusLinker.setEventWindow` are appended to the stale-events
list and emitted via
[`HashgraphModule.staleEventOutputWire`](../../../consensus-hashgraph/src/main/java/org/hiero/consensus/hashgraph/HashgraphModule.java).
Application consumers register through
[`PlatformBuilder.withStaleEventCallback`](../../../swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java)
(line 250).

No `StaleEventDetector` class exists in current code; the legacy
[`StaleEventDetectorOutput`](../../../consensus-model/src/main/java/org/hiero/consensus/model/event/StaleEventDetectorOutput.java)
enum still ships in `consensus-model` but appears unused outside its
declaration.

[TBD: question for engineer — the
[`StaleEventDetectorOutput`](../../../consensus-model/src/main/java/org/hiero/consensus/model/event/StaleEventDetectorOutput.java)
enum distinguishes `SELF_EVENT` from `STALE_SELF_EVENT` but no
production code references it. Is it dead code, or is a new detector
component planned?]

## Cross-references

- Architectural lens:
  [`../architecture/topics/hashgraph.md`](../architecture/topics/hashgraph.md),
  [`../architecture/topics/event-intake.md`](../architecture/topics/event-intake.md),
  [`../architecture/topics/gossip.md`](../architecture/topics/gossip.md).
- Sibling concept: [`event-lifecycle.md`](event-lifecycle.md).
- Glossary entry: [`../../hashgraphGlossary.md`](../../hashgraphGlossary.md).
