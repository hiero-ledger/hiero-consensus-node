---
type: concept
title: Judges
last_reviewed: 2026-05-22
---

# Judges

## Definition

A *judge* in a decided round *r* is the unique famous witness for some
creator in *r*. Unpacked: a [witness](rounds-and-witnesses.md) (the
first event by a creator in a round) that has been voted
[famous](voting.md), with the [deterministic merge](branching.md)
collapsing the case where a branched creator has more than one famous
witness in the round. The judges of round *r* jointly fix the
round-received of every event that is an ancestor of all of them:
those events reach consensus in round *r*.

## Mechanics

Once `RoundElections.isDecided()` returns true (every witness in *r*
has a fame verdict), the algorithm collapses the famous witnesses to
one per creator — a deterministic merge handles the case where a
creator branched and produced more than one famous witness in the
round — and sorts the result by creator id. The judges' base hashes
are XOR-ed to produce a per-round "whitening" string that participates
in the final tie-break when sorting events into consensus order.

## Example

Four nodes A, B, C, D. In round 5 fame is decided for one witness per
creator and all four are famous: those four events are the round 5
judges. Any event that is an ancestor of all four reaches consensus in
round 5; an event that is an ancestor of only three judges waits for a
later round.

## In current code

`RoundElections.findAllJudges`
([`RoundElections.java`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java)
line 136). Per-event flag: `EventImpl.setJudgeTrue`. Persisted form:
the `JudgeId` PBJ message (defined at
[`platform_state.proto`](../../../../hapi/hedera-protobuf-java-api/src/main/proto/platform/state/platform_state.proto)
line 168), carried inside `ConsensusSnapshot.judgeIds`.

The paper terms these "unique famous witnesses"; the code names them
*judges*. The two terms refer to the same set. The exact tie-break
sequence used when sorting events of a decided round is the open
`[TBD]` raised in the architectural lens; see that file rather than
duplicating the question here.

## Cross-references

- Architectural lens:
  [`../architecture/topics/hashgraph.md`](../architecture/topics/hashgraph.md).
- Sibling concepts:
  [`rounds-and-witnesses.md`](rounds-and-witnesses.md),
  [`voting.md`](voting.md),
  [`branching.md`](branching.md),
  [`strongly-seeing.md`](strongly-seeing.md),
  [`coin-rounds.md`](coin-rounds.md).
- Glossary entry: [`../glossary.md`](../glossary.md).
