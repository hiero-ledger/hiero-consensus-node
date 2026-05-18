---
title: Strongly-seeing
kind: concept
last_reviewed: TBD
---

# Strongly-seeing

## Definition

Event *x* *strongly sees* witness *y* iff there exists a set of
intermediate events, each by a distinct creator and collectively a
super-majority of roster weight, such that every event in the set is
both a descendant of *y* and an ancestor of *x*. In plain language: a
super-majority of distinct creators have all *seen* *y* on the path
between *y* and *x*.

## Mechanics

Strongly-seeing is computed as a memoized walk over the hashgraph.
`lastSee(x, m)` returns the most recent event by member *m* that is
an ancestor of *x*. For each creator `m₂`, the *path through `m₂`*
resolves to a particular witness by `m`: `seeThru(x, m, m₂)` walks
from `x` to the latest event by `m₂` in `x`'s ancestors, then back
along self-parents to the first witness by `m` in that event's
round-created. Call this the *canonical witness by `m` via
path-`m₂`* — the witness by `m` that the path-`m₂` resolution
points to. `stronglySeeP(x, m)` returns the canonical witness in
`x`'s parent round that a super-majority weight of paths agree on,
when such agreement exists; otherwise null. The relation is the
gating condition for round bumps and for fame voting.

## Example

Four equal-weight nodes (super-majority = 3 of 4). Witness *y* is at
round 1, created by node A. Event *x* at round 2 by node A has B, C,
and D ancestors that each descend from *y*. Three distinct creators ≥
super-majority, so *x* strongly sees *y* and *x* is eligible to take
round 2.

## In current code

`ConsensusImpl.stronglySeeP` (line 1045),
`ConsensusImpl.timedStronglySeeP` (line 871), and
`ConsensusImpl.lastSee` (line 956), in
[`ConsensusImpl.java`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java).
The super-majority test uses `Threshold.SUPER_MAJORITY`
([`Threshold.java`](../../../base-utility/src/main/java/org/hiero/base/utility/Threshold.java)).

## Cross-references

- Architectural lens:
  [`../architecture/topics/hashgraph.md`](../architecture/topics/hashgraph.md).
- Sibling concepts:
  [`rounds-and-witnesses.md`](rounds-and-witnesses.md),
  [`judges.md`](judges.md).
- Glossary entry: [`../../hashgraphGlossary.md`](../../hashgraphGlossary.md).
