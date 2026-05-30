---
id: a1-syn-hashgraph-synthesis
cluster: a1
title: "Synthesis: one event through the full algorithm"
pass: 2
prerequisites:
  - a1-01-hashgraph-dag
  - a1-02-rounds-and-witnesses
  - a1-03-strongly-seeing
  - a1-04-fame-voting-and-coin-rounds
  - a1-05-judges-and-consensus-order
  - a1-06-birth-round-and-ancient
  - a1-07-stale-events
kb_topics_touched:
  - architecture/topics/hashgraph.md
  - architecture/topics/event-intake.md
kb_concepts:
  - concepts/event-lifecycle.md
  - concepts/hashgraph-dag.md
  - concepts/rounds-and-witnesses.md
  - concepts/strongly-seeing.md
  - concepts/branching.md
  - concepts/voting.md
  - concepts/coin-rounds.md
  - concepts/judges.md
  - concepts/birth-round.md
  - concepts/stale-events.md
kb_glossary_terms:
  - Event
  - Witness
  - Round-created
  - Round-received
  - Birth round
  - Strongly seeing
  - Virtual voting
  - Famous witness
  - Judge
  - Consensus order
  - Ancient threshold
  - Stale
kb_invariants:
  - INV-001
kb_deltas:
  - delta-map/hashgraph.md
kb_decisions: []
learning_objectives:
  - Trace one self-event through the full Cluster A.1 pipeline — admitted by the birth-round gate and linked into the DAG, placed with a round-created and a witness flag, voted famous, collapsed with its round into judges and a consensus order, and finally aged out — naming which sub-lesson's mechanism owns each step.
  - Distinguish the two timescales the journey runs on — one DefaultConsensusEngine.addEvent call adds one event and runs the algorithm (which may decide earlier rounds), while the event's own path to consensus unfolds across many later addEvent calls as the graph grows above it.
  - Identify the single super-majority threshold (more than two-thirds of distinct-creator weight) reused at four points — the round bump, strong-seeing agreement, the fame decision, and the judge weight check — and the three distinct round quantities (birth round, round-created, round-received) the journey holds apart.
  - Explain why every product the event gains — its round, its votes, its fame, the judges, the order — is computed from the shared DAG with no message and no coordinator, so every honest node derives the identical result, and contrast that with the one output that is a per-node local observation, whether the event is reported stale.
  - State the bridge invariant the cluster ends on — an event's transactions are in the consensus order on every honest node or on none — and place the local stale report against it.
threshold_concepts: []
estimated_session_minutes: 40
status: drafted
last_verified_against: eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2
---

# Synthesis: one event through the full algorithm

## Prerequisites

- **`a1-01-hashgraph-dag`** — the hashgraph is an append-only DAG of immutable events linked by self-parent and (today) one other-parent edge; an event enters by being *looked up* against already-present parents and leaves only by aging out.
- **`a1-02-rounds-and-witnesses`** — an event carries three round quantities (birth round, the mutable round-created, round-received); round-created is the max non-ancient parent round plus a super-majority strong-seeing bump, and a witness is a creator's first event in a round-created.
- **`a1-03-strongly-seeing`** — strong-seeing is a super-majority of distinct creators' paths agreeing on one canonical witness; it is the branch-tolerant relation that makes virtual voting safe.
- **`a1-04-fame-voting-and-coin-rounds`** — witnesses vote on earlier witnesses' fame, computed from the DAG (first votes from seeing, counting votes from strong-seeing), decided on a super-majority; coin rounds give liveness only.
- **`a1-05-judges-and-consensus-order`** — a decided round's famous witnesses collapse to one judge per creator (least-base-hash on a brancher), and the judges fix a deterministic four-key consensus order.
- **`a1-06-birth-round-and-ancient`** — an entering event is sorted by birth round into future (buffered), admitted, or ancient (dropped); the ancient threshold sweeps up as rounds decide, evicting events.
- **`a1-07-stale-events`** — a stale event is one admitted then aged out without reaching consensus; the stale set is a per-node local observation, unlike the global consensus order.

This is the cluster's synthesis: it does not introduce a new mechanism, it runs **one event** through all seven so they are seen *collaborating* rather than described in turn. The two prerequisites worth a bounded recall probe if the learner hedges are **the three round quantities** (`a1-02`) and **derived order — judges, no coordinator** (`a1-05`); the whole trace turns on holding those straight.

## Incoming retrieval probes

This section is an authorial signal, not a session-open quiz. The tutor watches for these as the trace re-engages each mechanism and consolidates them in line; it does not open with a recall drill. Each entry names the concept, the prior lesson, and the one-line statement to consolidate against.

- **The three round quantities** (`a1-02`) — *Canonical:* birth round is creation-stamped and immutable (admission/retention), round-created is computed and mutable (voting), round-received is set once at consensus (order). Resurfaces across the whole trace; the journey touches all three in turn.
- **Super-majority across distinct creators** (`a1-02`/`a1-03`/`a1-04`) — *Canonical:* more than two-thirds of total roster weight, counted once per creator — the Byzantine quorum surviving up to one-third faulty weight. Resurfaces four times (round bump, strong-seeing, fame, judge check); consolidate it as *one* threshold reused, not four.
- **Virtual / derived from the DAG** (`a1-04`/`a1-05`) — *Canonical:* votes, fame, judges, and order are computed deterministically from the shared graph and never sent as messages, so every honest node derives the identical result. The spine's organizing idea; resurfaces at the voting and ordering chunks.
- **Strongly-seeing as the branch-tolerant gate** (`a1-03`) — *Canonical:* a super-majority of distinct creators agreeing on one canonical witness; it gates the round bump and backs every counting vote. Resurfaces wherever the trace says "strongly sees."
- **Stale is local; consensus is global** (`a1-07`/`a1-05`) — *Canonical:* the consensus order is identical on every honest node; the stale set is each node's local observation. The contrast the trace ends on; consolidate it at the aftermath chunk.

## Misconception watchlist

- **"One `addEvent` call carries the event all the way to consensus."** *(Over-generalization from the per-event API.)* Sounds like: "so when the engine adds `e`, it comes out ordered." Correction, in line: `addEvent(e)` adds `e` and runs the algorithm, which may decide rounds for *earlier* events; `e` itself reaches consensus only in some *later* call, once enough events pile on top of it to strongly see its round's witnesses ([`DefaultConsensusEngine.addEvent` #L131-L194](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L131-L194)).
- **"The four super-majority checks are four different thresholds."** *(Over-generalization.)* Sounds like: "which two-thirds rule is this one?" Correction: it is the *same* threshold — `Threshold.SUPER_MAJORITY`, more than two-thirds of distinct-creator weight — applied at the round bump, inside strong-seeing, at the fame decision, and at the judge weight check; the quorum reasoning is identical each time ([`Threshold.java#L71-L94`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/base-utility/src/main/java/org/hiero/base/utility/Threshold.java#L71-L94)).
- **"The event's round and witness status are settled once and stay put."** *(Round-conflation, carried into the synthesis.)* Sounds like: "once `e` is round 5 it's round 5." Correction: round-created is the *mutable* quantity — `recalculateAndVote` re-derives it for every non-decided event each time an earlier round decides — and an event's witness status moves with it; only birth round and round-received are fixed ([`recalculateAndVote` #L325-L371](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L325-L371)).
- **"Every output of this trace is agreed across nodes."** *(Adjacent import — assuming all consensus outputs are deterministic.)* Sounds like: "if `e` is stale here it's stale everywhere." Correction: the round, the votes, the judges, and the order are derived from the shared graph and agree network-wide; the **stale set is the one exception** — it reads this node's own admitted-and-ordered events, so it varies node to node ([`DefaultConsensusEngine.addEvent` #L188-L192](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L188-L192), [stale-events.md](../../concepts/stale-events.md)).

## Mechanism

This lesson introduces no new primitive. It takes the seven mechanisms the cluster built — the DAG (`a1-01`), rounds and witnesses (`a1-02`), strong-seeing (`a1-03`), fame voting (`a1-04`), judges and order (`a1-05`), birth-round filtering (`a1-06`), and stale events (`a1-07`) — and follows **one event** through all of them, so the pieces are seen working together. The spine is the real per-event code path, `DefaultConsensusEngine.addEvent`, which the topic file documents as a seven-step pipeline ([hashgraph.md](../../architecture/topics/hashgraph.md), [`DefaultConsensusEngine.addEvent` #L104-L200](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L104-L200)).

**Pre-training — the pieces, by link** (definitions live in the glossary, the concept files, and the prior lessons; named here only to set the trace's vocabulary):

- **`DefaultConsensusEngine`** — the per-event driver; one `addEvent` call gates, links, runs the algorithm, and advances the window (`a1-06`/`a1-07`; [hashgraph.md](../../architecture/topics/hashgraph.md)).
- **`ConsensusImpl`** — the algorithm itself: round, witness, strong-seeing, fame, judges, order (`a1-02`–`a1-05`; [glossary: Virtual voting](../../glossary.md#virtual-voting)).
- **Three round quantities** — birth round (admission/retention), round-created (voting), round-received (consensus) (`a1-02`/`a1-06`; [glossary: Round-created](../../glossary.md#round-created)).
- **Super-majority** — more than two-thirds of distinct-creator roster weight, the one Byzantine threshold the trace reuses (`a1-02`–`a1-05`; [glossary: Super-majority](../../glossary.md#super-majority)).
- **Stale output** — the engine's third output stream, the only per-node-local one (`a1-07`; [glossary: Stale](../../glossary.md#stale)).

**The running example.** Four equal-weight nodes A, B, C, D, so a super-majority is **3 of 4** — the same network the cluster's worked examples used. We follow a single self-event **`e`**, created by node A, from the moment it arrives at the hashgraph to the moment it ages out. `e` arrives validated and in topological order; intake, not the hashgraph, did that work (`a1-01`, [event-intake.md](../../architecture/topics/event-intake.md)).

**Two timescales — hold them apart.** A single `addEvent(e)` call adds `e` and runs the algorithm, which may decide rounds for events *below* `e`. But `e`'s *own* journey to consensus unfolds across **many later calls**, as B, C, and D gossip events that build on top of `e`. The chunks below follow `e`'s journey; each names the `addEvent` step that does the work.

### Chunk 1 — Admitted: `e` enters the DAG `{moment: m1-admit}`

`e` arrives on the engine's input wire and meets the front of `addEvent`. Three gates, in order ([`DefaultConsensusEngine.addEvent` #L107-L137](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L107-L137)):

1. **Freeze check** — `freezeRoundController.isFrozen()`. In steady state the node is not frozen, so `e` proceeds; the frozen path (post-freeze events pre-handled but no further rounds) is Cluster D ([#L107-L115](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L107-L115)).
2. **Birth-round gate** — `futureEventBuffer.addEvent(e)` (`a1-06`). `e`'s birth round is in-window — at or below the pending consensus round and at or above the ancient threshold — so the buffer returns it; a *future* event would be buffered, an *ancient* one discarded ([#L117-L122](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L117-L122), [`FutureEventBuffer.addEvent` #L82-L97](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-utility/src/main/java/org/hiero/consensus/event/FutureEventBuffer.java#L82-L97)).
3. **Link** — `linker.linkEvent(e)` (`a1-01`). Not ancient, so the linker resolves each parent descriptor to the parent `EventImpl` *already held* — it looks parents up, never waits, because intake guaranteed topological order — builds `new EventImpl(e, parents)`, and stores it in two maps ([#L133](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L133), [`ConsensusLinker.linkEvent` #L78-L97](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L78-L97)).

`e` the `PlatformEvent` is immutable from here — transactions, parent hashes, birth round, signature all fixed; the `EventImpl` wrapper adds the mutable scratch slots the algorithm will fill (`a1-01`, [`EventImpl` #L84-L95](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L84-L95)).

**Load-bearing line (signal):** two lessons collaborate at the door. `a1-06`'s birth-round gate decides *whether* `e` may enter; `a1-01`'s linker decides *how* — by lookup against an already-complete set of non-ancient parents. The link step is correct only because the gate (and intake upstream) guarantee every non-ancient parent is already present.

### Chunk 2 — Placed: `e` gets a round-created and a witness flag `{direct walk}`

With `e` linked, `consensus.addEvent(e)` runs the algorithm ([#L142](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L142)). Its first products for `e` are the two from `a1-02`. **Round-created:** the maximum of `e`'s non-ancient parents' round-created, plus one *iff* `e` strongly sees a super-majority of that round's witnesses ([`ConsensusImpl.round` #L1120-L1219](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1120-L1219)). That "strongly sees a super-majority" is exactly `a1-03`'s relation — a super-majority of distinct creators' paths agreeing on one canonical witness, counted once per creator ([`stronglySeeP` #L1054-L1104](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L1054-L1104)). Say `e` bumps to round **r**. **Witness:** `e` is the first event A places in round r — its round-created differs from its self-parent's — so the witness flag is set ([`witness` #L912-L915](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L912-L915)).

Both values are tentative. As earlier rounds decide, `recalculateAndVote` clears and re-derives round-created for every non-decided event, `e` included — what keeps the re-derivation from ever inverting ancestry is **INV-001**, round-created monotonic along ancestry ([`recalculateAndVote` #L325-L371](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L325-L371), [INV-001](../../invariants/INV-001-roundcreated-monotonic-along-ancestry.md)). This chunk is delivered as a direct walk — the learner built it in `a1-02`/`a1-03`; the synthesis only re-seats it as step one of the algorithm.

**Load-bearing line (signal):** the round bump is the first place the super-majority threshold fires, and it consumes `a1-03` whole — strong-seeing is not re-derived here, it is *used*. Note which round quantity moved: round-created, the mutable one.

### Chunk 3 — Decided: `e` votes, and `e`'s own fame is settled `{moment: m2-decide}`

Because `e` is a witness, it now takes part in elections — in both directions ([voting.md](../../concepts/voting.md)).

As a **voter**: when `e` enters round r it votes on every still-undecided witness in earlier rounds, in `voteInAllElections` ([#L506-L564](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L506-L564)). Round difference `d = round(e) − r_candidate` selects the vote: at `d == 1` a **first vote** (does `e` see the candidate — `a1-03`'s seeing as a yes/no), at `d > 1` a **counting vote** (tally the previous round's votes among the witnesses `e` strongly sees), and on a coin round (d a multiple of 12) a coin vote for liveness only ([`firstVote` #L661-L673](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L661-L673), [`getCountingVote` #L586-L605](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L586-L605), [`isCoinRound` #L613-L615](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L613-L615)). None of these is a message; each is read from `e`'s ancestry.

As a **candidate** — and this is where the second timescale matters — `e`'s own fame is decided not in `addEvent(e)` but in later calls, as B, C, and D add witnesses in rounds r+1, r+2, … that vote on `e`. Their first votes seed `e`'s election; their counting votes propagate it; the moment a counting round shows a YES super-majority on `e`, `e` is **famous** ([#L549-L562](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L549-L562)).

**Load-bearing line (signal):** the counting vote is `a1-03` applied to *votes* — a voter copies the previous round's votes among the witnesses it **strongly sees**, so a fork cannot feed it a forged vote, and the **same super-majority** decides. One event, `e`, is both a vote-caster (on its ancestors) and a candidate (for its descendants) — the algorithm has no separate voting phase; voting is what adding witnesses *does*.

### Chunk 4 — Ordered: `e`'s round collapses to judges and a total order `{moment: m3-order}`

A round is **decided** when every witness in it has a fame verdict — `RoundElections.isDecided()` — and the engine is not waiting for init judges (the restart/reconnect gate, open in steady state; Cluster C) ([`isDecided` #L99-L101](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java#L99-L101), [`calculateAndVote` gate #L416-L424](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L416-L424)). When round r decides, `roundDecided` turns its famous witnesses into a consensus order (`a1-05`) ([`roundDecided` #L706-L766](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L706-L766)):

- **Judges.** `findAllJudges` collapses the famous witnesses to one per creator. Since `e` is famous and A did not branch in round r, `e` is A's unique famous witness, so **`e` is A's judge for round r**; a branched creator's tie would break on least base hash ([`findAllJudges` #L136-L164](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java#L136-L164), [`uniqueFamous` #L174-L187](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java#L174-L187)).
- **Order.** The judges fix the round-received and a preliminary consensus timestamp of every event that is a common ancestor of all of them, then `ConsensusSorter` puts those events in a total order by four keys — preliminary consensus timestamp, extended median of received times, cGen, and the judges'-hash-whitened base hash ([`setIsConsensusTrue` #L834-L844](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L834-L844), [`ConsensusSorter.compare` #L42-L75](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusSorter.java#L42-L75)).

`e` itself reaches consensus when it becomes a common ancestor of *some* decided round's judges — its **round-received** set once, its `isConsensus` flag flipped true — and the round leaves the hashgraph on `consensusRoundOutputWire` toward signing ([`ConsensusImpl` sets it true #L836](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L836), [`HashgraphModule.consensusRoundOutputWire` #L73](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph/src/main/java/org/hiero/consensus/hashgraph/HashgraphModule.java#L73)).

**Load-bearing line (signal):** every key in that order is a function of the shared graph and the judge set — no node's local clock, no gossip arrival order, no proposer. That is why the order is **identical on every honest node**: it is *derived*, not chosen. `e`'s round-received, by contrast with its round-created, is now permanent.

### Chunk 5 — Aged out: `e` leaves, and the one local output `{exposition}`

The decided round also carries a new `EventWindow` with a higher ancient threshold, and the engine's back half applies it ([`DefaultConsensusEngine.addEvent` #L184-L193](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L184-L193)). `linker.setEventWindow` evicts every event whose birth round just fell below the threshold, calling `clear()` on each to sever its links for the garbage collector, and returns the evicted list ([`setEventWindow` #L105-L116](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L105-L116)). The engine splits that list with one predicate, `!e.isConsensus()`: events that reached consensus are released, events that **aged out unordered** go to the stale output ([#L188-L192](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L188-L192)). The same advance feeds `futureEventBuffer.updateEventWindow`, releasing buffered future events back into the loop ([#L193](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L193)).

When the threshold finally sweeps past `e`'s birth round, `e` is evicted — and because `e` reached consensus, it is simply released, never reported stale. A *sibling* event `e′`, admitted on this node but never ordered before it aged out, is the one routed to the stale output (`a1-07`). And here the trace ends on the cluster's last distinction: `e`'s round-received and the consensus order are **global** — every honest node computed them identically — but whether `e′` appears in *this* node's stale set is a **local observation**, because the `!isConsensus()` partition reads this node's own admitted-and-ordered events. The bridge invariant that survives: `e′`'s transactions are in the consensus order on every honest node or on none; a stale report is only this node's observation that it did not see `e′` get there before `e′` went ancient here ([stale-events.md](../../concepts/stale-events.md), [glossary: Consensus order](../../glossary.md#consensus-order)).

This chunk is delivered as exposition — it closes the journey and sets up the close-out retrieval; it carries no engagement moment of its own.

## Engagement moves

### Moment `m1-admit`

*Why load-bearing (exposition):* the entry is where two lessons interlock — the birth-round gate (`a1-06`) decides admission, the linker (`a1-01`) does the lookup — and the learner should see that the linker's "never wait, just look up" is *licensed* by the gate and intake, not an independent choice. This is a recap moment; keep the moves light.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is fluent on `a1-06`'s three-way gate and can classify before the reveal.
- `answer_shape`: a fate per event plus the link consequence — three classifications, then what the linker does to the admitted one.
- Framing + prompt (verbatim): "Our node has decided through round 99, so its pending consensus round is 100 and its ancient threshold is 90. Three events reach the engine over gossip with birth rounds 89, 99, and 101. Before I walk the gate: for each, predict whether it enters the DAG now, and for the one that does, say how the linker attaches it to its parents — does it ever wait for a parent to arrive?"
- `canonical_answer`: Birth round **99 → admitted**: the linker links it by *looking its parents up* in its maps and never waits, because intake delivered every non-ancient parent first (topological order). **89 → ancient**: dropped, `linkEvent` returns null. **101 → future**: buffered, released when the window advances ([`FutureEventBuffer.addEvent` #L82-L97](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-utility/src/main/java/org/hiero/consensus/event/FutureEventBuffer.java#L82-L97), [`ConsensusLinker.linkEvent` #L78-L97](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L78-L97)).
- `alternative_correct_answers`:
  - "99 in (linked by parent lookup, no waiting); 89 dropped as ancient; 101 held in the future buffer until the window catches up."
  - "Admit 99 — the linker resolves its already-present parents and never blocks; discard 89; buffer 101."
- `followup` (if the learner says the linker waits for a missing parent): "What did intake guarantee about the order events arrive in? Given that, when the linker goes to attach `99`'s parents, are they already in its map — and what would a *non-ancient* missing parent imply about that guarantee?"

**Move B — direct walk with cued check.** *Diagnosis tag:* the learner is fluent on the gate and the tutor only needs the immutability boundary confirmed before the algorithm runs.
- Prompt (verbatim): "Once `e` is linked, the algorithm starts writing a round number and other values onto it. Which part of `e` is immutable from creation, and which part carries those computed values — and does writing them change `e`'s hash?"
- `canonical_answer`: The `PlatformEvent` — `e`'s transactions, parent hashes, birth round, signature — is immutable from creation; the `EventImpl` wrapper holds the mutable scratch slots (round-created, witness flag, and so on). Writing them does not change `e`'s hash, because they live on the wrapper, not the event ([`EventImpl` #L84-L95](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L84-L95)).
- `alternative_correct_answers`:
  - "The event itself is fixed; the wrapper holds the computed metadata; the hash never changes."
  - "Immutable `PlatformEvent` plus mutable scratch on `EventImpl`; the algorithm writes the latter, so identity is untouched."

### Moment `m2-decide`

*Why load-bearing (exposition):* this is the synthesis insight that no single sub-lesson could state — `e` is simultaneously a voter on its ancestors and a candidate for its descendants, and its fame is decided in *later* `addEvent` calls, not the one that added it. If the learner leaves thinking one call settles `e`, the two-timescale model is lost. The learner built every piece in `a1-04`, so lead with retrieval.

**Move A — free recall.** *Diagnosis tag:* a mid-trace retrieval to make the two-role, two-timescale picture explicit before ordering.
- Prompt (verbatim): "In your own words: `e` is a witness in round r. Name the two different roles `e` plays in fame elections — one looking down the graph at older witnesses, one looking up at newer ones — and say which of those happens in the same `addEvent` call that added `e`, and which happens later."
- `canonical_answer`: As a **voter**, `e` votes on undecided witnesses in earlier rounds the moment it is added — a first vote (does it see the candidate) or a counting vote (tally the strongly-seen previous round's votes); that happens in `addEvent(e)`. As a **candidate**, `e`'s own fame is decided by witnesses in rounds r+1, r+2, … that vote on it — that happens in *later* `addEvent` calls, once those descendants exist. So one call casts `e`'s votes; later calls settle `e`'s fame ([`voteInAllElections` #L506-L564](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L506-L564)).
- `alternative_correct_answers`:
  - "Voter on older witnesses (in `e`'s own call) and candidate for newer ones (in later calls as descendants vote on it)."
  - "`e` votes down the graph when added; `e` is voted on from above across subsequent calls — its fame can't be known until those later events arrive."
- `followup` (if the learner collapses it to one call): "Which events decide `e`'s fame — ones below `e` or above it? Have those above-`e` witnesses even been created yet at the instant `addEvent(e)` runs?"

**Move B — direct walk with cued check.** *Diagnosis tag:* the learner has the roles and the tutor wants the strong-seeing reuse in the counting vote confirmed.
- Prompt (verbatim): "When `e` casts a *counting* vote on an older candidate, it tallies how the previous round's witnesses voted — but only the ones it **strongly sees**, not merely sees. In one sentence: what does insisting on strong-seeing here defend against, and is it the same threshold `e`'s round bump used?"
- `canonical_answer`: Strong-seeing forces a super-majority of distinct creators to agree on each previous-round witness, so a branched or equivocating creator cannot feed `e` a forged vote; and yes, it is the *same* super-majority threshold the round bump used — more than two-thirds of distinct-creator weight ([`getCountingVote` #L586-L605](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L586-L605), [strongly-seeing.md](../../concepts/strongly-seeing.md)).
- `alternative_correct_answers`:
  - "It defends against a fork injecting a fake vote; same >2/3 distinct-creator threshold as the round bump."
  - "Strong-seeing keeps an equivocator's vote out of the tally; it's the one super-majority rule reused."

### Moment `m3-order`

*Why load-bearing (exposition):* the climax — `e` becomes a judge and the round collapses to a deterministic order. The point to land is *derived, not chosen*: the same graph yields the same order on every node, with no coordinator. The learner built this in `a1-05`, so a prediction is apt; a free-recall fallback consolidates it.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is fluent on `a1-05`'s judges and four-key sort and can reason about cross-node agreement.
- `answer_shape`: a yes/no on identical order plus the reason — that every sort input is a graph/judge property, not a local one.
- Framing + prompt (verbatim): "Round r is decided and `e` is one of its judges. Two honest nodes both have this round and the same judge set, but they received the round's events over gossip in different orders and recorded different local arrival clocks. Before I show the sort: must the two nodes produce the *same* consensus order for this round's events? Say why, and name what the sort uses in place of each node's own arrival time."
- Confidence elicitation (verbatim, optional): "How sure are you, low to high, before I reveal it?"
- `canonical_answer`: Yes — identical. None of the four sort keys reads a node's local clock or gossip arrival: the preliminary consensus timestamp and the extended-median key both come from the times the *judges* first saw the event (a property in the shared graph), cGen is a topological property of the round's events, and the final key is the judges'-hash whitening. Every key is a function of the shared graph and judge set, so both nodes derive the same order — it is derived, not chosen ([`ConsensusSorter.compare` #L42-L75](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusSorter.java#L42-L75), [`setIsConsensusTrue` #L834-L844](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L834-L844)).
- `alternative_correct_answers`:
  - "Identical — the timestamp keys use the *judges'* first-seen times from the graph, not local arrival, and cGen and the whitened hash are graph/judge properties; no coordinator, so both nodes agree."
  - "Same order; arrival differs but no sort key reads it — they're all shared-graph functions, so the result is deterministic everywhere."
- `followup` (if the learner says the orders could differ because arrival differed): "Point to the one sort key you think reads local arrival. Now check what the received-times are filled from — this node's receipt clock, or when the *judges* first saw the event?"

**Move B — free recall.** *Diagnosis tag:* the learner is shaky on the famous→judge collapse and the tutor wants it retrieved before the close.
- Prompt (verbatim): "Without looking back: `e` was voted famous. Walk the two steps that turn a round's famous witnesses into a consensus order — what collapses the famous witnesses into judges (and how a branched creator's two famous witnesses are handled), and what the judges then fix for the events below them."
- `canonical_answer`: `findAllJudges` collapses the famous witnesses to one judge per creator, keyed by creator; a branched creator's multiple famous witnesses are merged to the one with the least base hash. The judges then fix the round-received of every event that is a common ancestor of all of them, and those events are sorted into the consensus order by the four keys ([`findAllJudges` #L136-L164](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java#L136-L164), [judges.md](../../concepts/judges.md)).
- `alternative_correct_answers`:
  - "One judge per creator (least base hash on a brancher); the judges fix round-received for their common ancestors, then the four-key sort orders them."
  - "Merge famous witnesses to one per creator deterministically; judges set the consensus round and order of the events below all of them."

## Completion problems

**Problem 1** *(small step blanked — the pipeline order).*
- Statement (verbatim): "An event `e` is added in one `addEvent` call. Put these four steps in the order the engine runs them, and fill the blank: (1) link `e` to its parents, (2) classify `e` against the birth-round gate / future-event buffer, (3) run the consensus algorithm on `e`, (4) if a round decided, advance the window and split off stale events. The order is ____ → ____ → ____ → ____. Then say, in one sentence, why `e` reaching consensus is usually *not* part of this same call."
- Hint ladder:
  - Rung 1 (verbatim): "Open `DefaultConsensusEngine.addEvent` and read it top to bottom — the freeze check, then the future-event buffer, then the loop with the linker and the consensus call, then the window update."
  - Rung 2 (verbatim): "The gate comes before the link (you don't link a future or ancient event); the algorithm runs on a linked event; the window advance happens only after a round decides. And `e` reaches consensus once enough events sit *above* it — have those arrived yet?"
  - Rung 3 (verbatim, gated on effort): "Order: 2 → 1 → 3 → 4. `e` reaches consensus only when later events strongly see its round's witnesses, and those are added in *later* calls, so `addEvent(e)` adds `e` but rarely orders `e` itself."
- `canonical_answer`: **2 → 1 → 3 → 4** (gate/buffer, then link, then run the algorithm, then advance the window and split stale). `e` reaching consensus is usually a *later* call because it requires events *above* `e` to strongly see `e`'s round's witnesses, and those descendants are added in subsequent `addEvent` calls ([`DefaultConsensusEngine.addEvent` #L107-L194](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L107-L194)).
- `alternative_correct_answers`:
  - "2, 1, 3, 4; `e` is ordered later because its descendants — needed to decide its round — arrive in later calls."
  - "Gate → link → algorithm → window/stale; `e`'s own consensus waits on events built on top of it, which come in subsequent calls."
- *Mechanism exercised (exposition):* one `addEvent` call gates, links, runs the algorithm, and (on a decided round) advances the window; an event's own path to consensus spans many such calls.
- `followup` (if the learner puts link before the gate): "Would you ever link a *future* or *ancient* event into the DAG? Which step decides that, and so which must run first?"

**Problem 2** *(more faded — the four super-majorities and the global/local split).*
- Statement (verbatim): "(a) Name the four points in `e`'s journey where the same super-majority threshold (more than two-thirds of distinct-creator weight) is applied. (b) Two honest nodes both finish processing the region of the graph around `e`. For which of these is their result guaranteed identical, and for which can it differ: `e`'s round-received, the consensus order of `e`'s round, and whether `e` (or a sibling `e′`) appears in the node's stale set? (c) State the one global invariant that makes a per-node stale report consistent with global consensus."
- Hint ladder:
  - Rung 1 (verbatim): "Re-read this lesson's Misconception watchlist on the four super-majority checks, and Chunk 5 on global-versus-local; then skim `concepts/stale-events.md`'s threshold-concept line."
  - Rung 2 (verbatim): "(a) Think round bump, strong-seeing, fame, and the judge weight check. (b) Which outputs are computed from the *shared graph* (same everywhere) and which from *this node's own admitted/ordered events*? (c) What does the all-or-none ordering guarantee say about an event's transactions across honest nodes?"
  - Rung 3 (verbatim, gated on effort): "(a) round bump, the agreement test inside strong-seeing, the fame decision, the judge weight check. (b) round-received and the consensus order are identical (graph-derived); the stale set can differ (local observation). (c) an event's transactions are in the consensus order on every honest node or on none."
- `canonical_answer`: (a) The round bump (`a1-02`), the agreement test inside strong-seeing (`a1-03`), the fame decision (`a1-04`), and the judge weight check (`a1-05`) — all `Threshold.SUPER_MAJORITY`. (b) `e`'s **round-received** and the **consensus order** are guaranteed identical on both nodes, because they are derived from the shared graph and judge set; the **stale set** can differ, because the `!isConsensus()` partition reads each node's own admitted-and-ordered events. (c) An event's transactions are in the consensus order on **every honest node or on none**; a local stale report only says this node did not observe the event reach that order before it went ancient here ([`Threshold.java#L71-L94`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/base-utility/src/main/java/org/hiero/base/utility/Threshold.java#L71-L94), [`DefaultConsensusEngine.addEvent` #L188-L192](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L188-L192), [stale-events.md](../../concepts/stale-events.md)).
- `alternative_correct_answers`:
  - "(a) round bump, strong-seeing agreement, fame decision, judge weight check; (b) round-received and order identical, stale set can differ; (c) ordered on all honest nodes or none."
  - "(a) the four are the +1, strong-seeing, fame, and judges; (b) graph-derived outputs (round-received, order) agree, the local stale set need not; (c) the all-or-none consensus-order invariant."
- *Mechanism exercised (exposition):* one super-majority threshold reused four times; graph-derived outputs are global, the stale set is local, and the all-or-none invariant reconciles them.
- `followup` (if the learner marks the stale set as guaranteed identical): "What two things does the `!isConsensus()` partition read — shared graph structure, or *this node's* own admitted and ordered events? Given that, must two nodes' stale sets match?"

## Delta callout

`[TBD: delta-map/hashgraph.md is not yet authored — the delta-map/ directory currently holds only README.md, so there is no current-versus-proposed entry for the hashgraph algorithm to summarize.]` Status: **not started** (per the [delta-map index](../../delta-map/README.md)). When that delta lands it will be linked here as `../../delta-map/hashgraph.md`. The hashgraph topic's own forward notes concern a future single Consensus public API — Execution pulling each round via `nextRound(roster)` for natural backpressure, replacing today's wired `consensusRoundOutputWire` — and a separate Sheriff module that is not present in the current code; neither changes the admit → place → decide → order → age-out algorithm this lesson traces ([hashgraph.md](../../architecture/topics/hashgraph.md)). *(Exposition; no learner-facing prompt.)*

## Transfer prompt

Forward framing (motivational only): the next cluster, **A.2 (Event Intake)**, is the stage *upstream* of everything you just traced — it is what hands the hashgraph the events this lesson assumed were already validated and in order. The question below is answerable now, purely from how the linker behaves at the start of `e`'s journey.

- Prompt (verbatim): "You saw that the linker admits `e` by *looking its parents up* in its maps and never waits — it trusts that every non-ancient parent already arrived. Using only that behavior: what single property must the upstream stage guarantee about the order events are delivered in, and what would go wrong in the linker if a non-ancient event were ever handed to it *before* one of its parents? Name the concrete effect on that event's edges."
- `canonical_answer`: The upstream stage must deliver events in **topological order** — every non-ancient parent before its child. If a child arrived before a non-ancient parent, the linker's parent lookup would miss: `getParentToLink` returns null for the absent parent, so the child would be linked **without that edge**, corrupting its ancestry — and since the linker never buffers or waits, nothing would repair it later. That missing-edge corruption is exactly why intake must establish the order before the hashgraph sees the stream ([`ConsensusLinker.linkEvent` #L78-L97](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L78-L97), [event-intake.md](../../architecture/topics/event-intake.md)).
- `alternative_correct_answers`:
  - "Topological order — parents before children. Out of order, the linker can't find the parent, so the child gets linked missing that edge, and nothing fixes it because the linker never waits."
  - "Every non-ancient parent must arrive first; otherwise the parent lookup returns null and the child is admitted with a broken parent edge, corrupting its ancestry."
- `followup` (if the learner says the linker would wait or buffer the early child): "Re-check what `linkEvent` does — does it ever block on a missing parent, or look up whatever is present and proceed? Given it never waits, what happens to the edge toward the not-yet-arrived parent?"

## Close-out retrieval

- Free-recall summary (verbatim): "In your own words, walk one self-event `e` through the whole algorithm: how it is admitted, how it gets a round and becomes a witness, the two roles it plays in fame elections, how it ends up ordered, and what finally happens to it when the window advances. Then say the one thing about its journey that every honest node agrees on, and the one thing that is only a local observation."
  - `canonical_answer`: `e` is gated by birth round and linked into the DAG by parent lookup (`a1-01`/`a1-06`); the algorithm computes its round-created — max parent round plus a super-majority strong-seeing bump — and flags it a witness (`a1-02`/`a1-03`); as a witness it votes on older witnesses and is itself voted famous by newer ones across later calls (`a1-04`); when its round decides it becomes A's judge, and the judges fix a deterministic four-key consensus order, setting `e`'s round-received once (`a1-05`); finally the ancient threshold sweeps past `e`'s birth round and evicts it, releasing it because it reached consensus (`a1-06`). Every honest node agrees on `e`'s round-received and the consensus order (graph-derived); the only local observation is whether a node reports an unordered event stale (`a1-07`).
  - `alternative_correct_answers`:
    - "Gate + link, then round/witness via strong-seeing, then vote-and-be-voted-on for fame, then judges fix the order (round-received set once), then age-out. Global: the order; local: the stale report."
    - "Admitted by birth round, placed with a computed round, made famous by later witnesses, ordered by the judges, then evicted; the order is identical everywhere, the stale set is per-node."
- Successive-relearning tags (exposition; added to the learner's relearning queue): this lesson establishes **no new threshold concept** — it is where the cluster's seven threshold concepts operate together on one event. Those concepts keep their existing relearning intervals; the tutor consolidates them here as "all seven at work on `e`'s journey," watching especially for the three round quantities (`a1-02`), derived order (`a1-05`), and stale-is-local (`a1-07`) if their intervals fall in this session, rather than scheduling new tags.

## Open questions

- **`[TBD]` Delta callout.** `delta-map/hashgraph.md` does not yet exist (the `delta-map/` directory holds only `README.md`), so there is no current-versus-proposed difference for the hashgraph algorithm to summarize. Deferred until the hashgraph delta-map entry is authored; the callout links the file once it lands.
- **`[TBD]` Several A.1 safety/liveness properties this synthesis leans on are catalogued only as concept prose, not as INV-NNN.** The trace rests on more than the one catalogued invariant (INV-001, round-created monotonicity): the Strongly Seeing Lemma (`a1-03`), fame-election termination (`a1-04`), consensus-order determinism (`a1-05`), the parent birth-round invariant (`a1-06`), and stale non-determinism (`a1-07`) are each stated in concept files and flagged in those lessons' Open questions, but `invariants/` holds only INV-001. The hashgraph topic raises the same gap as `[TBD: INV-NNN]` ([hashgraph.md](../../architecture/topics/hashgraph.md)). Deferred until the invariants catalog is extended; this is a consolidated pointer, not a new gap.
