---

title: Coin rounds
kind: concept
last_reviewed: TBD
------------------

# Coin rounds

## Definition

A *coin round* is a periodic round during a fame election in which
votes are not allowed to *decide* fame. If no super-majority is reached
on either side, the voting witness's vote is taken from a deterministic
pseudo-random source — specifically, a bit derived from the witness's
own signature. Coin rounds exist to guarantee liveness: without them,
an adversary could keep an election perpetually undecided.

## Mechanics

When fame is being voted on for a candidate witness in round *r*, every
voting witness in a later round *r + d* casts a vote. If *d* is a
multiple of the configured coin frequency, the vote is a *coin* vote:
it cannot decide fame, and if no super-majority is reached among the
strongly-seen prior-round witnesses, it falls back to the pseudo-random
bit. Otherwise it follows the super-majority. Non-coin rounds cast
ordinary counting votes that *can* decide fame on a super-majority.

## Example

With the default coin frequency 12: when fame for a round-*r* witness
is being voted on, voters at rounds *r+1* through *r+11* cast normal
counting votes; voters at round *r+12* cast coin votes; rounds *r+13*
through *r+23* are counting again; round *r+24* is a coin round; and
so on, until fame decides on either side.

## In current code

`ConsensusImpl.isCoinRound(diff)` is `diff % config.coinFreq() == 0`
(line 604 of
[`ConsensusImpl.java`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java)).
Coin vote application: `ConsensusImpl.coinVote` (line 621). Coin
frequency configuration:
[`ConsensusConfig#coinFreq`](../../../consensus-hashgraph/src/main/java/org/hiero/consensus/hashgraph/config/ConsensusConfig.java)
defaulting to `12`.

## Cross-references

- Architectural lens:
  [`../architecture/topics/hashgraph.md`](../architecture/topics/hashgraph.md).
- Sibling concepts:
  [`rounds-and-witnesses.md`](rounds-and-witnesses.md),
  [`judges.md`](judges.md).
- Glossary entry: [`../../hashgraphGlossary.md`](../../hashgraphGlossary.md).
