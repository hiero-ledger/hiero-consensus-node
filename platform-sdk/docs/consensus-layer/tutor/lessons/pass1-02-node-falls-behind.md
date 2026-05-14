---
id: pass1-02-node-falls-behind
cluster: pass1
title: "A node falls behind — orientation walkthrough"
pass: 1
depth: orientation
prerequisites: []
kb_topics_touched:
  - architecture/topics/gossip.md
  - architecture/topics/health-monitor-and-backpressure.md
  - architecture/topics/reconnect.md
  - architecture/topics/signed-state-management.md
  - architecture/topics/reasons-not-to-gossip.md
kb_concepts:
  - concepts/event-lifecycle.md
kb_glossary_terms: []
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name the components a fall-behind recovery passes through, in order — from local pressure through peer-driven detection, gossip halt, learner / teacher state transfer, wiring swap, and resumption.
  - State the rule that a node learns it has fallen behind from its peers' reports rather than from local self-inference, and locate the FallenBehindMonitor as the threshold gate that turns those reports into a reconnect trigger.
  - Describe at role level how gossip is halted before reconnect begins, and how the wiring is paused and re-anchored before gossip resumes.
threshold_concepts: []
estimated_session_minutes: 30
status: drafted
last_verified_against: 1b26efc7698950c1f1d52c9e1a32213c8d422b12
---

# A node falls behind — orientation walkthrough

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in.

## Incoming retrieval probes

None — orientation scenario, no incoming probes.

## Components in scope

Six components carry this node from steady-state operation through detection, recovery, and resumption. Each is named with one-sentence semantics so the trace can integrate them without further explanation.

- **Health Monitor** — watches every wired component's queue against its capacity and publishes a single "longest continuously unhealthy duration" signal; reaction sites elsewhere read that signal and decide how to throttle ([`health-monitor-and-backpressure.md`](../../architecture/topics/health-monitor-and-backpressure.md)).
- **Gossip** — runs the per-peer protocol stack; phase 1 of every sync exchanges each peer's `EventWindow` and tips, and a peer that observes the remote's window is too far back records that the remote has fallen behind ([`gossip.md` — Three-phase sync protocol](../../architecture/topics/gossip.md#three-phase-sync-protocol)).
- **FallenBehindMonitor** — collects fallen-behind reports from peers, recomputes whether the local node is behind on every report, and signals waiting threads when the state flips to behind ([`reconnect.md` — Detection (FallenBehindMonitor)](../../architecture/topics/reconnect.md#detection-fallenbehindmonitor)).
- **ReconnectController** — the recovery orchestrator; blocks on the FallenBehindMonitor until the node is reported behind, then drives a reconnect attempt through detection, peer selection, state transfer, validation, and resumption phases ([`reconnect.md` — Lifecycle](../../architecture/topics/reconnect.md#lifecycle)).
- **Reconnect learner / teacher protocol** — the paired state-transfer exchange that reuses the gossip connection's reconnect protocol slot; the local node acts as **learner** and a healthy peer as **teacher** ([`reconnect.md` — Learner / teacher protocol](../../architecture/topics/reconnect.md#learner--teacher-protocol)).
- **PlatformCoordinator** — orchestrates the local wiring across the swap: pauses pipelines, installs the validated signed state, clears in-flight work, and resumes ([`reconnect.md` — Post-reconnect resumption](../../architecture/topics/reconnect.md#post-reconnect-resumption)).

The trace also touches the **signed state** as the payload of the recovery — the per-round `SignedState` the teacher streams to the learner and that the local components re-anchor against ([`signed-state-management.md` — Runtime types](../../architecture/topics/signed-state-management.md#runtime-types)).

## Scenario setup

The node has been running steadily. Its hashgraph is current with the network's, gossip connections are up, signed states are being produced after each consensus round. Then something local goes wrong: a slow disk, a long GC pause, a sudden burst of inbound traffic that fills the event-intake pipeline — the specific cause does not matter for orientation. What matters is that one or more of the local component queues stays continuously over its preferred capacity for longer than a brief blip. We are about to follow what happens next, from the first signal of trouble through to the node rejoining the network.

## Trace

The trace is six stops. Each stop names the component the recovery sits in, what happens there, and links to the topic file that owns the mechanism for readers who want to go deeper after the session.

### Stop 1 — Local pressure builds; the Health Monitor signals it

**moment_id**: `moment-pre-trace` (before this stop)

A local queue is now over its preferred capacity, and stays there. The Health Monitor's heartbeat polls every watched scheduler's `getUnprocessedTaskCount()` against its `getCapacity()`; it tracks how long each scheduler has been continuously unhealthy and publishes the longest of those durations on its output wire ([`health-monitor-and-backpressure.md` — Detection](../../architecture/topics/health-monitor-and-backpressure.md#detection)).

The Health Monitor does not block enqueues or throttle anything directly. It is a detector. The reactions live elsewhere ([`health-monitor-and-backpressure.md` — Reactions](../../architecture/topics/health-monitor-and-backpressure.md#reactions)):

- The **event creator** stops minting new self-events when the duration exceeds its threshold (default 1 s).
- The **gossip sync layer** begins revoking sync permits after a one-second grace period at a configured rate per second, while continuing to send this node's own events outbound by default.
- The **transaction acceptance gate** rejects new application transactions while unhealthy.

The intent of these reactions is local: give the over-capacity pipeline time to drain. The side effect that matters for this scenario is that the node is now doing less gossip than the network around it.

### Stop 2 — The network advances; this node lags

The other nodes are not subject to the same pressure. They continue to gossip, decide consensus rounds, and advance their `EventWindow`s — the record carrying `latestConsensusRound`, `ancientThreshold`, and `expiredThreshold` in birth-round units ([`gossip.md` — Three-phase sync protocol](../../architecture/topics/gossip.md#three-phase-sync-protocol); concept reference at [`event-lifecycle.md`](../../concepts/event-lifecycle.md)).

With fewer permits and a saturated intake, this node ingests fewer events per second than the network produces. The gap grows. Events that this node still needs are aging toward ancient and then expired on its peers — and at that point gossip alone can no longer close the gap, because expired events are no longer retained for catch-up. This is the boundary the glossary names: a node so far behind that the events it needs are expired for its neighbors cannot catch up by gossip alone and has to reconnect.

### Stop 3 — Peers detect that this node has fallen behind

**moment_id**: `moment-detect-then-halt`

Detection is peer-driven, not local. During phase 1 of every sync, the two peers exchange `EventWindow` and tip hashes; on receipt, each side consults `FallenBehindStatus` to classify the gap ([`gossip.md` — Three-phase sync protocol](../../architecture/topics/gossip.md#three-phase-sync-protocol)). When a peer's sync observes that this node's window is too far back, that peer records a fallen-behind vote against this node and reports it.

The local accounting lives in `FallenBehindMonitor`. Peers call `FallenBehindMonitor.report(NodeId)` to register their observation; the monitor recomputes whether the local node is behind on every report ([`reconnect.md` — Detection (FallenBehindMonitor)](../../architecture/topics/reconnect.md#detection-fallenbehindmonitor)). The trigger condition is a proportion of peers — when more than `fallenBehindThreshold` of peers have reported, or when every peer has reported, `isBehind` flips to `true` and the monitor signals waiting threads. This is the orientation-altitude load-bearing fact: this node does not decide on its own that it has fallen behind. Its peers vote, and a quorum of votes is what trips the recovery path.

### Stop 4 — Gossip is halted; ReconnectController unblocks

`ReconnectController` runs a continuous loop on a dedicated thread; it spends most of that loop blocked on `FallenBehindMonitor.awaitFallenBehind()` ([`reconnect.md` — Lifecycle](../../architecture/topics/reconnect.md#lifecycle)). When the monitor flips, the controller unblocks and begins a reconnect attempt.

Before state transfer can begin, gossip is taken offline. The mechanism is the `gossipHalted` flag set by `RpcProtocol.stop()` / `RpcProtocol.pause()` ([`reasons-not-to-gossip.md` — Gossip globally halted](../../architecture/topics/reasons-not-to-gossip.md#gossip-globally-halted)). Once set, sync initiation, sync acceptance, and message dispatch all suppress on every peer connection. Existing syncs drain rather than being killed mid-stream; the per-peer protocol exits cleanly so that permits are freed and the connection becomes available for the reconnect protocol to take over.

### Stop 5 — Learner / teacher state transfer

The state transfer is a paired exchange over the same connection gossip was using. The reconnect protocol is one of three protocols multiplexed on the shared peer connection — Heartbeat, RPC, and Reconnect — and the connection-level protocol switch is what makes the slot available for the recovery ([`gossip.md` — Protocol stack](../../architecture/topics/gossip.md#protocol-stack); [`reconnect.md` — Learner / teacher protocol](../../architecture/topics/reconnect.md#learner--teacher-protocol)).

This node acts as **learner**. `ReconnectStateLearner.execute()` drives the learner side; a healthy peer's `ReconnectStateTeacher.execute()` streams its local signed state in response. The teacher side bounds how often it accepts a session through `ReconnectStateTeacherThrottle`, so a single healthy peer is not monopolized by repeated reconnects. The learner returns a reserved signed state — a `ReservedSignedState` carrying the per-round payload the teacher just sent ([`signed-state-management.md` — Runtime types](../../architecture/topics/signed-state-management.md#runtime-types)).

### Stop 6 — Validate, swap, and resume

The received state is checked by a `SignedStateValidator` and then handed to `PlatformCoordinator`, the orchestration object that owns the local wiring across the swap. The coordinator pauses the wiring, flushes in-flight pipelines, swaps in the new state via `StateLifecycleManager`, and resumes ([`reconnect.md` — Post-reconnect resumption](../../architecture/topics/reconnect.md#post-reconnect-resumption)).

When the wiring is back up, `ReconnectController` submits a `ReconnectCompleteAction` to the platform status machine, which transitions the node out of the `BEHIND` status. Gossip — now no longer halted, since the platform-status allow-list for sync includes `RECONNECT_COMPLETE` ([`reasons-not-to-gossip.md` — Platform status does not permit sync](../../architecture/topics/reasons-not-to-gossip.md#platform-status-does-not-permit-sync)) — can resume. The controller returns to phase 1 of its loop and blocks again on the next fall-behind signal.

The node is now caught up to the round of the signed state it received and can begin closing whatever remaining gap exists by ordinary gossip.

## Engagement moves

Two moments along the trace are load-bearing enough to warrant a choice of teaching technique. The tutor picks contingent on what the learner shows and varies move type across the two moments so the session does not become monotonous.

### Moment `moment-pre-trace` — eliciting the role-level prediction

Sits before Stop 1. Load-bearing because the orientation's first job is to surface the learner's existing mental model of how a node recovers from falling behind before walking the canonical version on top of it.

**Move A — prediction-and-reveal (role level).**

- **Diagnosis tag**: opening of an orientation session; the learner has general distributed-systems and consensus-layer familiarity and can produce a sketch.
- **Framing**: "A node has been running steadily, then loses ground relative to the network — its queues fill, gossip slows, and after some time it cannot catch up by gossip alone. Eventually the node rejoins the network at the current round. Before we walk the canonical recovery, what's your gut prediction — which components participate, and roughly in what order, from the first sign of trouble through to the node being caught up again?"
- **Confidence elicitation (optional)**: "On a one-to-five scale, how confident are you in who decides the node has fallen behind?" Useful if the learner has implementation history on one of the subsystems and may import an intuition from there.
- **`answer_shape`**: sequential pipeline with one structural side-fact. The canonical shape is `local pressure (Health Monitor) → peers detect (sync phase 1 → FallenBehindMonitor) → reconnect orchestrator unblocks → gossip halted → learner / teacher state transfer → wiring paused, state swapped, wiring resumed → platform status transitions out of BEHIND`. The single structural fact the prediction may or may not surface is that detection is peer-driven rather than locally inferred.
- **`alternative_correct_answers`**:
  - Compressed linear sketch that names the spine without the gossip-halt step: "pressure → peers notice → state transfer → swap → resume." Credit as correct on the spine; consolidation surfaces the explicit gossip-halt as the missing transition between detection and transfer.
  - Sketch that places the detector on the local node (Health Monitor or local hashgraph noticing its window has stopped advancing) rather than on peers. Treat as a half-correct answer — the learner has the recovery shape right but a wrong locus for detection. Consolidation moves detection to peer reports through `FallenBehindMonitor`.
  - Sketch that has state transfer happening on a separate channel rather than reusing the existing gossip connection's reconnect protocol slot. Credit as half — consolidate against the protocol-stack view at [`gossip.md` — Protocol stack](../../architecture/topics/gossip.md#protocol-stack).
- **Canonical answer**: the spine walked through quickly so the learner can see the gap before the trace replaces gut with mechanism. Anchored to [`reconnect.md` — Lifecycle](../../architecture/topics/reconnect.md#lifecycle).
- **Consolidation**: name three structural choices the canonical recovery makes that a typical first prediction misses or compresses — (a) detection is peer-driven via reports to `FallenBehindMonitor`, not local self-inference; (b) gossip is explicitly halted before state transfer begins and existing syncs drain rather than being interrupted; (c) state transfer reuses the gossip connection's reconnect protocol slot, not a separate channel.

**Move B — direct walk.**

- **Diagnosis tag**: the learner shows fluency on the consensus-layer's recovery-path vocabulary; eliciting a prediction would be redundant.
- **Move**: skip the prediction and walk Stop 1 through Stop 6 directly, with the cued check at Moment `moment-detect-then-halt` providing the only check on the trace.

### Moment `moment-detect-then-halt` — peer-driven detection and the gossip halt

Sits at Stop 3. Load-bearing because this is the orientation insight that distinguishes the canonical recovery from a naïve "node notices it is behind and starts a recovery" model. A learner who internalises that detection is peer-driven and that gossip is explicitly halted before transfer has the spine of every subsequent topic that touches reconnect, freeze, or any cross-cluster scenario where gossip must be quiescent for another flow to run.

**Move A — prediction-and-reveal.**

- **Diagnosis tag**: the learner has produced a coherent sketch in `moment-pre-trace` and can be expected to predict the detection mechanism once prompted. Particularly informative when the learner placed detection locally during the opening prediction.
- **Framing**: "We are at the point in the recovery where the node needs to decide that it has fallen behind. Before I tell you how it decides, what's your gut prediction — does this node notice the gap itself, or does it learn from somewhere else? In one or two sentences, name the mechanism."
- **`answer_shape`**: single mechanism name plus a one-sentence reason. The canonical answer is "the node learns from its peers — each peer that observes the gap during a phase-1 sync exchange reports this node as behind through `FallenBehindMonitor.report`, and the monitor flips when more than a configured proportion of peers have reported."
- **`alternative_correct_answers`**:
  - "From peer reports — peers see this node's `EventWindow` lagging in phase-1 sync and report it back; the monitor counts the reports against a threshold." Correct in substance and locus. Consolidation names the specific classes (`FallenBehindStatus`, `FallenBehindMonitor`) and the trigger condition.
  - "The peers vote; once a quorum has voted, the node accepts the verdict and triggers recovery." Correct in substance. Consolidation names the threshold as a configurable proportion rather than a fixed quorum, and points to `FallenBehindMonitor.checkAndNotify`.
  - "From the Health Monitor's local unhealthy signal." Incorrect. The Health Monitor is upstream cause, not the detector of fallen-behind. Consolidate by separating "what makes this node fall behind" (the local stress reactions) from "how this node learns it has fallen behind" (peer reports through `FallenBehindMonitor`).
  - "From the local node observing its own `EventWindow` has stopped advancing." Incorrect, and instructively so. A node's own window not advancing is the symptom on this side; the formal detector is the peer vote. Consolidate against the structural choice at [`reconnect.md` — Detection (FallenBehindMonitor)](../../architecture/topics/reconnect.md#detection-fallenbehindmonitor).
- **Canonical answer**: peer-driven. During phase 1 of every sync the two peers exchange `EventWindow` and tips; the remote consults `FallenBehindStatus` and, if it classifies this node as behind, records that vote. Vote accounting on this side lives in `FallenBehindMonitor.report(NodeId)`; the flip happens in `FallenBehindMonitor.checkAndNotify` when the threshold proportion is exceeded or every peer has reported. Anchored to [`reconnect.md` — Detection (FallenBehindMonitor)](../../architecture/topics/reconnect.md#detection-fallenbehindmonitor) and [`gossip.md` — Three-phase sync protocol](../../architecture/topics/gossip.md#three-phase-sync-protocol).
- **Consolidation**: name the structural choice — a node does not self-diagnose fallen-behind; its peers do, and a quorum of peer reports is the gate. Then connect the second half of the moment: once the flip has happened, gossip is halted globally before transfer begins, via the `gossipHalted` flag set by `RpcProtocol.stop()` / `pause()`. Existing syncs drain; new ones do not start. This is the explicit transition between "we are gossiping normally" and "the reconnect protocol owns the connection," and it lives at the gossip layer rather than inside the reconnect orchestrator.

**Move B — direct walk with cued check.**

- **Diagnosis tag**: the learner has not encountered the peer-vote detection model before and is unlikely to predict it; a worked walk is more useful than a prediction that would just frustrate.
- **Move**: walk Stop 3 directly, naming `FallenBehindStatus` on the gossip side and `FallenBehindMonitor.report` on the reconnect side. Then cue: "Given that detection is decided by peer votes, what state must the local gossip layer reach before the reconnect protocol can run, and who sets it?" Canonical answer for the cued check: `gossipHalted` must be set on `RpcProtocol`, which suppresses new syncs and lets existing ones drain; it is set by `RpcProtocol.stop()` or `pause()` as the pre-reconnect signal.

## Consolidation

The orientation succeeds when the learner can hold three things at once: the recovery's component sequence end to end, the peer-driven shape of detection, and the explicit gossip-halt that separates "gossiping normally" from "the reconnect protocol owns the connection." The tutor consolidates explicitly against the predictions made during the two moments — naming, in particular, any prediction that placed detection locally rather than on the peers, and any prediction that left the gossip-halt implicit or compressed it into the state-transfer step.

The trace also surfaces, by what it does not say, the boundaries the orientation respects: how the Health Monitor's reactions are tuned across the event creator, sync permits, transaction acceptance, and PCES replay; what the `FallenBehindStatus` classifier looks like in detail and how `EventWindow` advancement on a peer maps to a fallen-behind vote; how the learner / teacher protocol actually streams a signed state and what the throttle policy is; how `PlatformCoordinator` orders the pause, swap, clear, and resume; how the platform-status machine transitions across the recovery. These are the topics Pass 2 deepens.

## Close-out

A brief mental-sketch consolidation. The tutor asks: "If a colleague asked you in the hallway how a Hedera consensus node recovers from falling so far behind that gossip alone cannot catch it up, what would you draw on a whiteboard?" The canonical sketch is the six-stop spine — local pressure surfaced by the Health Monitor; peer-driven detection through phase-1 sync into `FallenBehindMonitor`; gossip halted on the trigger; learner / teacher state transfer over the same peer connection's reconnect slot; `PlatformCoordinator` pauses the wiring, swaps the state, resumes; platform status leaves `BEHIND` and gossip resumes. The tutor consolidates against whatever the learner draws and names the components the sketch should reach by way of Pass 2.

No threshold concepts; no successive-relearning tags for this lesson.

## Forward pointers

The Pass 2 lessons that deepen each component in this scenario:

- **Health Monitor and the stress reactions** — `c0-04-health-monitor-mechanics` covers the detection mechanism in the wiring framework; `b-01-health-monitor-role` and `b-02-backpressure-pacing-and-throttling` cover the stress-response role and the specific reactions (sync permits, event-creation throttling) that this scenario opens with.
- **Gossip phase-1 detection** — `a3-02-three-phase-sync` covers the `EventWindow` exchange that surfaces a peer's gap; `a3-05-falling-behind-and-roster` covers the gossip-side fallen-behind handling that feeds reports to the reconnect side.
- **Fallen-behind detection** — `c-04-fallen-behind-detection` covers `FallenBehindMonitor`, the trigger condition, and the threshold tuning in mechanism depth.
- **Reconnect protocol** — `c-05-reconnect-protocol` covers the learner / teacher exchange, peer selection, and the throttle policy; `c-06-post-reconnect-resumption` covers the `PlatformCoordinator` pause / swap / resume cycle and the platform-status transition.
- **Signed state as recovery payload** — `c-01-signed-state-creation-and-types` and `c-02-reservation-discipline-and-on-disk` cover what is being transferred and how the receiving node holds it safely.
- **State and recovery synthesis** — `c-syn-state-and-recovery-synthesis` revisits durability, restart, and reconnect together; the Pass 3 entry `pass3-02-node-falls-behind-deep` walks this same scenario at full depth.

This is the spiral the curriculum exists to walk: orientation here, mechanism in Pass 2, full-depth stitch in Pass 3.

## Open questions

- `[TBD: glossary path]` — the authoring prompt names `platform-sdk/docs/consensus-layer/glossary.md` as a canonical input, but that file does not exist; the term-definition source for this layer is `hashgraphGlossary.md` one directory up. Open question carried over from `pass1-01-tx-to-consensus`; populate `kb_glossary_terms` on this lesson's frontmatter once decided. The hashgraph glossary's "Expired event" entry contains the implicit definition of fallen-behind (a node so far behind that the events it needs are expired for its neighbors cannot catch up by gossip alone and must reconnect) but no dedicated "Fallen behind" entry; whether to add one is a glossary decision the reviewer can take alongside the path question.
