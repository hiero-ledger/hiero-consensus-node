---
type: invariant
id: INV-001
title: Voting round is monotonic along ancestry — a parent's round never exceeds its child's
class: safety
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: [SCN-001]
  heuristics: []
status: enforced
source: >
  Baird, "The Swirlds Hashgraph Consensus Algorithm: Fair, Fast, Byzantine
  Fault Tolerance" (SWIRLDS-TR-2016-01), Definition 5.2 — `round(x) = r + i`,
  where `r` is the maximum round of `x`'s parents and `i ∈ {0, 1}`. The
  invariant is a direct consequence: `round(x) ≥ r ≥ round(p)` for every
  parent `p`.
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java — `recalculateAndVote` (the per-round metadata-clearing step that preserves the property across roster changes)
provenance: entry originally added by prior elicitation; revised 2026-06-08 (terminology updated to "voting round")
curated_by: Kelly Greco (@poulok)
---

# INV-001 — Voting round is monotonic along ancestry — a parent's round never exceeds its child's

## Statement

For every event in the hashgraph, `votingRound(child) >= votingRound(parent)` holds for each of its parents. (The *voting round* is the round the algorithm assigns to an event; the implementation stores it in the field named `roundCreated`.) Equivalently, no ancestor may have a voting round strictly greater than any of its descendants. The property holds at all times — including across the per-round metadata reset that prepares for a possible roster change — and applies to every event whose voting round is a real round number (events marked terminal with `ROUND_NEGATIVE_INFINITY` are not constrained).

## Basis

The hashgraph paper (SWIRLDS-TR-2016-01) defines `round` directly in terms of parents. Definition 5.2:

> The round created number (or round) of an event x is defined to be `r + i`, where `r` is the maximum round number of the parents of `x` (or 1 if it has no parents), and `i` is defined to be 1 if `x` can strongly see more than 2n/3 witnesses in round `r` (or 0 if it can't).

The `divideRounds` pseudocode (Figure 5 of the paper) implements this directly: for each event `x`, set `r ← max round of parents of x`, then `x.round ← r + 1` if the strongly-sees condition holds, else `x.round ← r`.

For any parent `p` of `x`, `round(x) = r + i` with `r = max(round of parents of x) ≥ round(p)` and `i ∈ {0, 1}`. Therefore `round(x) ≥ round(p)`. Extending along ancestry — every ancestor is reached by a chain of parent links — gives `round(descendant) ≥ round(ancestor)` for every event in the hashgraph.

The property is a definitional consequence of how the algorithm assigns rounds, not of any implementation choice. Any consistent realization of the algorithm — regardless of how it stores metadata, batches recalculation, or handles roster changes — must preserve it. If a code path produces a state in which an ancestor's voting round exceeds a descendant's, the round assignment is no longer a valid output of `divideRounds`.

The wider algorithm relies on this property: the witness search (which looks for the first event a member created in each round, Definition 5.8) and the fame election (which votes along round boundaries, Figure 6) both depend on round numbers increasing along ancestry. Violating it causes them to stall, and consensus never progresses.

## Change risk

The invariant is at risk whenever the per-event voting round is recalculated for some events while other events keep a previously assigned voting round — that is, whenever metadata-clearing has a carve-out. The danger is that a roster change re-runs the round assignment on the cleared events under a different witness set, possibly producing higher voting-round values, while the carve-out leaves descendants behind at their old, now-smaller values.

Concrete mechanisms that have to be defended against:

- **Exempting a class of events from metadata clearing at the start of a round.** Any class that can be a descendant of another event in the same class is dangerous: the ancestor in the class may be recalculated higher under a new roster while its descendants in the class keep the old value. Historically, judges from the just-decided round were exempted from clearing; this is exactly the shape that broke the invariant (see SCN-001).
- **Caching a voting round across a roster change without also clearing every descendant.** If a cache survives the per-round reset, the recalculation must either invalidate every cached descendant of an event whose round can change, or invalidate nothing — partial invalidation is the failure mode.
- **Reordering the metadata-clearing step relative to the per-event round recalculation.** The clearing step has to run before any event's round is recomputed under the new roster; running them interleaved can leave an ancestor's recomputed round visible to a descendant that has already finished recalculating.
- **Introducing a side path that sets the voting round directly** (for example, a reconnect or replay path that imports round numbers from another source) without re-deriving descendants from the same source.

In each case the property holds by the algorithm regardless of the code, so any code change that produces an ancestor with a higher voting round than a descendant is a defect, not a tradeoff.

## Notes

The invariant is enforced today by the metadata-clearing step at the start of each round in
`ConsensusImpl.recalculateAndVote`. That step clears the voting round (the `roundCreated` field) of every non-terminal event, with one preservation: a judge from the last decided round whose parents are all terminal (`ROUND_NEGATIVE_INFINITY`) keeps its metadata so it can serve as an anchor for descendants' recalculation under the new roster. The preservation is safe precisely because such a judge has no non-terminal ancestor whose recalculated round could rise above it.

The immutable creator-claimed birth round (distinct from the calculated voting round) has its own monotonicity invariant, INV-011.
