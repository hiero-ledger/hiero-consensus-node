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
last_reviewed: TBD
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
cost of the algorithm. This rule is the short-circuit that returns
`ROUND_NEGATIVE_INFINITY` for events that **must not** influence the witnesses of
any undecided round — the events that are not descendants of the latest decided
round's judges (INV-015) — so their witness and round computation is skipped.

The frontier is `consensusRelevantNGen` — the minimum nGen among the judges of
the latest decided round, set in `ConsensusRounds.currentElectionDecided` from
`RoundElections.getMinNGen()` and, on snapshot load, in
`ConsensusImpl.checkInitJudges` via `setConsensusRelevantNGen`. The test is
`ConsensusRounds.isOlderThanDecidedRoundGeneration(x)`, which is just
`consensusRelevantNGen > x.getNGen()`. nGen is assigned once per event at the
orphan buffer's exit (`DefaultOrphanBuffer`). ADR-008 migrated this key to the
orphan-buffer sequence number; that exposed a latent round-assignment bug (#26529,
SCN-002) and was reverted to nGen in #26319 as a stopgap. The bug is now fixed and
the frontier is a valid short-circuit on either key — see *Why it holds now* — so
the conversion to the sequence number is unblocked but not yet made; see
[ADR-008](../decisions/ADR-008-replace-ngen-with-sequence-number.md).

## Why it holds now

The short-circuit runs only once a round's judges are decided. From that point every
event that is not a descendant of those judges must carry a `roundCreated` that every
node computes alike from the decided round's bootstrap data (INV-015); this
implementation uses the constant `ROUND_NEGATIVE_INFINITY`. No such event can become
a witness in — or change the outcome of — any undecided round, so skipping its witness
and round computation is safe; it can still reach consensus as an ancestor of the
judges. That sentinel carries downstream: it makes `notRelevantForConsensus(e)` true, so the
dependent walks — `lastSee`, `stronglySeeP`, `seeThru`, `firstWitnessS`,
`firstSelfWitnessS` — return `null` at the event and `witness(x)` rejects it.

The frontier is a **shortcut**, not the whole of that enforcement. A non-descendant
below the frontier is caught here; one above it is still made terminal by inheriting
`ROUND_NEGATIVE_INFINITY` from its parents (the second short-circuit, below), and
consensus events by the `x.isConsensus()` half of the guard. So the frontier is
correct on any key for which a judge's descendant outranks the judge — true of nGen
(a parent-derived height) and of the orphan-buffer sequence number (a parent is
numbered before its child), as ADR-008 works through. And because parent-propagation
reaches the same terminal value without entering the strongly-see walk, the frontier
is not what averts the dominant cost.

Nor does it carry correctness: the no-parent branch is terminal on its own for a
non-descendant, so nothing depends on the frontier to keep an event with no
non-ancient parents from being assigned a real round — the failure SCN-002 records.
It is redundant for correctness as well as for cost (see Notes). The nGen reset for
such an event is benign here: it only ever *under*-counts height, pushing the event
further below the frontier, never above.

`round(x)` assigns `ROUND_NEGATIVE_INFINITY` through two short-circuits: this
frontier check (`isOlderThanDecidedRoundGeneration`) and, separately, the case
where all of `x`'s non-ancient parents are already `ROUND_NEGATIVE_INFINITY`. Both
also feed the metadata-preservation carve-out in `recalculateAndVote` — a
decided-round judge keeps its round only when all its parents are
`ROUND_NEGATIVE_INFINITY`. So if a non-descendant that should be terminal is instead
given a real round — as the #26529 defect did — a judge carrying that event in its
parentage is wrongly recalculated instead of preserved: the mechanism behind the
SCN-002 ISS. See INV-001, INV-015, and SCN-001.

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
  the decided judges still resolves to the same value on every node (INV-015). The
  hazard is not the key's units: a key that lets a non-descendant clear the frontier
  is safe only while every other assignment path is terminal for it, which is what
  SCN-002 caught when it was not. Re-keying to the sequence number is unblocked
  (ADR-008); INV-015 remains the property any new key must preserve.
- **Removing the short-circuit.** The short-circuit is redundant (see Notes) and
  removing it is safe; the forced memoization in `calculateMetadata` already guards
  against deep recursion. The `ROUND_NEGATIVE_INFINITY` sentinel it assigns is
  separately part of the machinery that keeps cleared old events from being
  recomputed under a new roster during `recalculateAndVote` (INV-001, SCN-001), so
  weigh changes against that interaction.

Breaking this rule is a **flag for confirmation**. Confirmation looks like
answering: does the frontier remain a sound lower bound — is every event below it
provably unable to affect any undecided round — and does INV-015 still hold: does
every non-descendant of the decided judges still resolve to the same bootstrap-fixed
value on every node (here `ROUND_NEGATIVE_INFINITY`), rather than one that varies with
node-local ordering? If yes, the change is safe; if not, it reintroduces an
agreement / liveness risk, or an ISS (SCN-002).

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
- **The short-circuit is redundant for correctness.** Every non-descendant reaches
  the terminal value without it — via `x.isConsensus()`, parent-propagation, or the
  no-parent branch, none entering the strongly-see walk. The check could therefore
  be removed (memoization already bounds recursion depth); it is kept as a
  short-circuit, not as the thing that enforces INV-015.
