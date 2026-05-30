---
id: a1-03-strongly-seeing
cluster: a1
title: "Strongly-seeing and branching"
pass: 2
prerequisites:
  - a1-02-rounds-and-witnesses
kb_topics_touched:
  - architecture/topics/hashgraph.md
kb_concepts:
  - concepts/strongly-seeing.md
  - concepts/branching.md
  - concepts/rounds-and-witnesses.md
kb_glossary_terms:
  - Seeing
  - Strongly seeing
  - Super-majority
  - Branching
  - Virtual voting
  - Witness
kb_invariants: []
kb_deltas:
  - delta-map/hashgraph.md
kb_decisions: []
learning_objectives:
  - Distinguish seeing from strongly-seeing — seeing is a single branch-aware ancestry path; strongly-seeing requires a super-majority of distinct-creator paths to agree on the same canonical witness — and explain why one path is not safe under Byzantine conditions.
  - Trace how strong-seeing is computed as a memoized walk — lastSee (the most recent ancestor by each member), seeThru / firstSee (the canonical witness by m reached via the path through m2), and stronglySeeP (the super-majority agreement over distinct creators) — and name the exact threshold it uses (Threshold.SUPER_MAJORITY, more than two-thirds of total weight).
  - Define branching (the paper's forking) as one creator signing two events that share a self-parent, and explain why the distinct-creator counting in stronglySeeP means branching cannot manufacture a super-majority.
  - State the three structural defenses against branching — the agreement requirement in strong-seeing, multiple witnesses allowed per creator per round, and the deterministic judge merge — and that the code carries no explicit branch detector and sends no extra messages.
  - State the Strongly Seeing Lemma (a branch cannot be strongly seen on both sides across any two consistent hashgraphs) and give its quorum-intersection reason (the overlap of two super-majorities contains an honest, non-branching creator).
threshold_concepts:
  - "Strongly-seeing is the branch-tolerant form of seeing: by requiring a super-majority of distinct-creator paths to agree on the canonical witness, it lets each node infer agreement (virtual voting) without exchanging votes, and by the Strongly Seeing Lemma no two consistent hashgraphs can ever strongly see opposite sides of a branch — which is what makes virtual voting safe under up to one-third Byzantine (branching) weight."
estimated_session_minutes: 40
status: drafted
last_verified_against: eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2
---

# Strongly-seeing and branching

## Prerequisites

> - **`a1-02-rounds-and-witnesses`** — you can compute an event's round-created as the maximum of its non-ancient parents' round-created plus a bump of one *when the event strongly sees a super-majority of that round's witnesses*; you treated "strongly sees" as a black box and "super-majority" as more than two-thirds of distinct-creator roster weight; and you can identify a **witness** as the first event by a creator in a round-created.

Beyond that, this lesson assumes the general distributed-systems background the audience brings — in particular **equivocation** (a Byzantine node showing different histories to different peers) and **quorum-intersection** reasoning (any two super-majorities overlap in more than one-third of the weight, so their overlap contains an honest participant). This lesson opens the black box `a1-02` deferred: how "strongly sees" is actually computed, and *why* it is built the way it is — to tolerate **branching**. It stops short of how witnesses use the strong-seeing relation to **vote** on each other's fame (`a1-04`) and how the resulting famous witnesses are merged into **judges** that fix consensus order (`a1-05`); both are named here only as the consumers of what this lesson builds.

## Incoming retrieval probes

This is the third mechanism lesson in Cluster A.1. Three things from `a1-02` resurface and the tutor should consolidate against them when they come up — these are authorial watch-for signals, not an opening quiz.

- **Strongly-seeing as the round-bump gate**, from `a1-02-rounds-and-witnesses`. Canonical statement the tutor consolidates against: *in `a1-02`, "x strongly sees a super-majority of round-r witnesses" was a black box that gated the +1 round bump.* This lesson opens that box; if the learner reaches back to the bump, affirm and connect the new mechanics to the gate they already used.
- **Super-majority across distinct creators**, from `a1-02-rounds-and-witnesses`. Canonical statement: *more than two-thirds of total roster weight, counted across distinct creators — the Byzantine quorum that survives up to one-third faulty weight.* The whole lesson leans on this; consolidate explicitly if the learner is shaky when the counting first appears.
- **Witness = first event by a creator in a round-created**, from `a1-02-rounds-and-witnesses`. Canonical statement: *the round-opening event for a creator; the events elections run on.* The canonical event that strong-seeing resolves to is always a witness in the parent round, so a firm grip here keeps the walk legible.

## Misconception watchlist

- **"Strongly-seeing is just seeing *more* events — see enough and you strongly see."** *(Over-generalization.)* Sounds like: "strongly see means you see a lot of them," "it's seeing with a bigger count." Correction, in line: strong-seeing is an **agreement** condition, not a volume one — a super-majority of distinct creators' paths must converge on the *same* canonical witness by the target creator; seeing a hundred unrelated events clears nothing ([strongly-seeing.md](../../concepts/strongly-seeing.md), [glossary: Strongly seeing](../../glossary.md#strongly-seeing)).
- **"Tolerating equivocation needs a detector or an extra round of messages."** *(Adjacent-protocol import — PBFT / view-change intuition.)* Sounds like: "where's the code that flags a forking node?", "don't they exchange receipts or view-change messages to handle this?" Correction: there is **no branch detector** anywhere in the consensus implementation, and **no extra messages** — tolerance is structural, built into the strong-seeing agreement test, the allowance for multiple witnesses, and the judge merge ([branching.md](../../concepts/branching.md), [glossary: Virtual voting](../../glossary.md#virtual-voting)).
- **"A forking creator can split its weight across both branches and count twice toward a super-majority."** *(Quorum misconception.)* Sounds like: "can't A inflate the tally by making two events?" Correction: the weight loop sums over **member indices**, so each creator contributes its roster weight **at most once** no matter how many events it forks — and when a creator's branches split the paths, no single witness by it collects a super-majority at all ([`ConsensusImpl.stronglySeeP` #L1088-L1100](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1088-L1100)).
- **"A branched creator's events are detected and discarded."** *(Over-generalization.)* Sounds like: "the fork gets thrown out," "the node gets excluded." Correction: both branches may still become **witnesses**, and both may even be voted **famous**; nothing is discarded mid-algorithm. Only at the end does the **judge merge** (`a1-05`) collapse a creator's multiple famous witnesses to one deterministically — so branching costs only the non-judge branch's transactions, never correctness ([branching.md](../../concepts/branching.md), [`RoundElections.java#L36`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java#L36)).

## Mechanism

The round-bump in `a1-02` rested on one phrase: *x strongly sees a super-majority of the parent round's witnesses.* This lesson is what that phrase means and why it is built to survive Byzantine **branching** ([hashgraph.md](../../architecture/topics/hashgraph.md), [strongly-seeing.md](../../concepts/strongly-seeing.md)).

**Pre-training — terms this lesson integrates** (definitions live in the glossary and concept files; named here to set vocabulary):

- **Seeing** — *x* sees *y* if *y* is an ancestor of *x* and *x* cannot detect a branch by *y*'s creator on that path. A single-path relation; the building block ([glossary: Seeing](../../glossary.md#seeing)).
- **Strongly seeing** — *x* strongly sees witness *y* when a super-majority of roster weight, **across distinct creators**, lies on paths between *y* and *x* that agree *y* is the canonical witness by its creator ([glossary: Strongly seeing](../../glossary.md#strongly-seeing), [strongly-seeing.md](../../concepts/strongly-seeing.md)).
- **Super-majority** — more than two-thirds of total roster weight; the exact test is `Threshold.SUPER_MAJORITY` ([glossary: Super-majority](../../glossary.md#super-majority), [`Threshold.java#L71-L94`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/base-utility/src/main/java/org/hiero/base/utility/Threshold.java#L71-L94)).
- **Branching** (the paper's *forking*) — one creator signing two events that share a self-parent, so neither is a self-ancestor of the other; an honest creator's events form a single chain, a branched creator's a tree ([glossary: Branching](../../glossary.md#branching), [branching.md](../../concepts/branching.md)).
- **Virtual voting** — votes computed deterministically from the DAG and never exchanged as messages; strong-seeing is what makes it safe. Recap-forward to `a1-04` ([glossary: Virtual voting](../../glossary.md#virtual-voting)).
- **`lastSee` / `seeThru` / canonical witness** — the pieces of the memoized walk that computes strong-seeing, covered in Chunk 2 ([strongly-seeing.md](../../concepts/strongly-seeing.md)).

### Chunk 1 — Seeing vs. strongly-seeing: why one path is not enough `{moment: m1-see-vs-strongly-see}`

Plain **seeing** is ancestry along the DAG: *x* sees *y* when *y* is an ancestor of *x* — with one Byzantine-aware caveat baked into the definition, that *x* cannot detect a branch by *y*'s creator on that path ([glossary: Seeing](../../glossary.md#seeing)). Seeing is a single-path notion: it asks whether *one* chain of ancestry reaches *y*.

A single path is not enough to drive consensus, because that one path runs through creators who may **equivocate**. If the algorithm advanced rounds or counted votes on the strength of seeing alone, a Byzantine creator could show one history to some peers and another to others, and honest nodes would compute different things ([branching.md](../../concepts/branching.md)). **Strongly seeing** is the stronger relation that closes this: *x* strongly sees witness *y* only when a **super-majority of roster weight, spread across distinct creators**, lies on paths between *y* and *x* — and, crucially, those paths must **agree** that *y* is the canonical witness by its creator ([glossary: Strongly seeing](../../glossary.md#strongly-seeing), [strongly-seeing.md](../../concepts/strongly-seeing.md)).

**Load-bearing line (signal):** strong-seeing is an **agreement of a quorum of distinct creators**, not a count of how many events were seen. This is the whole reason the relation exists — it is the structural substitute for the message-passing a classical BFT protocol would use to tolerate the same Byzantine behavior, and it is what keeps **virtual voting** (`a1-04`) safe. Everything in the rest of this lesson is either *how* that agreement is computed (Chunks 2–3) or *why* it survives branching (Chunk 4).

### Chunk 2 — The walk: `lastSee` → `seeThru` → the canonical witness `{moment: m2-the-walk}`

Strong-seeing is computed by a **memoized walk over the hashgraph**, not a single ancestry test — three helper functions compose into it ([strongly-seeing.md](../../concepts/strongly-seeing.md)):

- **`lastSee(x, m)`** — the most recent event by member *m* that is an ancestor of *x*. It is **aggressively memoized**: the first call for a given *x* computes and stores the answer for *every* member at once, then returns the one for *m* ([`ConsensusImpl.lastSee` #L965-L1017](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L965-L1017)). An event is considered to "see itself" for its own creator ([`#L979-L981`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L979-L981)), and the walk takes, over all non-ancient parents, the latest event each parent can `lastSee` ([`#L990-L1013`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L990-L1013)).
- **`seeThru(x, m, m2)`** — the witness by *m* that *x* sees *through* an intermediary by *m2*. For the general case it is `firstSee(lastSee(x, m2), m)`: walk to the latest event by *m2* in *x*'s ancestry, then resolve the witness by *m* that path points at ([`ConsensusImpl.seeThru` #L1028-L1039](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1028-L1039)).
- **`firstSee(x, m)`** — `firstSelfWitnessS(lastSee(x, m))`: the round-opening witness by *m* reached by walking back along self-parents from the latest event by *m* that *x* sees ([`ConsensusImpl.firstSee` #L1307-L1309](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1307-L1309)).

Put together: for a target creator *m*, the **canonical witness by *m* via the path through *m2*** is `seeThru(x, m, m2)`. The path through each intermediary creator *m2* resolves to *some* witness by *m* — and the next chunk asks how many of those paths **agree**.

**Load-bearing line (signal):** the walk resolves, for each intermediary creator, **which witness by the target creator that creator's path points at**. The relation is built on *per-path resolution*, which is exactly what lets the next step ask whether a quorum of paths converge — and what lets branching split them apart. The memoization is bookkeeping for speed (the comment calls the total time a "generalized dot product"); the resolution is the load-bearing part.

### Chunk 3 — The super-majority gate: counting distinct creators that agree `{moment: m3-supermajority-gate}`

`stronglySeeP(x, m)` returns the canonical witness by member *m*, in *x*'s **parent round**, that *x* strongly sees — or null if there is none ([`ConsensusImpl.stronglySeeP` #L1054-L1104](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1054-L1104)). Its core, for each target creator *m*:

1. Take `st = seeThru(x, m, m)` — the canonical witness by *m* that *x*'s own resolution points at ([`#L1084`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1084)). If `st` is not in the parent round, *x* strongly sees nothing by *m* ([`#L1085-L1086`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1085-L1086)).
2. Otherwise, for **each intermediary creator *m3***, check whether *that* creator's path agrees — `seeThru(x, m, m3) == st` — and if so add *m3*'s roster weight to a running total ([`#L1088-L1093`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1088-L1093)).
3. If that agreeing weight clears `Threshold.SUPER_MAJORITY` — more than two-thirds of total weight — *x* strongly sees `st`; otherwise it strongly sees nothing by *m* ([`#L1094-L1100`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1094-L1100), [`Threshold.java#L71-L94`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/base-utility/src/main/java/org/hiero/base/utility/Threshold.java#L71-L94)).

**Structural detail (grounded in code, not paraphrase):** the agreement loop at step 2 iterates over **member indices** *m3*, summing roster weight per creator — not over events ([`#L1089-L1092`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1089-L1092)). So each creator contributes its weight **at most once**, however many events it has authored. This one detail is the entire defense against weight-inflation by forking, made precise in Chunk 4.

This is the relation `a1-02`'s round-bump consumes. `round(x)` counts how many distinct creators *m* have `stronglySeeP(x, m) != null` (each via `timedStronglySeeP`), sums their weight, and bumps to parent-round + 1 when that weight clears the super-majority ([`#L1203-L1214`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1203-L1214)). There are therefore **two** super-majority tests stacked: agreement on *which* witness by a creator is canonical (inside `stronglySeeP`), then enough distinct creators strongly seen (inside `round`). `timedStronglySeeP` is not a different relation — it is `stronglySeeP` wrapped only in a nanosecond timer that feeds a performance metric ([`ConsensusImpl.timedStronglySeeP` #L880-L886](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L880-L886)).

**Load-bearing line (signal):** strong-seeing fires on a **super-majority of distinct creators agreeing on one canonical witness** — counted by creator weight, once each. The "distinct" and the "agree" are both doing work: distinct is what bounds a Byzantine creator to one vote of weight; agree is what a branch breaks.

### Chunk 4 — Branching: why the agreement test tolerates forks `{moment: m4-branching}`

A creator **branches** (the whitepaper's *forking*) when it signs two events sharing a self-parent — two events by one creator, neither a self-ancestor of the other ([branching.md](../../concepts/branching.md), [glossary: Branching](../../glossary.md#branching)). It is a form of equivocation: the creator shows different views of its own history to different peers, intending to make honest nodes compute different round-createds, votes, or witness sets. **Branching is the Byzantine behavior the entire strong-seeing machinery exists to tolerate** — without it, plain seeing would suffice ([branching.md](../../concepts/branching.md)).

Three structural layers absorb the attack, and only the first lives in this lesson; the others are named so the picture is whole:

1. **Strong-seeing requires agreement among paths (this lesson).** When creator *m* branches, the per-path resolutions from Chunk 2 split: the path through one creator points at one branch's witness, the path through another points at the other. No single witness by *m* collects a super-majority of agreeing weight, so *x* strongly sees **nothing** by *m* ([branching.md](../../concepts/branching.md), [`stronglySeeP` #L1088-L1100](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1088-L1100)). And because the count is per-creator weight, the fork cannot inflate the tally either.
2. **Multiple witnesses per creator per round are allowed (`a1-04`).** A branched creator can be the first event in a round on *each* branch, so it can have more than one witness in a round; the round-elections state notes this explicitly ([`RoundElections.java#L36`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java#L36)).
3. **The judge merge collapses branched famous witnesses (`a1-05`).** When fame is decided, `findAllJudges` keeps one famous witness per creator, breaking ties on a branched creator deterministically by minimum hash, so branching never inflates a creator's voice in consensus order ([`RoundElections.findAllJudges` #L136-L150](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java#L136-L150), [judges.md](../../concepts/judges.md)).

**No explicit branch detector.** The code does not scan for, flag, punish, or evict branching creators — a grep for "fork" or "branch" across the consensus implementation turns up only comments. Tolerance is entirely structural ([branching.md](../../concepts/branching.md)).

**Load-bearing line (signal):** the safety guarantee is the **Strongly Seeing Lemma** — if *(x, y)* is a branch and one event in some hashgraph strongly sees *x*, then *no* event in any consistent hashgraph strongly sees *y* ([branching.md](../../concepts/branching.md)). The reason is quorum intersection: each strong-seeing rests on a super-majority of weight, and any two super-majorities overlap in more than one-third of the weight, so the overlap contains at least one **honest** creator; an honest creator does not branch, so its events lie on a single chain, which forces the two strongly-seen witnesses to be the same. This is the invariant the contrasting cases below surface, and it is what lets the DAG stand in for the vote messages a classical protocol would send. *(See the Contrasting cases material, which the tutor draws on at this moment.)*

## Engagement moves

### Moment `m1-see-vs-strongly-see`

*Why load-bearing (exposition):* the lesson's framing hinges on seeing being insufficient and strong-seeing being an agreement condition; if the learner leaves thinking "strongly see = see a lot," every later mechanic is mis-shaped.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is fluent on Byzantine quorums and on `a1-02`'s super-majority gate, and can reason about why one path is unsafe.
- `answer_shape`: a failure mode plus the requirement that closes it — *what a single path is vulnerable to, and the quorum condition that fixes it.*
- Framing + prompt (verbatim): "Plain 'seeing' is just ancestry: *x* sees *y* if *y* is an ancestor of *x* along a path. Before I show you the stronger relation the algorithm actually uses — a single ancestry path runs through one chain of creators. What kind of creator misbehavior could make that single path mislead you about a creator's latest event, and what would you require *instead* of one path to be safe against it?"
- Confidence elicitation (verbatim, optional): "How sure are you, low to high, before I reveal it?"
- `canonical_answer`: A creator can **equivocate / branch** — show different histories to different peers — so one path can point at a history other honest nodes do not share. To be safe you require not one path but a **super-majority of distinct creators' paths to agree** on the same witness; that quorum is what strong-seeing demands ([strongly-seeing.md](../../concepts/strongly-seeing.md), [branching.md](../../concepts/branching.md)).
- `alternative_correct_answers`:
  - "A Byzantine creator could fork/equivocate; you'd need a quorum — a super-majority of distinct creators — to agree rather than trusting one path."
  - "One path could go through a node showing a fake history; the fix is requiring more than two-thirds of weight, across distinct creators, to see the same thing."
  - "Equivocation breaks a single path; safety needs agreement among a Byzantine quorum of distinct creators, not a lone ancestry link."
- `followup` (if the learner names "more events" rather than agreement of distinct creators): "Say it in terms of *who* agrees, not *how many events* — would seeing a thousand events by one creator help? What is the quorum counted over?"

**Move B — worked example with self-explanation.** *Diagnosis tag:* the learner is new to the see/strongly-see distinction and unsure why a second, stronger relation is needed at all.
- Walk (exposition): lay seeing and strong-seeing side by side — seeing is one branch-aware ancestry path to *y*; strong-seeing is a super-majority of distinct creators' paths that **agree** *y* is the canonical witness by its creator. **Load-bearing line:** strong-seeing is agreement of a quorum, not a larger count.
- Self-explanation prompt (verbatim): "Both relations are about *x* reaching a witness *y* through the DAG. Seeing asks for one ancestry path; strong-seeing asks for a super-majority of distinct creators' paths to agree on *y*. What does the algorithm gain from the agreement of distinct creators that a single path — even a hundred single paths through one creator — cannot give it?"
- `canonical_answer`: Agreement across a super-majority of *distinct* creators guarantees that honest nodes, who together hold more than two-thirds of weight, converge on the same witness even if some creators equivocate — a single path (or many paths through one creator) carries no fault tolerance, because that one creator could be Byzantine and show a fork ([strongly-seeing.md](../../concepts/strongly-seeing.md), [branching.md](../../concepts/branching.md)).
- `alternative_correct_answers`:
  - "It gets Byzantine fault tolerance: a distinct-creator super-majority forces overlap with honest creators; one creator's paths give none."
  - "Distinct-creator agreement means a fork can't fool it; volume through a single (possibly faulty) creator proves nothing."
  - "A quorum of distinct creators can't all be lying under the one-third bound; a lone path can."
- `followup` (if the learner restates that strong-seeing needs more without saying why distinct creators matter): "That is what it requires — now say *why distinct* is the load-bearing word: what goes wrong if one creator could supply all the weight?"

**Move C — direct walk with cued check.** *Diagnosis tag:* the learner is clearly fluent and needs only a verify before moving on.
- Prompt (verbatim): "In one sentence each: what is the difference between *x* seeing *y* and *x* strongly seeing *y*, and which of the two could a single equivocating creator defeat?"
- `canonical_answer`: Seeing is *y* being an ancestor of *x* along one branch-aware path; strong-seeing requires a super-majority of distinct creators' paths to agree *y* is canonical. A single equivocating creator can defeat seeing (the lone path), not strong-seeing (the distinct-creator quorum) ([glossary: Seeing](../../glossary.md#seeing), [glossary: Strongly seeing](../../glossary.md#strongly-seeing)).
- `alternative_correct_answers`:
  - "Seeing = one ancestry path; strong-seeing = super-majority of distinct creators agreeing. Equivocation beats seeing, not strong-seeing."
  - "One path vs. a distinct-creator quorum; the single path is the one a forker can fool."

### Moment `m2-the-walk`

*Why load-bearing (exposition):* the three helpers compose into the relation, and the per-path resolution (`seeThru` through each intermediary) is precisely what the agreement test in `m3` counts and what branching splits. This is new mechanical material, so the worked example leads.

**Move A — worked example with self-explanation.** *Diagnosis tag:* the learner is new to the walk (the common case here) and may want to see the helpers compose.
- Walk (exposition): `lastSee(x, m)` is the most recent ancestor of *x* by member *m*, computed for all members at once and memoized ([`#L965-L1017`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L965-L1017)); `seeThru(x, m, m2) = firstSee(lastSee(x, m2), m)` is the witness by *m* that *x* reaches *through* the path that goes via creator *m2* ([`#L1028-L1039`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1028-L1039)). **Load-bearing line:** `seeThru` resolves *one witness by m per intermediary creator m2* — a per-path answer.
- Self-explanation prompt (verbatim): "`seeThru(x, m, m2)` resolves the witness by creator *m* that *x* reaches via the path through creator *m2*, and it is evaluated separately for each intermediary *m2*. Why does the algorithm resolve a *separate* answer per intermediary creator, instead of just asking once whether *x* sees a witness by *m*?"
- `canonical_answer`: Because the next step counts how many *distinct intermediary creators agree* on the same canonical witness by *m* — that agreement is only meaningful if each intermediary's path is resolved independently. Resolving once would throw away exactly the per-creator information the super-majority test needs (and the information a branch by *m* would split) ([strongly-seeing.md](../../concepts/strongly-seeing.md), [`stronglySeeP` #L1088-L1093](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1088-L1093)).
- `alternative_correct_answers`:
  - "So it can tally agreement per creator; one combined answer couldn't be checked for a super-majority of distinct intermediaries."
  - "Per-intermediary resolution is what makes 'do a super-majority agree?' a question you can ask — and what a fork can disagree on."
  - "The counting in the next step needs one vote per creator; resolving once would collapse that."
- `followup` (if the learner just restates what `seeThru` returns): "That is what it computes — I am asking why *per intermediary*. What does the step after this one do with one answer per creator that it could not do with a single combined answer?"

**Move B — direct walk with cued check.** *Diagnosis tag:* the learner is fluent on graph traversals and needs only a verify on how the helpers compose.
- Prompt (verbatim): "Given that `lastSee(x, m2)` returns the most recent ancestor of *x* created by *m2*, and `firstSee(x, m)` walks back to the round-opening witness by *m*, write `seeThru(x, m, m2)` as a composition of the two and say, in one phrase, what it represents."
- `canonical_answer`: `seeThru(x, m, m2) = firstSee(lastSee(x, m2), m)` — the canonical witness by *m* that *x* reaches through the path that runs via creator *m2* ([`seeThru` #L1028-L1039](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1028-L1039)).
- `alternative_correct_answers`:
  - "`firstSee(lastSee(x, m2), m)` — the witness by *m* seen via the intermediary *m2*."
  - "Compose lastSee then firstSee: the round-opening witness by *m* on the path through *m2*."

### Moment `m3-supermajority-gate`

*Why load-bearing (exposition):* the agreement-and-count is the lesson's central new mechanism, and the per-creator (not per-event) weighting is the hinge the branching tolerance turns on.

**Move A — worked example with self-explanation.** *Diagnosis tag:* the learner is new to the counting and may not yet see why it is per creator.
- Walk (exposition): four equal-weight nodes (super-majority = 3 of 4). For target creator A, `st` is the canonical witness by A that *x*'s own path points at; the algorithm then checks, for each intermediary creator B, C, D, whether `seeThru(x, A, ·)` equals `st`, adding that creator's weight when it agrees ([`stronglySeeP` #L1084-L1093](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1084-L1093)). If B, C, and D all agree on `st`, that is 3 of 4 — a super-majority — so *x* strongly sees `st`. **Load-bearing line:** the weight is summed over *creators*, once each.
- Self-explanation prompt (verbatim): "The loop that sums agreeing weight runs over creator indices, adding each agreeing creator's weight exactly once — not over events. Suppose creator B has authored five events on *x*'s path that all agree on `st`. How much does B add to the tally, and why is that the right amount?"
- `canonical_answer`: B adds its roster weight **once**, no matter how many of its events agree — the loop is over creators, not events. That is correct because the quorum that matters is a super-majority of *distinct creators* (the Byzantine threshold is about how much of the network's weight is honest), and a single creator's weight should count once regardless of how many events it produced ([`#L1088-L1093`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1088-L1093)).
- `alternative_correct_answers`:
  - "Once — B's weight — because the threshold counts distinct-creator weight, not event volume."
  - "Just B's single weight; the quorum is over creators, so five agreeing events still count as one creator."
  - "B contributes its weight one time; otherwise a chatty or forking creator could inflate the tally."
- `followup` (if the learner says "five times" or is unsure): "Re-read which index the loop runs over — creators or events? If it added per event, what could a single creator do to manufacture a super-majority?"

**Move B — prediction-and-reveal.** *Diagnosis tag:* the learner has the counting and can predict the outcome of a clean (non-branching) case.
- `answer_shape`: a yes/no plus the count that decides it — *whether x strongly sees the witness, and the agreeing weight versus the threshold.*
- Framing + prompt (verbatim): "Four equal-weight nodes, super-majority 3 of 4. For target creator A, the paths through B, C, and D all resolve to the *same* canonical witness `st` by A, while A's own resolution also points at `st`. Does *x* strongly see `st`, and what exactly was the deciding count?"
- `canonical_answer`: Yes — B, C, and D agree on `st`, which is 3 of 4 creators by weight, clearing the super-majority (more than two-thirds), so *x* strongly sees `st` ([`#L1094-L1100`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1094-L1100)).
- `alternative_correct_answers`:
  - "Yes; three distinct creators (B, C, D) agreed on `st`, and 3/4 > 2/3."
  - "It strongly sees `st` because agreeing weight (3 of 4) cleared the super-majority threshold."
- `followup` (if the learner answers "yes" without the count): "Name the deciding number — how many distinct creators agreed, and what threshold did that clear?"

**Move C — direct walk with cued check.** *Diagnosis tag:* the learner is fluent and the tutor wants to confirm the two-level structure before Chunk 4.
- Prompt (verbatim): "There are two separate super-majority tests between a witness being strongly seen and an event bumping its round. Name what each one is a super-majority *of*."
- `canonical_answer`: First, inside `stronglySeeP`: a super-majority of distinct intermediary creators agreeing on *which* witness by a creator is canonical. Second, inside `round`: a super-majority of distinct creators whose witnesses *x* strongly sees, which triggers the round bump ([`stronglySeeP` #L1088-L1100](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1088-L1100), [`round` #L1203-L1214](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1203-L1214)).
- `alternative_correct_answers`:
  - "Inner: creators agreeing on one canonical witness. Outer: creators whose witnesses are strongly seen, for the bump."
  - "One is agreement on a single witness by a creator; the other is how many creators' witnesses are strongly seen."

### Moment `m4-branching`

*Why load-bearing (exposition):* the threshold-concept moment — strong-seeing is the branch-tolerant relation, and the Strongly Seeing Lemma is the safety payoff. Offers the contrasting cases (to surface the lemma), a worked example (the split-paths fork is new), and a prediction on the lemma (for the confident).

**Move A — contrasting cases with comparison prompt.** *Diagnosis tag:* threshold concept; transfer is the goal. Uses the cases in the Contrasting cases material below.
- Prompt (verbatim): "Compare three ways to reach agreement that a witness is canonical. (1) Seeing: one ancestry path to the witness, branch-aware but single-creator. (2) Strong-seeing: a super-majority of distinct creators' paths must agree on the witness. (3) Classical async BFT: nodes exchange explicit vote or receipt messages to agree under equivocation. Across the three, what is the same, what differs, and what single property do strong-seeing and the BFT message protocol share that plain seeing lacks?"
- `canonical_answer`: All three aim to agree on something under possible equivocation. They differ in mechanism: seeing trusts one path, strong-seeing requires a distinct-creator super-majority to converge structurally from the DAG, and classical BFT achieves the same convergence by sending messages. The property strong-seeing shares with the BFT protocol — and that plain seeing lacks — is a **Byzantine quorum**: more than two-thirds of distinct weight must agree, which forces overlap with honest participants, so equivocation cannot split honest nodes ([strongly-seeing.md](../../concepts/strongly-seeing.md), [branching.md](../../concepts/branching.md)).
- `alternative_correct_answers`:
  - "Same goal (agree under faults); differ in path-vs-quorum-vs-messages. Strong-seeing and BFT both use a Byzantine quorum; seeing doesn't."
  - "All three reach agreement; seeing has no quorum, strong-seeing gets one from the graph, BFT gets one from messages — the shared thing is the >2/3 distinct-weight quorum."
  - "The common property is quorum intersection with honest nodes; strong-seeing derives it structurally, BFT by messaging, seeing not at all."
- *Deep invariant (exposition for consolidation, not read as a question):* the Strongly Seeing Lemma — a branch cannot be strongly seen on both sides across consistent hashgraphs — because two super-majorities overlap in more than one-third of the weight, so the overlap holds an honest creator whose single chain forces one answer. This is the structural equivalent of the vote messages classical BFT sends, achieved with no extra traffic.

**Move B — worked example with self-explanation.** *Diagnosis tag:* the learner is new to how a fork actually defeats the count and wants to see it concretely.
- Walk (exposition): node A is Byzantine and signs two children of the same self-parent — `y1` (gossiped to B) and `y2` (gossiped to C and D). From an event *x* that has both in its ancestry, the path through B may resolve to `y1` while the paths through C and D resolve to `y2` ([branching.md](../../concepts/branching.md)). **Load-bearing line:** the per-creator resolutions disagree, so no single witness by A collects a super-majority.
- Self-explanation prompt (verbatim): "A has branched into `y1` and `y2`; the path through B points at `y1`, and the paths through C and D point at `y2`. Walk the agreement count for target creator A in this four-node network, and say whether *x* strongly sees any witness by A — and why that outcome is the *safe* one."
- `canonical_answer`: For `st = y1`, only B agrees (1 of 4); for `st = y2`, only C and D agree (2 of 4). Neither clears the super-majority (3 of 4), so *x* strongly sees **nothing** by A. That is safe: rather than letting A's equivocation push some honest nodes onto `y1` and others onto `y2`, the agreement test simply declines to strongly see A at all, so A's branch advances nothing ([branching.md](../../concepts/branching.md), [`stronglySeeP` #L1088-L1100](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1088-L1100)).
- `alternative_correct_answers`:
  - "y1 gets 1 (B), y2 gets 2 (C, D); neither reaches 3/4, so A is un-strongly-seen — the fork buys nothing."
  - "Split paths mean no super-majority on either branch, so *x* strongly sees no witness by A; equivocation is neutralized, not propagated."
  - "Neither branch clears 2/3, so A contributes nothing — which is exactly what keeps honest nodes from diverging."
- `followup` (if the learner computes the split but calls the no-strong-see outcome a failure): "Why is 'strongly sees nothing by A' the *desired* result here — what would have gone wrong if the algorithm had instead strongly seen one branch on some nodes and the other branch on others?"

**Move C — prediction-and-reveal.** *Diagnosis tag:* the learner has the fork picture and can reason about the cross-hashgraph guarantee using quorum intersection.
- `answer_shape`: an impossibility claim plus its reason — *whether two honest nodes can strongly see opposite branches, and the quorum-intersection argument.*
- Framing + prompt (verbatim): "Two honest nodes have seen different subsets of A's branch — one node's hashgraph happened to strongly see `y1`, and you are asked whether the *other* node could, on its consistent hashgraph, strongly see `y2`. Using only the super-majority requirement and quorum intersection: can that happen? Give the reason."
- `canonical_answer`: No. Strongly seeing `y1` rests on a super-majority of weight, and strongly seeing `y2` would too; any two super-majorities overlap in more than one-third of the weight, so the overlap contains at least one honest creator. An honest creator does not branch — its events form a single chain — so it cannot have supported both `y1` and `y2`. Hence the two strongly-seen witnesses must coincide; opposite branches cannot both be strongly seen across consistent hashgraphs (the Strongly Seeing Lemma) ([branching.md](../../concepts/branching.md)).
- `alternative_correct_answers`:
  - "No — the two super-majorities intersect in an honest creator, who has only one chain, so both nodes must land on the same branch."
  - "Impossible: quorum intersection forces a common honest creator, and honest creators don't fork, so the strongly-seen witnesses are identical."
  - "It can't happen; overlap of two >2/3 sets holds an honest node whose single history rules out both branches being strongly seen."
- `followup` (if the learner answers "no" without invoking the honest creator in the overlap): "You have the conclusion — now name *why* the overlap settles it: who must be in the intersection of the two super-majorities, and what do you know about how that participant builds its events?"

## Contrasting cases material

The threshold concept is *strong-seeing is the branch-tolerant form of seeing: a distinct-creator super-majority agreeing on a canonical witness, which makes virtual voting safe under branching.* The three cases hold "reaching agreement that a witness is canonical" constant and vary the mechanism.

- **Case 1 — seeing.** *x* sees *y* when *y* is an ancestor of *x* on a single branch-aware path ([glossary: Seeing](../../glossary.md#seeing)). *Surface:* one path; one creator's word; cheap. *What it lacks:* no quorum, so an equivocating creator on that path can mislead it.
- **Case 2 — strong-seeing.** *x* strongly sees *y* when a super-majority of distinct creators' paths agree *y* is canonical ([strongly-seeing.md](../../concepts/strongly-seeing.md), [`stronglySeeP` #L1054-L1104](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1054-L1104)). *Surface:* a distinct-creator quorum read structurally from the DAG; no messages. *What it adds:* a Byzantine quorum, derived from the graph.
- **Case 3 — classical asynchronous BFT (e.g. PBFT).** Nodes tolerate the same equivocation by exchanging explicit vote / receipt messages each decision round ([branching.md](../../concepts/branching.md)). *Surface:* an extra message round per decision; agreement is sent, not inferred. *What it shares with Case 2:* the same Byzantine quorum.

**The deep invariant that survives the surface differences:** Cases 2 and 3 both rest on a **Byzantine quorum** — more than two-thirds of distinct weight must agree — while Case 1 does not. Quorum intersection is why that matters: two super-majorities overlap in more than one-third of weight, so their overlap contains an honest creator, whose single self-parent chain forbids it from having supported two sides of a branch. This is the **Strongly Seeing Lemma**, and it is exactly the guarantee classical BFT buys with message rounds — strong-seeing buys it structurally from the DAG instead, which is what lets votes be *virtual* (computed, never sent). The agreement requirement is the load-bearing mechanism; the lemma is what makes it safe. *(How witnesses use this relation to vote is `a1-04`; how famous witnesses merge into judges is `a1-05`. This case set is only about how agreement on a canonical witness is reached and why it tolerates branching.)*

## Completion problems

**Problem 1** *(small step blanked).*
- Statement (verbatim): "Four equal-weight nodes; super-majority is 3 of 4. For target creator A, the canonical witness *x* resolves to is `st`. The paths through creators B, C, and D each resolve `seeThru(x, A, ·)`, and B and C both resolve to `st` while D resolves to a *different* witness by A. The agreeing weight is ______ of 4, so *x* ______ (does / does not) strongly see `st`. Then, in one sentence, say what would change if D had also resolved to `st`."
- Hint ladder:
  - Rung 1 (verbatim): "Open `concepts/strongly-seeing.md` and read the Mechanics paragraph — note that strong-seeing needs a super-majority of paths to agree on the *same* canonical witness."
  - Rung 2 (verbatim): "Count only the creators whose path resolves to `st`: that is B and C. Is two out of four more than two-thirds?"
  - Rung 3 (verbatim): "B and C agree on `st` (2 of 4); D does not. Two of four is not a super-majority (you need more than 2/3, i.e. 3 of 4 here), so the strong-see does not fire."
  - Rung 4 (verbatim, gated on effort): "Agreeing weight is 2 of 4, so *x* does **not** strongly see `st`. If D had also resolved to `st`, the agreeing weight would be 3 of 4 — a super-majority — and *x* would strongly see `st`."
- `canonical_answer`: 2 of 4; *x* does **not** strongly see `st` (two of four is not more than two-thirds). If D had also agreed, it would be 3 of 4, a super-majority, and *x* would strongly see `st` ([`stronglySeeP` #L1088-L1100](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1088-L1100)).
- `alternative_correct_answers`:
  - "2 of 4; does not strongly see. With D agreeing it becomes 3 of 4 and does."
  - "Two agree, below the 3-of-4 super-majority, so no; D agreeing would tip it to a super-majority and yes."
- *Invariant exercised (exposition):* strong-seeing fires only when agreeing distinct-creator weight clears the super-majority threshold.
- `followup` (if the learner writes "2 of 4" but says it strongly sees): "Check the threshold — is two of four *more than two-thirds*? What is the smallest agreeing count that clears it here?"

**Problem 2** *(more blanked — a branching scenario).*
- Statement (verbatim): "Node A branches: it signs `y1` and `y2` on the same self-parent. In a four-equal-weight network, from event *x* the path through B resolves to `y1`, and the paths through C and D resolve to `y2`. Working the agreement count for each candidate, state whether *x* strongly sees `y1`, whether it strongly sees `y2`, and what that means for whether A's round advances through *x*."
- Hint ladder:
  - Rung 1 (verbatim): "Open `concepts/branching.md` and read 'How the algorithm tolerates branching', layer 1 — note what happens to the agreement count when paths split across branches."
  - Rung 2 (verbatim): "Count agreeing creators for `y1` (which creators' paths resolve to it?) and separately for `y2`. Does either reach 3 of 4?"
  - Rung 3 (verbatim): "`y1` is agreed only by B (1 of 4); `y2` only by C and D (2 of 4). Neither reaches the 3-of-4 super-majority, so neither is strongly seen."
  - Rung 4 (verbatim, gated on effort): "*x* strongly sees neither `y1` (only B agrees, 1 of 4) nor `y2` (only C and D agree, 2 of 4) — neither clears the super-majority. So *x* strongly sees no witness by A, and A contributes nothing toward *x*'s round advancement: the fork advances nothing."
- `canonical_answer`: *x* strongly sees neither branch — `y1` has only B (1 of 4) and `y2` has only C and D (2 of 4), and neither clears the 3-of-4 super-majority — so *x* strongly sees no witness by A, and A's branch contributes nothing to *x*'s round bump ([branching.md](../../concepts/branching.md), [`stronglySeeP` #L1088-L1100](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1088-L1100)).
- `alternative_correct_answers`:
  - "Neither: y1 gets 1, y2 gets 2, both below 3/4. A is un-strongly-seen through *x*, so the fork yields no advancement."
  - "x strongly sees no witness by A because the split keeps both branches under the super-majority; A adds nothing to the bump count."
- *Invariant exercised (exposition):* a branch splits the per-creator agreement so no single branch reaches a super-majority — the distinct-creator count neutralizes equivocation without a detector.
- `followup` (if the learner says *x* strongly sees `y2` because it has the most agreement): "Most is not the test — what is? Two of four: does it clear *more than two-thirds*? What does that make A's contribution?"

**Problem 3** *(most faded — produce the mechanism from a scenario).*
- Statement (verbatim): "A colleague used to PBFT asks why the hashgraph needs no branch detector and sends no extra messages to tolerate equivocation. Answer with three things: (a) what branching is, in one sentence; (b) the exact mechanism inside `stronglySeeP` that makes a branch advance nothing, and why a forking creator cannot inflate the count; and (c) the lemma that guarantees two honest nodes never strongly see opposite branches, with its one-line reason."
- Hint ladder:
  - Rung 1 (verbatim): "Open `concepts/branching.md` — read the Definition, the 'How the algorithm tolerates branching' list, and the 'Why this gives Byzantine fault tolerance' section. Then open `ConsensusImpl.java` and skim the weight-counting loop inside `stronglySeeP`."
  - Rung 2 (verbatim): "(a) What does a creator sign to branch? (b) Which index does the agreement loop run over — creators or events — and what does a fork do to the per-creator resolutions? (c) What do two overlapping super-majorities always contain, and why does that settle which branch is strongly seen?"
  - Rung 3 (verbatim): "(a) Branching is one creator signing two events on the same self-parent. (b) `stronglySeeP` counts agreeing weight per creator, once each; a fork splits the per-creator resolutions so no branch reaches a super-majority, and per-creator counting means extra events add no weight. (c) The Strongly Seeing Lemma: any two super-majorities overlap in an honest creator, who has one chain, so both nodes strongly see the same branch."
  - Rung 4 (verbatim, gated on effort): "(a) A creator branches when it signs two events sharing a self-parent (neither a self-ancestor of the other) — equivocation. (b) Inside `stronglySeeP`, the agreement loop sums roster weight over *creator* indices, counting each creator at most once; when a creator forks, its branches split the per-intermediary resolutions, so no single witness by it collects a super-majority, and because counting is per creator, extra forked events add no weight. (c) The Strongly Seeing Lemma: if one event strongly sees branch `x`, no event in any consistent hashgraph strongly sees branch `y` — because the two strong-sees rest on super-majorities that overlap in more than one-third of weight, so the overlap holds an honest, non-branching creator whose single chain forces both to the same branch."
- `canonical_answer`: (a) Branching is one creator signing two events that share a self-parent (neither a self-ancestor of the other) — equivocation. (b) `stronglySeeP` sums agreeing weight over creator indices, once per creator; a fork splits the per-intermediary resolutions so no branch reaches a super-majority, and per-creator counting means a forker's extra events add no weight. (c) The Strongly Seeing Lemma — a branch strongly seen on one side is never strongly seen on the other across consistent hashgraphs — because two super-majorities overlap in more than one-third of weight, so their overlap contains an honest creator whose single chain forces one branch ([branching.md](../../concepts/branching.md), [`stronglySeeP` #L1088-L1100](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1088-L1100)).
- `alternative_correct_answers`:
  - "(a) one creator, two events on the same self-parent; (b) per-creator weight counting in `stronglySeeP` splits on a fork and never double-counts, so a branch can't reach 2/3; (c) Strongly Seeing Lemma — overlapping super-majorities share an honest non-forking creator, forcing one branch."
  - "(a) equivocation via a shared self-parent; (b) the agreement loop counts creators once, so forks split the tally below super-majority and add no weight; (c) the lemma from quorum intersection: the honest creator in the overlap has one chain."
- *Invariant exercised (exposition):* tolerance is structural — per-creator agreement counting plus quorum intersection (the Strongly Seeing Lemma) replace the detector and the message rounds a classical protocol would need.
- `followup` (if the learner gives (a) and (b) but omits or hand-waves (c)): "You have what branching is and how the count defangs it — now state the lemma precisely and name *who* must be in the overlap of the two super-majorities, and why that participant settles which branch is strongly seen."

## Delta callout

`[TBD: delta-map/hashgraph.md is not yet authored — the delta-map/ directory currently holds only README.md, so there is no current-versus-proposed entry for strong-seeing or branching to summarize.]` Status: **not started** (per the [delta-map index](../../delta-map/README.md)). When that delta lands it will be linked here as `../../delta-map/hashgraph.md`. Note that the strong-seeing predicates in the code are already the **2020 redefinition** (`SWIRLDS-TR-2020-01`), in which seeing requires a super-majority of paths to agree — this is current code, not a pending change ([branching.md](../../concepts/branching.md)). The hashgraph topic's own forward notes concern a future single Consensus public API and a Sheriff module ([hashgraph.md](../../architecture/topics/hashgraph.md)); neither changes how strong-seeing or branching tolerance is computed. *(Exposition; no learner-facing prompt.)*

## Transfer prompt

Forward framing (motivational only): the next lesson, `a1-04`, builds **fame voting** directly on the relation you just opened — a witness "votes" on an earlier witness by checking what it strongly sees. The question below is answerable now, purely from strong-seeing and the Strongly Seeing Lemma.

- Prompt (verbatim): "You've seen that strong-seeing lets an event identify, for each creator, a single canonical witness that a super-majority of distinct creators agree on — and that by the Strongly Seeing Lemma two consistent hashgraphs can never strongly see opposite branches of a forked creator. Suppose, in a later round, two honest nodes each have a witness that 'votes' on an earlier witness *w* by checking whether it strongly sees *w*. Using only what you have here, argue whether those two honest nodes can ever derive *contradictory* votes about the same earlier witness — and where, exactly, the Strongly Seeing Lemma enters your argument."
- `canonical_answer`: They cannot derive contradictory votes about the same witness. Each node's vote is computed from what its witness strongly sees, and strong-seeing rests on a super-majority of distinct-creator agreement; for a forked creator, the Strongly Seeing Lemma guarantees both nodes strongly see the *same* branch (the overlap of their super-majorities contains an honest, non-forking creator), so they are voting about the same event, not two branches. The lemma enters precisely at the point where a Byzantine creator might otherwise have shown different branches to the two nodes — it forecloses the divergence that would let honest nodes disagree ([branching.md](../../concepts/branching.md), [strongly-seeing.md](../../concepts/strongly-seeing.md)).
- `alternative_correct_answers`:
  - "No — strong-seeing's distinct-creator quorum plus the lemma force both honest nodes onto the same branch of any fork, so their votes concern the same witness and can't contradict; the lemma is what rules out the split."
  - "Honest nodes can't contradict: the lemma (via quorum intersection at the honest creator in the overlap) makes both strongly see the same witness, so the votes are about one event, not two branches."
  - "They agree: equivocation is the only way they'd diverge, and the Strongly Seeing Lemma blocks both branches being strongly seen, so the votes line up."
- `followup` (if the learner asserts they agree without locating the lemma): "Name the exact step where a fork *could* have split the two nodes, and say what the lemma guarantees at that step that keeps them aligned."

## Close-out retrieval

- Free-recall summary (verbatim): "In your own words: what does strong-seeing add over plain seeing, how is the agreement actually counted, and why does the possibility of branching make that agreement requirement necessary?"
  - `canonical_answer`: Strong-seeing adds a **Byzantine quorum**: instead of one ancestry path, it requires a super-majority of *distinct creators'* paths to agree on the same canonical witness — counted by creator weight, once each, via the `lastSee`/`seeThru` walk and the `stronglySeeP` super-majority test. It is necessary because creators can **branch** (equivocate); the distinct-creator agreement neutralizes a fork (split paths reach no super-majority, and a forker's weight counts once), and the Strongly Seeing Lemma guarantees no two consistent hashgraphs strongly see opposite branches — which is what makes virtual voting safe without any messages.
  - `alternative_correct_answers`:
    - "It adds a >2/3 distinct-creator agreement (counted per creator) over the single-path seeing relation; branching is why — the quorum splits a fork below threshold and the lemma keeps honest nodes on one branch."
    - "Strong-seeing = quorum of distinct creators agreeing on a canonical witness, computed by the lastSee/seeThru walk plus a super-majority test; needed because forks would otherwise split honest nodes, which the lemma prevents."
    - "Over seeing it adds Byzantine agreement among distinct creators; branching makes it necessary, and per-creator counting plus quorum intersection (the lemma) make it work message-free."
- Successive-relearning tags (exposition; added to the learner's relearning queue): threshold concept *strong-seeing is the branch-tolerant form of seeing* —
  - Day 1: recall the difference between seeing and strong-seeing, and that strong-seeing needs a super-majority of distinct creators to agree on one canonical witness.
  - Day 3: apply it — work a four-node agreement count (including a branching split) through `stronglySeeP` and decide whether a witness is strongly seen.
  - ~2 weeks: state the Strongly Seeing Lemma and its quorum-intersection reason (overlap of two super-majorities contains an honest, non-branching creator), and explain why it makes virtual voting safe without extra messages.

## Open questions

- **`[TBD]` Delta callout.** `delta-map/hashgraph.md` does not yet exist (the `delta-map/` directory holds only `README.md`), so there is no current-versus-proposed difference for strong-seeing or branching to summarize. Deferred until the hashgraph delta-map entry is authored; the callout links the file once it lands.
- **`[TBD]` No catalogued invariant for the strong-seeing safety property.** The Strongly Seeing Lemma (whitepaper Lemma 5.12) is the safety basis this lesson rests on, but `invariants/` currently catalogs only INV-001 (round-created monotonicity); there is no INV-NNN stating the strong-seeing / branch-uniqueness guarantee. The hashgraph topic raises the same gap as `[TBD: INV-NNN]` ([hashgraph.md](../../architecture/topics/hashgraph.md)). Deferred until the invariants catalog is extended.
