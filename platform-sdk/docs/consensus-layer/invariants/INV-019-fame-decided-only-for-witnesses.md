---
id: INV-019
title: Only witnesses can have a decided fame value
class: integrity
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, Algorithm 3 (decideFame), §IV-D-2 / p. 4
provenance: algorithmic-consequences-extraction against Algorithm 3 (decideFame) §IV-D-2; 2026-05-21
curated_by: Michael Heinrichs (@netopyr)
---

# INV-019 — Only witnesses can have a decided fame value

## Statement
For any event x in an honest node's hashgraph, if `x.famous ≠
UNDECIDED` then `x.witness = TRUE`. Equivalently, non-witnesses
always have `x.famous = UNDECIDED`.

## Basis
Algorithm 3 (decideFame, Baird & Luykx 2020, §IV-D-2 / p. 4)
initialises `x.famous ← UNDECIDED` for every event in its outer
loop. The only subsequent write to `x.famous` occurs inside the
inner loop, guarded by `if x.witness and y.witness and y.round
> x.round then …` — the only assignment site is `x.famous ← v`
inside this conditional. Any decided value of `x.famous` must
therefore have come from this assignment, which fires only when
`x.witness = TRUE`. Contrapositively, a non-witness x is
written only by the outer-loop initialisation and retains
`UNDECIDED` permanently.

## Change risk
Any change that lets fame be assigned to non-witness events
breaks this invariant. Mechanisms: dropping the `x.witness`
guard from the inner conditional; introducing additional
fame-assignment sites elsewhere in the protocol (for example,
an "early decision" path or a post-hoc reconciliation step);
initialising fame to something other than UNDECIDED in a branch
that does not reset it for non-witnesses; redefining `x.witness`
*after* fame has already been written (which also breaks
INV-014). Downstream effects: Algorithm 4 reads "round r unique
famous witness" when computing round-received; if non-witnesses
can be famous, the unique-famous-witness set could contain
events that aren't witnesses, breaking the round-received and
consensus-timestamp computations.

## Notes
- Distinct from INV-008. INV-008 states fame *finality* for
  witnesses (decisions are stable once made); INV-019 states the
  *domain* of fame decisions (decisions exist only for
  witnesses). Both constrain the same `x.famous` field but on
  orthogonal axes — finality on the time axis, domain on the
  event-type axis.
- A typing constraint on the `famous` field: its domain of
  decision is exactly the witnesses.
- Load-bearing for the interpretation of "unique famous witness"
  in Algorithm 4, which presumes that every famous event is in
  fact a witness whose `x.round` is well-defined.
- `status` is [TBD: confirm enforced in current implementation].
