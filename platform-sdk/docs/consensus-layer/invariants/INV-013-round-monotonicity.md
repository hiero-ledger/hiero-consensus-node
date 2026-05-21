---
id: INV-013
title: An event's round assignment is monotone and eventually agreed
class: determinism
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, Algorithm 2 (divideRounds), §IV-D-1 / p. 4 + §IV-C end / p. 3
provenance: split-from-INV-007 against §IV-C-end meta-claim + Algorithm 2 definition of x.round; 2026-05-21
curated_by: Michael Heinrichs (@netopyr)
---

# INV-013 — An event's round assignment is monotone and eventually agreed

## Statement
The round assigned to an event (`x.round`) is a monotone
deterministic conclusion: once an honest node computes
`x.round`, the value never changes as its hashgraph grows, and
every honest node eventually computes the same value.

## Basis
`x.round` is computed by `divideRounds` (Baird & Luykx 2020,
Algorithm 2, §IV-D-1 / p. 4): the parent-round maximum `r` is
taken (or 1 if x has no parents), and `x.round` becomes `r + 1`
if x strongly sees more than 2n/3 round-`r` witnesses, else `r`.
Every input to this computation is a monotone function of the
frozen ancestor sub-DAG: parent rounds are themselves `x.round`
values on x's parents (recursive monotonicity, anchored at the
parentless base case), and the strongly-seeing predicate is
monotone by INV-012. The §IV-C end meta-claim ("All components
of the hashgraph consensus algorithm have mathematical proofs
that they have this consistency property") licenses round
assignment as one of those components.

## Change risk
Any change that lets `x.round` change after first being computed
breaks this invariant. Mechanisms: making the round-`r` witness
set depend on what x has subsequently received rather than what
is in x's ancestor sub-DAG; changing the strongly-sees threshold
or the witness-identity rule after the fact; allowing a parent's
round to be recomputed and propagated; lazily evaluating
`x.round` against a moving hashgraph state rather than committing
on first computation. Downstream effects: witness status
(INV-014), fame elections (INV-008, which range over witnesses),
and round received (INV-015) all read from `x.round`.

## Notes
- Depends on INV-012 (strongly-seeing monotonicity).
- Load-bearing for INV-014 (witness status is a function of
  round) and indirectly for INV-008 and INV-015.
- One of six per-component instantiations of the deterministic-
  conclusion meta-property catalogued under INV-007.
- `status` is [TBD: confirm enforced in current implementation].
