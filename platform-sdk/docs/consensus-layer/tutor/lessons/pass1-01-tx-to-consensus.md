---
id: pass1-01-tx-to-consensus
cluster: pass1
title: Transaction to consensus
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
kb_glossary_terms:
  - event
  - self-event
  - other-parent
  - round
  - judge
  - signed-state
  - preconsensus-event-stream
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name each component on the transaction-to-consensus path and state its role in one sentence
  - Sketch the order in which a transaction flows from submission to a signed state, including the fan-out that follows preconsensus persistence
threshold_concepts: []
estimated_session_minutes: 20
status: drafted
last_verified_against: 5251d3ac0eea9fe8723479aa9d5c2796708617ba
---

# Transaction to consensus

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in.

## Incoming retrieval probes

None — orientation scenario, no incoming probes.

## Components in scope

These are the components the trace walks through. One-sentence semantics per component; the deep mechanism is the subject of the Pass 2 lessons listed under Forward pointers.

- **Event creator** ([event-creator.md](../../architecture/topics/event-creator.md)) — decides when this node should create a new self-event, picks other-parents that maximise hashgraph progress, fills the event with user transactions, signs it, and emits it.
- **Event intake** ([event-intake.md](../../architecture/topics/event-intake.md)) — takes unordered events from gossip, the local event creator, or PCES replay, runs them through a validation pipeline, deduplicates, and emits them in topological order.
- **Preconsensus event stream (PCES)** ([restart-and-pces.md](../../architecture/topics/restart-and-pces.md)) — persists each validated event to disk before any downstream subsystem can act on it. The "persisted-before-gossip" rule is the durability guarantee that prevents a crashed node from accidentally branching after restart.
- **Gossip** ([gossip.md](../../architecture/topics/gossip.md)) — exchanges events with peers; pushes self-events to all connected peers via simple broadcast and reconciles with peers via the three-phase sync protocol.
- **Hashgraph** ([hashgraph.md](../../architecture/topics/hashgraph.md)) — holds the in-memory DAG of non-ancient events and runs the consensus algorithm, emitting an ordered stream of `ConsensusRound`s that name the judges, a consensus timestamp, and the new ancient and expired thresholds.
- **Signed-state management** ([signed-state-management.md](../../architecture/topics/signed-state-management.md)) — produces a `SignedState` after each consensus round, collects signatures from peers until quorum, and decides whether and when to persist the state to disk.

The wiring framework is the substrate that connects all of these together — it routes the outputs of each component to the inputs of the next, applies the ordering constraints that the durability rule depends on, and is what makes the fan-out described below possible. The wiring framework itself is Cluster 0 in the curriculum and is not part of this trace.

## Scenario setup

A small, healthy network: four nodes in steady state, all currently working on reaching consensus on some pending round `R`. There is no freeze in progress, no fallen-behind condition, no roster change. A client connected to node A submits a transaction. The trace follows that transaction from the moment node A receives it through to the round in which it lands inside a signed state on every node.

## Trace

### Stop 1 — A transaction arrives at node A {#stop-1-tx-arrives}

A client submits a transaction to node A through the execution-side ingest path. The transaction is held by execution until the event creator asks for it; in this codebase, transactions are not pushed into the event creator as a separate signal but pulled in when the event creator is assembling a new event. The transaction itself is opaque to the consensus layer at this altitude — what matters for this trace is that node A now holds a transaction it is willing to put into the next self-event it creates.

`moment_id: trace-open`

### Stop 2 — The event creator builds a self-event {#stop-2-creator-builds}

The event creator on node A periodically considers whether to build a new self-event. Several conditions must hold: the platform must be in a state that permits event creation, the rate limit must allow it, the health monitor must not be reporting an extended unhealthy duration, sync lag with peers must be acceptable, and the tipset advancement-score gate must find at least one peer event whose use as an other-parent would improve hashgraph progress. When all of those gates pass, the event creator pulls transactions from execution to fill the event (this is the moment node A's submitted transaction joins the event), selects an other-parent from the pool of childless peer events, builds the event with the previous self-event as self-parent and the chosen peer event as other-parent, hashes and signs it, and emits it.

### Stop 3 — The self-event flows through intake {#stop-3-intake}

The new self-event enters event intake. The intake pipeline is the same code path that processes peer events received over gossip, except that self-events skip two stages: the hasher (the event creator already hashed) and the signature validator (the event was just signed by this node, so re-verifying its signature would be wasted work). The remaining stages run: field-level validation, deduplication, and the orphan buffer. The orphan buffer is what gives intake its topological-ordering guarantee — it holds back any event whose non-ancient parents have not yet been emitted, releasing events only once all such parents are out. For a fresh self-event whose self-parent and other-parent are already known, intake emits it immediately on its output wire.

### Stop 4 — PCES persists the event before anything downstream sees it {#stop-4-pces}

The intake output is wired to the preconsensus event stream writer. PCES writes the event to disk and only then emits the event onward. This is the durability gate: the rule that a self-event must be persisted before it can be gossiped. The reason the rule exists is the worst-case crash story — if a node gossips a self-event and then crashes before the event reaches stable storage, on restart it will not know the event existed, and may build a new self-event on the same parent. Two self-events from the same creator on the same self-parent is a hashgraph branch, which is treated as Byzantine behaviour. PCES, soldered between intake and the downstream fan-out, prevents the race that would let this happen.

**Load-bearing transition.** The PCES boundary is where the "before" and "after" worlds of this event are separated. Everything before this stop is happening locally on node A and is reversible by a crash. Everything after this stop is durable.

### Stop 5 — From PCES, the event fans out three ways {#stop-5-fanout}

Once PCES has persisted the event, its output is delivered in parallel to three places:

- **Gossip** picks it up and broadcasts it to every connected peer; the same event also becomes part of the shadowgraph that the three-phase sync protocol uses to catch up peers who missed the broadcast.
- **Hashgraph** receives it as the next input to the consensus algorithm.
- **The event creator's own parent-selection pool** receives it, so that future self-events on this node can take this newly-persisted event as their self-parent.

Peer nodes B, C, and D receive the event over gossip and process it through their own intake → PCES → hashgraph pipelines. The same trace replays on each peer, with the only difference that the event arrives at intake as a peer event (so it goes through the hasher and the signature validator instead of skipping them).

**Load-bearing transition.** This is the only place in the trace where the path is not sequential. Mentally holding the fan-out, rather than thinking of the trace as a single line, is what later lessons on each downstream component build on.

### Stop 6 — Hashgraph runs the consensus algorithm {#stop-6-hashgraph}

The hashgraph module holds the non-ancient events as a DAG. As each new event arrives, it is linked to its parents (dropped if its birth round is ancient), and the consensus algorithm advances. The algorithm computes which events become witnesses, runs voting on whether each witness is famous, and identifies a set of *judges* for each round. Once a round's judges are decided, every event that is an ancestor of all those judges takes that round as its consensus round, and the round is emitted as a `ConsensusRound`. The `ConsensusRound` carries the ordered list of events in that round, the new ancient and expired thresholds going forward (the `EventWindow`), and the round's `ConsensusSnapshot`.

The deep mechanism — strongly-seeing, fame voting, coin rounds, judge selection, consensus order — is the subject of Cluster A.1 in the curriculum and is not unpacked here.

### Stop 7 — Signed-state management produces a SignedState {#stop-7-signed-state}

The `ConsensusRound` reaches the transaction handler, which applies the round's transactions to the state and produces a fresh `SignedState` capturing the immutable state at the moment of consensus. The state signature collector accumulates signatures from peers' state-signature transactions, adding them to the state's signature set as they arrive. When the signature weight reaches quorum, the state is fully signed. Depending on configured policy — periodic snapshot, freeze, ISS-recovery, and a few other reasons — the signed state may also be written to disk.

The transaction node A submitted at Stop 1 is now part of the consensus event list in the round, has been applied to the state, and is reflected in the `SignedState` that this round produced. Once that state is fully signed on every node, the transaction has reached consensus end-to-end.

## Engagement moves

### Moment `trace-open` — before Stop 1

This moment sits before the trace begins. Eliciting the learner's rough mental sketch up front turns the trace into a comparison rather than a one-way exposition, and surfaces whatever model they already have for the consensus-layer component split.

**Move A — prediction-and-reveal.** Diagnosis tag: the learner has general Hedera-team familiarity and can plausibly name the components from prior exposure to the architecture.

- Prompt (verbatim):
  > Before we walk it: a user just submitted a transaction to one node in the network. Name the components in this codebase you'd expect that transaction to pass through on its way to landing in a signed state on every node, and roughly the order they're touched in. A short list is fine — don't worry about the deep mechanism inside each one.
- `answer_shape`: sequential list of components, with a fan-out branch after preconsensus persistence acceptable as a more accurate shape.
- `canonical_answer`: event creator → event intake → preconsensus event stream (PCES) → fan-out to gossip and hashgraph → signed-state management. The fan-out also feeds the event creator's own parent pool, but that is a refinement, not part of the minimum correct answer.
- `alternative_correct_answers`:
  - A list omitting PCES — e.g. "creator → intake → gossip/hashgraph → state" — is correct at orientation altitude; PCES is a refinement, not a load-bearing component for the rough sketch.
  - A graph-shaped answer that names the fan-out explicitly ("after intake the event goes to gossip, hashgraph, and back to the creator in parallel") is correct and more accurate than the sequential canonical; credit it as such.
  - An answer that names gossip and hashgraph as parallel branches off the local persistence step is correct.
  - An answer that explicitly names the wiring framework as the substrate connecting the components is correct as additional detail; treat it as a refinement, not a different model.
- `followup` (verbatim, delivered when the learner names the components in roughly the right order but cannot justify the order between any two of them):
  > You have the components. Pick any two adjacent ones in your sketch — say a sentence about why the order matters between them, or what would go wrong if they were swapped.
- `followup_canonical_answer`: any of the following counts as a real reason — "intake has to validate before gossip so we don't propagate garbage"; "PCES has to persist before gossip so a crash can't leave us with a gossiped self-event we have no record of"; "hashgraph has to receive events topologically ordered so the algorithm can link parents"; "signed state has to wait for consensus so it captures the state at the round boundary."

**Move B — free recall.** Diagnosis tag: the learner is not confident enough to commit to an ordered sketch, asks for the canonical mapping first, or has been away from this part of the codebase long enough that retrieval is shaky. This move is lower stakes — it asks for the components without the order — and lets the trace itself fill in the order.

- Prompt (verbatim):
  > Before we walk it: just by name, which components in this codebase do you think are involved in carrying a transaction from a single node's submission to a signed state on every node? Order isn't important here — I'm checking what set of pieces you're holding in your head.
- `canonical_answer`: the set {event creator, event intake, PCES, gossip, hashgraph, signed-state management}.
- `alternative_correct_answers`:
  - Any non-empty subset of the canonical set that includes at least three of: creator, intake, gossip, hashgraph, state. Subsets that omit PCES are correct at orientation altitude.
  - Sets that additionally name the wiring framework, the transaction handler, the state signature collector, or the platform health monitor are correct; treat the extras as refinements, not as errors.

## Consolidation

After the trace, the learner should be able to name each of the six components in scope and state in one sentence what each one does in the transaction-to-consensus path. They should hold the fan-out at the PCES stop as a single mental image, not as a sequential chain, because the parallelism after that point is what every later lesson in Pass 2 builds on. They should also be able to say *why* PCES sits where it does — the durability rule and the branching risk it prevents — even though the mechanism of PCES itself is not covered until Cluster C.

If the learner ran Move A at the trace-open moment, name in plain words the gap between their prediction and the canonical trace: which components they named correctly, which they missed, where their order differed, whether they collapsed the fan-out into a single line or saw it as parallel. The point of the contrast is not to score the prediction — it is to make the corrections explicit so they consolidate against the trace rather than fade after the reveal.

## Close-out

The learner now holds the complete-but-low-fidelity sketch. Every Pass 2 lesson on a component this trace touched will assume that the learner can place that component in the trace; the trace itself does not need to be re-explained inside those lessons. The same applies to the other three Pass 1 scenarios — node falls behind, coordinated network upgrade, event creation under stress — each of which re-enters parts of this trace from a different starting condition.

**Free-recall summary** — delivered verbatim at session close:
> In your own words, sketch the path the transaction took: name each component as you go and say one sentence about what it did to the transaction or to the event carrying it.

`canonical_answer`: a coherent retelling of the seven stops, naming all six components, with the fan-out at PCES called out (or at least implied by gossip and hashgraph running in parallel).
`alternative_correct_answers`: any retelling that hits all six components in roughly the right order and reflects that gossip and hashgraph are not sequential. Compressing intake and PCES into a single "validation and persistence" step is fine at this altitude.

**Successive-relearning tags:** none — this lesson establishes no threshold concept. The mental sketch it plants is consolidated by the Pass 2 lessons that follow, each of which exercises one of its components at depth.

## Forward pointers

Each component in scope is covered at depth by a Pass 2 cluster or sub-cluster:

- **Wiring framework** (the substrate underneath the trace, not a stop on it) — Cluster 0, starting at [`c0-01-task-schedulers-and-queues`](./c0-01-task-schedulers-and-queues.md).
- **Hashgraph** (Stop 6) — Cluster A.1, starting at [`a1-01-hashgraph-dag`](./a1-01-hashgraph-dag.md).
- **Event intake** (Stop 3) — Cluster A.2, starting at [`a2-01-hashing-and-internal-validation`](./a2-01-hashing-and-internal-validation.md).
- **Gossip** (Stop 5, peer-facing branch) — Cluster A.3, starting at [`a3-01-protocol-stack`](./a3-01-protocol-stack.md).
- **Event creator** (Stop 2) — Cluster A.4, starting at [`a4-01-tipset-and-advancement-score`](./a4-01-tipset-and-advancement-score.md).
- **Signed-state management** (Stop 7) — Cluster C, starting at [`c-01-signed-state-lifecycle-and-reservations`](./c-01-signed-state-lifecycle-and-reservations.md).
- **PCES, the durability gate** (Stop 4) — Cluster A.2's durability handoff lesson, [`a2-05-durability-handoff`](./a2-05-durability-handoff.md), and Cluster C's PCES-side lesson, [`c-03-inline-pces-write-path`](./c-03-inline-pces-write-path.md).

Steady-state worked examples that re-enter this trace at depth: the synthesis sub-cluster A.5, in particular [`a5-syn-02-self-event-end-to-end`](./a5-syn-02-self-event-end-to-end.md). The Pass 3 deep version of this scenario revisits it at full mechanism altitude once the relevant Pass 2 clusters are taught.

## Open questions

- [TBD: glossary path mismatch] The tutor system prompt and the lesson-authoring meta-prompt both reference a `glossary.md` under `consensus-layer/`. No such file exists in the repo at present; the canonical glossary is at `platform-sdk/docs/hashgraphGlossary.md` (the curriculum manifest already points there). The `kb_glossary_terms` listed in this lesson's frontmatter are intended to resolve against that file. Reviewer: confirm whether the prompt should be updated to point at the actual path, or whether a thin shim `glossary.md` should be added.
- [TBD: invariants and delta-map are stubs] `consensus-layer/invariants.md` and the entries under `consensus-layer/delta-map/` do not exist yet (only README placeholders). For an orientation scenario this is fine — the template omits invariants and delta callouts at this depth — but Pass 2 and Pass 3 lessons that follow will need these populated before authoring can ground its structural claims.
