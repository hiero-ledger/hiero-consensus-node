---
type: invariant
id: INV-015
title: Non-descendants of the latest decided round's judges get a roundCreated fixed by bootstrap data alone
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
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java — `round` assigns non-descendants the constant `ROUND_NEGATIVE_INFINITY` (the current implementation's bootstrap-independent choice) through its two short-circuits; `recalculateAndVote` preserves a decided-round judge only when all its parents are terminal
provenance: elicitation-2026-07-27; re-diagnosis of SCN-002 (#26529); reworked 2026-07-29 from a fixed terminal value to bootstrap-determinism
curated_by: Kelly Greco (@poulok)
---

# INV-015 — Non-descendants of the latest decided round's judges get a roundCreated fixed by bootstrap data alone

## Statement

Every event that is **not** a descendant of any judge of the latest decided round
is assigned a `roundCreated` that is a deterministic function of **only the data a
node bootstraps the decided round from** — the judges, the latest decided consensus
round, and the roster carried in that round's snapshot — and of nothing below that
round. Because every node bootstraps the round from the same data, whether it has
run continuously or resumed from the snapshot on restart or reconnect, the value is
identical on every node and independent of the event's own history. (The *voting
round* is the round the algorithm assigns an event; the implementation stores it in
`roundCreated` — see INV-001.)

The invariant fixes the *determinism*, not the value. The current algorithm uses the
constant `ROUND_NEGATIVE_INFINITY` (reading no bootstrap data at all), but any value
fixed by the bootstrap data alone — for instance the latest decided consensus round
number — satisfies it equally. Events that *are* descendants of those judges instead
keep a real round, re-derived from the judges.

## Basis

Judge sets are a property of the round, agreed by every node that decides it
(INV-007), and consensus order and timestamp follow from them (INV-002, INV-003).
That agreement must hold across nodes with **different local histories** — in
particular a node that has reconnected.

A reconnected node resumes consensus from a signed state pinned to the latest
decided round. It does not receive, from that snapshot, the events below the decided
round's judges carrying their original round metadata; it relearns those lower
events afterwards, via gossip or PCES, without it. Such a node therefore **cannot
reconstruct** which of those lower events were witnesses in the rounds before the
decided one.

So when a node assigns `roundCreated` to a non-descendant it has only the bootstrap
data to go on, and for its result to match a continuously-running node the value
must be a function of that data alone. Any dependence on the event's structure below
the decided round — which the snapshot omits and a reconnected node cannot reproduce
— would let two nodes compute different values, then different witnesses, then
different judges: a divergence. The particular function is free (a constant, as
today, or a bootstrap datum such as the latest decided consensus round); only its
inputs are constrained. Descendants of the judges are different — they are
re-derivable from the judges the snapshot carries (round assignment is defined
relative to parents, INV-001), so every node computes their real rounds alike.

The property is thus a consequence of judge-set agreement under the reconnect model,
not of any particular implementation: any correct implementation must assign
non-descendants a `roundCreated` fixed by the decided round's bootstrap data.

## Change risk

Any change that lets the `roundCreated` of a non-descendant depend on something other
than the decided round's bootstrap data — the event's below-round structure, or
node-local ordering — breaks the invariant: a reconnected node, lacking that extra
input, computes a different value and consensus diverges. Concrete mechanisms:

- **Two assignment paths that give a non-descendant different values, chosen by
  node-local ordering.** `ConsensusImpl.round` assigns a non-descendant the terminal
  value through the RUL-005 frontier short-circuit, but assigns `ROUND_FIRST` through
  the no-parent branch — correct only for a genuine genesis, when the pending round
  is 1. Which path a no-parent non-descendant takes depends on where the frontier key
  sorts it, which is node-local under a release-order key, so the same event is
  terminal on one node and `ROUND_FIRST` on another. That is the latent bug #26529
  behind SCN-002; the fix is to make the no-parent branch yield the same
  bootstrap-fixed value once the pending round is greater than 1.
- **Deriving the value from the event's below-round ancestry.** A non-descendant's
  value must be fixed by bootstrap data; computing it from parents or witnesses below
  the decided round — which a reconnected node lacks — makes it history-dependent.
- **A side path that imports or caches a below-round `roundCreated` across the
  decided-round boundary** — a reconnect or replay path that carries round numbers
  forward instead of re-deriving them from bootstrap data.

Because the property holds by the algorithm regardless of the code, any of these is a
defect to be stopped, not a tradeoff — its symptom is an ISS (SCN-002).

## Notes

- **Enforced today, with a latent defect.** The current implementation assigns
  non-descendants the constant `ROUND_NEGATIVE_INFINITY` — a valid,
  bootstrap-independent choice — and the RUL-005 frontier, keyed on `nGen`, keeps
  every non-descendant below the frontier so they all take that path and never reach
  the no-parent `ROUND_FIRST` branch (#26529, Change risk). The bug is masked, not
  fixed: re-keying the frontier to the orphan-buffer sequence number without fixing
  #26529 lets a non-descendant clear the frontier on some nodes and take the
  `ROUND_FIRST` branch, so its value differs across nodes — the ISS in SCN-002.
  ADR-008 makes #26529 the prerequisite for that re-keying.
- RUL-005 and the round-assignment code choose the specific value
  (`ROUND_NEGATIVE_INFINITY`) that realizes this invariant today; INV-001 (voting
  round monotonic along ancestry) and INV-007 (judge set agreed across deciders) are
  the neighbouring properties it rests on.
