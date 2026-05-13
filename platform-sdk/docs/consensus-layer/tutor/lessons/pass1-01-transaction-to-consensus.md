---
id: pass1-01-transaction-to-consensus
cluster: pass1
title: A transaction flows from submission to consensus
pass: 1
depth: orientation
prerequisites: []
kb_topics_touched:
  - architecture/topics/event-creator.md
  - architecture/topics/event-intake.md
  - architecture/topics/restart-and-pces.md
  - architecture/topics/gossip.md
  - architecture/topics/hashgraph.md
  - architecture/topics/signed-state-management.md
kb_concepts:
  - concepts/event-lifecycle.md
  - concepts/hashgraph-dag.md
  - concepts/rounds-and-witnesses.md
  - concepts/judges.md
kb_glossary_terms:
  - transaction
  - events
  - fields-of-an-event
  - rounds
  - event-relationships
  - misc
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name every consensus-layer component a single user transaction touches between submission and the per-round signed state, and the order they touch it in.
  - State, in one sentence each, what each touched component contributes to moving the transaction toward consensus.
  - Identify the inline-PCES waypoint as the load-bearing durability boundary that sits between event creation and both gossip and hashgraph ingest.
threshold_concepts: []
estimated_session_minutes: 30
status: drafted
last_verified_against: 1b26efc7698950c1f1d52c9e1a32213c8d422b12
---

# A transaction flows from submission to consensus

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in: distributed-systems background, BFT and asynchrony intuitions, and awareness that the platform processes user-submitted transactions and reaches a deterministic order on them.

## Incoming retrieval probes

None — orientation scenario, no incoming probes. The retrieval and self-explanation work begins in the Pass 2 lessons that the Forward pointers section names.

## Components in scope

The trace touches six consensus-layer components and one upstream boundary. Each is named here with a one-sentence semantic so the learner can hold the cast in working memory before the trace integrates them.

- **Execution layer (upstream boundary)** — pool of pending user transactions held outside the consensus layer; the consensus layer pulls from this pool synchronously when an event is being assembled. The interaction surface is the `EventTransactionSupplier` functional contract; see [event-creator.md](../../architecture/topics/event-creator.md).
- **Event creator** — decides when this node creates a new self-event, picks self-parent and other-parent(s) using the tipset algorithm, drains pending transactions from the supplier, signs the event, and emits it. See [event-creator.md](../../architecture/topics/event-creator.md).
- **Inline PCES writer** — synchronously persists every event to the on-disk preconsensus event stream before any downstream component (gossip, hashgraph, event creator's own parent feed) is allowed to see it. The "persisted before gossiped" rule is enforced by wiring ordering, not by the writer itself. See [restart-and-pces.md](../../architecture/topics/restart-and-pces.md).
- **Event intake** — for events arriving from gossip or from PCES replay: hashes, runs four validation stages (internal, deduplication, signature, orphan-buffer linking), and emits a topologically ordered stream. Self-events skip the hashing and signature stages. See [event-intake.md](../../architecture/topics/event-intake.md).
- **Gossip** — broadcasts the locally persisted self-event to every connected peer (simple broadcast over the RPC pipeline) and runs the three-phase sync protocol as a fallback for missed broadcasts and catch-up. See [gossip.md](../../architecture/topics/gossip.md).
- **Hashgraph** — links each topologically ordered event into the in-memory DAG, runs round assignment, witness identification, fame voting, judge selection, and consensus ordering, and emits a `ConsensusRound` when a round is decided. See [hashgraph.md](../../architecture/topics/hashgraph.md).
- **Signed-state management** — at each consensus round, captures the post-handle state into a `SignedState`, collects signatures from peers via `StateSignatureTransaction` payloads, and periodically persists complete round directories to disk. See [signed-state-management.md](../../architecture/topics/signed-state-management.md).

## Scenario setup

A four-node network — call the nodes A, B, C, D — is running steady-state. The hashgraph DAG on each node contains the most recent few rounds of non-ancient events; the latest decided round is *r*. A user submits a transaction `T` to node A through HAPI; the transaction lands in node A's execution-layer pending-transaction pool. No node is in a freeze, no node is reconnecting, no node is unhealthy. We follow `T` from the moment it enters A's pool until the round in which it reaches consensus is signed and (potentially) written to disk.

## Trace

Each stop sits at one component and describes what `T` (or the event carrying `T`) experiences there. Code anchors are omitted at orientation depth — the Pass 2 lessons named in Forward pointers anchor the mechanism in code.

### Stop 1 — Submission to the pending pool

**Component:** Execution layer (upstream boundary). **Topic:** [event-creator.md](../../architecture/topics/event-creator.md) (only the `EventTransactionSupplier` boundary).

**Moment:** `m1-prediction-of-cast`.

`T` is accepted by HAPI on node A and placed in the execution-layer pool of pending transactions. At this point `T` is just a payload waiting for a self-event slot. The consensus layer does nothing with `T` directly; it will only see `T` when the event creator decides to build the next self-event and synchronously calls `EventTransactionSupplier#getTransactionsForEvent()` to drain the pool.

This stop is intentionally light: the orientation lesson exists to plant the cast and the order, not the submission details. The opening engagement-move runs here and is about who comes next, not about what HAPI does internally.

### Stop 2 — Event creator decides and assembles the self-event

**Component:** Event creator. **Topic:** [event-creator.md](../../architecture/topics/event-creator.md). Load-bearing.

The event creator on node A is asked to create an event. It runs the tipset algorithm: each candidate other-parent (a non-ancient peer event with no observed children) is scored by the advancement weight it would produce against the current snapshot tipset; only candidates with non-zero advancement weight are eligible. If at least one candidate qualifies, the event creator selects the highest-scoring candidate(s) up to `maxOtherParents`, sets node A's previous self-event as the self-parent, drains pending transactions (including `T`) by calling `EventTransactionSupplier#getTransactionsForEvent()`, hashes the event, signs it, and emits the new `PlatformEvent` on `createdEventOutputWire`.

The tipset selection logic and the snapshot/advancement-score machinery are mechanism-deep — they live in the A.4 cluster of Pass 2 and are not unpacked here. What matters for the orientation: the event creator is the component that turns "a pile of pending transactions" into "a signed event with parent edges into the existing DAG", and the act of creating an event commits the chosen parents.

### Stop 3 — Inline PCES persists the event before anything else sees it

**Component:** Inline PCES writer. **Topic:** [restart-and-pces.md](../../architecture/topics/restart-and-pces.md). Load-bearing — surprising waypoint.

**Moment:** `m2-pces-waypoint`.

The newly created self-event flows to the PCES writer before it reaches gossip, the hashgraph, or even the event creator's own ordered-event input. The wiring framework solders the writer's output to gossip, to the hashgraph's input, and back to the event creator's parent-selection input with `INJECT` ordering, so none of those downstream consumers can observe the event until the PCES write returns. The motivating invariant: if a self-event were gossiped before being persisted, the node could crash, lose the event, restart without knowing it existed, and create a new self-event on the same self-parent — a hashgraph branch, which is an attack on consensus and is punishable. Inline PCES makes the branch impossible by construction.

The on-disk durability semantics — whether each write triggers an `fsync` or only a buffered write — are governed by configuration and are out of scope for this orientation. What matters: this is the hard boundary between "the event exists in memory" and "the event is part of the node's durable history," and it sits before every other downstream stop. The reviewer note flagged in `[TBD]` below records that the Pass 2 lessons must clarify the current production fsync default; the orientation does not depend on that detail.

[TBD: question for reviewer — the PCES topic flags a delta between `inlinePces.md` (claims default `EVERY_SELF_EVENT`) and current code (`PcesConfig.java:91` defaults `DONT_SYNC`). Pass 2 lesson `c-04-inline-pces-and-restart` must surface the current production default; orientation does not require it.]

### Stop 4 — Gossip broadcasts the persisted self-event

**Component:** Gossip. **Topic:** [gossip.md](../../architecture/topics/gossip.md).

Once PCES has returned, the persisted self-event reaches gossip on the simple-broadcast path. Gossip ships the event as a `BROADCAST_EVENT` over the RPC pipeline to every connected peer (B, C, D in this scenario), bypassing sync's three-phase reconciliation for the immediate push. Sync runs in parallel at a reduced cadence as a fallback for missed broadcasts and for peers that are temporarily disconnected. On each receiving peer, the same event re-enters the platform from the gossip side and runs the *peer-event* path through intake and hashgraph that the next two stops describe.

Broadcast can be paused per peer when the per-peer output queue grows past a threshold or ping latency rises above a threshold; sync handles that connection during the cooldown. The throttling specifics are an A.3 / B-cluster concern and are not unpacked here.

### Stop 5 — Event intake on a receiving peer

**Component:** Event intake. **Topic:** [event-intake.md](../../architecture/topics/event-intake.md).

On node B (and analogously on C, D), the broadcast event arrives at intake's `unhashedEventsInputWire`. Intake runs five stages in series: hash the event; check internal field validity; deduplicate against descriptor + signature pairs already seen; verify the cryptographic signature against B's view of the roster; pass through the orphan buffer, which holds the event until every non-ancient parent has been observed and then releases it (and any descendants whose last missing parent just resolved) in topological order. Each non-ancient stage applies the same birth-round-based ancient filter to drop events outside the current event window.

On node A — the originating node — intake is reached through a different door: A's *own* self-event arrives through intake's `nonValidatedEventsInputWire` (after PCES persists it), bypassing the hash and signature-verification stages because A pre-hashed and pre-signed it. The orphan-buffer stage runs uniformly for self and peer events. Intake's output is a topologically ordered stream of `PlatformEvent`s ready to be linked into the hashgraph.

The stage-by-stage detail (what each stage drops, the orphan-buffer's release walk, the birth-round filter at each stage) lives in cluster A.2 of Pass 2.

### Stop 6 — Hashgraph links the event and decides rounds

**Component:** Hashgraph. **Topic:** [hashgraph.md](../../architecture/topics/hashgraph.md). Load-bearing.

Each topologically ordered event reaches the hashgraph on `eventInputWire`. The hashgraph's per-event lifecycle: short-circuit if the freeze controller has fired, route future-birth-round events to the future-event buffer, link the event into the DAG (drop if ancient, otherwise attach to its already-linked parents and assign the per-event memoized slots used by the algorithm), then call the consensus engine. The engine assigns the event's round-created (inheriting the parents' max, or bumping by one if the event strongly sees a super-majority of weight on the parent round's witnesses), marks the event as a witness if its round-created exceeds its self-parent's, and runs fame voting for any prior witness still undecided. When a round's witnesses all have fame verdicts, judges are selected (one famous witness per creator), the events that are ancestors of every judge take that round-received, and a `ConsensusRound` is emitted on `consensusRoundOutputWire`. The same call may also advance the event window, which retires events past the new ancient threshold and releases any future-buffered events whose birth round is now eligible.

The transaction `T` reaches consensus when its carrying event becomes an ancestor of every judge of some round *r'* ≥ *r*. Round-received and consensus order are decided per round; within a round, events are ordered by preliminary consensus timestamp, extended median of received-times, consensus generation, and the whitened-hash tie-break. The mechanism details — strongly-seeing, fame voting, coin rounds, consensus ordering — are the spine of cluster A.1 in Pass 2.

### Stop 7 — Signed state captures the round and accumulates signatures

**Component:** Signed-state management. **Topic:** [signed-state-management.md](../../architecture/topics/signed-state-management.md).

The decided `ConsensusRound` flows downstream of the hashgraph to the transaction handler, which applies the round's transactions (including `T`) to the working state and produces a fresh `SignedState` for that round. The signature collector accumulates `StateSignatureTransaction` signatures from peers — these payloads ride inside subsequent events on the gossip path — and adds each valid signature to the `SignedState`'s `SigSet` until the signing-weight threshold is crossed. Whether the round's state is also written to disk is decided by the saved-state controller: freeze rounds are saved unconditionally, non-freeze rounds are saved when the round's consensus timestamp crosses a configured `saveStatePeriod` boundary. When a save is selected, the state and a copy of the PCES files needed to replay from it are written under a temporary directory and atomically renamed into the saved-state hierarchy. State copies whose reservation count reaches zero are reclaimed asynchronously by the garbage collector.

This stop closes the trace: `T` has reached consensus, has been applied to the per-round state, and is now part of the signed history (and, eventually, of the on-disk snapshot for any round whose consensus timestamp triggers the periodic write).

## Engagement moves

The trace has two moments. Stop 1 carries the opening prediction-and-reveal that elicits the learner's mental sketch of the cast and order; the inline-PCES stop carries a direct-walk-with-cued-check that surfaces the durability waypoint. Every other stop is a direct walk at orientation altitude.

### Moment `m1-prediction-of-cast` — Stop 1

Why load-bearing: the lesson's job is to plant a complete-but-low-fidelity sketch of which components touch the transaction and in what order. Eliciting the learner's prior model up front gives the tutor a baseline to consolidate against at the close, and surfaces any wrong orderings (e.g. assuming gossip happens before persistence, or assuming the hashgraph receives events directly from gossip) before the trace overwrites them.

**Available moves:**

- **Prediction-and-reveal** *(diagnosis: the learner has prior Hedera or distributed-systems context and can produce a coherent role-level sketch).* Framing: "I'm going to walk a transaction from submission on node A all the way to the round where it's signed. Before I start — what's your gut prediction? Which components on node A do you think this transaction touches, and in what order? Don't worry about being precise; a rough cast list is fine." Optional confidence elicitation when the learner volunteers strong opinions about the order: "How sure are you about the ordering between gossip and persistence?" Canonical answer to consolidate against: the seven stops in this trace, in order: execution-layer pool → event creator → PCES writer → gossip → (peer-side intake → hashgraph → signed state). Consolidation move: name the gap between the prediction and the canonical sketch, with particular attention to (a) where PCES sits relative to gossip and the hashgraph, and (b) the fact that the originating node also runs its own self-event back through intake.
- **Direct walk with cued check** *(diagnosis: the learner is new to the consensus-layer codebase and would frame their own prediction as a guess they could not be expected to have).* Walk the seven-component sketch in one breath without unpacking, then run a cued check: "If I told you that one of these components is the durability boundary — the place where the event becomes part of the node's irreversible history — which one would you pick?" Consolidate on the inline-PCES answer and use that as the bridge into the rest of the trace.

### Moment `m2-pces-waypoint` — Stop 3

Why load-bearing: the inline-PCES stop is the surprising waypoint of the orientation. A senior distributed-systems engineer often expects gossip to ship events the moment they are created and persistence to be a downstream concern; the codebase inverts that ordering specifically to make accidental branching impossible. Naming the surprise explicitly here (without unpacking the wiring-INJECT mechanism) sets up the Pass 2 cluster C lessons that explain the persistence machinery and the cluster A.2 lesson on durability handoff.

**Available moves:**

- **Direct walk with cued check** *(diagnosis: this is the default move for orientation depth — the learner has just heard the stop and the tutor needs to verify that the unusual ordering registered).* Walk the stop as written, then run a cued-recall check: "Why does the wiring put PCES before gossip — what could go wrong if we let gossip ship first and persisted later?" Canonical answer: a self-event that was gossiped but not persisted lets the node, on restart, build a new self-event on the same self-parent — a hashgraph branch, which is provably attributable misbehavior and is punishable. Consolidate on "honest nodes never branch; inline PCES makes branching impossible by construction."
- **Free recall** *(diagnosis: the learner has shown fluency on the durability rationale earlier in the session — for instance, they raised it themselves at moment `m1`).* Skip the walk-through and ask the learner to articulate, in their own words, why the PCES write must complete before gossip sees the event. Consolidate against the same canonical answer.

Prediction-and-reveal is **not** offered at this moment: the durability rationale rests on the no-branch invariant, which the orientation has not yet built up; asking the learner to predict the rationale would be plain failure rather than productive failure. The Pass 2 cluster C lessons reach this point with the invariant in scope and can offer prediction-and-reveal there.

## Consolidation

Two consolidation acts at the end of the trace, before the close-out retrieval.

First, consolidate against the opening prediction from moment `m1`. Walk the canonical seven-stop ordering one more time at headline level — execution pool, event creator, PCES writer, gossip, peer-side intake, hashgraph, signed state — and name explicitly any place the learner's prediction differed: a missing component (most commonly the inline-PCES waypoint or the self-event-through-intake re-entry), an inverted ordering (most commonly gossip placed before persistence), or a missing surface (most commonly the signed-state stop). Do not lecture the difference; just name it.

Second, name the two non-obvious facts the orientation has planted:

- **Inline PCES sits before gossip and before hashgraph ingest.** This is wiring-enforced, not writer-enforced; the persistence-first ordering is a property of how `INJECT` solderings are arranged in `PlatformWiring`. The motivation is the no-branch invariant.
- **The originating node runs its own self-event back through intake on a separate door.** Self-events bypass hashing and signature verification (already done at creation time) but go through orphan-buffer linking on the same path as peer events. The hashgraph receives the originating node's self-event through the same intake → PCES → hashgraph wiring as it receives peer events.

These two facts are the load-bearing orientation takeaways; the rest of the trace is shape and cast.

## Close-out

A brief mental-sketch consolidation: the tutor asks the learner to name the seven stops in order in their own words, without prompting on individual components. The canonical answer is the headline list above. If the learner stalls, hint by naming the boundaries (submission, persistence, gossip, consensus, signed state) rather than the components themselves; the learner should be able to fill the components into those slots.

Successive-relearning tags: none. Orientation scenarios establish the cast and the sketch but do not establish threshold concepts; threshold-concept tagging begins at Pass 2.

A final pointer to the Forward pointers section: the orientation has planted the spiral; each touched component is unpacked at depth in the Pass 2 lesson(s) named below. The Pass 3 deep version of this same trace (`pass3-01-transaction-to-consensus-deep`) revisits every stop with code anchors, perturbations, and cross-cluster invariants once the Pass 2 cluster lessons are in scope.

## Forward pointers

Each component touched by the trace is unpacked in the following Pass 2 lessons. These IDs are drawn from the current manifest at `tutor/curriculum.md`; they are the lessons that will be authored to deepen each stop.

- **Wiring framework foundation** (the substrate every stop runs on): `c0-01-task-schedulers`, `c0-02-wires-and-soldering`, `c0-03-transformers-and-splitters`, `c0-04-wiring-model-and-validation`, `c0-05-backpressure-and-flushing`, `c0-syn-wiring-composition`. The `INJECT` ordering called out at Stop 3 is a wiring-framework concept; `c0-02` is the canonical lesson for it.
- **Event creator** (Stop 2): `a4-01-tipsets-and-advancement`, `a4-02-snapshot-and-creation-rule`, `a4-03-selfishness-and-pity-picks`, `a4-04-creation-permission-rules`, `a4-syn-event-creator-synthesis`.
- **Inline PCES and restart** (Stop 3): `c-04-inline-pces-and-restart`. The replay procedure and ISS-recovery flow live in `c-05-pces-replay-and-iss-recovery`.
- **Gossip** (Stop 4): `a3-01-protocol-stack`, `a3-02-rpc-sync-threading`, `a3-03-three-phase-sync`, `a3-04-broadcast-and-fair-selector`, `a3-syn-gossip-synthesis`.
- **Event intake** (Stop 5): `a2-01-intake-validation-pipeline`, `a2-02-orphan-buffer`, `a2-03-birth-round-filtering-in-intake`, `a2-04-durability-handoff`, `a2-syn-intake-synthesis`.
- **Hashgraph** (Stop 6): `a1-01-hashgraph-dag`, `a1-02-rounds-and-witnesses`, `a1-03-strongly-seeing`, `a1-04-fame-voting-and-coin-rounds`, `a1-05-judges-and-consensus-order`, `a1-06-birth-round`, `a1-07-event-lifecycle-and-stale`, `a1-syn-hashgraph-synthesis`.
- **Signed-state management** (Stop 7): `c-01-signed-state-and-reservations`, `c-02-signed-state-on-disk`, `c-03-signed-state-lifecycle`.
- **Steady-state synthesis across the four A clusters**: `a5-syn-steady-state-synthesis`. This synthesis lesson is the closest Pass 2 analogue of the present orientation trace, exercising A.1–A.4 collaborating rather than each in isolation.
- **Deep revisit of this same trace at full depth**: `pass3-01-transaction-to-consensus-deep`.

## Open questions

- [TBD: question for reviewer — the PCES topic flags a delta between `inlinePces.md` (claims default `EVERY_SELF_EVENT`) and current code (`PcesConfig.java:91` defaults `DONT_SYNC`). Pass 2 lesson `c-04-inline-pces-and-restart` must surface the current production default; orientation does not require it.]
