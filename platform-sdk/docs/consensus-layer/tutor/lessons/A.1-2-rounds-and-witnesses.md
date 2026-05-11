---

lesson_id: A.1-2-rounds-and-witnesses
cluster: A.1
title: Rounds and witnesses
prerequisites: [A.1-1-hashgraph-dag]
kb_refs:
topics: [hashgraph]
concepts: [rounds-and-witnesses, hashgraph-dag, strongly-seeing, birth-round, judges]
invariants: []
glossary_terms: []
learning_objectives:
- State the definitions of round-created, witness, and round-received and explain why round-created and round-received are separate fields on the same event.
- Trace the four cases of `ConsensusImpl.round` and identify which case fires for an event with no parents, an event whose parents agree on a round, and an event whose parents disagree.
- Justify why `ConsensusImpl.witness` uses `round(x) != round(selfParent(x))` rather than `>` and name the invariant on `round()` that makes the two equivalent.
estimated_read_minutes: 8
status: drafted
last_verified_against: 1978c2c357d1da3a30e2f870429b96d764ff18fc
---------------------------------------------------------------

# Rounds and witnesses

## Where we are

This is the second lesson in cluster A.1 (Hashgraph Algorithm). Lesson [A.1-1 the hashgraph DAG](A.1-1-hashgraph-dag.md) defined the data structure: the vertices are events, the edges are immutable parent references, and the in-memory graph is the non-ancient slice. This lesson writes the first piece of algorithmic decoration onto each vertex — a *round-created* number — and identifies a small subset of vertices, the *witnesses*, that the rest of cluster A.1's machinery will operate on. We do not decide fame here ([A.1-4 voting and coin rounds](A.1-4-voting-and-coin-rounds.md)), pick judges ([A.1-5 judges](A.1-5-judges.md)), or open up strongly-seeing in any depth ([A.1-3 strongly-seeing](A.1-3-strongly-seeing.md)). This lesson treats strongly-seeing as a black box: a relation defined over the DAG that, given an event and a round, says how many witnesses of that round the event strongly sees. The next lesson opens the box.

## Motivating problem

The hashgraph algorithm needs a coordinate system for events that every honest node converges on without coordination. Wall-clock time fails — clocks drift and Byzantine creators lie about timestamps. Hash height fails — a Byzantine creator can manipulate the height of its self-line. The hashgraph's answer is a coordinate, *round-created*, that is computable from the DAG alone and that two nodes with the same DAG necessarily compute identically.

Round-created is two things at once: a label on every event, and the unit on which the rest of the consensus algorithm runs. Fame voting runs *per round*. Judges are picked *per round*. *Round-received* — the round in which an event reaches consensus order — is decided once every witness in some earlier round has had its fame settled. Before any of that machinery makes sense, we need precise definitions of what a round is, how the algorithm assigns one to an event, and what it means for an event to be a *witness*.

## Concept

The conceptual definition is at [`concepts/rounds-and-witnesses.md`](../../concepts/rounds-and-witnesses.md). Read its *Definition* and *Mechanics* sections before continuing.

The three definitions to anchor on:

- **Round-created.** A non-negative integer assigned to every event when it enters the DAG. The first events take round 1. Every later event inherits the maximum of its parents' rounds, *or* — when it strongly sees a super-majority of weight on the witnesses of that parent round — bumps to *parent round + 1*.
- **Witness.** The first event by a creator in a given round. Equivalently: an event whose round-created exceeds its self-parent's round-created, or which has no self-parent.
- **Round-received.** A separate quantity — the round in which an event reaches consensus order — set by the consensus pipeline once judges of a round are decided. The cluster takes round-received apart in [A.1-5 judges](A.1-5-judges.md). For this lesson, the only thing to register is that round-created and round-received are different fields on `EventImpl` and are set at different points in the algorithm.

Two structural consequences flow from these definitions and shape everything that follows. First, round-created is monotone along self-parent chains: an event's round-created is at least its self-parent's. There is no path for round numbers to decrease as a creator extends its self-line. Second, witnesses are sparse — at most one witness per creator per round. The super-majority of weight that fame voting will eventually run on cannot include two witnesses from the same creator in the same round, regardless of how branchy a creator becomes.

## How it works

Round assignment lives in `ConsensusImpl.round` ([`ConsensusImpl.java#L1120`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1120)). Two facts about its shape before the cases. It returns `long`, not `int` — the sentinel values are `ROUND_UNDEFINED = -1` (not yet computed), `ROUND_NEGATIVE_INFINITY = 0` (older than the current frontier; treated as "no round"), and `ROUND_FIRST = 1` (the first real round). Real rounds are positive; `0` is a dropped-floor sentinel, not a literal mathematical −∞. And it is memoized: the function writes the answer to the event's `roundCreated` slot via `EventImpl.setRoundCreated` and short-circuits on subsequent calls when the slot is already populated.

The computation has four cases:

1. **Null.** A null reference returns `ROUND_NEGATIVE_INFINITY`. Null arises when a parent has been cleared during ancient eviction, so the function treats it as "older than any round we still care about."
2. **Below the latest decided round, or already consensus.** `rounds.isOlderThanDecidedRoundGeneration(x) || x.isConsensus()` — the event has already been processed by the consensus pipeline. It gets `ROUND_NEGATIVE_INFINITY` and is excluded from any future graph walk.
3. **No parents.** A first-event-by-creator with an empty `allParents` list takes `ROUND_FIRST = 1`.
4. **Has parents.** The function walks `allParents`, recursively resolving each parent's round, and computes `greatestParentRound`. If parents *disagree* on round, an optimization at [`ConsensusImpl.java#L1182`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1182) skips the strongly-seeing check and returns `greatestParentRound` directly — the inline comment justifies this as: if a higher-round parent could not bump from its inherited round, neither can this event. The exception is a single-node-super-majority test case. If parents *agree* on round `r`, the function counts witnesses of round `r` that this event strongly sees, weighted by roster, and bumps to `r + 1` iff the count meets `Threshold.SUPER_MAJORITY`. The strongly-seeing primitive is `timedStronglySeeP`; depth deferred to [A.1-3 strongly-seeing](A.1-3-strongly-seeing.md).

The witness predicate is `ConsensusImpl.witness` ([`ConsensusImpl.java#L912`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L912)):

```java
private boolean witness(EventImpl x) {
    return round(x) > ROUND_NEGATIVE_INFINITY
            && (!x.hasSelfParent() || round(x) != round(selfParent(x)));
}
```

Two clauses. The first excludes ancient and pre-decided-round events from witness consideration outright. The second is the round-bump test: an event with no self-parent is a witness; an event whose round differs from its self-parent's round is a witness. The JavaDoc states the second test as `round(x) > round(selfParent(x))`; the code uses `!=`. The two are equivalent because round-created is monotone along self-parent chains — `round(x) >= round(selfParent(x))` always, by construction in case 4. Equality means "no bump"; inequality must therefore mean "bump up by one."

The witness predicate is consulted in `ConsensusImpl.addEvent` around [`ConsensusImpl.java#L385`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L385). When it returns true, the engine flips `EventImpl.setWitness(true)` and registers the witness with `RoundElections` for fame voting. Non-witness events skip the witness slot but still carry their round-created label; they will be touched again only when their round-received is decided.

## Worked example

Four nodes A, B, C, D with equal weight. The super-majority threshold is 3 of 4. Each node creates an initial event with no parents:

- A creates `a₀`; B creates `b₀`; C creates `c₀`; D creates `d₀`.

When each is added, `round()` falls through to case 3 (no parents) and assigns `ROUND_FIRST = 1`. None has a self-parent, so the witness predicate's second clause is vacuously satisfied — every initial event is a witness. After this batch, round 1 has four witnesses, one per creator.

A now gossips with B and creates `a₁` with `selfParent = a₀` and `otherParents = [b₀]`. `round(a₁)` walks `allParents`, finds `round(a₀) = 1` and `round(b₀) = 1`. Both equal 1, so `greatestParentRound = 1` and `allParentsHaveTheSameRound = true`. The function falls through to the strongly-seeing check. `a₁` strongly sees the round-1 witnesses it has ancestral paths to: through `a₀` it sees A's round-1 witness, through `b₀` it sees B's. C's and D's round-1 witnesses are not ancestors. Two of four creators is not a super-majority. The function returns `parentRound(a₁) = 1`. `a₁` takes round 1.

`witness(a₁)` then evaluates: `round(a₁) = 1`, `round(selfParent(a₁)) = round(a₀) = 1`. They are equal — `a₁` is *not* a witness. A's round-1 witness is and remains `a₀`.

Suppose C now gossips with A, B, and D in close succession and creates `c₁` with `selfParent = c₀` and other-parents reaching back to recent events from A, B, and D. When `round(c₁)` runs, `c₁` has paths to round-1 witnesses by A, B, and D through its other-parents, plus its own self-parent path to `c₀`. Three creators' worth of weight strongly seen — a super-majority. The function returns `1 + parentRound(c₁) = 2`. `c₁` takes round 2. `witness(c₁)` then sees `round(c₁) = 2`, `round(c₀) = 1`; the rounds differ, so `c₁` is a witness — C's round-2 witness. The engine registers `c₁` with `RoundElections` for round 2.

A subtle case worth pausing on: parents that disagree on round. Suppose D creates `d₁` with `selfParent = d₀` (round 1) and `otherParent = c₁` (round 2). `round(d₁)` walks the parents and finds `greatestParentRound = 2`, but `allParentsHaveTheSameRound = false`. With four equal-weight nodes, no node has super-majority by itself, so the optimization at line 1182 fires and the function returns `greatestParentRound = 2` without consulting strongly-seeing. `d₁` takes round 2. `witness(d₁)` then sees `round(d₁) = 2`, `round(d₀) = 1`; the rounds differ, so `d₁` is D's round-2 witness even though we never counted its strongly-seen witnesses to get there.

## Code anchor

- [`ConsensusImpl.round`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1120) — the round-assignment function. Case 3 (no parents) is at [line 1149](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1149); case 4 spans lines 1154–1218; the parent-disagreement optimization is at [line 1182](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1182); the super-majority strongly-seen branch is at [lines 1201–1215](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1201).
- [`ConsensusImpl.witness`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L912) — the witness predicate. The call site that flips the per-event flag and registers with `RoundElections` is in `addEvent` around [line 385](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L385).
- [`EventImpl.roundCreated`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L68) — the per-event memoized round; getter at [line 334](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L334), setter at [line 338](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L338). `roundReceived` is the *separate* field at [line 27](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L27); getter at [line 112](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L112).
- [`EventImpl.isWitness`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L29) — the per-event witness flag; getter at [line 125](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L125), setter at [line 129](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L129).
- [`ConsensusConstants`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/hashgraph/ConsensusConstants.java) — the sentinel values: `ROUND_UNDEFINED = -1`, `ROUND_NEGATIVE_INFINITY = 0`, `ROUND_FIRST = 1`.

## Delta callout

No `delta-map/hashgraph.md` entry exists yet. The architecture topic's [Future state section](../../architecture/topics/hashgraph.md#future-state) describes proposal-level changes — a single public `Consensus` API with a `nextRound(roster)` pull boundary, and a separate Sheriff module — but neither change touches round assignment or the witness predicate. Round-created and witness definitions are unchanged in the proposed redesign. Lesson [A.1-8 hashgraph algorithm synthesis](A.1-8-hashgraph-algorithm-synthesis.md) revisits the API boundary at the cluster end.

## Comprehension prompt

Two openings to take into the tutor chat. First: the witness predicate uses `round(x) != round(selfParent(x))` while its JavaDoc states `round(x) > round(selfParent(x))`. What property of `round()` makes the two equivalent today, and what would have to change in `round()` for them to drift apart? Second: when an event's parents disagree on round, `ConsensusImpl.round` skips the strongly-seeing check and returns the greatest parent round directly. What argument about the higher-round parent's strongly-seen-witness set makes that optimization sound? Where does the single-node-super-majority test case break the argument, and why does the code carve it out as an exception?

## Open questions

> - [TBD] No `glossary.md`, `invariants.md`, or `delta-map/hashgraph.md` exists at the consensus-layer KB root yet — the same gap [A.1-1 the hashgraph DAG](A.1-1-hashgraph-dag.md#open-questions) flagged. The terms *round-created*, *round-received*, *witness*, *round-bump*, and the sentinels *ROUND_FIRST* / *ROUND_NEGATIVE_INFINITY* / *ROUND_UNDEFINED* are load-bearing here and need glossary entries; candidate invariants surface below for `INV-NNN` registration when the catalog populates.
> - [TBD] Candidate invariant: *round-created is monotone along self-parent chains — for every event `x` with a non-ancient self-parent `p`, `round(x) >= round(p)`*. This is the property that makes the witness predicate's `!=`-vs-`>` equivalence hold; it should be a registered invariant on `ConsensusImpl.round`.
> - [TBD] Candidate invariant: *at most one witness per creator per round*. Direct consequence of monotone round-created plus the witness definition; load-bearing for fame voting in [A.1-4 voting and coin rounds](A.1-4-voting-and-coin-rounds.md), which assumes `RoundElections` will see a single witness per creator per round.
> - [TBD] The concept file [`rounds-and-witnesses.md`](../../concepts/rounds-and-witnesses.md) frames round as "a non-negative integer". The current code uses `long` and uses `ROUND_NEGATIVE_INFINITY = 0` as a "less-than-any-real-round" sentinel rather than a literal mathematical −∞. Real rounds are 1, 2, 3, …; 0 is the dropped-floor sentinel. Worth noting in the concept file or glossary entry to avoid confusion when readers see `0` in logs.
> - [TBD] The optimization at `ConsensusImpl.java` line 1182 (parents disagree → take greatest parent round directly) is sound under a non-trivial argument the inline comment only hints at. SME confirmation that the argument is exactly: "the higher-round parent's strongly-seen-witness set is a superset of any set this event could assemble through it, so if that parent did not bump from its inherited round, neither can this event." If that is the argument, a one-line comment expansion or an ADR slug would be valuable so the tutor chat can cite it directly.
> - [TBD] `ConsensusImpl.round`'s memoization check uses `x.getRoundCreated() >= ROUND_NEGATIVE_INFINITY` (i.e., `>= 0`) to detect "already computed". The initial value of `roundCreated` on a fresh `EventImpl` is `ROUND_UNDEFINED = -1`. SME confirmation that the contract is: `ROUND_UNDEFINED` strictly precedes any computed round under the comparison used here, and no other code path can set `roundCreated` to a negative value other than via `round()` itself.
> - [TBD] [`concepts/rounds-and-witnesses.md`](../../concepts/rounds-and-witnesses.md) cross-references a global `../../hashgraphGlossary.md` rather than a consensus-layer KB-local `glossary.md`. Same KB-layout question [A.1-1 the hashgraph DAG](A.1-1-hashgraph-dag.md#open-questions) flagged; resolution applies uniformly across cluster A.1.

## Where we're going next

Lesson [A.1-3 strongly-seeing](A.1-3-strongly-seeing.md) opens the black box this lesson punted on: the strongly-seeing relation that case 4 of `round()` consults to decide whether to bump from `r` to `r + 1`. Strongly-seeing is itself defined per-creator-and-witness and uses the `lastSee` and `stronglySeeP` slots on `EventImpl` we have not yet introduced. The next lesson defines both, walks the recursion that `timedStronglySeeP` does over the parent graph, and shows why a super-majority of weight on round-`r` witnesses is the right threshold for the round bump.
