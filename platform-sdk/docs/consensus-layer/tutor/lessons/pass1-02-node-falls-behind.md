---
id: pass1-02-node-falls-behind
cluster: pass1
title: Node falls behind and reconnects
pass: 1
depth: orientation
prerequisites: []
kb_topics_touched:
  - architecture/topics/gossip.md
  - architecture/topics/reconnect.md
  - architecture/topics/signed-state-management.md
  - architecture/topics/event-intake.md
  - architecture/topics/hashgraph.md
  - architecture/topics/event-creator.md
kb_concepts:
  - concepts/event-lifecycle.md
  - concepts/birth-round.md
  - concepts/stale-events.md
kb_glossary_terms:
  - signed-state
  - event-window
  - ancient
  - birth-round
  - reconnect
  - fallen-behind
  - learner
  - teacher
  - gossip
  - platform-status
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name each component involved in falling behind and reconnecting, and state its role in one sentence
  - Sketch the order of stages from detection through state load to resumption, separating the steps that quiesce the local pipeline from the steps that transfer data over the network
  - Identify the load-bearing transition — the validate-and-load step — where the local node's in-flight state is replaced wholesale rather than updated incrementally
threshold_concepts: []
estimated_session_minutes: 25
status: drafted
last_verified_against: 9db53cdbe89215743387f536b7dfb4f84878f7af
---

# Node falls behind and reconnects

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in.

## Incoming retrieval probes

None — orientation scenario, no incoming probes.

## Components in scope

These are the components the trace walks through. One-sentence semantics per component; the deep mechanism is the subject of the Pass 2 lessons listed under Forward pointers.

- **Gossip** ([gossip.md](../../architecture/topics/gossip.md)) — exchanges events with peers over a per-connection protocol stack. The first phase of every sync also exchanges each peer's current event window, which is how the network observes that a node has fallen behind. The gossip-side reconnect protocol is the one that yields the socket when state transfer needs to take over.
- **Reconnect** ([reconnect.md](../../architecture/topics/reconnect.md)) — the recovery path for a node that has fallen too far behind for gossip alone to catch up. Owns the fallen-behind detection signal, the learner/teacher state-transfer exchange, and the post-transfer re-anchoring of local components.
- **Signed-state management** ([signed-state-management.md](../../architecture/topics/signed-state-management.md)) — produces and persists the `SignedState` objects that form the platform's verifiable history. A signed state is the unit of recovery — what the teacher streams to the learner and what the learner loads as its new starting point.
- **Event intake** ([event-intake.md](../../architecture/topics/event-intake.md)) — runs the validation and topological-ordering pipeline for events arriving from gossip, PCES replay, or the local event creator. Its ancient threshold is fed by the current event window, so re-anchoring intake to the loaded state's window is what lets it discard the now-stale events left over from before reconnect.
- **Hashgraph** ([hashgraph.md](../../architecture/topics/hashgraph.md)) — holds the in-memory DAG of non-ancient events and runs the consensus algorithm. Its DAG is one of the things wiped and re-seeded when the new state is loaded, so the node restarts the algorithm from the round the loaded state captured.
- **Event creator** ([event-creator.md](../../architecture/topics/event-creator.md)) — decides when to build the node's next self-event. It pauses while reconnect runs, because creating a self-event while the local state is in the middle of being replaced would link that self-event to parents the rest of the network has already aged out.

The wiring framework is the substrate underneath all of this. It is the orchestration object — `PlatformCoordinator` in current code — that pauses gossip and the downstream pipelines, clears in-flight events, swaps the state in, and resumes. The wiring framework itself is Cluster 0 in the curriculum and is not part of this trace.

## Scenario setup

A four-node network in steady state, rounds advancing normally. One node — call it node N — falls behind: it may have been disconnected by a network partition, it may have been stop-the-world garbage collecting, or it may have been slow enough across recent rounds that the events its peers are sending no longer fit inside the event window N is willing to accept. The other three nodes — call them N's peers — continue to make progress on their own without N's contribution; they hold enough weight between them to keep consensus moving. The trace follows N from the moment its lag becomes a problem through the moment it has caught back up.

This is the orientation altitude. The trace deliberately does not unpack what "falling behind" looks like inside the event window, the threshold-tripping arithmetic, or the precise wiring around `PlatformCoordinator`. Each Pass 2 lesson listed under Forward pointers exists to walk one of those at depth.

## Trace

### Stop 1 — Node N starts to lag {#stop-1-lag}

Node N is still connected to its peers, still running gossip, still trying to keep up — but for whatever reason, the events its peers are producing arrive faster than N can integrate them, or arrive with birth rounds higher than the event window N is currently willing to accept. The peers continue to advance rounds without N's contribution. N is now lagging, but no recovery has been triggered yet — what the next stop describes is how the network notices.

`moment_id: trace-open`

### Stop 2 — Peers report N as fallen behind {#stop-2-detection}

Every time two peers run gossip's three-phase sync, the first phase exchanges each side's current event window. When a peer P and node N sync, P sees that N's event window has fallen far enough below P's own window that P will no longer be able to send N events that fit — N's ancient threshold has drifted out of P's working set. P reports N as fallen behind. As more peers reach the same conclusion in their own syncs with N, a per-node monitor on N accumulates these reports. When enough peers — a configurable proportion of N's neighbors — have reported N behind, the monitor flips its `isBehind` flag.

The trigger condition is in the `FallenBehindMonitor` ([reconnect.md, Detection section](../../architecture/topics/reconnect.md#detection-fallenbehindmonitor)). The deep mechanism — the exact threshold formula, the metric surface, the spurious-detection edge cases — is the subject of [`c-06-fallen-behind-detection`](./c-06-fallen-behind-detection.md). What matters at this altitude is the role: gossip *observes* the lag at the event-window boundary; the monitor *consolidates* observations from many peers into a single, sticky, "you have fallen behind" signal.

### Stop 3 — Gossip yields the socket to reconnect {#stop-3-yield}

Every connection between N and a peer carries one of three multiplexed protocols at a time: heartbeat keeps idle connections alive, RPC sync is the workhorse that exchanges events, and the reconnect protocol takes over when state transfer needs to happen. Once N's monitor reports `isBehind`, the gossip pipeline on N stops initiating new syncs and the gossip-side reconnect protocol is eligible to be chosen the next time N and a healthy peer renegotiate which protocol holds their connection. On the peer side, the same negotiation happens — the peer's per-connection protocol object decides whether to act as teacher.

This is the gossip-level handoff: nothing about the data on the wire changes yet, but the *kind* of conversation that connection is having shifts from "exchange events" to "transfer a whole signed state." See [gossip.md, Protocol stack](../../architecture/topics/gossip.md#protocol-stack) for the three-protocol catalog and [reconnect.md, Learner / teacher protocol](../../architecture/topics/reconnect.md#learner--teacher-protocol) for the protocol object that drives this handoff.

### Stop 4 — The learner/teacher exchange streams a signed state {#stop-4-transfer}

Once the connection is held by the reconnect protocol, the actual state transfer runs. Node N takes the **learner** role; the peer takes the **teacher** role. The teacher streams its most recent signed state — the immutable, hashed, peer-signed snapshot of the platform after a particular consensus round — to the learner over the same socket gossip was using. State transfer is large: the signed state contains the full state tree at that round, plus the signature set that proves the network agreed on it.

A throttle on the teacher side limits how often any one node accepts the teacher role, so a healthy peer is not monopolized by repeated learner requests. The deep mechanism — exact peer selection rules, the throttle's parameters, the streaming format — is the subject of [`c-07-reconnect-state-transfer`](./c-07-reconnect-state-transfer.md). At orientation altitude, the load-bearing facts are: the unit transferred is a `SignedState`, and the transfer reuses the gossip socket rather than opening a new connection.

### Stop 5 — Validate, pause, and swap in the new state {#stop-5-load}

When the streamed state arrives, it is not yet usable. Node N validates it — at minimum that the accompanying signatures meet quorum against the roster the state was signed under — and only then begins the load. Loading is not a merge: the new state replaces the local state wholesale. To do this without corrupting any in-flight work, the wiring framework's orchestration object (`PlatformCoordinator`) pauses the downstream pipelines, flushes any events still moving through intake and hashgraph, swaps the live state for the loaded one via the state-lifecycle hook, and clears the pipelines' working memory so nothing left over from before the reconnect can act on the new round.

**Load-bearing transition.** This is the irreversible step of the scenario. Everything before this stop is *gathering* — observing the lag, picking a peer, streaming the bytes. This stop is where N's working state ceases to exist and is replaced by the teacher's state. Any event N had buffered, any in-flight self-event the creator had not yet emitted, any orphaned event waiting on parents N would have learned about from gossip — all of it is discarded, because in the round the loaded state captures, all of those events are now either already accounted for or now ancient. The discontinuity here is what makes reconnect categorically different from normal gossip catch-up: gossip is incremental, reconnect is a jump.

### Stop 6 — Components re-anchor to the new round {#stop-6-reanchor}

With the new signed state installed, the components that depend on the event window re-anchor. Event intake reads the new event window from the loaded state and starts dropping any event whose birth round is below the new ancient threshold. Hashgraph re-seeds its DAG and consensus engine from the loaded state's round and resumes consuming events forward from there. The event creator updates its parent pool and resumes considering whether to build self-events, now with the round number and event window from the loaded state. Gossip is allowed to resume initiating syncs. The platform status machine receives a `ReconnectCompleteAction` and transitions the node out of the BEHIND status.

The re-anchoring is the symmetric counterpart to the wholesale replacement in Stop 5: where Stop 5 wiped the working memory, Stop 6 fills it back in with content consistent with the loaded round. The Pass 2 lesson on this step is [`c-08-post-reconnect-resumption`](./c-08-post-reconnect-resumption.md).

### Stop 7 — Normal gossip closes the remaining gap {#stop-7-catchup}

The signed state N loaded is not necessarily the most recent round on the network — the teacher's signed state was the latest one it had completed signing, but the peers may have advanced several rounds in the meantime. That residual gap is closed by ordinary three-phase sync: now that N has rejoined gossip in good standing, the events it is missing between the loaded round and the network's current round fit inside its event window, and they arrive through the same gossip → intake → hashgraph path that every steady-state event takes (the path the previous orientation scenario, [`pass1-01-tx-to-consensus`](./pass1-01-tx-to-consensus.md), walked through).

N is now back in the network's steady state. The next time a peer syncs with N, the event-window check that originally tripped fallen-behind will pass, and N will not be reported behind again.

## Engagement moves

### Moment `trace-open` — before Stop 1

This moment sits before the trace begins. Reconnect is one of the canonical scenarios the audience may have prior exposure to from incident reports, design documents, or earlier work — getting the learner's mental sketch on the table first turns the trace into a comparison rather than a one-way exposition, and surfaces whichever model they already have.

**Move A — prediction-and-reveal.** Diagnosis tag: the learner shows general Hedera-team familiarity and is willing to commit to a sketch from prior exposure. Reconnect is well-documented enough that a confident prediction is plausible from prior reading, even if the learner has not worked the code at depth.

- Prompt (verbatim):
  > Before I walk it: a node has stopped keeping up with the rest of the network — far enough behind that ordinary gossip can no longer catch it up. Sketch what you think happens next. What components get involved in noticing that the node is behind, in getting it caught up, and in letting it resume normal operation? A rough sequence is fine — don't worry about the deep mechanism inside each step.
- `answer_shape`: sequential list of stages — detection → handoff → transfer → load → resumption — with one parallel detail acceptable (gossip and reconnect share a connection rather than running on separate ones).
- `canonical_answer`: peers notice the lag during gossip's sync exchange and report it; a per-node monitor consolidates the reports and flips a fallen-behind flag; the gossip connection yields to a reconnect protocol; a peer becomes the teacher and streams a signed state to the learner; the learner validates the state, pauses its local pipelines, and swaps the state in wholesale; the downstream components — intake, hashgraph, event creator — re-anchor to the round the loaded state captured; gossip resumes and ordinary sync closes any residual gap.
- `alternative_correct_answers`:
  - An answer that elides Stop 2's gossip-side detection — "the node notices it's behind, asks a peer for a state, loads it" — is correct at orientation altitude; how the network arrives at the detection is a refinement.
  - An answer that elides Stop 3's gossip-yields-the-socket handoff is correct; the load-bearing facts at this altitude are detection, transfer, load, and resumption.
  - An answer that frames Step 5 as "replace the local state with the teacher's state" without explicitly calling out the pause-and-clear of the pipelines is correct; the pipeline-quiescence detail is the load-bearing point of the trace itself, not part of the minimum sketch.
  - An answer that names the wiring framework (or `PlatformCoordinator`) as the orchestrator of the pause/swap/resume is correct as additional detail; treat it as a refinement, not a different model.
  - An answer that says the node "downloads the latest state and resumes from there" — without explicitly naming reconnect, the learner/teacher roles, or the signed-state object — is correct as a sketch and the trace fills in the names.
- `followup` (verbatim, delivered when the learner produces the sequence but does not articulate why the load step has to pause the pipelines):
  > You described loading the new state. Say one sentence about why the node has to pause its local pipelines and clear them before the new state takes effect — what goes wrong if it just installs the new state and keeps running?
- `followup_canonical_answer`: any of the following counts — "an in-flight event from before the reconnect could land in the freshly loaded hashgraph and either be invalid against the new event window or, worse, link to a parent that no longer exists in the new state"; "the event creator could emit a self-event whose self-parent is from the pre-reconnect world, branching itself"; "the intake pipeline's ancient threshold would still be the old one until the event window from the loaded state propagates, so old-world events would slip through as if they were current"; or any answer that points at the irreversibility of the swap and the corruption risk of mixing pre- and post-load work.

**Move B — free recall.** Diagnosis tag: the learner is not confident enough to commit to an ordered sketch, asks for the canonical mapping first, or wants the names of the components without the choreography. This move lowers the stakes — it asks for the set, not the order — and lets the trace itself supply the order.

- Prompt (verbatim):
  > Before we walk it: just by name, which components or subsystems do you think are involved when a node that has fallen behind catches itself back up? Order isn't important here — I'm checking which pieces you're holding in your head.
- `canonical_answer`: the set {gossip (carries the detection signal and the protocol handoff), reconnect (the orchestration plus learner/teacher state transfer), signed-state management (the object that gets transferred), event intake / hashgraph / event creator (the downstream components re-anchored after the load), the wiring framework or `PlatformCoordinator` (pauses, swaps, resumes)}.
- `alternative_correct_answers`:
  - Any non-empty subset that includes gossip, reconnect, and signed-state management — the three load-bearing components for this scenario. The downstream re-anchoring components are refinements at the set-naming altitude.
  - Sets that additionally name the platform status machine, the state signature collector, the roster, or PCES are correct; treat the extras as refinements rather than errors. PCES is briefly relevant — the snapshot the teacher streams may include PCES files for replay — but not load-bearing for the orientation trace.
  - Sets that name "fallen-behind monitor" or "reconnect controller" by their class-level names rather than the topic-level name are correct.

## Consolidation

After the trace, the learner should be able to name each of the six components in scope and state in one sentence what each one does on the fall-behind-and-reconnect path. They should hold the trace as a sequence with one true discontinuity in the middle — the validate-and-load step at Stop 5, where the local state is replaced wholesale rather than updated incrementally. Every other step on the trace either *prepares for* that discontinuity (detection, handoff, transfer) or *recovers from* it (re-anchor, gossip resumes, catch-up). They should be able to say *why* the discontinuity has to exist — the node is too far behind for incremental gossip catch-up to work, by definition, because that is what fallen-behind means — and *why* the pipelines have to be quiesced through it.

If the learner ran Move A at the trace-open moment, name in plain words the gap between their prediction and the canonical trace: which stops they hit, which they merged or skipped, whether they spotted the discontinuity at Stop 5 as the load-bearing transition or treated it as just one step among many. The point of the contrast is to make the corrections explicit so they consolidate against the trace rather than fade after the reveal.

## Close-out

The learner now holds the complete-but-low-fidelity sketch of reconnect. Every Pass 2 lesson under Cluster C — fallen-behind detection, state transfer, post-reconnect resumption — will assume that the learner can place its component on this trace; the trace itself does not need to be re-explained inside those lessons. The Pass 3 deep version of this scenario, [`pass3-02-node-falls-behind-deep`](./pass3-02-node-falls-behind-deep.md), revisits the same path once the relevant Pass 2 clusters are taught. Two Pass 3 edge cases also re-enter this trace from a different starting condition — reconnect during freeze ([`pass3-edge-01-reconnect-during-freeze`](./pass3-edge-01-reconnect-during-freeze.md)) and fall-behind during heavy gossip ([`pass3-edge-02-fall-behind-during-heavy-gossip`](./pass3-edge-02-fall-behind-during-heavy-gossip.md)).

**Free-recall summary** — delivered verbatim at session close:
> In your own words, sketch the path a fallen-behind node takes back to steady state: name each component as you go, and say one sentence about what it does at that step. Highlight the one step in the middle where the local node's state is replaced wholesale rather than updated incrementally.

`canonical_answer`: a coherent retelling of the seven stops, naming the six components in scope, with the validate-and-load step at Stop 5 called out as the wholesale-replacement discontinuity (and the pause-and-clear of the pipelines around it identified as what makes the discontinuity safe).

`alternative_correct_answers`: any retelling that hits at least detection → handoff → transfer → load → re-anchor → catch-up, names gossip, reconnect, and signed-state management explicitly, and identifies Stop 5 (under any phrasing — "the swap," "the load step," "where the state gets replaced") as the load-bearing transition. Compressing detection-and-handoff into a single step is fine at this altitude. Omitting the residual catch-up at Stop 7 is fine if the learner notes that ordinary gossip resumes after re-anchoring.

**Successive-relearning tags:** none — this lesson establishes no threshold concept. The mental sketch it plants is consolidated by the Pass 2 lessons under Cluster C, each of which exercises one of its components at depth, and by the Pass 3 deep version of the scenario.

## Forward pointers

Each component in scope is covered at depth by a Pass 2 cluster or sub-cluster:

- **Wiring framework** (the substrate that orchestrates the pause/swap/resume at Stop 5, not a stop on the trace) — Cluster 0, starting at [`c0-01-task-schedulers-and-queues`](./c0-01-task-schedulers-and-queues.md).
- **Event intake** (re-anchored at Stop 6) — Cluster A.2, starting at [`a2-01-hashing-and-internal-validation`](./a2-01-hashing-and-internal-validation.md).
- **Gossip** (carries the detection signal at Stop 2, yields the socket at Stop 3, resumes at Stop 7) — Cluster A.3, starting at [`a3-01-protocol-stack`](./a3-01-protocol-stack.md). The three-phase sync that surfaces the event-window mismatch is [`a3-03-three-phase-sync`](./a3-03-three-phase-sync.md).
- **Event creator** (re-anchored at Stop 6) — Cluster A.4, starting at [`a4-01-tipset-and-advancement-score`](./a4-01-tipset-and-advancement-score.md).
- **Hashgraph** (re-seeded at Stop 6) — Cluster A.1, starting at [`a1-01-hashgraph-dag`](./a1-01-hashgraph-dag.md).
- **Signed-state management** (the object streamed at Stop 4 and loaded at Stop 5) — Cluster C, starting at [`c-01-signed-state-lifecycle-and-reservations`](./c-01-signed-state-lifecycle-and-reservations.md).
- **Fallen-behind detection** (Stop 2) — [`c-06-fallen-behind-detection`](./c-06-fallen-behind-detection.md).
- **Reconnect state transfer** (Stops 3 and 4) — [`c-07-reconnect-state-transfer`](./c-07-reconnect-state-transfer.md).
- **Post-reconnect resumption** (Stops 5 and 6) — [`c-08-post-reconnect-resumption`](./c-08-post-reconnect-resumption.md).

The deep version of this scenario at full mechanism altitude is [`pass3-02-node-falls-behind-deep`](./pass3-02-node-falls-behind-deep.md), once the Pass 2 clusters above are taught. The Cluster C synthesis lesson [`c-syn-recovery-synthesis`](./c-syn-recovery-synthesis.md) exercises the same components collaborating on this and adjacent recovery paths.

## Open questions

- [TBD: roster-change handling at the boundary] The trace assumes the roster the loaded state was signed under is the roster N can validate against — i.e. the network has not changed roster between N falling behind and the teacher's signed state being captured. The roster-change-during-reconnect edge case ([`pass3-edge-03-roster-change-during-reconnect`](./pass3-edge-03-roster-change-during-reconnect.md)) is where the interaction is worked. The orientation trace deliberately stays inside the no-roster-change case; reviewer should confirm this is the right scope for the orientation altitude or flag if the roster check at Stop 5 needs to be surfaced even at sketch level.
- [TBD: glossary path mismatch — inherited from pass1-01] The lesson-authoring meta-prompt references `consensus-layer/glossary.md`, which does not exist; the canonical glossary is at `platform-sdk/docs/hashgraphGlossary.md`. The `kb_glossary_terms` listed in frontmatter resolve against that file. This is the same open question raised by [`pass1-01-tx-to-consensus`](./pass1-01-tx-to-consensus.md); flagging here so the reviewer fix at the prompt level closes both.
- [TBD: invariants and delta-map remain stubs] `consensus-layer/invariants.md` and the entries under `consensus-layer/delta-map/` do not exist yet (only README placeholders). Orientation depth does not require them; this open question carries forward from [`pass1-01-tx-to-consensus`](./pass1-01-tx-to-consensus.md) so subsequent Pass 2 and Pass 3 work in this area can ground its structural and delta claims.
