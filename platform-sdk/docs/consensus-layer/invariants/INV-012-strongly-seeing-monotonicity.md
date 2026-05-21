---
id: INV-012
title: Strongly-seeing is monotone and eventually agreed
class: determinism
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, §IV-C + §IV-C end / p. 3
provenance: split-from-INV-007 against §IV-C-end meta-claim + §IV-C definition of strongly-seeing; 2026-05-21
curated_by: Michael Heinrichs (@netopyr)
---

# INV-012 — Strongly-seeing is monotone and eventually agreed

## Statement
Whether one event strongly sees another is a monotone
deterministic conclusion: once an honest node concludes that x
strongly sees y, the conclusion never reverses as its hashgraph
grows, and every honest node eventually reaches the same
conclusion.

## Basis
The paper directly names this property in Baird & Luykx 2020,
§IV-C / p. 3: "strongly seeing is an example of a conclusion
that is a deterministic function of a hashgraph that is
guaranteed to eventually be reached by all nodes no matter what
order they receive the hashgraph." Strongly-seeing is defined in
the same section: "the event x strongly sees y if x can see more
than 2n/3 events by different nodes, each of which can see y."
Both the membership of the witness set (events that see y, by
INV-011) and x's seeing of those events (INV-011 again) are
monotone, so the quorum threshold is crossed once and stays
crossed. The §IV-C end meta-claim provides the broader license
covering this and the other deterministic conclusions.

## Change risk
Any change that lets the strongly-seeing predicate flip as the
local hashgraph grows breaks this invariant. Mechanisms:
lowering or making variable the >2n/3 distinct-node threshold
(a later threshold change un-decides previous conclusions);
deriving the witness set from a global or mutable view rather
than from x's frozen ancestor sub-DAG; allowing events authored
by a forking creator to count toward the distinct-node quorum
(this also interacts with INV-006). Downstream effects: round
assignment (INV-013), fame voting (INV-008), and consensus order
(INV-002) all inherit any flip-flopping here.

## Notes
- Depends on INV-011 (seeing monotonicity).
- Sibling to INV-006 (anti-fork property of the same operation:
  INV-006 says no event can strongly see both branches of a
  fork; INV-012 says the conclusion does not flip over time).
- Load-bearing for INV-013 (round assignment uses
  strongly-sees-prior-round-witnesses), INV-008 (fame voting
  tallies over strongly-seen previous-round witnesses), and
  INV-002 (consensus order via INV-013 → INV-015).
- One of six per-component instantiations of the deterministic-
  conclusion meta-property catalogued under INV-007.
- `status` is [TBD: confirm enforced in current implementation].
