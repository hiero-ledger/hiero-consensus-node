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
  invariants: [INV-001]
  decisions: [ADR-008]
  scenarios: [SCN-001]
  heuristics: []
status: holds
confidence: high
provenance: elicitation-2026-06-23
curated_by: Kelly Greco (@poulok)
---

# RUL-005 — Events below the latest decided round's judges are excluded from witness calculation

## Statement

When `ConsensusImpl.round(x)` runs, any event whose sequence number is below the
latest decided round's judges — and any event already marked consensus — is
assigned `roundCreated = ROUND_NEGATIVE_INFINITY` and returns immediately,
skipping the witness check and the strongly-seeing computation.

## Context

Deciding whether an event is a witness requires `round(x)`, which in the general
case counts, per member, the witnesses in the parent round that `x` strongly
sees — a per-member, super-majority "generalized dot product" walk over the DAG
(`ConsensusImpl.stronglySeeP`, driven from `round`). That walk is the dominant
cost of the algorithm. This rule is the short-circuit that keeps it off the part
of the graph that can no longer influence which rounds decide.

The frontier is `consensusRelevantSeqNum` — the minimum sequence number among
the judges of the latest decided round, set in
`ConsensusRounds.currentElectionDecided` (`ConsensusRounds.java:145`, reached
from `roundDecided`) and, on snapshot load, in `checkInitJudges`
(`ConsensusImpl.java` lines ~474–477). The test is
`ConsensusRounds.isOlderThanDecidedRoundSeqNum(x)`, which is just
`consensusRelevantSeqNum > x.getSequenceNumber()` (`ConsensusRounds.java:121`).
The sequence number is assigned once per event at the orphan buffer's exit
(`DefaultOrphanBuffer`); per [ADR-008](../decisions/ADR-008-replace-ngen-with-sequence-number.md)
it replaced the former non-deterministic generation (`nGen`) as the algorithm's
local ordering key.

## Why it holds now

The optimization runs only when a round's judges are decided, and at that point
no event below those judges can become a witness in — or change the outcome of —
any undecided round, so skipping their witness calculation changes nothing. Such
an event can still reach consensus as an ancestor of the judges; only its witness
and round computation is skipped. `ROUND_NEGATIVE_INFINITY` carries this
downstream: it makes `notRelevantForConsensus(e)` (line 895) true, so the
dependent walks — `lastSee`, `stronglySeeP`, `seeThru`, `firstWitnessS`,
`firstSelfWitnessS` — return `null` when they reach the event, and `witness(x)`
(line 913) rejects it.

The frontier is compared using the event **sequence number**, and that is sound
because the check needs only one fact: whether `x` falls *earlier in topological
order* than the decided round's judges. The sequence number is a single counter
incremented as each event leaves the orphan buffer (`DefaultOrphanBuffer`); the
buffer releases an event only once its parents are present or ancient, and the
counter only ever increases, so every ancestor still in the graph carries a
strictly smaller sequence number than its descendants. It is a valid topological
ordering (`PlatformEvent.getSequenceNumber`) — which is exactly and only the
property this comparison consults. Unlike the `nGen` it replaced (ADR-008), the
sequence number is **not** a graph height: siblings and unrelated events get
distinct numbers by release order. But the short-circuit never needed height,
only the ancestry ordering, which the sequence number preserves and — unlike
`nGen`, which could reset to `1` when a node fell behind — never resets.

The sequence number is local to a node and non-deterministic across the network
(ADR-008: it reflects this node's orphan-buffer release order and is not
persisted across restarts). Consensus output must never depend on it, and it does
not here: the comparison only gates whether the expensive witness and
strongly-seeing work is skipped for events whose consensus fate is already
settled. The `x.isConsensus()` half of the guard covers events that have already
reached consensus, which are likewise irrelevant going forward.

## Change risk

- **Mis-computing the frontier so a still-relevant event is skipped.** If
  `consensusRelevantSeqNum` is set too high, or `isOlderThanDecidedRoundSeqNum`
  stops being a sound lower bound on consensus-relevant events, an event that
  should still be counted as a witness or voter is silently skipped. Fame can
  then be mis-decided — this is an **agreement / liveness defect**, not a
  slowdown.
- **Changing how the sequence number is assigned so it stops being a valid
  topological ordering.** The frontier check means "below the decided judges"
  only because the orphan buffer numbers an event after its parents and the
  counter never resets, so an ancestor's number is always smaller
  (`DefaultOrphanBuffer`). A change that let an event's sequence number fall to
  or below an ancestor's — for instance reintroducing the parent-derived reset
  that motivated ADR-008 — would break that meaning and could skip a
  still-relevant event: the same agreement/liveness failure as a mis-computed
  frontier, reached through a different module.
- **Removing the short-circuit.** On its own this is "only" a performance
  regression (the forced memoization in `calculateMetadata`, line 489, also
  guards against deep recursion). But the `ROUND_NEGATIVE_INFINITY` sentinel is
  part of the same machinery that keeps cleared old events from being recomputed
  under a new roster during `recalculateAndVote` — see INV-001 and SCN-001 —
  so changes here must be weighed against that interaction.

Breaking this rule is a **flag for confirmation**. Confirmation looks like
answering: does the frontier remain a sound lower bound — is every event below
it provably unable to affect any undecided round — and does the sequence number
still reflect topological order? If yes, the change is safe; if not, it
reintroduces an agreement / liveness risk.

## Notes

- The claim that a decided round's famous-witness set is final rests on the
  immutability theorem in the `ConsensusImpl` class JavaDoc — a permanent,
  proof-backed property and a candidate to be cataloged as an invariant; if it
  is, this rule should reference it under `related.invariants` alongside INV-001.
- INV-001 (voting round monotonic along ancestry) and SCN-001 (same-round judge
  ancestry stalls consensus) both concern how old events' rounds are frozen or
  cleared across roster changes; the sentinel assigned here is part of that
  mechanism.
- The frontier was keyed off `nGen` until [ADR-008](../decisions/ADR-008-replace-ngen-with-sequence-number.md)
  replaced `nGen` with the event sequence number and removed
  `NonDeterministicGeneration`; this entry was re-grounded on the sequence
  number accordingly.
