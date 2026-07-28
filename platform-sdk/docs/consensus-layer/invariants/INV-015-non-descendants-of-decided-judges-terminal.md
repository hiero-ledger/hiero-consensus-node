---
type: invariant
id: INV-015
title: Events that are not descendants of the latest decided round's judges are terminal
class: agreement
topics: [hashgraph]
related:
  rules: [RUL-005]
  decisions: [ADR-008]
  scenarios: [SCN-002]
  heuristics: []
status: enforced
source: >
  The hashgraph consensus algorithm (protocol definition): a round's judge set is
  agreed by all deciders (INV-007) and re-derivable from a decided-round snapshot,
  combined with the reconnect/state-transfer design in which a node resumes
  consensus from the latest decided round downward.
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java — `round` (the two `ROUND_NEGATIVE_INFINITY` short-circuits) and `recalculateAndVote` (a decided-round judge keeps its metadata only when all its parents are terminal)
provenance: elicitation-2026-07-27; re-diagnosis of SCN-002 (#26529)
curated_by: Kelly Greco (@poulok)
---

# INV-015 — Events that are not descendants of the latest decided round's judges are terminal

## Statement

Every event that is **not** a descendant of any judge of the latest decided round
is assigned a terminal voting round — `roundCreated = ROUND_NEGATIVE_INFINITY` — on
every node, regardless of that node's reconnect or state-load history. (The *voting
round* is the round the algorithm assigns to an event; the implementation stores it
in `roundCreated`, and *terminal* denotes the `ROUND_NEGATIVE_INFINITY` sentinel —
see INV-001.) Events that *are* descendants of those judges keep a real round
number.

## Basis

Judge sets are a property of the round, agreed by every node that decides it
(INV-007), and consensus order and timestamp follow from them (INV-002, INV-003).
That agreement must hold across nodes with **different local histories** — in
particular a node that has reconnected.

A reconnected node resumes consensus from a signed state pinned to the latest
decided round. It does not receive, from that snapshot, the events below the
decided round's judges carrying their original round metadata; it relearns those
lower events afterwards, via gossip or PCES, without it. Such a node therefore
**cannot reconstruct** which of those lower events were witnesses in the rounds
before the decided one.

For all nodes to agree on the judges of every round regardless of history, every
event that is not a descendant of the decided round's judges must be treated
identically on every node. The only history-independent treatment is the terminal
value: assigning a *real* round to such an event would make the outcome depend on
sub-decided-round structure that a reconnected node has no way to reproduce, so two
nodes could compute different witnesses and then different judges — a divergence.
Descendants of the decided round's judges are different: they are re-derivable from
the judges the snapshot carries (round assignment is defined relative to parents,
INV-001), so they keep real rounds and every node computes them alike.

The property is thus a consequence of the algorithm's judge-set agreement under the
reconnect model, not of any particular way the code is written: any correct
implementation must pin non-descendants of the decided judges to the terminal value.

## Change risk

Any change that lets a non-descendant of the latest decided round's judges acquire
a **real** (non-terminal) voting round breaks the invariant. Concrete mechanisms:

- **A round-assignment path that assigns a real round without checking descent from
  the decided judges.** The parentless branch of `ConsensusImpl.round` assigns
  `ROUND_FIRST` to any event with no parents — correct for a true first-round event,
  but a defect for a non-descendant genesis event that clears the frontier, which
  must be terminal. This is the latent bug #26529 (see Notes and SCN-002).
- **Weakening the terminal short-circuits so a non-descendant slips through.** A
  non-descendant is kept terminal either by the RUL-005 frontier short-circuit or,
  above the frontier, by inheriting `ROUND_NEGATIVE_INFINITY` from its parents. The
  two must jointly cover **every** non-descendant — including the parentless case,
  which has no parents to inherit from.
- **A side path that imports or caches a real round for such an event** — a reconnect
  or replay path that carries round numbers across the decided-round boundary
  instead of re-deriving them from the judges.

Because the property holds by the algorithm regardless of the code, any of these is
a defect to be stopped, not a tradeoff — its symptom is an ISS (SCN-002).

## Notes

- **Enforced today, with a latent defect.** In the shipping configuration the
  RUL-005 frontier is keyed on `nGen`, which keeps every non-descendant below the
  frontier (a genesis event carries `nGen = 1`), so the parentless `ROUND_FIRST`
  branch is never reached by a non-descendant and the invariant holds. The
  round-assignment code nonetheless contains the latent defect #26529
  (`ConsensusImpl.round`, the parentless branch assigns `ROUND_FIRST`); it is masked
  only by that `nGen` keying. Re-keying the frontier to the orphan-buffer sequence
  number without fixing #26529 lets a non-descendant genesis event clear the
  frontier and reach the defect — the ISS in SCN-002. ADR-008 makes #26529 the
  prerequisite for that re-keying.
- RUL-005 is the implementation-level short-circuit that enforces this invariant as
  an optimization; INV-001 (voting round monotonic along ancestry) and INV-007
  (judge set agreed across deciders) are the neighbouring properties it rests on.
