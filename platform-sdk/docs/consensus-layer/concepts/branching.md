---
type: concept
title: Branching
last_reviewed: 2026-06-08
---

# Branching

## Definition

A creator *branches* (the original whitepaper calls this *forking*)
when it signs two events `x` and `y` — neither a self-ancestor of the
other — whose birth rounds differ by at most the non-ancient window.
An honest creator's events form a single chain under the self-parent
edge; a branched creator's events form a tree.

Branching is the Byzantine behaviour that the entire strong-seeing
machinery exists to tolerate. Without the possibility of branching,
ordinary "seeing" would be enough to drive consensus; strong-seeing
([`strongly-seeing.md`](strongly-seeing.md)) is the stronger relation
that keeps virtual voting safe when up to `<n/3` of the creators
branch.

## Mechanics

**Why a Byzantine creator might branch.** Branching is a form of
equivocation: the creator shows different views of its own history to
different peers. The intent is to make different honest peers compute
different things (different round-created, different votes, different
witness sets) so the consensus order disagrees across nodes. In a
naive consensus design, branching is fatal; classical async BFT
defends against the analogous attack by requiring receipt traffic on
every vote. The hashgraph's defence is structural — built into the
strong-seeing relation, the witness set, and the judge merge — and
requires no extra messages.

**How the algorithm tolerates branching.** Three layers absorb the
attack:

1. **Strong-seeing requires agreement among paths.** The
   2020 algorithm in `ConsensusImpl` (see
   [`strongly-seeing.md`](strongly-seeing.md) for the full mechanics)
   says `x` strongly sees a witness by creator `m` only when a
   super-majority weight of paths agree on *which* witness by `m` is
   canonical (one path per creator `m₂` — the resolution
   `seeThru(x, m, m₂)`). If `m` branches and the paths through
   different creators split between branches, no single witness by
   `m` collects super-majority agreement, so `x` strongly sees
   nothing by `m`. If one branch happens to propagate far enough
   that a super-majority weight of paths converge on it, the
   algorithm admits that one branch's witness as strongly seen —
   safely, because by the Strongly Seeing Lemma no consistent
   hashgraph can also strongly see the other branch.
2. **Multiple witnesses per creator per round are allowed.** A
   branched creator can produce more than one witness in the same
   round — there is no rule that says a creator has exactly one
   witness per round. Each witness participates in fame voting on
   its own merits. Both branches of a branched creator may even be
   voted famous in the same round.
3. **The judge merge collapses branched famous witnesses.**
   `RoundElections.findAllJudges` deduplicates famous witnesses to
   one per creator before the judges fix consensus order; when a
   creator branched and produced multiple famous witnesses in the
   round, `RoundElections.uniqueFamous` picks one deterministically
   (the witness with the minimum hash). The result is that branching
   never inflates a creator's voice in the consensus order.

**Branch detection is observability, not defense.** The intake
pipeline scans for branches, but its only consumer logs and meters
(see *In current code*); nothing flags a branching creator to the
algorithm, punishes it, or evicts it. Tolerance is structural: the
agreement requirement in `stronglySeeP`, the allowance for multiple
witnesses per creator per round, and the deterministic judge merge
are sufficient to keep the algorithm safe under any pattern of
branching by up to `<n/3` of the creators (by weight) in any given
voting round.

## Why this gives Byzantine fault tolerance

The Strongly Seeing Lemma (whitepaper Lemma 5.12) is the formal
statement: if `(x, y)` is a branch and `x` is strongly seen by some
event in hashgraph `A`, then `y` is not strongly seen by any event in
any hashgraph `B` consistent with `A`. The proof is a quorum-
intersection argument — both strongly-seeing intermediary sets
contain events by super-majority weight, so they overlap on more
than `n/3` of the weight, so the overlap contains at least one
honest creator. An honest creator does not branch, so any of its
events in the overlap must be on a single self-parent chain, which
forces the two strongly-seen witnesses to be the same.

Every safety result in the algorithm (round assignment, virtual
voting, consensus order) reduces to this lemma. Because of it, two
honest nodes with consistent hashgraphs can never disagree about
which witness by a branched creator they are counting a vote from,
even when they have observed different subsets of the branches.

## Example

Node A is Byzantine. At its third self-event A signs two children of
the same self-parent: `y₁` carrying transaction `t₁`, and `y₂`
carrying transaction `t₂` (different, possibly contradictory). A
gossips `y₁` to B and `y₂` to C and D.

For a while, B's hashgraph contains `y₁` and not `y₂`; C and D's
hashgraphs contain `y₂` and not `y₁`. Each is locally a valid
hashgraph; neither node knows yet that A has branched.

As gossip continues, eventually some honest node sees both `y₁` and
`y₂`. From `x` in that node's hashgraph, the path through creator
`m₂` may reach a witness on the `y₁` branch while the path through
creator `m₃` reaches one on the `y₂` branch — the paths disagree
about which event is the "latest by A". The strong-seeing test
(`stronglySeeP`) only succeeds for a witness by A if a super-
majority weight of paths agree on which witness by A is canonical.
If gossip propagated `y₁` and `y₂` roughly evenly, no super-majority
converges and A is effectively un-strongly-seen.

Both `y₁` and `y₂` may still become witnesses (they are the first
events by A in their respective round-created), and both may even be
voted famous. When the round decides, `RoundElections.findAllJudges`
merges them: one of `y₁` or `y₂` is picked as A's judge by the
minimum-hash rule, and that single judge fixes consensus order for
events under it. A's branching cost the network nothing in
correctness — only the transactions in the non-judge branch fail to
reach consensus through that round, and A is free to keep authoring
events on top of the chosen branch (or, again, to branch further).

## In current code

**The 2020 strong-seeing redefinition.** All of the seeing predicates
in `ConsensusImpl` are annotated as functions from `SWIRLDS-TR-2020-
01`. There is no separate `see` predicate; `lastSee`,
`firstSee`, `seeThru`, and `stronglySeeP` jointly encode the 2020 definition, in which strong-
seeing requires a super-majority weight of paths (one per creator
`m₂`) to agree on the canonical witness by each creator. See
[`strongly-seeing.md`](strongly-seeing.md) for the full walkthrough.

**Witness set per round.** A round's witnesses can include more than
one event per creator if the creator branched. The class comment for
[
`RoundElections`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java)
notes explicitly: "if a member branches, it could have
multiple [witnesses in a round]". Each branched witness has its own
candidate-witness entry and its own fame election.

**Judge merge.** When fame is decided,
`RoundElections.findAllJudges` builds a map keyed by
creator id and merges multiple famous witnesses for the same creator
via `RoundElections.uniqueFamous`, which keeps the
witness with the minimum base hash. This is the deterministic tie-
break that ensures every node picks the same judge for a branched
creator.

**Branch detector — reporting only.** Branch detection lives in the
`branching/` package of `consensus-event-intake-impl` and feeds
logging and metrics.
[
`DefaultBranchDetector.checkForBranches`](../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/branching/DefaultBranchDetector.java#checkForBranches)
is soldered to the orphan buffer's split output in
[
`DefaultEventIntakeModule.initialize`](../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java#initialize),
so every non-ancient event leaving the orphan buffer is checked: it
counts as a branch when the creator's most recent non-ancient event
is not this event's self-parent. The detector's output wire feeds
[
`DefaultBranchReporter.reportBranch`](../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/branching/DefaultBranchReporter.java#reportBranch),
which rate-limits an error log per creator, updates the
`branchingEvents`, `branchingNodeCount` and `branchingWeightFraction`
metrics
([
`BranchingMetrics`](../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/branching/BranchingMetrics.java)),
and logs a fatal "Excessive branching detected!" once branching
creators hold a strong minority of the weight — the point at which
the `<n/3` assumption is violated.

No class excludes a branching creator or discounts its events. The
detector's output ends at the reporter, whose own output wire is never
soldered, so no result of branch detection reaches the hashgraph.

## Cross-references

- Sibling concepts:
  [`strongly-seeing.md`](strongly-seeing.md),
  [`judges.md`](judges.md),
  [`rounds-and-witnesses.md`](rounds-and-witnesses.md).
- Architectural lens:
  [`../architecture/topics/hashgraph.md`](../architecture/topics/hashgraph.md).
- Whitepaper: SWIRLDS-TR-2016-01 (Lemma 5.12, the Strongly Seeing
  Lemma, is the formal safety result; Definition 5.4 defines the
  fork relation). The 2020 redefinition of seeing is documented in
  `SWIRLDS-TR-2020-01`, referenced from the JavaDoc on the seeing
  predicates in `ConsensusImpl`.
- Invariants: INV-002 (consensus order is agreed by all nodes), INV-007 (all deciders of a round agree on its judge set).
- Glossary entry: [`../glossary.md`](../glossary.md).
