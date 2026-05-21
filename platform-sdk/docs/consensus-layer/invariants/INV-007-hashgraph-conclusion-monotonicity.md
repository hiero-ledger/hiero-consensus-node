---
id: INV-007
title: Every deterministic conclusion on the hashgraph is monotone and eventually agreed
class: determinism
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, §IV-B + §IV-C end / p. 3
provenance: paper-extraction-2026-05-20; reframed-as-umbrella-2026-05-21
curated_by: Michael Heinrichs (@netopyr)
---

# INV-007 — Every deterministic conclusion on the hashgraph is monotone and eventually agreed

## Statement
Every deterministic conclusion that the protocol derives from
the local hashgraph is monotone: at any moment an honest node
either has no conclusion (it has not yet received enough of the
hashgraph) or has a conclusion that never changes as its
hashgraph grows, and every honest node eventually reaches the
same conclusion.

## Basis
The paper states the design constraint in Baird & Luykx 2020,
§IV-B / p. 3: "the algorithm must be designed so that a
conclusion based on a deterministic function of the hashgraph at
one moment will not change as the hashgraph grows." The §IV-C
end sentence (p. 3) promotes this from a design constraint into
a verified property of every component: "All components of the
hashgraph consensus algorithm have mathematical proofs that they
have this consistency property." Mechanism: the hashgraph only
grows — events are added, never removed — and every protocol
conclusion is defined over the ancestor sub-DAG of an event,
which is itself frozen the moment the event is known (INV-004,
INV-005).

## Change risk
The meta-property breaks if the protocol introduces a
deterministic conclusion that depends on something other than the
immutable ancestor sub-DAG — for instance, a conclusion that
depends on wall-clock arrival order, on which events have been
received but not yet integrated, on the sync order with peers,
or on global state outside the ancestor sub-DAG. Any such
conclusion can flip as the local hashgraph grows or as
scheduling changes, and the monotonicity argument collapses for
that conclusion and for everything downstream of it. New
protocol features that add a deterministic step must demonstrate
that the step reduces to the ancestor sub-DAG.

## Notes
- This is the umbrella entry for the deterministic-conclusion
  meta-property. The per-component instantiations of monotonicity
  live in dedicated entries:
  - INV-010 — ancestry
  - INV-011 — seeing
  - INV-012 — strongly-seeing
  - INV-013 — round assignment
  - INV-014 — witness status
  - INV-015 — round received
  Two further components are absorbed into the entries that
  state them rather than carrying their own splits: fame
  monotonicity is encoded directly in INV-008 (a fame decision,
  once made, never changes), and consensus-order monotonicity is
  encoded directly in INV-002 (once two honest nodes have enough
  hashgraph to compute the ith output, they compute the same
  ith output).
- This entry was originally a single bundled monotonicity claim
  (paper-extraction-2026-05-20). It was reframed as the umbrella
  on 2026-05-21 when the per-component splits (INV-010 through
  INV-015) were created, prioritising downstream catalog
  usability — search by concern — over preserving the paper's
  bundling.
- The reframed Statement covers any future deterministic
  conclusion the protocol may add. If a new conclusion is added
  that does not yet have its own entry, this umbrella is the
  catalogued home for the monotonicity claim until a split is
  warranted.
- `status` is [TBD: confirm enforced in current implementation].
