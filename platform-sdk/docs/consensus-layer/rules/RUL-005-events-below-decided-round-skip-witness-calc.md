---
type: rule
id: RUL-005
title: Events below the latest decided round's judges are excluded from witness calculation
class: protocol
topics: [hashgraph]
components:
  - consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java
  - consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusRounds.java
  - consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java
related:
  invariants: [INV-001, INV-007, INV-009, INV-015]
  decisions: [ADR-008]
  scenarios: [SCN-001, SCN-002]
  heuristics: []
status: holds
confidence: high
provenance: elicitation-2026-06-23; revised from #26319 (sequence-number threshold reverted to nGen); re-diagnosed 2026-07-27 (SCN-002 root cause is #26529, not the key; frontier safety rests on INV-015)
curated_by: Kelly Greco (@poulok)
---

# RUL-005 — Events below the latest decided round's judges are excluded from witness calculation

## Statement

When `ConsensusImpl.round(x)` runs, any event whose nGen (non-deterministic
generation) is below the latest decided round's judges — and any event already
marked consensus — is assigned `roundCreated = ROUND_NEGATIVE_INFINITY` and
returns immediately, skipping the witness check and the strongly-seeing
computation.

## Context

Deciding whether an event is a witness requires `round(x)`, which in the general
case counts, per member, the witnesses in the parent round that `x` strongly
sees — a per-member, super-majority "generalized dot product" walk over the DAG
(`ConsensusImpl.stronglySeeP`, driven from `round`). That walk is the dominant
cost of the algorithm. This rule is the short-circuit that keeps it off the part
of the graph that can no longer influence which rounds decide.

The frontier is `consensusRelevantNGen` — the minimum nGen among the judges of
the latest decided round, set in `ConsensusRounds.currentElectionDecided` from
`RoundElections.getMinNGen()` and, on snapshot load, in
`ConsensusImpl.checkInitJudges` via `setConsensusRelevantNGen`. The test is
`ConsensusRounds.isOlderThanDecidedRoundGeneration(x)`, which is just
`consensusRelevantNGen > x.getNGen()`. nGen is assigned once per event at the
orphan buffer's exit (`DefaultOrphanBuffer`). ADR-008 migrated this key to the
orphan-buffer sequence number; that exposed a latent round-assignment bug (#26529)
and was reverted to nGen in #26319 as a stopgap. The frontier is a valid
short-circuit on either key — see *Why it holds now* — so once #26529 is fixed the
threshold can key on the sequence number; see
[ADR-008](../decisions/ADR-008-replace-ngen-with-sequence-number.md).

## Why it holds now

The optimization runs only when a round's judges are decided, and at that point
no event below those judges can become a witness in — or change the outcome of —
any undecided round, so skipping their witness calculation changes nothing. Such
an event can still reach consensus as an ancestor of the judges; only its witness
and round computation is skipped. `ROUND_NEGATIVE_INFINITY` carries this
downstream: it makes `notRelevantForConsensus(e)` true, so the dependent walks —
`lastSee`, `stronglySeeP`, `seeThru`, `firstWitnessS`, `firstSelfWitnessS` —
return `null` when they reach the event, and `witness(x)` rejects it.

The short-circuit **enforces INV-015**: every event that is not a descendant of the
decided round's judges is made `ROUND_NEGATIVE_INFINITY`, so it cannot become a
witness in any undecided round. The frontier is a **shortcut**, not the whole of
that enforcement — it catches a non-descendant that sorts below the frontier
without walking to the bottom of the graph; a non-descendant that sorts *above* the
frontier is still made terminal, by inheriting `ROUND_NEGATIVE_INFINITY` from its
parents (the second short-circuit, below). It is correct on **any ordering key for
which a judge's descendant outranks the judge**: a descendant then always sorts
above the frontier and is recalculated, while non-descendants collapse to terminal.
nGen has this property — it is parent-derived (one plus the maximum tracked-parent
nGen), so a descendant's nGen exceeds its ancestor judge's — and so does the
orphan-buffer sequence number, since a parent is released, and numbered, before its
child. The frontier is therefore sound on either key; the SCN-002 ISS came not from
the key but from a latent bug (#26529) it exposed — a parentless non-descendant
assigned a real round instead of terminal — which ADR-008 makes the prerequisite
for re-keying to the sequence number.

nGen's own defect (a reset to 1 when an event's parents are already ancient,
ADR-008) is benign for this comparison: the reset only ever *under*-counts an
event's height, pushing an affected event *further* below the frontier, never
above it. The `x.isConsensus()` half of the guard covers events that have already
reached consensus, which are likewise irrelevant going forward.

`round(x)` assigns `ROUND_NEGATIVE_INFINITY` through two short-circuits: this
frontier check (`isOlderThanDecidedRoundGeneration`) and, separately, the case
where all of `x`'s non-ancient parents are already `ROUND_NEGATIVE_INFINITY`. Both
also feed the metadata-preservation carve-out in `recalculateAndVote` — a
decided-round judge keeps its round only when all its parents are
`ROUND_NEGATIVE_INFINITY`. So if a non-descendant that should be terminal is instead
given a real round (the #26529 defect), a judge carrying that event in its parentage
is wrongly recalculated instead of preserved — the mechanism behind the SCN-002 ISS.
See INV-001, INV-015, and SCN-001.

## Change risk

- **Mis-computing the frontier so a still-relevant event is skipped.** If
  `consensusRelevantNGen` is set too high, or `isOlderThanDecidedRoundGeneration`
  stops being a sound lower bound on consensus-relevant events, an event that
  should still be counted as a witness or voter is silently skipped. Fame can
  then be mis-decided — this is an **agreement / liveness defect**, not a
  slowdown.
- **Re-keying the frontier without preserving INV-015.** The frontier is safe on
  any key for which a judge's descendant outranks the judge — nGen and the sequence
  number both qualify (see *Why it holds now*) — *provided* every non-descendant of
  the decided judges still collapses to `ROUND_NEGATIVE_INFINITY`. The hazard is not
  the key's units but an unfixed #26529: keyed on the sequence number, a parentless
  non-descendant cleared the frontier and reached the branch that assigns a real
  round, diverging consensus (SCN-002). Re-key only once #26529 is fixed (ADR-008).
- **Removing the short-circuit.** On its own this is "only" a performance
  regression (the forced memoization in `calculateMetadata` also guards against
  deep recursion). But the `ROUND_NEGATIVE_INFINITY` sentinel is
  part of the same machinery that keeps cleared old events from being recomputed
  under a new roster during `recalculateAndVote` — see INV-001 and SCN-001 —
  so changes here must be weighed against that interaction.

Breaking this rule is a **flag for confirmation**. Confirmation looks like
answering: does the frontier remain a sound lower bound — is every event below it
provably unable to affect any undecided round — and does INV-015 still hold, i.e. is
every event that is not a descendant of the decided judges still made
`ROUND_NEGATIVE_INFINITY` under the key in use? If yes, the change is safe; if not,
it reintroduces an agreement / liveness risk, or an ISS (SCN-002).

## Notes

- The safety of the short-circuit rests on the immutability of a decided round:
  a decided election never flips (INV-009), so a decided round's judge set is
  fixed once decided (INV-007) and no event below those judges can still become a
  famous witness. The underlying theorem is stated and proved in the
  `ConsensusImpl` class JavaDoc.
- INV-001 (voting round monotonic along ancestry) and SCN-001 (same-round judge
  ancestry stalls consensus) both concern how old events' rounds are frozen or
  cleared across roster changes; the sentinel assigned here is part of that
  mechanism.
- The property this short-circuit enforces — every non-descendant of the decided
  judges is terminal — is INV-015. An earlier reading of SCN-002 held that the
  frontier had to be keyed on a graph height and that the sequence number was
  fundamentally unsafe here; re-diagnosis (2026-07-27) showed the SCN-002 ISS was a
  latent round-assignment bug (#26529), not a property of the key, and that the
  frontier is sound on either key once INV-015 is upheld.
