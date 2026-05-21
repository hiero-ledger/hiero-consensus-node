---
id: INV-017
title: At most one witness per round on any single self-parent chain
class: safety
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, Algorithm 2 (divideRounds), §IV-D-1 / p. 4
provenance: algorithmic-consequences-extraction against Algorithm 2 (divideRounds) §IV-D-1; 2026-05-21
curated_by: Michael Heinrichs (@netopyr)
---

# INV-017 — At most one witness per round on any single self-parent chain

## Statement
Along any single self-parent chain in an honest node's
hashgraph, at most one event per round has `x.witness = TRUE`.
Equivalently, for any honest non-forking creator, at most one of
that creator's events per round is a witness.

## Basis
Algorithm 2 (divideRounds, Baird & Luykx 2020, §IV-D-1 / p. 4)
computes `x.witness ← (x has no self parent) OR (x.round >
x.selfParent.round)`. Walk a self-parent chain `e₁ ← e₂ ← e₃
← …`, where each `eᵢ₊₁` has `eᵢ` as its self-parent. The
self-parent is by definition a parent, so parent-edge round
monotonicity (INV-016) gives `eᵢ.round ≤ eᵢ₊₁.round`: the chain
carries a non-decreasing integer sequence of rounds. The witness
predicate makes `eᵢ₊₁.witness` depend on `eᵢ₊₁.round >
eᵢ.round` — a *strict* increase. Strict increases on a
non-decreasing integer sequence are unique per integer value,
so each round number can host at most one witness on the chain.

## Change risk
Any change that lets multiple events at the same round on a
single self-parent chain become witnesses breaks this invariant.
Mechanisms: weakening the witness predicate from `x.round >
x.selfParent.round` to `x.round ≥ x.selfParent.round`; allowing
the predicate to consult inputs other than the self-parent;
recomputing witness status against a moving hashgraph state in
a way that flips the result (which also breaks INV-014);
allowing the "no self-parent" branch to fire for events that do
have a self-parent. Downstream effects: per-round
famous-witness sets used in Algorithm 4 would no longer
correspond to one event per (creator, round) along an honest
chain, and "unique famous witness" — central to round-received
determination — would lose its single-event grounding for
honest creators.

## Notes
- A forking creator can have multiple self-parent chains; this
  invariant bounds witnesses per chain, not per (creator, round)
  globally. The "unique" in "unique famous witness" (Algorithm 4,
  §IV-D-3 / p. 5) is the consensus-level mechanism that handles
  the multiple-chains case.
- Depends on INV-016 (parent-edge round monotonicity used in the
  inference).
- Structural counterpart to INV-014 (which is *temporal*
  monotonicity of the `x.witness` flag — once decided, doesn't
  flip — not a cap on count). Both can be violated independently.
- Load-bearing indirectly for INV-008 and INV-015, which both
  range over witnesses; a violation here would change which
  events are subjects of fame elections.
- `status` is [TBD: confirm enforced in current implementation].
