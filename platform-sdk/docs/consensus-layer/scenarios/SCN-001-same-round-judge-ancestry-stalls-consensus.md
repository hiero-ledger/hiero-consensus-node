---
id: SCN-001
title: A round's judge exempted from clearing has another same-round judge in its ancestry — consensus stalls after roster change
symptoms: [ SYM-001 ]
topics: [ hashgraph ]
kind: near-miss
verification: observed
severity: high
related:
  invariants: [ INV-001 ]
  decisions: [ ]
  scenarios: [ ]
  tests: [ ]
status: verified
provenance: caught by a JRS address-book/roster-change integration test that halted on this stall; JRS test framework subsequently retired
curated_by: Kelly Greco (@poulok)
---

# SCN-001 — A round's judge exempted from clearing has another same-round judge in its ancestry — consensus stalls after roster change

## Summary

When the consensus algorithm prepares for a possible roster change at the start of a new round, events that may
participate in the new round's witness search have their `roundCreated` cleared and recomputed under the new roster.
Under the original rules, every judge from the just-decided round was exempt from clearing. That carve-out interacts
badly with the algorithm: if a round's judge has another same-round judge in its ancestry, a non-judge event between
them can be recalculated up to the next round under the new roster, leaving the descendant judge with a `roundCreated`
smaller than its recalculated ancestor's. This violates `roundCreated(child) >= roundCreated(parent)` (INV-001) and
consensus stops making progress.

## Setup

**Preconditions:**

- The hashgraph contains at least two judges of the same round (call them `J1` and `J2`) such that `J1` is an ancestor
  of `J2` along a chain of events created during that round.
- At least one non-judge event `A` exists on the ancestry chain strictly between `J1` and `J2` — `J1` is an ancestor of
  `A`, `A` is an ancestor of `J2`, and `A` is not a judge.
- The next round is being prepared under a roster `R_new` that differs from the roster `R_old` under which the
  just-decided round was computed. The new roster's witness weighting differs such that `A` strongly sees a
  supermajority of the just-decided round's witnesses under `R_new` even though it did not under `R_old`.
- The per-round metadata-clearing step exempts all judges of the just-decided round from clearing (the pre-fix
  behavior).

**Trigger:** The start-of-round metadata reset that runs in preparation for the new round under `R_new`.

## Sequence

All steps below were confirmed by an engineer who replayed the failing JRS run in the hashgraph GUI — starting from
the saved state and the PCES events that fed it — and watched each transition occur on the actual graph that triggered
the halt.

1. Round `N` reaches consensus under `R_old`. The set of round-`N` judges is fixed; `J1` and `J2` are both judges, and
   `J1` is an ancestor of `J2`. The non-judge event `A` sits on the ancestry chain between them, with
   `roundCreated(A) = N` and `roundCreated(J2) = N`.
2. Round `N+1` is prepared, possibly under `R_new`. The platform clears the metadata of every eligible non-terminal
   event so that `roundCreated` can be recalculated under the new roster.
3. Under the pre-fix carve-out, both `J1` and `J2` are exempt from clearing — they retain `roundCreated = N`. `A` is not
   a judge, so its metadata is cleared and its `roundCreated` is set back to undefined.
4. Recalculation re-derives `roundCreated(A)` under `R_new`. Because `R_new` weights witnesses differently, `A` now
   strongly sees a supermajority of round-`N` witnesses that it did not strongly see under `R_old`. `A` is therefore
   assigned `roundCreated(A) = N+1`.
5. `J2` is a descendant of `A`, but `J2` was exempt from clearing and still carries `roundCreated(J2) = N`. The state
   now has `roundCreated(A) = N+1 > roundCreated(J2) = N` with `A` an ancestor of `J2`. INV-001 is violated.
6. Round-`(N+1)` witness search and fame election rely on the monotonicity property. With the invariant broken, no new
   round reaches consensus. The network halts. (observed — the JRS address-book/roster-change integration test failed at
   this step)

## Observable signature

After the restart that applies the new roster, the nodes' platform status does not advance to `ACTIVE`. It stays in
`CHECKING` because consensus is not being reached across the network (SYM-001). In the JRS run that caught this, the
integration test failed on exactly this stall — the network never reached `ACTIVE` post-restart.

The bug is intermittent: it only manifests when a specific graph/roster combination arises — two same-round judges on a
single ancestry chain, with the new roster weighting witnesses such that an intermediate non-judge gets promoted. Runs
that drive roster changes will not hit it deterministically. The JRS catch was an intermittent failure in a
roster-change test, not a deterministic reproduction.

SYM-001 has many possible causes; the discriminator for this scenario is the timing (immediately after a roster-change
restart) plus the graph shape under **Setup**. Deeper log/metric signatures beyond the status stall have not been
catalogued here — the JRS run output was not preserved against this entry.

## Contributing factors

- **The carve-out treated "already-decided" as "safe to leave alone."** Judges of the just-decided round were considered
  immutable because their fame was already resolved, so clearing them seemed wasteful and possibly destructive of the
  decided ordering. The carve-out was correct for the judge's role in the decided round, but it ignored the judge's
  other role as an ancestor whose `roundCreated` constrains its descendants.
- **The monotonicity property was load-bearing but not surfaced in the implementation docs.** It is a definitional
  consequence of the hashgraph paper (see INV-001), and the witness search and fame election rely on it, but no
  consensus-layer doc called it out as a load-bearing maintenance invariant — so a carve-out that broke it could be
  added without anyone making the connection. INV-001 now captures it explicitly so a future carve-out has something
  concrete to be checked against.
- **Roster changes loosen the assumption that yesterday's round numbers will be reproduced today.** The
  pre-roster-change algorithm could rely on `A` keeping its old `roundCreated` because witnesses and supermajorities did
  not change between rounds. Once the roster is allowed to change, any non-terminal event's round becomes contingent on
  the next roster, and only descendants that are themselves recalculated can be made consistent with that.
- **No invariant check existed on the recalculation step.** Nothing asserted
  `roundCreated(child) >= roundCreated(parent)` after the per-round metadata reset, so a violation could exist for
  arbitrarily long before manifesting as a stall.

## Mitigation

Implemented in `consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java`
in `recalculateAndVote`. A judge from the just-decided round keeps its metadata only if all of its parents have
`roundCreated = ROUND_NEGATIVE_INFINITY` (i.e., it has no non-terminal ancestor, and therefore no other judge from the
same round in its ancestry). Any judge with a non-terminal ancestor — which is the case when another same-round judge is
in its ancestry — has its metadata cleared and its `roundCreated` recalculated under the new roster like any other
event.

This is sufficient to uphold INV-001 across roster changes. At least one judge per same-round ancestry chain (the
earliest one — by definition not a descendant of another same-round judge) is preserved as an anchor for descendants'
recalculation. The decided round's judge set and consensus ordering are not revisited; only how cleared judges later
classify (as a same-round witness or a next-round witness) is affected.

A side effect of the mitigation: a cleared judge no longer appears as a judge in tooling that inspects
post-recalculation metadata. After clearing and recalculation, it is classified as a witness in the same round (
non-famous, because the decided round's fame election is not re-run) or as a witness in the next round (if the new
roster promotes it). The decided round's outcome is preserved; only the post-recalculation classification of that event
differs.

## Verification

`observed`. The bug halted at least one JRS integration test in the subset that exercised address-book changes (now
called roster changes). The state and PCES events from that failing run were preserved long enough for an engineer to
replay them in the hashgraph GUI and step through the graph; every step in **Sequence** above was confirmed visually on
the actual graph that produced the halt, not inferred from the algorithm. The structural argument from INV-001 predicts
the halt under the conditions in **Setup**; the GUI replay confirmed that exactly that sequence is what occurred.

Verification is `observed` rather than `test-reproduced` because no test deterministically reproduces this scenario; the
JRS catch and the GUI replay together are the empirical evidence.

A deterministic test for this scenario does not exist today and, as far as is known, never did — even the JRS catch was
an intermittent failure rather than a targeted reproduction. Establishing one would require a hand-constructed test
(most naturally in `consensus-hashgraph-impl`) that builds the exact J1 → A → J2 graph with two same-round judges on a
single ancestry chain, drives a roster change crafted to promote the intermediate non-judge under the new roster, and
asserts that the pre-fix carve-out produces an ancestor/descendant `roundCreated` inversion while the current code does
not. That same test would be the natural place to attach a direct assertion of INV-001.

## Open questions

- Is a deterministic test of the J1 → A → J2 + roster-change shape worth hand-crafting? — feasible in
  `consensus-hashgraph-impl` (build the graph and the crafted roster change directly), but not currently a priority
  because the fix is in place. If a future run reproduces the stall, the graph and roster from that run can be lifted
  directly into a unit test with no hand-crafting needed.
- Are there other shapes of carve-out in the metadata-clearing step that could re-introduce the same class of
  violation? — answered by an audit of every event class that is exempted from clearing in `recalculateAndVote`,
  checking whether any can be an ancestor of another in the same class.

## Notes

2026-05-20 — created — Kelly Greco. Origin: consensus-roster-change bug-fix work.
