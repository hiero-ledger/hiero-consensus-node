---
type: concept
title: Coin rounds
last_reviewed: 2026-05-22
---

# Coin rounds

## Definition

A *coin round* is a periodic round during a fame election that exists
to break ties when ordinary counting votes have failed to reach a
super-majority on either side for too long. In a coin round, voters
that do not strongly see a super-majority fall back to a bit derived
from a dedicated `coin` field — a securely-generated random value that
the voting event's creator stamped on the event at creation — forcing
the election out of a stalemate. Coin rounds guarantee liveness: without
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
its vote is set to a bit derived from the voting event's `coin`
field — a securely-generated random value chosen by the creator at
event creation. The coin vote itself does not invoke
the fame-decision step — instead, it produces the aligned votes that
let a subsequent counting round reach super-majority and decide.
Because the `coin` field is stamped on the event once at creation and
travels with it through gossip, every honest node that has that event
computes the same coin-bit for that voter, which is why a single coin
round is enough to break a long-running tie.

## Example

With the default coin frequency 12: when fame for a round-*r* witness
is being voted on, voters at rounds *r+1* through *r+11* cast normal
counting votes; voters at round *r+12* cast coin votes; rounds *r+13*
through *r+23* are counting again; round *r+24* is a coin round; and
so on, until fame decides on either side.

## In current code

`ConsensusImpl.isCoinRound(diff)` is `diff % config.coinFreq() == 0`
(line 613 of
[`ConsensusImpl.java`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java)).
Coin vote application: `ConsensusImpl.coinVote` (line 630), which
delegates to `ConsensusUtils.coin(event)`
([`ConsensusUtils.java`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusUtils.java)
line 28) — the bit is the parity of the voting event's `coin` field:
`event.getEventCore().coin() % 2 == 0`. The field itself is
`EventCore.coin` (PBJ field 5 in
[`EventCore`](../../../../hapi/hedera-protobuf-java-api/src/main/proto/platform/event/event_core.proto)),
a `long` stamped on the event by its creator at creation time,
populated by
[`TipsetEventCreator`](../../../consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/tipset/TipsetEventCreator.java)
using a `java.security.SecureRandom`. The value must be genuinely
unpredictable to any adversary in advance — a deterministic
pseudo-random source seeded from event content would let an adversary
predict the bit and exploit it to keep an election undecided. This unpredictability requirement is INV-015. Coin frequency configuration:
[`ConsensusConfig#coinFreq`](../../../consensus-hashgraph/src/main/java/org/hiero/consensus/hashgraph/config/ConsensusConfig.java)
defaulting to `12`.

Earlier code derived the coin bit from the middle bit of the voting
event's signature; current code uses the dedicated `coin` field
instead.

## Cross-references

- Architectural lens:
  [`../architecture/topics/hashgraph.md`](../architecture/topics/hashgraph.md).
- Sibling concepts:
  [`rounds-and-witnesses.md`](rounds-and-witnesses.md),
  [`judges.md`](judges.md).
- Invariants: INV-015 (an honest event's coin value is unpredictable to the creators of its ancestors), INV-005 (every honest event eventually reaches consensus or becomes stale).
- Glossary entry: [`../glossary.md`](../glossary.md).
