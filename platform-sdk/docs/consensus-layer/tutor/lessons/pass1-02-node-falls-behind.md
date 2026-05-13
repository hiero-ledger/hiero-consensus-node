---
id: pass1-02-node-falls-behind
cluster: pass1
title: A node falls behind and recovers via reconnect
pass: 1
depth: orientation
prerequisites: []
kb_topics_touched:
  - architecture/topics/gossip.md
  - architecture/topics/reconnect.md
  - architecture/topics/signed-state-management.md
  - architecture/topics/event-intake.md
kb_concepts:
  - concepts/event-lifecycle.md
  - concepts/birth-round.md
  - concepts/stale-events.md
kb_glossary_terms:
  - Fallen behind
  - Reconnect
  - Learner
  - Teacher
  - Signed state
  - Reservation
  - Event window
  - Ancient threshold
  - Birth round
  - Stale event
  - Platform status
  - Roster
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name the consensus-layer components a fall-behind-and-reconnect event involves, in the order they react.
  - Describe at role level how a node decides that gossip alone cannot catch it up, and why the decision is peer-driven rather than self-detected.
  - Distinguish the learner and the teacher in a reconnect exchange, and name what crosses the wire.
  - Identify the in-place state swap as the moment local components are re-anchored, and name what is reset on intake, the hashgraph, and the event creator.
  - Explain at role level why event creation and gossip are gated during the transfer, and when each resumes.
threshold_concepts: []
estimated_session_minutes: 35
status: drafted
last_verified_against: 1b26efc7698950c1f1d52c9e1a32213c8d422b12
---

# A node falls behind and recovers via reconnect

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in. If you have done `pass1-01-transaction-to-consensus`, the component vocabulary it planted (event intake, gossip, hashgraph, signed-state management) carries straight into this scenario; if not, the components are re-introduced briefly below.

## Incoming retrieval probes

None — orientation scenario, no incoming probes.

## Components in scope

Five consensus-layer components participate in this scenario. Each gets a one-line role here; depth lives in the Pass 2 lessons linked under Forward pointers.

- **Gossip** ([`architecture/topics/gossip.md`](../../architecture/topics/gossip.md)) — owns the RPC sync layer whose phase-1 window exchange surfaces the mismatch, and the connection-level reconnect protocol that yields the socket to the reconnect mechanism when a peer has fallen too far behind.
- **Fallen-behind monitor** (a sub-component of reconnect, in [`architecture/topics/reconnect.md`](../../architecture/topics/reconnect.md)) — collects per-peer fallen-behind reports and flips a single `isBehind` flag once enough peers agree.
- **Reconnect** ([`architecture/topics/reconnect.md`](../../architecture/topics/reconnect.md)) — owns the five-phase recovery lifecycle: detection, peer selection, state transfer, validation and load, resumption. Acts as **learner** when receiving a state, **teacher** when serving one to a peer.
- **Signed-state management** ([`architecture/topics/signed-state-management.md`](../../architecture/topics/signed-state-management.md)) — produces the round-by-round `SignedState` objects, reference-counts them so a teacher can pin one for transfer, and validates and installs the incoming state on the learner side.
- **Event intake** ([`architecture/topics/event-intake.md`](../../architecture/topics/event-intake.md)) — receives a new event window after the swap and resets its deduplicator and orphan buffer accordingly; events older than the new state's ancient threshold are dropped at the door.

## Scenario setup

A small steady-state network of N nodes. Pick one node — call it node A — and place it in a degraded position: it has been disconnected, GC-paused, or backpressured for long enough that peers' event windows have advanced past what A holds. When the disruption ends, A's gossip layer reopens connections and begins to sync. From peers' side, the phase-1 `EventWindow` exchange immediately surfaces the mismatch: A's `ancientThreshold` sits below the events peers can still serve, and peers' tips reference parents A has never seen.

The hashgraph on A still works; event intake on A still works; the event creator still works. The problem is that gossip alone cannot reach back far enough to fill the gap. The scenario follows what happens from the first peer report of A's lag through to the moment A's gossip stream is live again on a freshly installed state.

## Productive impasse

Before walking through the trace, predict, thinking-aloud style:

- Which component on node A first knows that A is behind, and how does it find out? Is the signal local or peer-driven?
- The remedy is to install a fresh signed state from a peer. While that state is in flight, which of A's components have to stop, and which can keep running?
- What does A do with its in-flight events — orphans waiting on parents, half-processed intake entries, freshly buffered peer events — at the moment the new state lands?

There is no quiz here; the goal is to surface the model you are bringing in. If your prior is "catching up means replaying a tail log from a known offset," say so — the consolidation at the end will flag where reconnect deviates from that mental model.

## Trace

The trace runs in five role-level stages: peers notice, A confirms, A pauses, the state is transferred, and A's components re-anchor and resume. Code anchors live in the Pass 2 lessons; this lesson stays at role level.

### Stop 1 — Peers surface the window mismatch

A's gossip layer opens RPC sync conversations with its peers as normal. The first phase of every sync is an `EventWindow` exchange: each side sends its `latestConsensusRound`, `newEventBirthRound`, `ancientThreshold`, and `expiredThreshold` (see [`concepts/event-lifecycle.md`](../../concepts/event-lifecycle.md) and [`concepts/birth-round.md`](../../concepts/birth-round.md) for what these are). When A's window is far enough behind a peer's, two things follow:

- The peer notices that A is behind. It would have to send events whose parents have already aged out of A's view, and serving them is pointless because A could not link them anyway.
- A notices that the peer is "ahead" — its tips reference parents A does not hold and cannot fetch by sync.

The sync attempt aborts (the RPC sync phase machine enters one of `OTHER_FALLEN_BEHIND` or `SELF_FALLEN_BEHIND`) and the peer makes a record: A is behind.

### Stop 2 — Peer reports accumulate; the monitor flips

Each peer that observes A as behind reports it to A's **fallen-behind monitor**. The monitor counts reports per peer. The trigger is proportional, not absolute: A is considered behind once a configured fraction of A's known peers — not just one or two — have reported it. The edge where every peer has reported also trips the flag.

The flag is the single piece of state the reconnect lifecycle blocks on. While `isBehind` is false, the reconnect controller sits idle. When it flips true, the controller wakes.

Two things to notice at role level:

- **The detection is peer-driven, not self-detected.** A does not decide on its own that gossip cannot save it. Peers tell it. A node with a buggy event window or a broken hashgraph that nevertheless still gossips might never self-diagnose; the peer-driven design treats the network's consensus on the local node's lag as authoritative.
- **The threshold is quorum-shaped.** A single flaky peer that briefly under-reports cannot force a reconnect on its own. The proportion is configurable.

### Stop 3 — Reconnect wakes; gossip yields the socket

The reconnect controller transitions the platform status machine into a `BEHIND` state. Two reactions follow on A:

- **Event creation is gated.** The event creator's permission rules check platform status; in `BEHIND`, it stops emitting self-events. This prevents A from building new events on a self-parent that is about to be replaced.
- **Gossip's payload protocol stack yields.** On each connection, RPC sync stops driving the conversation and the connection-level reconnect protocol takes over. It is the same socket; only the protocol multiplexed over it changes.

A is now quiescent in the sense that matters: it is not creating new self-events and not exchanging non-reconnect events with peers. It is still receiving fallen-behind reports (useful if the in-flight reconnect fails) and the connection-level reconnect protocol is ready to drive a state transfer when a teacher is selected.

### Stop 4 — Peer selection and the learner/teacher exchange

For each connection, A's reconnect peer protocol asks the same question on both sides: should I act as learner here, or as teacher? On A, with `isBehind` true and platform status `BEHIND`, the answer is *learner*. On the peer, if the peer holds a recent signed state and is not itself overloaded, the answer is *teacher*.

A throttle on the teacher side bounds how often any one peer accepts a teacher session, so a single healthy peer cannot be monopolised by repeated learners. State retrieval is asynchronous on A's side: the reconnect controller waits on a blocking resource provider that hands it the next available signed state, no matter which peer it came from.

The transfer itself, at role level:

- The teacher pins a recent signed state by taking a reservation on it. The reservation is what keeps the state alive in memory while the transfer is in flight — see [`architecture/topics/signed-state-management.md`](../../architecture/topics/signed-state-management.md) for the rules around reservations.
- The learner reads the state off the connection. The state arrives as a full merkle structure, not as a delta or a log: A is not replaying events to catch up; A is being handed a finished snapshot of the platform state as it stood at some recent consensus round, together with the round's `ConsensusSnapshot`, the active roster, and the signatures peers have already collected on that state.

When the read completes, the learner has a freshly reserved signed state in hand. The teacher releases its reservation; if no other consumer holds one, the teacher's copy becomes eligible for garbage collection.

### Stop 5 — Validation, in-place swap, and re-anchoring

The learner does not adopt the state until it is validated. A signed-state validator inspects the received state; the load-bearing check at orientation altitude is that it carries enough peer signatures to count as official, and the cluster C Pass 2 lessons cover the additional roster, version, and birth-round checks. If validation fails the attempt is aborted, the reconnect controller backs off, and on too many consecutive failures A exits.

Assuming validation passes, the swap proceeds. The platform coordinator pauses the wiring, drains in-flight work, and installs the new state via the state lifecycle manager. Three resets ripple out from this moment:

- **Event intake** receives a fresh `EventWindow`. The deduplicator and the orphan buffer clear their internal state — the orphan buffer's tracked events and missing-parent index reset, and waiting orphans whose parents are now ancient are dropped silently rather than emerging as stale events on the hashgraph side (see [`concepts/stale-events.md`](../../concepts/stale-events.md) for why pre-admission drops are not "stale" in the codebase's vocabulary).
- **The hashgraph** is rebuilt around the new state's consensus snapshot. The `ancientThreshold` and `expiredThreshold` jump forward, the round counter is re-anchored, and the DAG starts fresh from the loaded state's tips.
- **The event creator** has its self-parent reset to whatever the loaded state records as A's most recent self-event. A will not create a new self-event on a stale self-parent and will not branch.

### Stop 6 — Resumption

The reconnect controller submits a `ReconnectCompleteAction` to the platform status machine. The status leaves `BEHIND`, the wiring resumes, and:

- Gossip's RPC sync re-takes the socket on each connection. The next phase-1 exchange surfaces A's new window, which now matches the network.
- The event creator's permission gates reopen. Once a fresh peer event arrives via gossip and clears intake, the creator can pick it as an other-parent and emit a new self-event.
- The reconnect controller loops back to the detection phase. If `isBehind` flips true again later — for any reason, including a *fresh* fall-behind during heavy gossip or a freeze-related disruption — the same five phases run again.

A is now caught up in the sense the network cares about: its event window agrees with peers', and it can both produce and consume events on the live stream. The events A held during the lag — in its orphan buffer, in its hashgraph DAG, or buffered behind the preconsensus event stream — are gone. Their transactions were not part of the consensus order, and if A had unflushed self-events whose transactions matter to the application, the application is on the hook to resubmit them on fresh self-events.

## Consolidation

The trace has three pivots worth naming explicitly, in case your prediction missed them:

- **Detection is peer-driven, not self-detected.** A does not measure its own staleness and decide to reconnect. Peers measure A's window against theirs and report; A's monitor aggregates the reports and trips on a configurable proportion. A node whose local clocks or counters are wrong cannot mis-detect the fall-behind on its own, and a single flaky peer cannot force a reconnect.
- **Catch-up is a snapshot transfer, not a log replay.** If your prior is Raft-style tail replay from a known offset, the codebase's choice goes the other way: A receives a finished signed state together with its consensus snapshot and signatures, and A's in-flight per-event work is *discarded*, not reconciled. The orphan buffer is cleared. Events buffered behind the preconsensus event stream that never made it into a consensus round are not replayed against the new state.
- **The gating around the transfer is asymmetric.** Event creation and gossip's payload exchange both pause, but A keeps accepting fallen-behind reports throughout. The reconnect lifecycle wants the latest peer judgement on A's state even while a state is being transferred, so that a failed transfer can retry against fresh information rather than against a stale snapshot of who said what.

The transaction-level mental model from `pass1-01` still applies for events A creates after the swap, but the events A had buffered before the swap do not get a second chance through this path. They are gone with the cleared intake state. Their transactions, if they were self-events, can be resubmitted at the application layer.

## Close-out

You should now be able to:

- Sketch the trace from the first peer report of A's lag through to A's resumed event creation, naming each component as a box and the transitions between them.
- Distinguish learner from teacher, and say what crosses the wire and in which direction.
- Point to the in-place state swap as the moment A's per-event state is reset, and name the three downstream resets it triggers (intake, hashgraph, event creator self-parent).
- Explain at role level why gossip and event creation pause during the transfer, and why fallen-behind reports continue to arrive throughout.

The Pass 2 lessons listed under Forward pointers below take each component apart at mechanism depth. The Pass 3 deep version of this scenario (`pass3-02-node-falls-behind-deep`) returns to the trace with code anchors, the invariants the swap maintains, and the cross-cluster stitch points the Pass 2 lessons could not teach in isolation. Two Pass 3 edge scenarios — `pass3-edge-01-reconnect-during-freeze` and `pass3-edge-02-fall-behind-during-heavy-gossip` — perturb this same trace by overlapping it with a freeze or with the health monitor.

## Forward pointers

Each component touched in this trace has a dedicated Pass 2 cluster. After you have a steady sketch from this lesson, walk through them in roughly this order:

- **Health monitor and fall-behind context** — cluster B: `b-01-health-monitor-detection`, `b-02-reaction-sites`. The health monitor and the fallen-behind monitor are distinct, but they share the "what stops or slows the platform" surface, and the Pass 2 lessons in B explain the partitioning.
- **Signed-state management** — cluster C: `c-01-signed-state-runtime-types`, `c-02-signed-state-lifecycle-and-on-disk-layout`. Reservation discipline is the load-bearing rule the learner/teacher transfer rests on.
- **Reconnect** — cluster C: `c-05-reconnect-detection-and-orchestration`, `c-06-learner-teacher-and-resumption`. The five-phase lifecycle, the peer-selection and throttle mechanics, the learner/teacher exchange, and post-reconnect resumption at mechanism depth.
- **Gossip protocol stack** — cluster A.3: `a3-01-protocol-stack-and-rpc`, `a3-02-three-phase-sync`. The phase-1 window exchange, the `SyncPhase` terminal states, and the way the connection-level reconnect protocol takes over from RPC sync.
- **Event intake re-anchor** — cluster A.2: `a2-01-validation-pipeline`, `a2-03-orphan-buffer`. The `clear()` semantics and the broadcast event-window input wire are the mechanism behind the intake reset described at role level here.
- **Recovery synthesis** — `c-syn-recovery-synthesis` integrates signed-state, restart/PCES, and reconnect once each is established.
- **Deep return** — `pass3-02-node-falls-behind-deep` revisits this scenario at mechanism level.
- **Edges** — `pass3-edge-01-reconnect-during-freeze`, `pass3-edge-02-fall-behind-during-heavy-gossip`, `pass3-edge-03-roster-change-during-reconnect`.

Cluster 0 (Wiring Framework Foundation) underlies the wiring resets described in Stops 5 and 6. If "wire", "scheduler", "broadcast input wire", or "pause the wiring" felt under-defined here, take cluster 0 before cluster C.

## Open questions

None. The KB material this lesson rests on is present at orientation depth; mechanism-level gaps — peer-selection ranking, the exact event-intake re-anchor sequence, the post-resumption verification step — surface in the Pass 2 lessons and in the Pass 3 deep return rather than here.
