---
id: pass1-02-node-falls-behind
cluster: pass1
title: A node falls behind and reconnects
pass: 1
depth: orientation
prerequisites: []
kb_topics_touched:
  - architecture/topics/gossip.md
  - architecture/topics/reconnect.md
  - architecture/topics/signed-state-management.md
  - architecture/topics/event-intake.md
  - architecture/topics/hashgraph.md
kb_concepts:
  - concepts/event-lifecycle.md
  - concepts/stale-events.md
  - concepts/birth-round.md
  - concepts/rounds-and-witnesses.md
kb_glossary_terms:
  - events
  - rounds
  - event-relationships
  - misc
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name every consensus-layer component that participates when a node falls behind and recovers via reconnect, and the order in which each becomes active.
  - State, in one sentence each, what each touched component contributes — detecting the lag, halting gossip, transferring the signed state, swapping it in, and re-anchoring the in-memory pipelines on the new round.
  - Identify "fall-behind is reported by peers, not self-detected" as the non-obvious entry condition that distinguishes the consensus layer's recovery path from textbook catch-up-by-streaming-events.
threshold_concepts: []
estimated_session_minutes: 30
status: drafted
last_verified_against: 1b26efc7698950c1f1d52c9e1a32213c8d422b12
---

# A node falls behind and reconnects

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in: distributed-systems background, BFT and asynchrony intuitions, and a working sense that a node can lose contact with the network long enough that the rest of the network advances past it.

## Incoming retrieval probes

None — orientation scenario, no incoming probes. The retrieval and self-explanation work begins in the Pass 2 lessons that the Forward pointers section names.

## Components in scope

The trace touches eight consensus-layer components. Each is named here with a one-sentence semantic so the learner can hold the cast in working memory before the trace integrates them.

- **Gossip** — runs sync with peers and the simple-broadcast path; phase 1 of each sync is where one side notices the other's tips and event window are stale and records a "fallen-behind" vote. Gossip is also where the global halt happens (`gossipHalted=true`) and where the gossip-connection-level reconnect protocol yields the socket to the reconnect mechanism when one peer has fallen behind. See [gossip.md](../../architecture/topics/gossip.md).
- **FallenBehindMonitor** — accumulates fallen-behind reports from peers; flips `isBehind = true` once a configured proportion of peers have reported. Owned by reconnect rather than gossip: gossip raises the votes, the monitor consolidates them. See [reconnect.md](../../architecture/topics/reconnect.md).
- **ReconnectController** — runs the reconnect lifecycle on a dedicated thread. Blocks on detection, attempts state transfer when the monitor signals, retries on failure up to a configured cap, and submits the completion action when finished. See [reconnect.md](../../architecture/topics/reconnect.md).
- **Reconnect learner/teacher** — paired exchange that streams a signed state from a healthy peer (the teacher) to the falling-behind node (the learner) over the same connection gossip was already using. See [reconnect.md](../../architecture/topics/reconnect.md).
- **PlatformCoordinator and SignedStateValidator** — validates the received state (at minimum, signature quorum) and atomically swaps it in: pauses the wiring, flushes in-flight pipelines, installs the new state via `StateLifecycleManager`, and clears stale buffers. See [reconnect.md](../../architecture/topics/reconnect.md).
- **Hashgraph** — at swap time, clears the in-memory DAG and reloads its algorithm state from the received state's `ConsensusSnapshot` via `Consensus.loadSnapshot`. While it rebuilds the snapshot's judges from incoming events, an init-judge gate suppresses round emission. See [hashgraph.md](../../architecture/topics/hashgraph.md).
- **Event intake** — at swap time, advances the ancient threshold via `setEventWindow`; the orphan buffer evicts entries below the new threshold, and in-flight events older than the threshold are dropped at intake's stages. See [event-intake.md](../../architecture/topics/event-intake.md).
- **Signed-state management** — the runtime types (`SignedState`, `ReservedSignedState`, `SignedStateReference`) that underlie the state being transferred and swapped. The loaded state takes the place of the prior local state; the prior state's reservations are released and it is reclaimed asynchronously. See [signed-state-management.md](../../architecture/topics/signed-state-management.md).

## Scenario setup

A four-node network — call the nodes A, B, C, D — was running steady-state. Node A loses network connectivity to B, C, D for a substantial interval — long enough that B, C, D advance many rounds without A's participation, and the new ancient threshold in each of their event windows climbs past the birth round of A's most recent tip. A's local hashgraph still contains the events it had at the moment of partition; A's event creator is still trying to mint self-events on those parents; A's local platform status is still nominally ACTIVE because, from A's vantage, nothing has gone wrong.

Then connectivity restores. We follow A from the moment its first re-established sync attempt with a peer reveals the lag until A is once again participating in consensus.

## Trace

Each stop sits at one component and describes what happens there. Code anchors are omitted at orientation depth — the Pass 2 lessons named in Forward pointers anchor the mechanism in code.

### Stop 1 — A peer's sync with A discovers A is behind

**Component:** Gossip (phase 1 of sync). **Topic:** [gossip.md](../../architecture/topics/gossip.md).

**Moment:** `m1-prediction-of-cast`.

A connection between A and (say) B re-establishes. The RPC sync protocol drives a sync, and phase 1 exchanges each side's `EventWindow` and tip hashes. B sees that A's `newEventBirthRound` is far below B's `ancientThreshold` — i.e. every event A's tips can reach is already ancient from B's perspective. B records a fallen-behind vote against A (and aborts the sync) by calling its local `FallenBehindMonitor.report(A)` for A's node id. The same thing happens, independently, on C and D the next time each of them syncs with A.

A symmetric possibility exists in the opposite direction: A's `FallenBehindMonitor` could in principle observe that *B* (or some peers in general) is ahead by the same phase-1 exchange. The orientation altitude does not turn on this distinction; the load-bearing fact is that the fallen-behind judgement comes out of *the sync's window comparison*, not out of either side reading its own queues or event window in isolation.

### Stop 2 — FallenBehindMonitor consolidates peer votes

**Component:** FallenBehindMonitor. **Topic:** [reconnect.md](../../architecture/topics/reconnect.md). Load-bearing — surprising waypoint.

**Moment:** `m2-peer-driven-detection`.

A's `FallenBehindMonitor` is the consolidation point: it holds the set of peer ids that have currently reported A as behind and recomputes whether that set crosses the threshold on every report. The trigger is a configured proportion (`fallenBehindThreshold`) of A's peers, with a degenerate-case branch for "every peer has reported." Once the threshold trips, `isBehind` flips to true and any thread waiting on `awaitFallenBehind()` is signalled.

The non-obvious takeaway lives here. From A's vantage, nothing observably broke — A has not crashed, its queues are not saturated, its event creator is still running rules-of-creation, the event window it knows about is the one it last computed from its own decided rounds. A learns it has fallen behind only because *enough peers told it so*, and only via the sync protocol that already runs for unrelated reasons. The orientation does not unpack *why* the design works this way (peers have authoritative views of the current ancient threshold that a partitioned node cannot reconstruct alone) — the Pass 2 lesson on reconnect detection covers that.

### Stop 3 — ReconnectController unblocks and gossip halts

**Component:** ReconnectController (orchestration) plus the gossip-side reconnect protocol. **Topic:** [reconnect.md](../../architecture/topics/reconnect.md). Load-bearing.

The `ReconnectController` thread, which was blocked on `awaitFallenBehind()` since startup, returns and enters the reconnect attempt path. As part of preparing for state transfer, gossip is globally halted on A: `gossipHalted` flips to true, sync initiation and acceptance stop on every peer connection, in-flight sync conversations drain, and the gossip-connection-level reconnect protocol yields each socket to the reconnect mechanism. (See [reasons-not-to-gossip.md](../../architecture/topics/reasons-not-to-gossip.md) for the categorical "globally halted" guard, which is the same flag.)

A's platform status transitions to `BEHIND`. From this point until the resumption stop, A is not exchanging events with peers and not creating new self-events.

### Stop 4 — Learner and teacher stream the signed state

**Component:** Reconnect learner/teacher. **Topic:** [reconnect.md](../../architecture/topics/reconnect.md).

A acts as **learner**; a healthy peer (which one is selected via the gossip-side `ReconnectStatePeerProtocol`'s `shouldInitiate`/`shouldAccept` handshake) acts as **teacher**. The reconnect protocol multiplexes on the same socket that gossip was using moments earlier — same connection, different protocol — and `ReconnectStateLearner.execute()` on A's side streams the signed state from `ReconnectStateTeacher.execute()` on the teacher's side. The teacher's offer rate is bounded by `ReconnectStateTeacherThrottle` so a healthy peer is not monopolised by reconnect duty.

What is transferred is the signed state itself — a full `SignedState` (state hash, `SigSet`, `ConsensusSnapshot`, the state's working data) — not a delta or a stream of missing events. The orientation does not unpack the on-the-wire format; the Pass 2 cluster C lessons do.

### Stop 5 — Validation, wiring pause, and state swap

**Component:** PlatformCoordinator and SignedStateValidator. **Topic:** [reconnect.md](../../architecture/topics/reconnect.md).

`SignedStateValidator` checks the received state — at minimum, that the accumulated `SigSet` carries a signing-weight quorum against the state's roster. `PlatformCoordinator` then orchestrates the swap: pauses the wiring framework, flushes every component's in-flight pipeline, installs the new state via `StateLifecycleManager`, and clears stale buffers across the platform.

The orientation-altitude takeaway is the discard: events that were in flight at intake, in the orphan buffer, or already linked into the hashgraph but pre-consensus are not preserved across the swap. The platform discards in-flight work and resumes from the loaded round, not from where A was before the partition. From A's prior local state to the loaded state is an atomic replacement on the wiring substrate, not a merge.

### Stop 6 — Hashgraph reloads from the snapshot

**Component:** Hashgraph. **Topic:** [hashgraph.md](../../architecture/topics/hashgraph.md). Load-bearing.

The loaded state carries a `ConsensusSnapshot` for the round it represents. The hashgraph reloads via `Consensus.loadSnapshot`: the linker is cleared, the future-event buffer is cleared, and the consensus engine sets its round-and-witness state to match the snapshot. The DAG that A had before the swap is gone.

An init-judge gate (`Consensus.waitingForInitJudges()`) then suppresses round emission until enough events arrive — from the peers' gossip once it resumes — to reconstruct the snapshot's judges as `EventImpl` nodes in the linker. While the gate is set, `addEvent` returns an empty output even when consensus would otherwise advance, because A cannot yet tell whether an incoming event might already have been part of a previously decided round. When the last init judge arrives, the gate clears and the hashgraph is ready to decide new rounds.

The mechanism details — snapshot fields, init-judge reconstruction, the round / witness / fame pipeline — are unpacked in cluster A.1 of Pass 2.

### Stop 7 — Event intake re-anchors and gossip resumes

**Component:** Event intake plus signed-state management. **Topic:** [event-intake.md](../../architecture/topics/event-intake.md) and [signed-state-management.md](../../architecture/topics/signed-state-management.md).

Event intake's `setEventWindow` is broadcast to the deduplicator, signature validator, and orphan buffer. The orphan buffer's `eventsWithParents` and `missingParentMap`, both keyed on birth round, shift to the new ancient threshold; entries below it drop, releasing or discarding whatever orphans were waiting on them. From this point any event arriving via intake whose birth round is below the threshold is dropped at the door.

`ReconnectController` submits a `ReconnectCompleteAction` to the platform status machine, which transitions A out of `BEHIND`. The wiring resumes, gossip starts initiating syncs again, and A begins receiving recent peer events on `unhashedEventsInputWire`. These events feed the hashgraph through the same intake → PCES → hashgraph pipeline as steady-state. The signed-state management module holds the loaded state via `SignedStateReference`; the prior local state's reservations have been released and it is reclaimed asynchronously by the state garbage collector.

A is caught up. The trace closes.

## Engagement moves

The trace has two moments. Stop 1 carries the opening prediction-and-reveal that elicits the learner's mental sketch of the cast and order; Stop 2 carries the surprising peer-driven-detection waypoint. Every other stop is a direct walk at orientation altitude.

### Moment `m1-prediction-of-cast` — Stop 1

Why load-bearing: the lesson's job is to plant a complete-but-low-fidelity sketch of which components touch the recovery flow and in what order. Eliciting the learner's prior model up front gives the tutor a baseline to consolidate against at the close, and surfaces any wrong orderings (e.g. assuming the node detects its own lag and pulls missing events; assuming reconnect is event-streaming rather than state-transfer; assuming gossip keeps running during state transfer) before the trace overwrites them.

**Available moves:**

- **Prediction-and-reveal** *(diagnosis: the learner has prior distributed-systems or Hedera context and can produce a coherent role-level sketch).* Framing: "Picture a four-node network. Node A loses connectivity for a while; B, C, and D advance many rounds without it. Connectivity restores. What's your gut prediction for how A catches up? Which components on A are involved, in what order? Don't worry about being precise; a rough cast list is fine." Optional confidence elicitation when the learner volunteers strong opinions about the mechanism: "How sure are you about whether A streams missing events from peers, versus something else?" Canonical answer to consolidate against: the seven stops in this trace, in order: peer-side detection in sync phase 1 → A's FallenBehindMonitor consolidates votes → ReconnectController unblocks and gossip halts → learner/teacher streams a full signed state → validation + wiring pause + state swap → hashgraph reloads from snapshot → intake re-anchors and gossip resumes. Consolidation move: name the gap between the prediction and the canonical sketch, with particular attention to (a) whether the learner assumed A self-detects the lag versus learns it from peers, and (b) whether the learner assumed catch-up is event-streaming versus full state transfer plus DAG wipe.
- **Direct walk with cued check** *(diagnosis: the learner is new to the consensus-layer codebase and would frame their own prediction as a guess they could not be expected to have).* Walk the seven-component sketch in one breath without unpacking, then run a cued check: "If I told you that A doesn't figure out it's behind on its own — it learns from the network — which of these components do you think is the one that holds the verdict?" Consolidate on the FallenBehindMonitor answer and use that as the bridge into the rest of the trace.

### Moment `m2-peer-driven-detection` — Stop 2

Why load-bearing: the peer-driven detection is the surprising waypoint of the orientation. A senior distributed-systems engineer often expects a node to notice it has fallen behind from its own queues, its own event window, or a missing-heartbeat timer; the codebase instead waits for peers to vote, via the sync protocol that runs for unrelated reasons. Naming the surprise explicitly here (without unpacking the *why*) sets up the Pass 2 cluster C lessons that explain the detection mechanism and the broader reconnect lifecycle.

**Available moves:**

- **Direct walk with cued check** *(diagnosis: this is the default move for orientation depth — the learner has just heard the stop and the tutor needs to verify that the unusual ordering registered).* Walk the stop as written, then run a cued-recall check: "What part of A's own local state would, in principle, tell A that it's behind — and why isn't that the trigger here?" Canonical answer: A's local event window reflects only the rounds A itself has decided, which are necessarily stale during a partition; A cannot recompute the network's current ancient threshold from its own decided rounds alone. The peers, who have continued advancing, are the ones who hold the up-to-date view, and they communicate it through the sync window exchange. Consolidate on "the network's current ancient threshold is a peer-held fact, not a self-held one, during a partition."
- **Free recall** *(diagnosis: the learner has shown fluency on the peer-driven rationale earlier in the session — for instance, they raised it themselves at moment `m1`).* Skip the walk-through and ask the learner to articulate, in their own words, why detection must be peer-driven. Consolidate against the same canonical answer.

Prediction-and-reveal is **not** offered at this moment: the peer-driven rationale rests on properties of the sync protocol and the event window that the orientation has not yet built up; asking the learner to predict the rationale would be plain failure rather than productive failure. The Pass 2 cluster C lesson on reconnect detection reaches this point with the relevant machinery in scope and can offer prediction-and-reveal there.

## Consolidation

Two consolidation acts at the end of the trace, before the close-out retrieval.

First, consolidate against the opening prediction from moment `m1`. Walk the canonical seven-stop ordering one more time at headline level — peer-side fallen-behind vote, monitor consolidates, controller unblocks and gossip halts, learner/teacher state transfer, validation and atomic swap, hashgraph reloads from snapshot, intake re-anchors and gossip resumes — and name explicitly any place the learner's prediction differed: a missing component (most commonly the FallenBehindMonitor or the swap-and-flush step), an inverted ordering (most commonly assuming gossip continues during transfer), or a wrong mechanism (most commonly assuming event-streaming rather than full state transfer). Do not lecture the difference; just name it.

Second, name the three non-obvious facts the orientation has planted:

- **Fall-behind is peer-reported, not self-detected.** A's own local state cannot tell A it is behind; only peers' sync-window exchanges can. The `FallenBehindMonitor` is a consolidation point for those peer reports, not an autonomous detector.
- **Recovery is state transfer, not event streaming.** A does not catch up by receiving the events it missed during the partition. The whole signed state — including its `ConsensusSnapshot` and working data — is transferred from a healthy peer; A's prior in-memory DAG is then discarded and replaced.
- **The swap atomically discards in-flight work.** Events at intake, orphans waiting for parents, and pre-consensus events linked into the hashgraph are flushed during the swap, not migrated. The platform resumes from the loaded round, not from A's last local position.

These three facts are the load-bearing orientation takeaways; the rest of the trace is shape and cast.

## Close-out

A brief mental-sketch consolidation: the tutor asks the learner to name the seven stops in order in their own words, without prompting on individual components. The canonical answer is the headline list above. If the learner stalls, hint by naming the boundaries (peer-side detection, local consolidation, controller + halt, transfer, validate + swap, hashgraph reload, intake re-anchor + resume) rather than the components themselves; the learner should be able to fill the components into those slots.

Successive-relearning tags: none. Orientation scenarios establish the cast and the sketch but do not establish threshold concepts; threshold-concept tagging begins at Pass 2.

A final pointer to the Forward pointers section: the orientation has planted the spiral; each touched component is unpacked at depth in the Pass 2 lesson(s) named below. The Pass 3 deep version of this same trace (`pass3-02-node-falls-behind-deep`) revisits every stop with code anchors, perturbations, and cross-cluster invariants once the Pass 2 cluster lessons are in scope.

## Forward pointers

Each component touched by the trace is unpacked in the following Pass 2 lessons. These IDs are drawn from the current manifest at `tutor/curriculum.md`.

- **Gossip and the gossip-side reconnect protocol** (Stop 1, Stop 3): `a3-01-protocol-stack` (where the reconnect protocol sits alongside RPC and Heartbeat), `a3-02-rpc-sync-threading`, `a3-03-three-phase-sync` (phase 1 is where the fallen-behind vote is recorded), `a3-04-broadcast-and-fair-selector`, `a3-syn-gossip-synthesis`. The categorical "gossip globally halted" guard at Stop 3 is catalogued under `b-03-reasons-not-to-gossip-categorical`.
- **Reconnect lifecycle as a single thread** (Stops 2, 3, 4, 5, 7): `c-06-reconnect-lifecycle`. The recovery synthesis that exercises this whole trace at Pass 2 depth is `c-syn-recovery-synthesis`.
- **Signed-state management** (Stop 4, Stop 5, Stop 7): `c-01-signed-state-and-reservations` (the reservation discipline that governs the loaded state and the discarded prior state), `c-02-signed-state-on-disk` (the on-disk layout the teacher reads from and the learner can write to after a successful reconnect), `c-03-signed-state-lifecycle`.
- **Hashgraph snapshot reload and init-judge gate** (Stop 6): `a1-05-judges-and-consensus-order` (the judge mechanism the gate reconstructs), `a1-06-birth-round` (the ancient-threshold semantics the gate also enforces), `a1-syn-hashgraph-synthesis`.
- **Event intake re-anchoring** (Stop 7): `a2-02-orphan-buffer` (where the window shift evicts pending orphans), `a2-03-birth-round-filtering-in-intake` (the ancient gate applied at each stage), `a2-syn-intake-synthesis`.
- **Wiring framework** (Stop 5, underlying the pause/flush/swap): `c0-04-wiring-model-and-validation` (lifecycle), `c0-05-backpressure-and-flushing` (flushing in-flight pipelines), `c0-syn-wiring-composition`.
- **Deep revisit of this same trace at full depth**: `pass3-02-node-falls-behind-deep`. The cross-cluster edge cases that perturb this trace are `pass3-edge-01-reconnect-during-freeze`, `pass3-edge-02-fall-behind-during-heavy-gossip`, and `pass3-edge-03-roster-change-during-reconnect`.

## Open questions

- [TBD: question for reviewer — the reconnect topic flags that it is not yet documented whether the event creator pauses for a quiescent period after `ReconnectCompleteAction` (so that fresh self-events are not built on parents that are now ancient under the new threshold), or whether it relies entirely on intake-side filtering downstream. Stop 7 of this orientation states that gossip resumes and the node begins receiving peer events, but stays silent on the event creator's exact post-resumption posture. Pass 2 lessons `c-06-reconnect-lifecycle` and `a4-04-creation-permission-rules` should resolve this; orientation does not require it.]
- [TBD: question for reviewer — the reconnect topic flags that the precise checks performed by `SignedStateValidator` beyond signature quorum (roster compatibility, software version, birth-round monotonicity) are not yet documented. Stop 5 of this orientation states "at minimum, signature quorum"; that hedge is honest at orientation altitude but the Pass 2 lesson on reconnect validation must surface the full check set.]
