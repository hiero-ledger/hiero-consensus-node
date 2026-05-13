---
id: pass1-01-tx-to-consensus
cluster: pass1
title: "Transaction to consensus — orientation walkthrough"
pass: 1
depth: orientation
prerequisites: []
kb_topics_touched:
  - architecture/topics/event-creator.md
  - architecture/topics/event-intake.md
  - architecture/topics/hashgraph.md
  - architecture/topics/gossip.md
  - architecture/topics/restart-and-pces.md
  - architecture/topics/signed-state-management.md
kb_concepts:
  - concepts/event-lifecycle.md
  - concepts/hashgraph-dag.md
  - concepts/rounds-and-witnesses.md
kb_glossary_terms:
  - event
  - self-parent
  - other-parent
  - round
  - witness
  - judge
  - consensus-event
  - consensus-timestamp
  - birth-round
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name every component a transaction passes through between the moment it arrives at Execution and the moment it is part of a decided consensus round.
  - State the role of each component in one sentence — what it produces, what it consumes, what it does not own.
  - Describe in role-level terms why the event is persisted by PCES before it is gossiped or fed to the hashgraph algorithm.
  - Distinguish what happens on the originating node from what happens on its peers as the event propagates.
threshold_concepts: []
estimated_session_minutes: 30
status: drafted
last_verified_against: 1b26efc7698950c1f1d52c9e1a32213c8d422b12
---

# Transaction to consensus — orientation walkthrough

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in.

## Incoming retrieval probes

None — orientation scenario, no incoming probes.

## Components in scope

Each component is described here in one sentence. Deeper mechanism for each lives in the linked topic file and in the Pass 2 cluster that owns it.

- **Execution layer** *(external to this curriculum, named for boundary clarity)* — buffers user transactions and applies decided consensus rounds. The consensus layer reads transactions from Execution via a synchronous supplier when building an event, and hands decided rounds back when they are ready to apply.
- **Event Creator** — decides when this node creates a new self-event, picks the other parents, pulls transactions from Execution into the event, signs it, and emits it. See [`event-creator.md`](../../architecture/topics/event-creator.md).
- **Event Intake** — receives every event (self-events from the local creator, peer events from gossip, replayed events from PCES) and produces a topologically ordered stream of validated events. Owns validation, deduplication, signature verification, and the orphan buffer. See [`event-intake.md`](../../architecture/topics/event-intake.md).
- **PCES (Pre-Consensus Event Stream)** — synchronous on-disk writer that sits between Intake and the rest of the platform. Each event passes through it; nothing downstream sees the event before PCES has written it. See [`restart-and-pces.md`](../../architecture/topics/restart-and-pces.md).
- **Gossip** — exchanges events with peers over per-connection RPC, both by broadcast (push self-events as soon as they are persisted) and by sync (reconcile two peers' DAGs at a reduced cadence). See [`gossip.md`](../../architecture/topics/gossip.md).
- **Hashgraph** — holds the in-memory DAG of non-ancient events and runs the consensus algorithm that turns a topologically ordered event stream into an ordered stream of `ConsensusRound`s. See [`hashgraph.md`](../../architecture/topics/hashgraph.md).
- **Signed state management** *(named for boundary clarity, deep coverage in Cluster C)* — captures a `SignedState` after each consensus round, collects signatures from peers, and exposes the state for persistence and reservation. See [`signed-state-management.md`](../../architecture/topics/signed-state-management.md).

The components are connected by the **wiring framework** — schedulers, queues, and wires that solder outputs to inputs. The wiring framework itself is the substrate covered by Cluster 0; for this orientation walkthrough, treat "the wiring framework routes X from component A to component B" as a black box and focus on what each component does at its input and output.

## Scenario setup

A four-node network (A, B, C, D) is in steady-state operation. All four nodes are healthy, connected, gossiping normally, and writing consensus rounds without backpressure. A user submits a single transaction to node A's Execution layer. Hold the rest of the network's behavior implicit — peers continue creating their own self-events in parallel; the trace below follows only what happens to this transaction on its way from the user's submit to a decided consensus round.

The trace deliberately glosses over what *triggers* the moments described — when exactly does the event creator decide to create an event, when exactly does the hashgraph decide a round, what makes a round eligible for consensus. Those triggers are mechanism-level concerns covered in Pass 2.

## Trace

Each stop names the component the transaction (or the event carrying it) is currently sitting in, describes what that component does with it at orientation altitude, and links the topic file that owns the mechanism. Code anchors are deferred to Pass 2; this trace stays at role-and-component level.

### Stop 1 — Transaction arrives at Execution on node A *(moment_id: opening)*

The user's transaction is submitted to node A's Execution layer and waits in Execution's outbound transaction buffer. Execution is outside this curriculum's scope; the only consensus-layer-facing fact about it is that Execution exposes a synchronous supplier — [`EventTransactionSupplier`](../../architecture/topics/event-creator.md) — that the event creator calls when it is ready to fill a new self-event.

*Load-bearing boundary:* the transaction has not yet entered the consensus layer. Nothing in the consensus layer knows about this transaction until the event creator pulls it out of Execution at stop 2.

### Stop 2 — Event Creator builds a self-event carrying the transaction

Periodically — driven by the tipset algorithm — node A's [Event Creator](../../architecture/topics/event-creator.md) evaluates whether creating a new self-event now would advance consensus. When the answer is yes, it selects one or more other-parent candidates from the pool of non-ancient peer events that have no observed children, pulls the pending transactions from Execution via `EventTransactionSupplier.getTransactionsForEvent()`, assembles the event (self-parent + other-parents + transactions + metadata), hashes it, signs it, and emits it on `createdEventOutputWire`.

The event creator does not gossip the event, does not persist it, and does not feed it to the hashgraph. It hands the event to the wiring framework, which routes it onward.

### Stop 3 — Event Intake validates the self-event

The wiring framework delivers the new self-event to node A's [Event Intake](../../architecture/topics/event-intake.md) — specifically to `nonValidatedEventsInputWire`, which is the entry point for events that have already been hashed by their creator. Intake runs internal validation (field-level checks), deduplication, signature verification (bypassed for `RUNTIME`-origin self-events, since the local creator just signed it), and the orphan buffer (where events wait for their parents and emerge in topological order).

The output of intake is the same `PlatformEvent`, now stamped as validated and topologically ordered. Peer events and self-events flow through the same intake pipeline; the bypassed stages are an optimization, not a separate path.

### Stop 4 — PCES persists the event before anyone else sees it *(moment_id: pces-rule)*

The validated event is routed next to [PCES](../../architecture/topics/restart-and-pces.md). The PCES writer is **synchronous**: it accepts the event on its input wire and only emits the event on its output wire after the on-disk write has completed. Nothing downstream — not gossip, not the hashgraph, not the event creator's parent-selection input — observes the event until PCES has written it.

*Load-bearing rule:* a self-event must be persisted before it is gossiped. If a node gossips a self-event and then crashes before that event is durably stored, on restart the node will not know the event existed and may build a new self-event on the same parent — producing two self-events with the same self-parent, which is a **branch**. Branches are an attack on consensus and are punished. The PCES writer's position between Intake and the downstream fan-out is what enforces "no gossip before durability" at the wiring level.

### Stop 5 — After persistence, the event fans out three ways

Once PCES emits the event on `writtenEventsOutputWire`, the wiring framework routes the same event to three places in parallel:

1. **Gossip** — for propagation to peers (see stop 6).
2. **Hashgraph** — to enter node A's own DAG and contribute to consensus computation (covered at stop 8 when it joins the decided round).
3. **Event Creator** — back to the creator's `orderedEventInputWire`, so the creator's tipset state catches up and the next self-event can use this one as its self-parent.

The third destination is what enforces "no successor self-event before predecessor is persisted." Even though the event creator just produced this event, it does not treat it as committed for parent-selection purposes until the wiring framework hands it back from PCES.

### Stop 6 — Gossip propagates the event to peers B, C, D

Node A's [Gossip](../../architecture/topics/gossip.md) layer pushes the new self-event to every connected peer using **simple broadcast** — a single `BROADCAST_EVENT` message over the RPC connection to each peer, sent as soon as the event is available. **Sync** — the three-phase reconciliation protocol — runs in parallel at a reduced cadence as a fallback for missed broadcasts and for peers that are temporarily disconnected; for a healthy steady-state network, the broadcast usually arrives first.

The transaction is now in flight to peers. Gossip does not validate, persist, or order events; it carries them.

### Stop 7 — Peers receive the event and run the same pipeline

On each peer (B, C, D), the event arrives at that peer's Gossip layer, which hands it to that peer's [Event Intake](../../architecture/topics/event-intake.md) — this time on `unhashedEventsInputWire`, because peer events need hashing before validation. The peer's intake runs the full validation pipeline (hashing, internal validation, deduplication, signature verification *(not* bypassed; this event came from a remote creator), orphan buffer). The validated event then flows through the peer's PCES writer, fans out the same three ways, and enters the peer's own hashgraph DAG.

Every node ends up holding the same set of non-ancient events in its DAG — this is what makes the algorithm's decisions deterministic across nodes despite the asynchronous propagation.

### Stop 8 — Hashgraph reaches consensus on a round containing the event

At some later point — after enough additional events have arrived on every node — each node's [Hashgraph](../../architecture/topics/hashgraph.md) algorithm independently decides a round. "Deciding a round" means: every witness in that round has a settled `famous`/`not famous` verdict from the voting machinery; from the famous witnesses, the algorithm picks one **judge** per creator; every event that is an ancestor of all the round's judges is now part of that round's consensus order, with a **consensus timestamp** derived from the median of the times the judges first received it.

The event carrying our transaction picks up its consensus order and consensus timestamp when this round is decided on each node. Because all honest nodes hold the same DAG and run the same deterministic algorithm, they all assign the same consensus order and the same consensus timestamp to the event.

### Stop 9 — ConsensusRound flows out to signed state and Execution

The decided round leaves the hashgraph on `consensusRoundOutputWire` as a `ConsensusRound` — the consensus-ordered event list (with our transaction inside one of those events), an `EventWindow` defining the new ancient and expired thresholds, the round's roster, and a `ConsensusSnapshot` capturing the round number, the judges, and the running consensus event counter.

Downstream:

- [Signed-state management](../../architecture/topics/signed-state-management.md) captures a `SignedState` for the round, will collect signatures from peers, and will eventually allow it to be written to disk.
- Execution applies the transactions in consensus order — at which point the user's transaction has "reached consensus" and its effect on the network state is fixed.

The transaction's journey from "submitted to Execution on node A" to "applied to state in consensus order on every node" is complete.

## Engagement moves

Orientation lessons run light. Two named moments are populated; the rest of the trace is delivered as direct walks by the tutor.

### Moment `opening` — what components, in what order?

This moment sits at stop 1, before the tutor begins walking the trace. It is the one place where a senior Hedera engineer's existing schema for the platform should be elicited rather than overwritten.

- **Prediction-and-reveal** *(diagnosis: the learner is a senior engineer with general Hedera familiarity)*.
  - **Framing prompt** (low-stakes thinking-aloud): "A user submits a transaction on node A and it eventually reaches consensus across the network. Before we walk through it — what's your gut model of which consensus-layer components that transaction passes through, and roughly in what order? Don't worry about being complete; I just want to see the shape of your model."
  - **Confidence elicitation** *(optional)*: if the learner gives a confident, specific answer, ask "how confident are you in the ordering of those steps?" before revealing — high-confidence wrong orderings are the richest contrast.
  - **Answer shape**: graph, not a linear list. The mechanism has a fan-out at PCES (event goes to gossip, hashgraph, and event creator simultaneously) and a join at the hashgraph (where the event interacts with peer events to decide a round). A learner who describes a single sequential chain — Execution → Creator → Gossip → peers → Hashgraph → Execution — has captured a real part of the structure but missed the fan-out.
  - **Alternative correct answers** *(credit each as a correct graph-shaped answer)*:
    1. *Sequential trunk*: Execution → Creator → Intake → PCES → Gossip → peers → Hashgraph → ConsensusRound → Execution. (The most common answer. Correct in shape but flattens the fan-out at PCES; consolidation should add that branch back in.)
    2. *Creator-first fan-out*: Execution → Creator → (Gossip + Intake + Hashgraph in parallel). (Plausible; misses that intake/PCES sits *between* the creator and gossip, not in parallel with it. Consolidation re-orders.)
    3. *Two-side trace*: "On the creating node it goes Creator → local hashgraph; on peers it arrives via gossip and goes Intake → local hashgraph". (Captures the per-node structure; consolidation adds the PCES waypoint and the fan-out on the originating side.)
  - **Canonical answer** *(the tutor reveals at orientation altitude, not mechanism-level)*: Execution → Event Creator → Event Intake → PCES → fan-out (Gossip + Hashgraph + Event Creator-feedback). Gossip carries the event to each peer; on each peer the same Intake → PCES → Hashgraph chain runs. Eventually every node's Hashgraph independently decides a round containing the event, emits a `ConsensusRound`, and hands it down to signed-state and Execution.
  - **Consolidation move**: name the gap between the learner's model and the canonical structure. Likely gaps to highlight: (a) the PCES waypoint, which is invisible from a "events flow from creator to gossip" mental model but is load-bearing for correctness; (b) the fan-out after PCES, including the feedback wire back to the event creator; (c) the symmetry between the creating-node pipeline and the peer-node pipeline (same components, same order).
- **Direct walk with cued check** *(diagnosis: the learner asks to skip the prediction, or hesitates on naming components and would rather hear them first)*.
  - State the canonical answer at orientation altitude, then ask a short check before moving into the trace: "Which two of those components do you think you understand the least at the moment? That tells me where to spend the most time."
  - This is the fallback for learners who are not yet ready to predict; it surfaces the same component list without putting the learner on the spot.

### Moment `pces-rule` — why is the event persisted before it is gossiped?

This moment sits at stop 4. The PCES rule is the first non-obvious thing in the trace — it is invisible from a "creator emits, gossip propagates" mental model — and it is load-bearing for the rest of the curriculum, because the "no branching" requirement comes up again in restart, freeze, and reconnect.

- **Prediction-and-reveal** *(diagnosis: the learner has shown general distributed-systems schema; the answer is reachable by reasoning about crash-recovery without needing this codebase's specifics)*.
  - **Framing prompt**: "Before I show you what PCES does — imagine the wiring had the event creator's output going directly to gossip, with no persistence step in between. What's the worst thing that could happen if the node creates a self-event, gossips it, and then crashes a millisecond later?"
  - **Answer shape**: a short causal chain ending in "branching". Roughly: "the node gossips event X with self-parent Y; the node crashes before storing X; on restart the node only knows about Y; the next self-event the node creates uses Y as its self-parent — but X already used Y as its self-parent and X was gossiped — so there are now two self-events on Y, which is a branch."
  - **Alternative correct answers** *(credit each)*:
    1. *Branching-named*: the answer above, with the word "branch" or "fork" used.
    2. *Branching-described-without-the-word*: "the node would create two self-events claiming the same self-parent" — credit as correct, then introduce the term "branch" in consolidation.
    3. *Wider-blast-radius answer*: "the node would lose track of events it gossiped, and peers would have events the node doesn't, and reconnect-style state divergence" — credit the safety intuition, then narrow to the specific branching failure mode the PCES rule prevents.
  - **Canonical answer** *(reveal at orientation altitude)*: branching is the failure mode. PCES sits between Intake and the downstream fan-out *specifically* to ensure that a self-event becomes durable before any peer or any successor self-event can depend on it. The rule is enforced at the wiring level (PCES is synchronous and emits only after the write completes); it is not the writer's job to know about the rule.
  - **Consolidation move**: name the rule once explicitly — "self-events are persisted before they are gossiped, before they reach consensus, and before they can be used as the self-parent of a successor self-event" — and tag it as a recurring concern the learner will see again in Cluster C (restart and reconnect) and Cluster D (freeze).
- **Free recall** *(diagnosis: the learner has answered the prediction confidently and the tutor wants to verify the term landed before moving on)*.
  - **Prompt**: "In your own words, what does PCES do for this event, and what does it stop from happening?"
  - **Canonical answer for consolidation**: PCES persists the event to disk synchronously; nothing downstream sees the event until the write completes; this prevents the node from gossiping an event it might then forget on crash and accidentally branching its own self-event chain.

## Consolidation

At the end of the trace, the tutor briefly recaps the canonical component path and the load-bearing structural facts:

- The originating-node path is Execution → Event Creator → Event Intake → PCES → (fan-out: Gossip, Hashgraph, Event Creator feedback). Gossip carries the event off-node.
- On each peer, the same Intake → PCES → Hashgraph chain runs after the event arrives over gossip.
- Each node's Hashgraph independently runs the consensus algorithm on its own DAG and independently decides the same rounds in the same order — that is the deterministic-replay property that makes the system work.
- The decided round leaves the hashgraph as a `ConsensusRound` and is handed downstream to signed-state-management and Execution.
- One non-obvious waypoint, PCES, sits between Intake and the fan-out to enforce "no gossip before durability" — this rule will reappear in restart, reconnect, and freeze.

If the learner ran the opening prediction, the tutor names the gap between the learner's model and the canonical structure here in addition to the in-line consolidation at moment `opening`.

## Close-out

A brief mental-sketch consolidation: the learner should now be able to name every component the transaction passes through, in roughly the right order, and to state the role of each in one sentence. Threshold concepts (birth-round, strongly-seeing, judges, signed-state lifecycle, reconnect-handoff) have been named in passing but **not** taught — those land in Pass 2.

Pointers to the Pass 2 lessons that deepen each component are listed below under **Forward pointers**. The tutor should mention them in close-out as the next step: "If you want the mechanism behind Event Intake, that's `a2-01` through `a2-syn`. For Gossip, that's `a3-01` through `a3-syn`." — not as a checklist to commit to, but as a map of where the depth lives.

## Forward pointers

The components in scope, each pointing to the Pass 2 lessons that cover its mechanism in depth:

- **Wiring framework substrate** — `c0-01-components-and-schedulers`, `c0-02-wires-and-soldering`, `c0-03-backpressure-modes`, `c0-04-health-monitor-mechanics`, `c0-05-determinism-and-exceptions`, `c0-syn-wiring-synthesis`.
- **Hashgraph algorithm** — `a1-01-hashgraph-dag` through `a1-syn-hashgraph-synthesis` (eight lessons covering DAG, rounds, strongly-seeing, judges, consensus order, birth-round, stale events, synthesis).
- **Event Intake** — `a2-01-intake-overview-and-inputs` through `a2-syn-intake-synthesis` (seven lessons).
- **Gossip** — `a3-01-protocol-stack-and-neighbors` through `a3-syn-gossip-synthesis` (six lessons).
- **Event Creator** — `a4-01-when-to-create` through `a4-syn-event-creator-synthesis` (five lessons).
- **Cluster A.5 — steady-state synthesis** — `a5-01-steady-state-event-flow-trace`, `a5-02-self-event-feedback-loop`, `a5-syn-steady-state-synthesis`. The full-depth version of the trace this lesson walked at orientation altitude.
- **PCES, signed-state, and recovery** — `c-01-signed-state-creation-and-types`, `c-02-reservation-discipline-and-on-disk`, `c-03-pces-write-and-replay`, plus reconnect-related entries `c-04` through `c-syn`.
- **Pass 3 deep-dive of this same scenario** — `pass3-01-tx-to-consensus-deep`. Once Pass 2 has filled in the mechanisms, Pass 3 walks the same path again with code anchors, invariants, and perturbation prompts.

## Open questions

None — the lesson is at orientation altitude and does not depend on KB material that is missing or `[TBD]` in the consulted topic files. Delta callouts are deliberately omitted for orientation (template constraint); invariants, scenarios, and ADRs are deferred to Pass 2 and Pass 3 entries that need them.
