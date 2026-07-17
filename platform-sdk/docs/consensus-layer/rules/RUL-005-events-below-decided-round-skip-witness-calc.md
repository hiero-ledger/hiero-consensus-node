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
  invariants: [INV-001, INV-007, INV-009]
  decisions: [ADR-008]
  scenarios: [SCN-001, SCN-002]
  heuristics: []
status: holds
confidence: high
provenance: elicitation-2026-06-23; revised from #26319 (sequence-number threshold reverted to nGen)
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
orphan buffer's exit (`DefaultOrphanBuffer`) and approximates graph height. ADR-008
migrated this key to the orphan-buffer sequence number, but that diverged
consensus (an ISS, SCN-002) and was reverted to nGen in #26319; see
[ADR-008](../decisions/ADR-008-replace-ngen-with-sequence-number.md) for why nGen
is retained here.

## Why it holds now

The optimization runs only when a round's judges are decided, and at that point
no event below those judges can become a witness in — or change the outcome of —
any undecided round, so skipping their witness calculation changes nothing. Such
an event can still reach consensus as an ancestor of the judges; only its witness
and round computation is skipped. `ROUND_NEGATIVE_INFINITY` carries this
downstream: it makes `notRelevantForConsensus(e)` true, so the dependent walks —
`lastSee`, `stronglySeeP`, `seeThru`, `firstWitnessS`, `firstSelfWitnessS` —
return `null` when they reach the event, and `witness(x)` rejects it.

The comparison is a **graph-height frontier**: an event structurally below the
decided judges must be classified as below on every node, or nodes disagree on
which events are skipped. nGen provides this because it is parent-derived (one plus
the maximum tracked-parent nGen), so a structurally-low event carries a low nGen
everywhere. The orphan-buffer sequence number does not: it is a release-order
counter, so a structurally-low event received late gets a high, node-local number
and can rank above the frontier on one node and below on another. Keying the
threshold on it diverged consensus — an ISS (SCN-002) — which is why nGen is
retained here.

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
`ROUND_NEGATIVE_INFINITY` — so a mis-classified frontier flips whether that judge
is preserved or recalculated, the mechanism behind the SCN-002 ISS. See INV-001
and SCN-001.

## Change risk

- **Mis-computing the frontier so a still-relevant event is skipped.** If
  `consensusRelevantNGen` is set too high, or `isOlderThanDecidedRoundGeneration`
  stops being a sound lower bound on consensus-relevant events, an event that
  should still be counted as a witness or voter is silently skipped. Fame can
  then be mis-decided — this is an **agreement / liveness defect**, not a
  slowdown.
- **Re-keying the comparison to an ordering that is not a graph height.** The
  frontier is safe only because a structurally-below event ranks below it on every
  node (see *Why it holds now*). A non-height key — most concretely the
  orphan-buffer **sequence number**, a release-order counter — breaks that: the
  same event ranks below the frontier on one node and above it on another,
  diverging consensus. Not hypothetical — SCN-002 records exactly this when ADR-008
  re-keyed the threshold to the sequence number.
- **Removing the short-circuit.** On its own this is "only" a performance
  regression (the forced memoization in `calculateMetadata` also guards against
  deep recursion). But the `ROUND_NEGATIVE_INFINITY` sentinel is
  part of the same machinery that keeps cleared old events from being recomputed
  under a new roster during `recalculateAndVote` — see INV-001 and SCN-001 —
  so changes here must be weighed against that interaction.

Breaking this rule is a **flag for confirmation**. Confirmation looks like
answering: does the frontier remain a sound lower bound — is every event below
it provably unable to affect any undecided round — and does the ordering key still
reflect graph height, so a structurally-below event ranks below the frontier on
every node? If yes, the change is safe; if not, it reintroduces an agreement /
liveness risk, or — if the cross-node height property is lost — an ISS (SCN-002).

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
