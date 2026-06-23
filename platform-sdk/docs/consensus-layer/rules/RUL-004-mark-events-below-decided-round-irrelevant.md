---
type: rule
id: RUL-004
title: Events below the latest decided round's judges are excluded from consensus calculation
class: protocol
topics: [hashgraph]
components:
  - consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java
  - consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusRounds.java
  - consensus-model/src/main/java/org/hiero/consensus/model/event/NonDeterministicGeneration.java
related:
  invariants: [INV-001]
  decisions: []
  scenarios: [SCN-001]
  heuristics: []
status: holds
confidence: high
provenance: elicitation-2026-06-23
curated_by: Kelly Greco (@poulok)
---

# RUL-004 — Events below the latest decided round's judges are excluded from consensus calculation

## Statement

When `ConsensusImpl.round(x)` runs, any event older than the generation of the
latest decided round's judges — and any event already marked consensus — is
assigned `roundCreated = ROUND_NEGATIVE_INFINITY` and returns immediately,
skipping the witness check and the strongly-seeing computation.

## Context

Deciding whether an event is a witness requires `round(x)`, which in the general
case counts, per member, the witnesses in the parent round that `x` strongly
sees — a per-member, super-majority "generalized dot product" walk over the DAG
(`ConsensusImpl.stronglySeeP`, line 1054; driven from `round`, lines ~1199–1213).
That walk is the dominant cost of the algorithm. This rule is the short-circuit
that keeps it off the part of the graph that can no longer influence consensus.

The frontier is `consensusRelevantNGen` — the minimum `NGen` among the judges of
the latest decided round, set in `ConsensusRounds.currentElectionDecided`
(`ConsensusRounds.java:144`, reached from `roundDecided`) and, on snapshot load,
in `checkInitJudges` (`ConsensusImpl.java` lines ~474–477). The test is
`ConsensusRounds.isOlderThanDecidedRoundGeneration(x)`, which is just
`consensusRelevantNGen > x.getNGen()` (`ConsensusRounds.java:119`). `NGen` is
`NonDeterministicGeneration`: a locally-computed, non-deterministic generation
used for topological ordering and "higher in the hashgraph" comparisons, not the
deterministic generation and not `birthRound`.

## Why it holds now

The short-circuit rests on the immutability theorem stated and proved in the
`ConsensusImpl` class JavaDoc (lines ~61–84 and ~117–124; described there as a
theorem not included in SWIRLDS-TR-2016-01): once a round `R`'s fame is decided,
its set of famous witnesses (judges) is final, and any witness discovered at or
below `R` afterward is instantly decided not famous. The optimization keys off
exactly that — the latest round being *decided* — not off any separate test for
an `R+2` event. (The theorem is usually stated with an explicit "an `R+2` event
exists" hypothesis, but decidedness *entails* that rather than depending on it: a
round's fame cannot be decided until a round `R+2` witness casts the counting
vote that settles the election, so the hypothesis already holds whenever the
optimization fires.) Consequently an event below the latest decided round's
judges can neither become a famous witness nor change the outcome of any
undecided round, so its exact round number is irrelevant to future consensus.
`ROUND_NEGATIVE_INFINITY` is therefore a safe value to assign, and via
`notRelevantForConsensus(e)` (line 895) the dependent walks skip it —
`lastSee`, `stronglySeeP`, `seeThru`, `firstWitnessS`, `firstSelfWitnessS` all
return `null` when they reach such an event, and `witness(x)` (line 913) rejects
it.

The frontier is keyed off `NGen`, and that is sound because the check needs only
one fact: whether `x` sits lower in the hashgraph than the decided round's
judges. `NGen` is assigned as `max(parent NGen) + 1`
(`NonDeterministicGeneration.assignNGen`), so an event's `NGen` is always
strictly greater than any of its ancestors' — a faithful "higher or lower in the
graph" indicator, which is exactly and only the property this comparison
consults. `NGen` values are non-deterministic across nodes by design, but that
non-determinism is in the absolute numbering, not in the graph-height ordering
the check relies on; topological ordering is `NGen`'s sole sanctioned use
(`NonDeterministicGeneration` class JavaDoc). Consensus output must never depend
on `NGen`, and it does not here: the comparison only gates
whether the expensive witness and strongly-seeing work is skipped for events
whose consensus fate is already settled. The `x.isConsensus()` half of the guard
covers events that have already reached consensus, which are likewise irrelevant
going forward.

## Change risk

- **Mis-computing the frontier so a still-relevant event is marked irrelevant.**
  If `consensusRelevantNGen` is set too high, or
  `isOlderThanDecidedRoundGeneration` stops being a sound lower bound on
  consensus-relevant events, an event that should still be counted as a witness
  or voter is silently skipped. Fame can
  then be mis-decided — this is an **agreement / liveness defect**, not a
  slowdown.
- **Changing `NGen` so it stops reflecting graph height.** The frontier check
  means "below the decided judges" only because `assignNGen` makes every event's
  `NGen` strictly exceed its ancestors'
  (`NonDeterministicGeneration.assignNGen`). A change that let an event's `NGen`
  fall to or below an ancestor's would break that meaning and could mark a
  still-relevant event — the same agreement/liveness failure as a mis-computed
  frontier, reached through a different file.
- **Removing the short-circuit.** On its own this is "only" a performance
  regression (the forced memoization in `calculateMetadata`, line 489, also
  guards against deep recursion). But the `ROUND_NEGATIVE_INFINITY` sentinel is
  part of the same machinery that keeps cleared old events from being recomputed
  under a new roster during `recalculateAndVote` — see INV-001 and SCN-001 —
  so changes here must be weighed against that interaction.

Breaking this rule is a **flag for confirmation**. Confirmation looks like
answering: does the frontier remain a sound lower bound — is every event below
it provably unable to affect any undecided round — and does `NGen` still reflect
graph height? If yes, the change is safe; if not, it reintroduces an
agreement / liveness risk.

## Notes

- Rests on the immutability theorem in the `ConsensusImpl` class JavaDoc. That
  theorem is a permanent, proof-backed property and a candidate to be cataloged
  as an invariant; if it is, this rule should reference it under
  `related.invariants` alongside INV-001.
- INV-001 (`roundCreated` monotonic along ancestry) and SCN-001 (same-round
  judge ancestry stalls consensus) both concern how old events' rounds are
  frozen or cleared across roster changes; the sentinel assigned here is part of
  that mechanism.
