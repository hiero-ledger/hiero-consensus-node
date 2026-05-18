---
title: Rounds and witnesses
kind: concept
last_reviewed: TBD
---

# Rounds and witnesses

## Definition

Each event has two distinct round quantities. Keeping them separate
matters because one of them is mutable during the algorithm's working
phase and the other is not.

- *Round-created* — the round in which an event was created, computed
  from the event's parents and the strongly-seeing relationship over
  prior witnesses. Round-created is a property of the event in the
  hashgraph but is not final: it can be **recalculated** as earlier
  rounds decide, and an event's round-created may change.
- *Round-received* (also called the *consensus round*; both terms
  refer to the same quantity, and different parts of the code and
  these docs use both interchangeably) — the round in which the event
  reaches consensus order. Round-received is set exactly once, at the
  moment the event reaches consensus, and **never changes after
  that**.
- *Witness* — the first event by a creator in a given round-created.
  Equivalently, an event whose round-created exceeds its self-parent's
  round-created (or which has no self-parent). Witnesses are the
  events on which elections are held, who vote, and collect votes.

## Mechanics

**Computing round-created.** An event's round-created normally
inherits the maximum of its parents' round-created values. The exception
is the *round-bump*: when an event strongly sees a super-majority of
the weight on the witnesses of the parent round, its round-created
bumps to *parent round + 1*. Events older than the latest decided
ancient threshold are marked `ROUND_NEGATIVE_INFINITY` and skipped.

**Why round-created can change.** When an earlier round decides,
`ConsensusImpl.recalculateAndVote` walks the `recentEvents` list and
re-derives round-created (and the per-event scratch metadata) for
every non-ancient non-consensus event. The recalculation is only
*required* when the roster — the set of valid members and their
weights — that governs the next round differs from the roster used
for the previous round, because strongly-seeing and the
super-majority threshold that drives round-bumps are both
weighted by the active roster. Today the roster does not change
during normal operation, so the recalculation is in practice a
no-op for round-created values; the code runs it on every decided
round anyway in anticipation of *fully dynamic address book* (mid-run
roster changes), so that round-created values (and other metadata that depends on the round created) stay correct once
roster transitions are enabled. Until an event reaches consensus,
treat its round-created as tentative.

**Election round and voting rounds.** As fame voting proceeds, the
algorithm labels round numbers by the role they play. There is
exactly one *election round* at any time — the lowest round that
still has any witness whose fame is undecided; the code calls this
`ConsensusRounds.getElectionRoundNumber`, and `isElectionRound(r)`
is just `getElectionRoundNumber() == r`. Any round whose
round-created is strictly greater than the election round is a
*voting round*. All voting rounds are labelled the same way, but
they play different roles depending on how far they are above the
election round:

- *Round r+1* — casts a *first vote* on each undecided witness in
  the election round, based on direct visibility. A first vote
  never decides; it only seeds the count for later rounds.
- *Round r+2* — looks at the strongly-seen witnesses of r+1,
  tallies their first votes, and casts its own vote following that
  tally. If the tally is a super-majority, the election decides and
  the round's recorded vote is irrelevant. If it isn't, the recorded
  vote becomes the data that round r+3 will count.
- *Round r+3 and beyond* — same as r+2, one round shifted: each
  voting round counts the previous round's votes, records its own
  according to that count, and may decide. The recorded vote only
  matters if the current round fails to decide.

Multiple voting rounds therefore coexist, all carrying the "voting
round" label even though some end up only voting, some only
collecting-and-deciding, and most do both. The labels shift forward
by one whenever the current election decides and a new one opens.

Coin rounds ([`coin-rounds.md`](coin-rounds.md)) are voting rounds
too; they record a vote but never decide, regardless of whether a
super-majority is seen.

**Reaching consensus.** Once every witness in round *r* has a fame
verdict, *r* is "decided". At that point every event that is an
ancestor of all judges of *r* takes round-received *r*. From then on,
those events' round-received is fixed; their round-created is also
no longer recalculated (they leave `recentEvents`).

## Example

Four equal-weight nodes (super-majority = 3 of 4). Round 1 has four
witnesses, one per creator. An event *x* at round 1 by node A whose
ancestors include witnesses by B, C, and D — three distinct creators —
strongly sees a super-majority of round-1 witnesses, so *x* takes round
2 and is round 2's witness for A.

## In current code

Round assignment: `ConsensusImpl.round`
([`ConsensusImpl.java`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java)).
Round-created recalculation: `ConsensusImpl.recalculateAndVote`
(line 325), invoked after each election decides (call sites at
lines 297 and 308). It walks `recentEvents` and re-derives
round-created and per-event scratch metadata for every event still
in an undecided round (i.e. non-ancient, non-consensus).
Witness predicate: `ConsensusImpl.witness`. Per-event accessors:
`EventImpl.getRoundCreated` (line 334) and
`EventImpl.getRoundReceived` (line 112). Per-round election state:
[`RoundElections`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java),
whose `getRound()` (line 48) is what `ConsensusRounds.getElectionRoundNumber`
exposes. "Voting round" is not a class or method name; it is the
conceptual label for any round whose round-created is greater than
the election round during `voteInAllElections` (line 506).

Round bumps work the same way in the paper and in the code:
an event's round is bumped when it strongly sees a super-majority of
the previous round's witnesses. The paper and the code only diverge
on the ancient/expired horizon — the paper uses an event's
deterministic generation, and the code uses
[`birth-round.md`](birth-round.md).

## Cross-references

- Architectural lens:
  [`../architecture/topics/hashgraph.md`](../architecture/topics/hashgraph.md).
- Sibling concepts:
  [`strongly-seeing.md`](strongly-seeing.md),
  [`judges.md`](judges.md),
  [`birth-round.md`](birth-round.md).
- Glossary entry: [`../../hashgraphGlossary.md`](../../hashgraphGlossary.md).
