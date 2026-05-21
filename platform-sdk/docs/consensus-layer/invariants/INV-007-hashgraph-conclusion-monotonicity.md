---
id: INV-007
title: Deterministic conclusions on the hashgraph are monotone and eventually agreed
class: determinism
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, §IV-B + §IV-C end / p. 3
provenance: paper-extraction-2026-05-20
curated_by: Michael Heinrichs (@netopyr)
---

# INV-007 — Deterministic conclusions on the hashgraph are monotone and eventually agreed

## Statement
Every deterministic function of the local hashgraph used by the
protocol (ancestry, seeing, strongly seeing, fame of a witness,
round received, consensus order) is monotone: at any moment a node
either has no conclusion (undecided) or has a conclusion that will
never change as its local hashgraph grows, and every honest node
eventually reaches the same conclusion.

## Basis
The paper states the design constraint in §IV-B (p. 3): "the
algorithm must be designed so that a conclusion based on a
deterministic function of the hashgraph at one moment will not
change as the hashgraph grows." Ancestry is given as the canonical
example: at any moment a node either does not yet have enough
hashgraph to conclude whether D1 is an ancestor of C6, or it has
concluded yes and that conclusion never reverses. §IV-C end (p. 3)
promotes the ancestry example to every deterministic conclusion
used by the protocol: "All components of the hashgraph consensus
algorithm have mathematical proofs that they have this consistency
property." Mechanism: the hashgraph only grows — events are added,
never removed — and every protocol conclusion is defined over the
ancestor sub-DAG of an event, which is itself frozen the moment
the event is known (INV-004).

## Change risk
The invariant breaks if the protocol introduces a deterministic
conclusion that depends on something other than the immutable
ancestor sub-DAG — for instance, a conclusion that depends on
wall-clock arrival order, on which events have been received but
not yet integrated, or on the sync order with peers. Any such
conclusion can flip as the local hashgraph grows or as scheduling
changes, and the monotonicity argument collapses. The downstream
effect: virtual votes flip retroactively, fame decisions
un-decide, and consensus order is no longer well-defined.

## Notes
- The paper states this as a single meta-property covering every
  component of the algorithm. This entry preserves that framing.
  An alternative is to split into per-conclusion invariants
  (ancestry, seeing, strongly-seeing, fame, consensus-order
  monotonicity); the paper does not state them separately, and the
  merged form is the closer-to-source choice.
- Load-bearing for INV-002 (total order), INV-006 (strongly-seeing
  fork resolution), and INV-008 (fame finality), each of which
  cites monotonicity as the mechanism that prevents flip-flopping
  conclusions.
- `status` is [TBD: confirm enforced in current implementation].
