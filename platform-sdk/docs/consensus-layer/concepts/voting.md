---
title: Voting
kind: concept
last_reviewed: TBD
---

# Voting

## Definition

*Voting* — also called *fame voting* or *virtual voting* — is the
algorithm by which witnesses in later rounds determine whether
witnesses in earlier rounds are *famous*. It is *virtual* because the
votes themselves are not exchanged as messages: each witness's vote on
each candidate is a deterministic function of the hashgraph DAG, so
every node computes the same votes from the same DAG.

## Mechanics

For each candidate witness in round *r* with undecided fame, every
witness in a later round *r + d* casts a vote. If *d == 1* the vote
is a *first vote* — does the voter directly see the candidate? If
*d > 1* the vote is a *counting vote* — a weighted YES/NO tally over
the prior-round witnesses that the voter strongly sees, with fame
decided when a super-majority of weight lands on either side. When *d*
is a multiple of the configured coin frequency the round is a *coin
round*: the vote cannot decide fame, and a stalled vote falls back to
a pseudo-random bit derived from the witness signature.

## Example

Four equal-weight nodes; super-majority = 3. Witness *w* lives at
round 5 with undecided fame. A round-6 witness (d = 1) casts a first
vote on *w* based on whether it sees *w*. A round-7 witness (d = 2)
tallies the round-6 witnesses it strongly sees: if at least three of
those four agree YES, the round-7 vote is YES with super-majority
support and *w* is decided famous on that voter's pass. If voting
drags to *d == coinFreq* (default 12), that round is a coin round
and cannot decide.

## In current code

`ConsensusImpl.voteInAllElections` (line 497) drives one voter's pass
across all undecided candidates, dispatching to `firstVote` (line
652), `getCountingVote` (line 578), or `coinVote` (line 621) per
candidate, in
[`ConsensusImpl.java`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java).
Per-round election state lives in
[`RoundElections`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java).

The paper indexes round arithmetic against an event's
non-deterministic generation; the current code uses event sequence
numbers and birth round (see
[`rounds-and-witnesses.md`](rounds-and-witnesses.md)).

## Cross-references

- Architectural lens:
  [`../architecture/topics/hashgraph.md`](../architecture/topics/hashgraph.md).
- Sibling concepts:
  [`rounds-and-witnesses.md`](rounds-and-witnesses.md),
  [`strongly-seeing.md`](strongly-seeing.md),
  [`coin-rounds.md`](coin-rounds.md),
  [`judges.md`](judges.md).
- Glossary entry: [`../../hashgraphGlossary.md`](../../hashgraphGlossary.md).
