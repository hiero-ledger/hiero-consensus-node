---
title: Coin rounds
kind: concept
last_reviewed: TBD
---

# Coin rounds

## Definition

A *coin round* is a periodic round during a fame election that exists
to break ties when ordinary counting votes have failed to reach a
super-majority on either side for too long. In a coin round, voters
that do not strongly see a super-majority fall back to a deterministic
pseudo-random bit derived from their own signature, forcing the
election out of a stalemate. Coin rounds guarantee liveness: without
them, an adversary could keep an election perpetually undecided.

## Mechanics

When fame is being voted on for a candidate witness in round *r*,
every voting witness in a later round *r + d* casts a vote. In a
non-coin round (*d* is not a multiple of the configured coin
frequency), the voter casts an ordinary counting vote: it follows
the majority of the prior-round witnesses it strongly sees, and if
that majority is a super-majority, fame is decided on the spot. In a
coin round (*d* is a multiple of the coin frequency), the voter
still follows a strongly-seen super-majority if one exists; otherwise
its vote is set to the deterministic pseudo-random bit. The coin
vote itself does not invoke the fame-decision step — instead, it
produces the aligned votes that let a subsequent counting round
reach super-majority and decide. The pseudo-random bit is identical
across all honest voters that look at the same signature, which is
why a single coin round is enough to break a long-running tie.

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
- Glossary entry: [`../glossary.md`](../glossary.md).
