---
id: a1-02-rounds-and-witnesses
cluster: a1
title: "Rounds and witnesses"
pass: 2
prerequisites:
  - a1-01-hashgraph-dag
kb_topics_touched:
  - architecture/topics/hashgraph.md
kb_concepts:
  - concepts/rounds-and-witnesses.md
  - concepts/strongly-seeing.md
  - concepts/birth-round.md
kb_glossary_terms:
  - Round
  - Round-created
  - Round-received
  - Birth round
  - Witness
  - Strongly seeing
  - Super-majority
kb_invariants:
  - INV-001
kb_deltas:
  - delta-map/hashgraph.md
kb_decisions: []
learning_objectives:
  - Distinguish the three round quantities an event carries — birth round (stamped at creation, immutable, gates DAG membership), round-created / voting round (computed by the algorithm, mutable), and round-received / consensus round (set once at consensus) — and state what each is used for.
  - Compute an event's round-created from its parents — the parent round (the maximum of its non-ancient parents' round-created) plus a bump of one when the event strongly sees a super-majority of that round's witnesses.
  - Define a witness as the first event by a creator in a given round-created, and apply the predicate (no self-parent, or round-created differs from the self-parent's).
  - Explain why round-created is tentative and recalculated as earlier rounds decide, while birth round and round-received are not.
  - State INV-001 (round-created is monotonic along ancestry) and explain why it makes the witness predicate's `!=` equivalent to `>`, and why the algorithm stalls if it is ever violated.
threshold_concepts:
  - "Round-created is computed, not stamped: the algorithm derives each event's round-created from its position in the DAG (via strongly-seeing over the roster) and recomputes it as earlier rounds decide, so it is tentative until the event's round is decided — unlike birth round (fixed at creation) and round-received (fixed at consensus)."
estimated_session_minutes: 35
status: drafted
last_verified_against: eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2
---

# Rounds and witnesses

## Prerequisites

> - **`a1-01-hashgraph-dag`** — you can picture the hashgraph as an append-only DAG of immutable events linked by self-parent and (today) one other-parent edge; you know an event's content and parent edges are fixed the moment it is built, so its set of ancestors never changes; and you know an event's **birth round** is stamped at creation and used only to decide when the event ages out of the DAG.

Beyond that, this lesson assumes the general distributed-systems background the audience brings — in particular Byzantine quorum reasoning, so that "more than two-thirds of weight, across distinct creators" reads as a familiar fault-tolerance threshold rather than something to derive here. This lesson opens up the first piece of the consensus algorithm that runs over the DAG: how each event gets a *round*, and which events become *witnesses*. It deliberately stops short of how the round-advancing relation is computed (strongly-seeing is a black box here — `a1-03`), how witnesses vote (`a1-04`), and how judges fix consensus order (`a1-05`).

## Incoming retrieval probes

This is the second mechanism lesson in Cluster A.1. Two concepts from `a1-01` resurface here and the tutor should consolidate against them when they come up — these are authorial watch-for signals, not an opening quiz.

- **Birth round**, from `a1-01-hashgraph-dag`. Canonical statement the tutor consolidates against: *birth round is stamped at creation, is immutable, and is used only for windowed retention (ancient filtering) — it is not the round the algorithm uses to order events.* This lesson reintroduces birth round as the *third, fixed* round quantity, held apart from the computed round-created. If the learner reaches for it, affirm and sharpen the distinction; do not quiz it at session open.
- **Fixed ancestry / append-only**, from `a1-01-hashgraph-dag`. Canonical statement: *once an event exists, its parent edges — and therefore its set of ancestors — are fixed.* Round-created is computed *from* that ancestry, and INV-001 below rests on it, so consolidate it explicitly if the learner is shaky when round computation first leans on it.

## Misconception watchlist

- **"A round number is assigned once and then fixed."** *(Adjacent-protocol import.)* In Paxos/Raft/PBFT a ballot/term/view is a monotonic label that, once chosen, does not change. Sounds like: "once it's in round 5 it's round 5," "why would a round ever change?" Correction, in line: **round-created is mutable** — the algorithm recalculates it for every non-decided event each time an earlier round decides (`recalculateAndVote`), so treat it as *tentative until the round decides*. Only round-received and birth round are fixed ([rounds-and-witnesses.md](../../concepts/rounds-and-witnesses.md)).
- **"Round-created is the wall-clock round the event was made in, or just its birth round."** *(Round-conflation — the trap that recurs all through A.1.)* Sounds like: "round-created is when it was created," "isn't that the birth round?" Correction: round-created (the *voting round*) is a computed quantity used only to identify witnesses and run elections; it is derived from the event's parents and strongly-seeing, **not** from wall-clock time and **not** the same as birth round (the creation-stamped retention key from `a1-01`) ([glossary: Round-created](../../glossary.md#round-created), [glossary: Birth round](../../glossary.md#birth-round)).
- **"Every event is a witness."** *(Over-generalization.)* Sounds like: using "witness" as a synonym for "event." Correction: a witness is specifically the **first** event by a creator in a given round-created; most events are not witnesses. Only witnesses vote and can become judges — the subject of `a1-04` and `a1-05` ([rounds-and-witnesses.md](../../concepts/rounds-and-witnesses.md)).
- **"Round-created and round-received are the same round."** *(Round-conflation.)* Sounds like: "the round it reaches consensus is its round-created." Correction: round-created is the mutable voting round the elections run on; round-received is the round the event reaches consensus order, set exactly once and never changed. How round-received is assigned is `a1-05`; this lesson only needs that the two are different quantities ([glossary: Round-received](../../glossary.md#round-received)).

## Mechanism

The hashgraph module runs the consensus algorithm over the DAG `a1-01` built ([hashgraph.md](../../architecture/topics/hashgraph.md)). This lesson covers the algorithm's first two products for each event: its **round-created** and whether it is a **witness**. Everything downstream — strongly-seeing's internals, voting, judges, consensus order — builds on these and comes in later lessons.

**Pre-training — terms this lesson integrates** (definitions live in the glossary and concept files; named here to set vocabulary):

- **Round-created** (= *voting round*) — a computed, *mutable* per-event round number the algorithm derives to identify witnesses and run elections; "voting round" is the more accurate name because it is not wall-clock ([glossary: Round-created](../../glossary.md#round-created), [rounds-and-witnesses.md](../../concepts/rounds-and-witnesses.md)).
- **Round-received** (= *consensus round*) — the round in which an event reaches consensus order; set exactly once, never changed ([glossary: Round-received](../../glossary.md#round-received)).
- **Birth round** — recap from `a1-01`: stamped at creation, immutable, gates DAG membership; not used by the algorithm to order events ([glossary: Birth round](../../glossary.md#birth-round), [birth-round.md](../../concepts/birth-round.md)).
- **Witness** — the first event by a creator in a given round-created ([glossary: Witness](../../glossary.md#witness)).
- **Parent round** — the maximum of an event's *non-ancient* parents' round-created; the base the round-bump adds to ([`ConsensusImpl.parentRound`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L941-L954)).
- **Strongly seeing** — used here as a **black box**: "event *x* strongly sees witness *y*" means a super-majority of roster weight, across distinct creators, lies on paths between *y* and *x*. It is the gate for a round bump. *How* it is computed is `a1-03` ([glossary: Strongly seeing](../../glossary.md#strongly-seeing), [strongly-seeing.md](../../concepts/strongly-seeing.md)).
- **Super-majority** — more than two-thirds of total roster weight ([glossary: Super-majority](../../glossary.md#super-majority)).

### Chunk 1 — Three round numbers, kept distinct `{moment: m1-three-rounds}`

Each event carries **three** different round quantities, and conflating them is the recurring trap of Cluster A.1. Keeping them apart is the foundation everything else in this cluster rests on ([rounds-and-witnesses.md](../../concepts/rounds-and-witnesses.md)):

- **Birth round** — stamped on the event by its creator at creation, **immutable**, used for ancient filtering and roster lookup. You met it in `a1-01` as the key the DAG retains events by. It is *not* used by the algorithm to order events ([glossary: Birth round](../../glossary.md#birth-round)).
- **Round-created** (voting round) — a per-event number the algorithm **computes** from the event's parents and strongly-seeing, used to identify witnesses and run elections. It is **mutable**: it is recomputed as earlier rounds decide (Chunk 4). It lives in a scratch slot on the in-memory node — recall from `a1-01` that `EventImpl` memoizes derived consensus values alongside the immutable event; round-created is one of them ([`EventImpl.getRoundCreated`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L334-L340)).
- **Round-received** (consensus round) — the round in which the event reaches consensus order. Set **exactly once**, at consensus, and never changed thereafter. It too is a scratch slot, holding `ROUND_UNDEFINED` until the event reaches consensus ([`EventImpl.getRoundReceived`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L112-L114)). How it is assigned is `a1-05`; here it serves only as the third quantity to keep distinct.

**Load-bearing line (signal):** of the three, **round-created is the only one that changes** after first assignment. Birth round is fixed at creation (`a1-01`); round-received is fixed at consensus (`a1-05`); round-created is computed and tentative (this lesson). Every later confusion in the cluster traces back to merging two of these three — hold them apart.

### Chunk 2 — Computing round-created: parent round plus a bump `{moment: m2-round-bump}`

Ancient events and events that have already reached consensus are marked with a round of negative infinity — call these **terminal** events — and take no further part in round computation. The algorithm assigns every other event a round-created by one rule ([`ConsensusImpl.round`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1120-L1219), matching the paper's `round(x) = r + i`, `i ∈ {0,1}`):

1. An event with **no parents** takes the first round, round 1 ([`#L1149-L1152`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1149-L1152)).
2. Otherwise let **r = the parent round** — the maximum round-created over the event's *non-ancient* parents ([`parentRound` #L941-L954](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L941-L954)).
3. The event's round-created is **r**, **unless** it strongly sees a super-majority of the witnesses of round r — in which case it **bumps to r + 1** ([`#L1195-L1218`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1195-L1218)). Concretely, the code counts the creators whose round-r witness this event strongly sees, sums their roster weight, and applies the bump when that weight clears the super-majority threshold.

Worked shape (from [rounds-and-witnesses.md](../../concepts/rounds-and-witnesses.md)): four equal-weight nodes, so super-majority is 3 of 4. Event *x*'s non-ancient parents are all in round 1, and among *x*'s ancestors are round-1 witnesses created by three of the four nodes (B, C, D). Three distinct creators clear the super-majority, so *x* strongly sees a super-majority of round-1's witnesses and its round-created bumps to **2**.

**Structural detail (grounded in code, not paraphrase):** when the parents' rounds are **unequal**, the code assigns the greatest parent round directly and *skips* the strong-seeing check — because an event that could not strongly see a super-majority at the higher round provably cannot bump above it ([`#L1172-L1185`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1172-L1185)). This is an optimization; the conceptual rule (`r` plus zero or one) is unchanged. (There is a narrow single-node-super-majority carve-out that matters only in testing; the production path is the two cases above.)

**Load-bearing line (signal):** the **+1 is gated on a super-majority**. Round advancement is not "one more parent, one more round" — it requires a Byzantine quorum of distinct creators' witnesses to be strongly seen. That gate is exactly what makes round advancement safe under up to one-third faulty weight; it is why the round number means something the algorithm can vote on, rather than just a depth counter.

### Chunk 3 — Witnesses: the first event of a creator in a round `{moment: m3-witness}`

A **witness** is the first event by a creator in a given round-created ([rounds-and-witnesses.md](../../concepts/rounds-and-witnesses.md)). The code treats this predicate as the definition ([`ConsensusImpl.witness`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L912-L915)): *x* is a witness iff its round-created is a real round (greater than negative infinity) **and** either it has no self-parent **or** its round-created **differs from its self-parent's**.

Be precise about the comparison. The code writes the second clause as `round(x) != round(selfParent(x))`, not `>`. By **INV-001** (Chunk 4 — round-created is monotonic along ancestry), a child's round-created is always greater than or equal to its self-parent's, so `!=` can only mean *greater*. The two phrasings coincide, and the `!=` form is safe *precisely because* INV-001 holds: a witness is the event where a creator's round-created **first exceeds** its previous event's — the first event that creator places in a new round ([INV-001](../../invariants/INV-001-roundcreated-monotonic-along-ancestry.md)).

Two edges worth noting:

- An event with **no self-parent** (a creator's very first event, or one whose self-parent has gone ancient — `selfParent` returns null for an ancient self-parent) is a witness by the predicate's first branch ([`selfParent` #L920-L922](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L920-L922)).
- The witness flag is set on the node straight from this predicate: per event the engine computes round-created, tests `witness`, and stores the result ([`calculateAndVote` #L373-L404](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L373-L404), [`EventImpl.isWitness` #L125-L131](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L125-L131)).

**Load-bearing line (signal):** witness-hood is **relative to round-created**, which is mutable. When round-created is recalculated (Chunk 4), an event's witness status can change with it. Witnesses are also the events that vote and can become judges — the machinery of `a1-04` and `a1-05` — so getting "which events are witnesses" right is what those later lessons stand on. (This lesson does not run any election; it only identifies the witnesses elections will run on.)

### Chunk 4 — Round-created is tentative: recalculation and INV-001 `{moment: m4-recalculation}`

Because round-created depends on strongly-seeing and the super-majority threshold — both **weighted by the active roster** — it is not final until the event's round is decided. Each time an earlier round decides, the engine recomputes it: `recalculateAndVote` walks the list of recent (non-decided) events and, for every non-ancient non-consensus event, clears its consensus metadata, resets its round-created, and recomputes round and witness status from scratch ([`ConsensusImpl.recalculateAndVote` #L325-L371](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L325-L371)).

Today the roster does not change during normal operation, so the recomputed round-created values come out **identical** — the recalculation is, in practice, a no-op for round-created. The code runs it on every decided round anyway, in anticipation of a **fully dynamic address book** (mid-run roster changes): when a roster transition lands, the weights behind a round-bump change, and an event's round-created genuinely can come out different, so re-deriving every non-decided event keeps the values correct ([rounds-and-witnesses.md](../../concepts/rounds-and-witnesses.md)). Until then, the lesson-level takeaway is the threshold concept: **round-created is computed and tentative, not stamped.**

What must survive every recalculation is **INV-001**: round-created is **monotonic along ancestry** — `roundCreated(child) ≥ roundCreated(parent)` for every parent, at all times, including in the middle of the per-round metadata reset ([INV-001](../../invariants/INV-001-roundcreated-monotonic-along-ancestry.md)). It is a definitional consequence of the round rule (`r` = max parent round, plus zero or one, so a child is never below any parent), not an implementation choice. The algorithm leans on it directly: the witness search and fame voting both assume rounds increase along ancestry, so an ancestor whose round-created exceeded a descendant's would stall consensus.

**Structural detail (grounded in code):** recalculation clears and recomputes *every* non-terminal event, with **one carve-out** — a *judge* from the last decided round whose parents are all terminal keeps its metadata, to serve as the anchor descendants recompute against. (A judge is a special witness from a decided round; it is defined fully in `a1-05`.) The carve-out is safe precisely because such an event has no non-terminal ancestor whose recomputed round could rise above it — so it cannot break monotonicity ([`#L330-L350`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L330-L350)). A broader carve-out — exempting judges that *do* have non-terminal descendants in the same class — is the documented way INV-001 was historically broken ([SCN-001](../../scenarios/SCN-001-same-round-judge-ancestry-stalls-consensus.md); the judge mechanics are `a1-05`).

**Load-bearing line (signal):** round-created is **mutable but INV-001-constrained**. The mutability is what lets the algorithm keep rounds correct as the graph (and one day the roster) evolves; the invariant is what keeps that mutability from ever inverting ancestry, which would freeze consensus. This pairing *is* the threshold concept of the lesson. *(See the Contrasting cases material below, which the tutor draws on at this moment.)*

## Engagement moves

### Moment `m1-three-rounds`

*Why load-bearing (exposition):* this is the disambiguation the whole cluster depends on and the chief misconception site; the learner must leave with three distinct round quantities, one of them mutable.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is fluent on `a1-01`'s birth round and on consensus rounds from other protocols, and may assume a single fixed round number.
- `answer_shape`: a distinction plus a mutability claim — *a separately-computed round number, and whether it is fixed or changing.*
- Framing + prompt (verbatim): "From the last lesson, you know every event is stamped with a birth round at creation — fixed forever, used only to decide when it ages out. The consensus algorithm also needs a notion of 'round' to run its elections on. Before I show you: would you expect that election round to be the *same* number as the birth round, or something the algorithm computes *separately*? And once it's assigned, would you expect it to stay fixed, or to be able to change?"
- Confidence elicitation (verbatim, optional): "How sure are you, low to high, before I reveal it?"
- `canonical_answer`: It is a separately-computed quantity — round-created, the voting round — derived from the event's parents and strongly-seeing, not the birth round; and it is **mutable**: it can be recalculated as earlier rounds decide, unlike birth round (fixed at creation) and round-received (fixed at consensus) ([rounds-and-witnesses.md](../../concepts/rounds-and-witnesses.md)).
- `alternative_correct_answers`:
  - "Separate and computed, not the birth round — and it can change, because the algorithm recomputes it as the graph grows."
  - "The algorithm works out its own round from the parents; it's tentative until the round decides, whereas birth round never moves."
  - "Different number, computed from ancestry; mutable until consensus settles the round."
- `followup` (if the learner says it is the birth round, or assumes it is fixed): "Birth round is stamped once at creation and never recomputed — but this election round is recomputed by the algorithm as the graph grows. So can it be the birth round? What would have to be true of birth round for that to work?"

**Move B — worked example with self-explanation.** *Diagnosis tag:* the learner is new to the three-way distinction and is unsure why a separate round is needed at all.
- Walk (exposition): lay the three side by side — birth round (creation / immutable / retention), round-created (computed / mutable / elections), round-received (consensus / set-once / consensus order). **Load-bearing line:** only round-created changes.
- Self-explanation prompt (verbatim): "Birth round and round-created are both 'a round number on an event,' yet one is stamped once at creation and the other is recomputed by the algorithm. What goes wrong if you just use the birth round as the election round, instead of computing a separate round-created?"
- `canonical_answer`: Birth round is fixed by the creator at creation and reflects the creator's own pending round, not the event's position relative to *other* creators' witnesses. The algorithm needs a round derived from the event's ancestry — who it strongly sees — and one that can be recomputed as the graph (and one day the roster) evolves. Birth round can serve neither role: it is not computed from ancestry and it never changes.
- `alternative_correct_answers`:
  - "Birth round doesn't depend on what the event sees of other creators, and it can't be recomputed; elections need a round that does both."
  - "You'd be voting on a number fixed at creation that ignores the event's actual ancestry — and that can't adjust as rounds decide."
  - "Birth round is a retention key, not a measure of graph position; the election round has to track ancestry and stay recomputable."
- `followup` (if the learner restates that they differ without saying why birth round can't serve): "That's the distinction — now say the *job* round-created does that birth round structurally cannot, and why being computed-from-ancestry matters for that job."

**Move C — direct walk with cued check.** *Diagnosis tag:* the learner is clearly fluent and needs only a verify before moving on.
- Prompt (verbatim): "Name the three round quantities an event carries, and say which single one of the three can change after it is first assigned."
- `canonical_answer`: Birth round (fixed at creation), round-created / voting round (mutable), and round-received / consensus round (fixed at consensus); only round-created changes.
- `alternative_correct_answers`:
  - "Birth round, round-created, round-received — round-created is the mutable one."
  - "Birth round and round-received are fixed; round-created (the voting round) is the one that gets recalculated."

### Moment `m2-round-bump`

*Why load-bearing (exposition):* the round-created computation is the lesson's central new mechanism; the super-majority gate on the bump is what makes the round meaningful. This is new material, so the worked example leads.

**Move A — worked example with self-explanation.** *Diagnosis tag:* the learner is new to the round computation (the common case here).
- Walk (exposition): four equal-weight nodes (super-majority 3 of 4). *x*'s non-ancient parents are all in round 1; among *x*'s ancestors are round-1 witnesses by B, C, and D. *x* strongly sees a super-majority of round-1's witnesses, so its round-created bumps from the parent round 1 to **2** ([`round` #L1195-L1218](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1195-L1218)). **Load-bearing line:** the +1 fires only on a super-majority of *distinct creators'* round-1 witnesses being strongly seen.
- Self-explanation prompt (verbatim): "The rule bumps *x* to round 2 only because it strongly sees round-1 witnesses by three of the four creators. Why does advancing the round require a *super-majority of distinct creators'* witnesses, rather than, say, just one witness being seen?"
- `canonical_answer`: The super-majority across distinct creators is the Byzantine quorum that tolerates up to one-third faulty weight. A single witness could belong to a faulty creator, so one is not enough; requiring more than two-thirds of weight across distinct creators guarantees that any two events advancing to the next round share enough honest common ancestry for strong-seeing and fame to stay safe.
- `alternative_correct_answers`:
  - "One witness could be a Byzantine creator's; >2/3 of distinct weight is what survives up to 1/3 faulty."
  - "It's the BFT quorum — it forces overlap of honest creators between any two advancing events, which a single witness can't."
  - "Without the super-majority the round number couldn't be safely voted on; one witness gives no fault tolerance."
- `followup` (if the learner just names the threshold): "You've named the threshold — now say concretely what a one-witness rule would *fail to tolerate* that the super-majority rule does."

**Move B — prediction-and-reveal.** *Diagnosis tag:* the learner is fluent on Byzantine quorums and can predict the outcome.
- `answer_shape`: a value plus its condition — *the round x takes, and the test that produced it.*
- Framing + prompt (verbatim): "Four equal-weight nodes. Event *x*'s non-ancient parents are all in round 1, and among *x*'s ancestors are round-1 witnesses created by three of the four nodes. The algorithm is about to assign *x* its round-created. What round does it get, and what exactly did it just check?"
- `canonical_answer`: Round 2 — because *x* strongly sees a super-majority (3 of 4) of round-1's witnesses, the bump applies, so round-created = parent round (1) + 1 ([`round` #L1195-L1218](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1195-L1218)).
- `alternative_correct_answers`:
  - "Round 2; it checked whether *x* strongly sees a super-majority of round-1 witnesses, and it does."
  - "2 — parent round 1 plus the bump, because the strongly-seen weight cleared two-thirds."
- `followup` (if the learner answers "2" without the condition): "Right — now name the exact condition that forced the +1, in terms of witnesses and weight."

**Move C — direct walk with cued check.** *Diagnosis tag:* the learner is fluent and the tutor wants to confirm the unequal-parents path.
- Prompt (verbatim): "An event's non-ancient parents are in rounds 4 and 6. With no strong-seeing check at all, what round-created does the event take, and why is the algorithm allowed to skip the strong-seeing check in this case?"
- `canonical_answer`: Round 6 — the parent round is the maximum of the parents' rounds (6); when the parents' rounds are unequal the code assigns that greatest parent round directly, because an event that could not strongly see a super-majority at the higher round cannot bump above it, so the check is unnecessary ([`round` #L1172-L1185](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1172-L1185)).
- `alternative_correct_answers`:
  - "6 — the max parent round; unequal parent rounds mean it can't bump, so the check is skipped."
  - "It takes 6 directly; the optimization skips the strong-see test when parents disagree on round."

### Moment `m3-witness`

*Why load-bearing (exposition):* the witness definition; this is also where the `!=`-vs-`>` precision ties witness-hood to INV-001.

**Move A — worked example with self-explanation.** *Diagnosis tag:* the witness predicate is new to the learner.
- Walk (exposition): the predicate — *x* is a witness iff its round-created is real and (no self-parent, or round-created differs from the self-parent's) ([`witness` #L912-L915](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L912-L915)). **Load-bearing line:** a witness is the first event a creator places in a new round.
- Self-explanation prompt (verbatim): "The code marks *x* a witness when *x* has no self-parent, or when *x*'s round-created is **not equal** to its self-parent's round-created. Given that round-created never decreases from parent to child, why is 'not equal' the same test as 'strictly greater' here — and what does that make a witness, in one phrase?"
- `canonical_answer`: Because round-created is monotonic along ancestry (INV-001), a child's round-created is always at least its self-parent's, so 'not equal' can only mean 'greater.' A witness is therefore the first event by a creator whose round-created exceeds its previous event's — the first event that creator places in a new round ([INV-001](../../invariants/INV-001-roundcreated-monotonic-along-ancestry.md)).
- `alternative_correct_answers`:
  - "Monotonicity rules out 'less,' so 'not equal' means 'greater'; a witness is a creator's first event in a new round."
  - "Round-created can't drop along the self-parent edge, so ≠ collapses to >; the witness is where a creator's round first ticks up."
  - "Since child ≥ parent always, ≠ is >; the witness is the round-opening event for that creator."
- `followup` (if the learner only restates the predicate): "That's the rule — now say *why* the self-parent comparison is enough to mean 'first in the round,' using the fact that round-created never decreases along ancestry."

**Move B — prediction-and-reveal.** *Diagnosis tag:* the learner has the round-bump from `m2` and can predict witness-hood.
- `answer_shape`: a yes/no per event with the rule applied — *is each event a witness.*
- Framing + prompt (verbatim): "Say *x* took round 2 by the bump you just worked out, and *x*'s self-parent is in round 1. Is *x* a witness? Now take the next event *x′* by the same creator, which stays in round 2 — is *x′* a witness?"
- `canonical_answer`: *x* is a witness — its round-created (2) exceeds its self-parent's (1), so it is the creator's first event in round 2. *x′* is not — its round-created (2) equals its self-parent *x*'s (2), so it is not the first in round 2 ([`witness` #L912-L915](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L912-L915)).
- `alternative_correct_answers`:
  - "*x* yes (2 > 1), *x′* no (2 = 2)."
  - "*x* opens round 2 so it's a witness; *x′* sits in the same round as its self-parent, so it isn't."
- `followup` (if the learner gets *x* but calls *x′* a witness too): "Check *x′* against its *self-parent's* round-created, not against round 1 — what are the two round numbers being compared for *x′*, and does the predicate fire?"

**Move C — direct walk with cued check.** *Diagnosis tag:* the learner is fluent and needs only a verify on the no-self-parent branch.
- Prompt (verbatim): "In one sentence: what is a witness, and what makes the very first event a brand-new creator ever makes — one with no self-parent — automatically one?"
- `canonical_answer`: A witness is the first event by a creator in a given round-created; an event with no self-parent has no prior round to match against, so the predicate's 'no self-parent' branch makes it a witness ([`witness` #L912-L915](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L912-L915)).
- `alternative_correct_answers`:
  - "First event by a creator in a round; with no self-parent there's nothing to equal, so it's a witness by default."
  - "The round-opening event for a creator; a parentless first event trivially satisfies the predicate."

### Moment `m4-recalculation`

*Why load-bearing (exposition):* the threshold-concept moment — round-created is computed and tentative, held safe by INV-001. Offers the contrasting cases (to surface the invariant), a worked example (recalculation is new), and a prediction on INV-001 (for the confident).

**Move A — contrasting cases with comparison prompt.** *Diagnosis tag:* threshold concept; transfer is the goal. Uses the three cases in the Contrasting cases material below.
- Prompt (verbatim): "Compare the three round quantities an event carries. (1) Birth round: stamped at creation, never recomputed. (2) Round-created: computed by the algorithm from the event's ancestry, and recomputed every time an earlier round decides. (3) Round-received: set once, when the event reaches consensus, then frozen. Across the three, what is the same, what differs, and which single property of round-created sets it apart from the other two?"
- `canonical_answer`: All three are per-event round numbers stored on the event. They differ in *when* and *how* each is set — birth round at creation by the creator, round-created computed (and recomputed) by the algorithm from ancestry and strongly-seeing, round-received once at consensus. The property that sets round-created apart is that it is the only one that is **mutable** — recalculated after first assignment, tentative until its round decides ([rounds-and-witnesses.md](../../concepts/rounds-and-witnesses.md)).
- `alternative_correct_answers`:
  - "Same: all per-event round numbers. Different: when/how set. Round-created is the only mutable one."
  - "All three are 'a round on the event'; birth round and round-received are fixed, round-created is recomputed — that's what's unique."
  - "They share being round labels; they split on fixed-vs-computed, and round-created alone gets re-derived as rounds decide."
- *Deep invariant (exposition for consolidation, not read as a question):* round-created is computed and tentative, but INV-001 (monotonic along ancestry) constrains it at all times, including across recalculation — and that monotonicity is exactly what the witness search and fame voting rely on.

**Move B — worked example with self-explanation.** *Diagnosis tag:* the learner is new to the recalculation mechanism and puzzled why it runs when nothing changes.
- Walk (exposition): on each decided round, `recalculateAndVote` walks the recent non-decided events and, for each non-ancient non-consensus one, clears metadata, resets round-created, and recomputes round and witness status ([`recalculateAndVote` #L325-L371](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L325-L371)). **Load-bearing line:** everything non-decided is recomputed wholesale; the just-decided anchor events are carved out so descendants have something to recompute against.
- Self-explanation prompt (verbatim): "Today the roster never changes mid-run, so recomputing round-created produces exactly the same values every time — yet the code runs the recalculation on every decided round anyway. What future capability is this for, and what would actually make a round-created value come out different when it arrives?"
- `canonical_answer`: It is for a fully dynamic address book — mid-run roster changes. Strongly-seeing and the super-majority threshold are weighted by the active roster, so if the roster governing a round changes, the weights behind the round-bump change, and an event's round-created can come out different; the recalculation re-derives every non-decided event under the new roster so the values stay correct ([rounds-and-witnesses.md](../../concepts/rounds-and-witnesses.md)).
- `alternative_correct_answers`:
  - "Dynamic rosters; changing weights change which events strongly see a super-majority, so the bump — and the round — can change."
  - "It's groundwork for mid-run roster changes; a different roster reweights strong-seeing, which can move a round-created."
  - "For when the address book can change during a run; the roster weights feeding the bump would differ, altering round-created."
- `followup` (if the learner says "for roster changes" without the weight mechanism): "That's the trigger — now say *how* a roster change reaches round-created: what does it alter that feeds into the bump?"

**Move C — prediction-and-reveal.** *Diagnosis tag:* the learner has the recalculation picture and can reason about the invariant it must preserve.
- `answer_shape`: an inequality between parent and child round-created, plus the consequence of violating it.
- Framing + prompt (verbatim): "You've seen round-created can be recomputed as rounds decide. Given how it's computed — the maximum of the parents' rounds, plus zero or one — what relationship must always hold between a parent's round-created and its child's, even in the middle of a recalculation? And what would break in the algorithm if a recalculation ever left an ancestor with a *higher* round-created than a descendant?"
- `canonical_answer`: A child's round-created is always greater than or equal to every parent's (INV-001) — because the round is the maximum parent round plus zero or one, a child can never fall below a parent. It must hold at all times, including mid-recalculation. If an ancestor's round-created ever exceeded a descendant's, the witness search and fame voting — which assume rounds increase along ancestry — would stall, and consensus would stop advancing ([INV-001](../../invariants/INV-001-roundcreated-monotonic-along-ancestry.md)).
- `alternative_correct_answers`:
  - "child ≥ parent for round-created, always; invert it and the witness/fame logic stalls consensus."
  - "Round-created is monotonic along ancestry; a descendant lower than an ancestor would freeze the algorithm."
  - "≥ from parent to child by construction (max + {0,1}); violating it breaks the round-boundary assumptions voting needs."
- `followup` (if the learner gives the inequality without the consequence): "You've got the rule — now name what the algorithm *does* with rounds increasing along ancestry, so that an inversion stalls it."

## Contrasting cases material

The threshold concept is *round-created is computed, not stamped — tentative until its round decides.* The three cases hold "a round number carried on an event" constant and vary how it is set and whether it changes.

- **Case 1 — birth round.** Stamped by the creator at creation from its pending round, immutable thereafter; used for ancient filtering and roster lookup, not for ordering ([birth-round.md](../../concepts/birth-round.md), [glossary: Birth round](../../glossary.md#birth-round)). *Surface:* set once, at creation, by the creator; never recomputed.
- **Case 2 — round-created (voting round).** Computed by the algorithm as the maximum of the parents' rounds plus a super-majority strong-seeing bump; recomputed for every non-decided event each time an earlier round decides ([`round` #L1120-L1219](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1120-L1219)). *Surface:* computed from ancestry; mutable; tentative until decided.
- **Case 3 — round-received (consensus round).** Set exactly once, at the moment the event reaches consensus order, then frozen ([`EventImpl.getRoundReceived` #L112-L114](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L112-L114)). *Surface:* set once, at consensus, by the algorithm; never recomputed after.

**The deep invariant that survives the surface differences:** all three are per-event round numbers, but only round-created is computed-and-recomputed — and *that* is the one the elections run on, which is why it has to track the event's ancestry and stay re-derivable as the graph and roster evolve. What keeps its mutability safe is **INV-001**: across every recalculation, round-created stays monotonic along ancestry (`child ≥ parent`), so the round boundaries the witness search and fame voting depend on never invert. The mutable quantity is the load-bearing one; the invariant is what lets it be mutable without breaking consensus. *(Strongly-seeing's internals — `a1-03` — and the elections themselves — `a1-04` — are out of scope here; this case set is only about how the three round numbers are set and constrained.)*

## Completion problems

**Problem 1** *(small step blanked).*
- Statement (verbatim): "An event *x* has two non-ancient parents, both with round-created 3, and *x* strongly sees round-3 witnesses created by a super-majority of the roster's weight. *x*'s round-created is ______. Then, in one sentence, say what *x*'s round-created would be instead if it did *not* strongly see a super-majority of round-3 witnesses."
- Hint ladder:
  - Rung 1 (verbatim): "Open `concepts/rounds-and-witnesses.md` and read the 'Computing round-created' paragraph — note the parent round and the round-bump exception."
  - Rung 2 (verbatim): "The parent round here is 3 (both parents are in round 3). What does the bump add when a super-majority of that round's witnesses is strongly seen?"
  - Rung 3 (verbatim): "With the super-majority strong-see, the round bumps to parent round + 1; without it, the event stays at the parent round."
  - Rung 4 (verbatim, gated on effort): "4 — parent round 3 plus the bump, because the super-majority strong-see fires. Without the super-majority it would stay at 3, the parent round, with no bump."
- `canonical_answer`: 4 (parent round 3 + 1, from the super-majority strong-see); without the super-majority it would be 3 (the parent round, no bump).
- `alternative_correct_answers`:
  - "4 with the bump; 3 without it."
  - "Bumped to 4; absent the strong-see super-majority it would remain at the parent round, 3."
- *Invariant exercised (exposition):* round-created = parent round + (1 if a super-majority of that round's witnesses is strongly seen, else 0).
- `followup` (if the learner writes 4 but gives a vague second clause): "Be exact about the no-bump case — what number does it take, and what is that number called relative to the parents?"

**Problem 2** *(more blanked — a chain of events).*
- Statement (verbatim): "Creator A makes three events in order: *a1* (no self-parent, round-created 1), *a2* (self-parent *a1*, round-created 1), *a3* (self-parent *a2*, round-created 2). For each of *a1*, *a2*, *a3*, state whether it is a witness and why."
- Hint ladder:
  - Rung 1 (verbatim): "Open `ConsensusImpl.java` and read the `witness` method — note its two branches: no self-parent, or round-created not equal to the self-parent's."
  - Rung 2 (verbatim): "For each event, compare its round-created with its self-parent's. Which ones differ? And what does the predicate do when there is no self-parent?"
  - Rung 3 (verbatim): "*a1* has no self-parent (first branch). *a2* and *a3* each compare against their self-parent's round-created — equal means not a witness, different means witness."
  - Rung 4 (verbatim, gated on effort): "*a1*: witness (no self-parent). *a2*: not a witness (round-created 1 equals self-parent *a1*'s 1). *a3*: witness (round-created 2 differs from self-parent *a2*'s 1)."
- `canonical_answer`: *a1* is a witness (no self-parent); *a2* is not (round-created 1 = self-parent *a1*'s 1); *a3* is a witness (round-created 2 ≠ self-parent *a2*'s 1) ([`witness` #L912-L915](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L912-L915)).
- `alternative_correct_answers`:
  - "*a1* yes (parentless), *a2* no (1 = 1), *a3* yes (2 ≠ 1)."
  - "Witnesses are *a1* and *a3*; *a2* sits in the same round as its self-parent so it isn't one."
- *Invariant exercised (exposition):* witness-hood is the round-created differing from the self-parent's (or no self-parent), so each creator contributes exactly one witness per round it opens.
- `followup` (if the learner compares *a3* against *a1* instead of *a2*): "Compare each event against its *own* self-parent — *a3*'s self-parent is *a2*, not *a1*. Which two round numbers does the predicate put side by side for *a3*?"

**Problem 3** *(most faded — produce the mechanism from a scenario).*
- Statement (verbatim): "A colleague asks why the consensus code recomputes every non-decided event's round-created every time a round decides, when today those numbers never change. Answer with three things: (a) what makes round-created able to change at all; (b) which method performs the recomputation, and what it does to each event; and (c) which invariant the recomputation must preserve, and why the algorithm needs it."
- Hint ladder:
  - Rung 1 (verbatim): "Open `ConsensusImpl.java` and read `recalculateAndVote`; then open `invariants/INV-001-roundcreated-monotonic-along-ancestry.md` and read the Statement and Notes."
  - Rung 2 (verbatim): "What roster-weighted input feeds the round-bump, and so could change a round-created if it changed? Which method walks the recent events and re-derives their rounds? What property does INV-001 require, and which algorithm steps assume it?"
  - Rung 3 (verbatim): "(a) round-created comes from strongly-seeing weighted by the active roster, so a roster change can change it. (b) `recalculateAndVote` clears metadata and recomputes round and witness status for each non-ancient non-consensus event. (c) INV-001 — round-created monotonic along ancestry — which the witness search and fame voting depend on."
  - Rung 4 (verbatim, gated on effort): "(a) Round-created is computed from ancestry via strongly-seeing, weighted by the active roster, so it is tentative — a roster change can change the weights behind the bump and thus the value. (b) `ConsensusImpl.recalculateAndVote` walks the recent events and, for each non-ancient non-consensus one, clears its metadata, resets round-created, and recomputes round and witness status (carving out last-decided judges with terminal parents to anchor the rest). (c) INV-001 — round-created stays monotonic along ancestry (`child ≥ parent`); the witness search and fame voting assume rounds increase along ancestry, so violating it stalls consensus."
- `canonical_answer`: (a) round-created is computed from the event's ancestry via strongly-seeing, weighted by the active roster, so it is tentative until its round decides and a roster change can change it; (b) `ConsensusImpl.recalculateAndVote` walks the recent non-decided events and, for each non-ancient non-consensus one, clears metadata, resets round-created, and recomputes round and witness status, keeping last-decided judges with terminal parents intact as anchors; (c) INV-001 — round-created is monotonic along ancestry — which the witness search and fame voting rely on, so any inversion stalls consensus ([`recalculateAndVote` #L325-L371](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L325-L371), [INV-001](../../invariants/INV-001-roundcreated-monotonic-along-ancestry.md)).
- `alternative_correct_answers`:
  - "(a) roster-weighted strong-seeing feeds round-created, so it can change; (b) `recalculateAndVote` re-derives round and witness status for every non-decided event; (c) INV-001 monotonicity, needed because voting works along round boundaries."
  - "(a) it's computed from ancestry under a roster that may change; (b) `recalculateAndVote` clears and recomputes the recent events' rounds; (c) round-created stays monotonic along ancestry or consensus stalls."
- *Invariant exercised (exposition):* round-created is mutable-but-constrained — recomputed wholesale yet kept monotonic along ancestry across every reset.
- `followup` (if the learner gives (a) and (b) but omits (c)): "You've got what changes and what recomputes it — now name the invariant the recomputation must never break, and the algorithm steps that would stall if it did."

## Delta callout

`[TBD: delta-map/hashgraph.md is not yet authored — the delta-map/ directory currently holds only README.md, so there is no current-versus-proposed entry for round computation or witnesses to summarize.]` Status: **not started** (per the [delta-map index](../../delta-map/README.md)). When that delta lands it will be linked here as `../../delta-map/hashgraph.md`. The hashgraph topic's own forward notes concern a future single Consensus public API and a Sheriff module ([hashgraph.md](../../architecture/topics/hashgraph.md)); neither changes how round-created or witnesses are computed. *(Exposition; no learner-facing prompt.)*

## Transfer prompt

Forward framing (motivational only): the next lesson, `a1-03`, opens up how "strongly sees" — the relation the round-bump depends on — is actually computed. The question below is answerable now, purely from the witness definition and the monotonicity rule.

- Prompt (verbatim): "You've seen that an event's round-created is the maximum of its parents' rounds plus one *exactly when* it strongly sees a super-majority of that round's witnesses, and that round-created never decreases from parent to child. Suppose an event *x* bumps to round r+1. Using only the witness definition and the monotonicity rule: must *x* be a witness in round r+1? And must every later event by *x*'s creator that stays in round r+1 *not* be a witness? Explain both."
- `canonical_answer`: Yes on both. *x* bumped to r+1 means its parent round (the max over its parents, including its self-parent) is r, so its self-parent's round-created is at most r — strictly below r+1. By the witness predicate, *x*'s round-created differs from (exceeds) its self-parent's, so *x* is a witness: the first event its creator places in r+1. A later event *x′* by the same creator that stays in r+1 has a self-parent already in r+1, so its round-created equals its self-parent's, and the predicate does not fire — *x′* is not a witness ([`witness` #L912-L915](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L912-L915), [INV-001](../../invariants/INV-001-roundcreated-monotonic-along-ancestry.md)).
- `alternative_correct_answers`:
  - "*x* is a witness — its self-parent is at most r, below r+1, so its round-created exceeds the self-parent's. *x′* shares r+1 with its self-parent, so it isn't."
  - "Yes: the bump puts *x* a round above its self-parent, opening r+1 (a witness); later same-creator events in r+1 match their self-parent's round, so they aren't witnesses."
  - "*x* opens round r+1 for its creator (witness); only the first such event qualifies, so subsequent r+1 events by that creator don't."
- `followup` (if the learner asserts *x* is a witness without grounding the self-parent's round): "Say why *x*'s self-parent must be *below* r+1 — what does *x* bumping to r+1 tell you about the maximum of its parents' rounds, and therefore about its self-parent's round?"

## Close-out retrieval

- Free-recall summary (verbatim): "In your own words: what is round-created, how is it computed from an event's parents, and which one of the event's three round quantities is the one that can change after it's assigned?"
  - `canonical_answer`: Round-created (the voting round) is a computed per-event round number — the maximum of the event's non-ancient parents' round-created, plus one when the event strongly sees a super-majority of that round's witnesses. It identifies witnesses and runs elections. It is the only one of the three round quantities that is mutable: it is recalculated as earlier rounds decide, unlike birth round (fixed at creation) and round-received (fixed at consensus).
  - `alternative_correct_answers`:
    - "It's the algorithm's computed round = max parent round + (1 on a super-majority strong-see); the mutable one of the three (birth round and round-received are fixed)."
    - "A voting round derived from parents and strongly-seeing; tentative and recomputed as rounds decide, whereas birth round and round-received don't change."
    - "Max of parents' rounds, bumped by one on a super-majority of strongly-seen witnesses; the only round quantity that gets recalculated."
- Successive-relearning tags (exposition; added to the learner's relearning queue): threshold concept *round-created is computed, not stamped* —
  - Day 1: recall the three round quantities (birth round, round-created, round-received) and which one is mutable.
  - Day 3: apply it — compute a round-created from an event's parents (max parent round, plus one on a super-majority strong-see), and decide whether an event is a witness.
  - ~2 weeks: state INV-001 (round-created monotonic along ancestry), why recalculation preserves it, and why the algorithm needs it (the witness search and fame voting depend on rounds increasing along ancestry).

## Open questions

- **`[TBD]` Delta callout.** `delta-map/hashgraph.md` does not yet exist (the `delta-map/` directory holds only `README.md`), so there is no current-versus-proposed difference for round computation or witnesses to summarize. Deferred until the hashgraph delta-map entry is authored; the callout links the file once it lands.
