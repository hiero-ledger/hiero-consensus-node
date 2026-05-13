---
id: pass1-04-event-creation-under-stress
cluster: pass1
title: Event creation under stress — health, backpressure, and refusals to gossip
pass: 1
depth: orientation
prerequisites: []
kb_topics_touched:
  - architecture/topics/health-monitor-and-backpressure.md
  - architecture/topics/reasons-not-to-gossip.md
  - architecture/topics/event-creator.md
  - architecture/topics/gossip.md
kb_concepts:
  - concepts/event-lifecycle.md
kb_glossary_terms:
  - Platform status
  - Roster
  - Self-event
  - Broadcast
  - Stale event
  - Quiescence
  - Permit
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name the consensus-layer surfaces that react when one node enters a stressed regime, and identify which one originates the stress signal versus which ones consume it.
  - Distinguish graded backpressure (queue-driven, gradual, recovers gradually) from categorical refusals (condition-driven, all-or-nothing, recovers when the condition flips), and place each surface on the right side of that line.
  - Describe at role level how a single "longest unhealthy duration" signal fans out to four independent reaction sites with four independent thresholds, and why those sites are not coordinated.
  - Name the five permission gates the event creator consults before minting a self-event, and identify which of them the health-monitor signal flows into.
  - Explain at role level why the platform keeps sending self-events while unhealthy, and which categorical rules nonetheless cause gossip to stop along specific edges.
threshold_concepts: []
estimated_session_minutes: 35
status: drafted
last_verified_against: 1b26efc7698950c1f1d52c9e1a32213c8d422b12
---

# Event creation under stress — health, backpressure, and refusals to gossip

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in. If you have done `pass1-01-transaction-to-consensus`, the component vocabulary it planted (event creator, gossip, intake, PCES) carries straight into this scenario; if not, the components are re-introduced briefly below.

## Incoming retrieval probes

None — orientation scenario, no incoming probes.

## Components in scope

Six consensus-layer surfaces participate in this scenario. Each gets a one-line role here; depth lives in the Pass 2 lessons linked under Forward pointers.

- **Health monitor** ([`architecture/topics/health-monitor-and-backpressure.md`](../../architecture/topics/health-monitor-and-backpressure.md)) — polls every watched scheduler's queue size on a heartbeat, tracks how long each one has been over its preferred capacity, and publishes the **longest** such duration on a wire. A detector, not an enforcer: it never blocks an enqueue and never throttles a producer itself.
- **Event creator** ([`architecture/topics/event-creator.md`](../../architecture/topics/event-creator.md)) — before minting a self-event, walks a chain of permission rules. Two of them are relevant here: the rule that gates on the unhealthy-duration signal, and the rule that gates on platform status.
- **Gossip — sync permits** ([`architecture/topics/gossip.md`](../../architecture/topics/gossip.md)) — every sync this node initiates requires a permit. The permit provider listens to the same unhealthy-duration signal and revokes permits **gradually** while unhealthy, returns them **gradually** while healthy. Permits already in flight are not interrupted.
- **Gossip — broadcast and overload** ([`architecture/topics/gossip.md`](../../architecture/topics/gossip.md)) — the simple-broadcast channel that pushes each self-event to every peer as soon as it is created has its own per-peer overload monitor (output-queue depth, ping latency) that pauses broadcast on a specific connection without affecting sync on the same connection.
- **Categorical guards on gossip** ([`architecture/topics/reasons-not-to-gossip.md`](../../architecture/topics/reasons-not-to-gossip.md)) — a catalogue of all-or-nothing reasons gossip may not run on a specific edge or at all: a peer has fallen behind, this peer is still streaming events from a prior sync, gossip is globally halted (pre-reconnect), platform status is not in the sync allow-list, a self-event has not yet been persisted through PCES, and several others.
- **Transaction acceptance** (a Hedera-side gate forwarded from the platform health wire — covered in [`architecture/topics/health-monitor-and-backpressure.md`](../../architecture/topics/health-monitor-and-backpressure.md)) — the application-transaction submission path consults the same signal: when the duration exceeds its (separately configured) threshold, application transactions are refused at submit time. Priority/system transactions are not gated.

PCES replay is also a reaction site for the same signal, but at a different lifecycle moment (boot, not steady state). It appears in this scenario only as a forward pointer; its full treatment is in the Pass 2 lessons in cluster B and cluster C.

## Scenario setup

A small steady-state network of N nodes. Pick one node — call it node A — and assume it has been keeping up: gossip is running, the hashgraph is decided up to some recent round R, event creation has been steady, and every permission gate on event creation has been open.

Something downstream of intake starts to stall. The exact culprit is not important at orientation altitude — it could be the state-hashing scheduler, the consensus engine, a downstream Execution queue surfaced through the wiring, or any other watched scheduler whose unprocessed-task count has climbed past its declared capacity. The effect is that one queue's depth crosses its capacity threshold and stays there. Hard backpressure is off by default, so the upstream producer is **not** blocked at enqueue; the queue simply continues to grow.

Concurrently with this growing queue, one of node A's gossip peers — call it peer B — has fallen behind. A recent phase-1 window exchange on the sync between A and B surfaced the mismatch, and A's per-peer state for B records `peerIsBehind = true`.

The scenario follows what happens at node A from the moment the health monitor first reports a non-zero unhealthy duration through to the moment the network returns to a healthy steady state.

## Productive impasse

Before walking through the trace, predict, thinking-aloud style:

- One queue inside the platform starts overflowing. By default, nothing blocks the producer that is feeding it. So what *does* stop the queue from running away — and which component originates the "we are unhealthy" decision in the first place?
- The event creator consults a chain of permission rules before minting a self-event. Five of them are named in the KB: a rate cap, a health rule, a platform-status rule, a sync-lag rule, and a quiescence rule. If you had to guess which would fire first under a transient downstream overload, which would it be — and is the answer different depending on whether the overload is one second long or one minute long?
- A peer of yours has fallen behind. Does that fact suppress gossip globally on your side, only specific edges, or only specific message types — and if the answer is "specific edges", what stops you from gossiping to that one peer while still gossiping normally to everyone else?
- If your prior comes from "downstream queue overflows, so upstream backs off until it drains" (the classic flow-control shape), say so — the consolidation at the end will flag where this codebase's choice goes the other way.

There is no quiz here; the goal is to surface the model you are bringing in.

## Trace

The trace runs in seven role-level stages. The first three observe how a single overflow propagates into four independent reactions. The next two add the categorical-refusal angle layered on top. The last two trace recovery.

### Stop 1 — A scheduler crosses its capacity threshold; the health monitor takes note

The health monitor sits on top of the wiring substrate. On each heartbeat — by default once per millisecond — it walks every watched scheduler and compares its unprocessed-task count against its declared capacity. Schedulers constructed with unlimited capacity are skipped at registration time and can never be reported as unhealthy; that is by design.

When a scheduler first appears unhealthy, the monitor records the wall-clock time of the first observation. From then on it knows how long that scheduler has been *continuously* over capacity. If the same scheduler is later observed healthy, that timer is reset, so transient blips do not persist in the signal.

The monitor's output is a single `Duration` value — the longest such continuous-unhealthy duration across all watched schedulers, that is, the worst single offender at this instant. `Duration.ZERO` means every watched scheduler is healthy; a non-zero value means at least one is over capacity, and the magnitude tells reactors how long.

Two things to notice at role level:

- **Soft signal, not hard backpressure.** The platform ships with hard wire-level backpressure disabled by default. The producer feeding the saturated scheduler is **not** blocked at enqueue. The platform's posture is "let the queue grow; publish a signal; let downstream consumers decide what to do." See [`architecture/topics/health-monitor-and-backpressure.md`](../../architecture/topics/health-monitor-and-backpressure.md) for the configuration that flips this default.
- **Worst single offender, not an aggregate.** The signal is `max(per-scheduler unhealthy duration)`, not a sum or an average. Any one queue can drive the signal on its own.

### Stop 2 — The signal fans out to four independent reaction sites

The health-monitor output is soldered (and in one case polled) into four reaction sites. Each reads the same number; each owns its own threshold and its own response shape.

- **Event creation** (gated by the event creator's `PlatformHealthRule`).
- **Gossip sync permits** (gradually revoked while unhealthy, gradually returned while healthy).
- **Application transaction acceptance** (a refusal gate that fires once the duration crosses its threshold).
- **PCES replay** (a polling site, used at boot rather than at steady state — appears here as a forward pointer; the others are the steady-state spine).

Two things to notice at role level:

- **No coordinator.** Each site has its own threshold (different defaults, separately tunable) and decides independently whether to react. There is no central policy that says "the platform is now degraded; everyone enter degraded mode together."
- **Different reaction shapes.** Some sites flip a boolean (event creation: rule returns false, no event minted; transaction acceptance: submit returns false). One site (sync permits) responds along a slope — it revokes a few permits per second, not all at once. The differing shapes are deliberate, and they show up in how the system recovers.

### Stop 3 — Event creation halts at the health rule; the other permission gates stand

The event creator's `DefaultEventCreationManager` consults a chain of permission rules before it ever asks the tipset algorithm "would a new self-event advance the snapshot?". Five rules sit in that chain:

- **Maximum rate** — cap on self-events per unit time.
- **Platform health** — the rule that watches the unhealthy-duration signal.
- **Platform status** — admits creation only in specific platform-lifecycle statuses (`ACTIVE`, `CHECKING`, plus the narrow `FREEZING` carve-out for signature-carrying events that `pass1-03` covered).
- **Sync lag** — gates creation on how far this node's sync progress lags the network.
- **Quiescence** — gates creation in the quiescent / idle-network regime.

The chain is consulted before the tipset advancement-score gate runs, so any one of these rules can stop a creation attempt cold. Under our stressed-but-still-`ACTIVE` regime, the rate cap, the platform-status rule, the sync-lag rule, and the quiescence rule are all open; what fires is the health rule. Its threshold (default one second of continuous unhealth) is met, the rule reports `OVERLOADED`, and the manager declines to create. The tipset algorithm is not consulted.

[TBD: precise order in which the five rules are evaluated.] — The Pass 2 cluster A.4 will pin this down; at orientation altitude what matters is that all five must pass.

Self-events from this node stop being produced. The hashgraph, gossip, and intake continue to do whatever work they can on already-admitted events.

### Stop 4 — Sync permits drain gradually; broadcast continues unless its own overload monitor fires

The gossip-side reaction is graded rather than binary, and it is also gated by a grace period: the sync permit provider only enters its unhealthy branch once the reported duration crosses `sync.unhealthyGracePeriod` (default one second). Brief blips under that grace period leave permits untouched.

Once that grace is crossed, permits are revoked at a slow rate while the signal stays unhealthy, and they return at a different (slower) rate when the system becomes healthy again. A floor of at least one un-revoked permit is enforced as soon as the system becomes healthy, so a recovered node is not stuck at zero permits waiting for the gradual return rate to climb. Permits already issued — syncs in flight — are not interrupted by a revocation; only fresh sync initiations are affected.

A configuration switch (`sync.keepSendingEventsWhenUnhealthy`, default true) softens the reaction further. When set, an unhealthy node keeps sending its own events to its peers; the regime suppresses inbound-event processing on those syncs rather than the sync itself. The intent is that even while overloaded, this node continues to inform the rest of the network of its own self-events; what it stops is taking in new peer events that would deepen its overload.

[TBD: how `keepSendingEventsWhenUnhealthy` interacts mechanically with permit revoke/return.] — Flagged in the topic file; resolved at Pass 2 cluster B depth.

Separate from the health-driven permit story, the broadcast channel has its own per-peer overload monitor. If a specific peer's output queue grows past a threshold, or the ping round-trip to that peer climbs past another threshold, broadcast pauses to that peer for a cool-down. Sync continues to that peer on the same connection during the cool-down; the two channels share a connection but have independent throttling.

### Stop 5 — Transaction acceptance refuses application transactions

Through a separate path — the platform health wire is soldered into the Execution layer, which forwards it into the Hedera-side `TransactionPoolNexus` — the same unhealthy-duration signal reaches the gate on application-transaction submission. When the duration crosses its (independently configured) threshold, `submitApplicationTransaction` returns false. Priority transactions (system transactions, including state-signature transactions) are *not* gated; they continue to be accepted.

The intent is to relieve the saturated downstream by refusing the producers Execution feeds in, while still allowing the consensus-layer's own internal signature traffic to flow. The carve-out is what keeps state-signature transactions making it into events — and through them, into consensus, and through them, into the network-wide signing of saved states.

### Stop 6 — Categorical refusals on the gossip side, layered on top

Independently of the stress signal, several categorical guards are in force on node A's gossip module. They are not driven by queue depth — they are driven by **conditions**, and they shape gossip into all-or-nothing patterns:

- **Self-event durability before gossip.** A self-event that has not yet been written through the inline PCES writer is not gossiped, not even via broadcast. This is the persist-before-gossip rule from `pass1-01`. Under our stressed regime, the PCES writer is itself a watched scheduler; if it is the one running unhealthy, self-events still get persisted in order (the writer is synchronous), but the chain is slower.
- **Platform-status allow-list for sync.** Gossip's sync layer admits sync initiation and acceptance only in a specific allow-list of platform statuses: `ACTIVE`, `FREEZING`, `FREEZE_COMPLETE`, `OBSERVING`, `CHECKING`, `RECONNECT_COMPLETE`. Our scenario stays in `ACTIVE`, so this guard passes; it is the same allow-list that `pass1-03` showed admitting both `FREEZING` and `FREEZE_COMPLETE` during a freeze.
- **Peer-fallen-behind suppression.** Recall that peer B is marked `peerIsBehind`. Node A will not initiate new syncs to B, and will not broadcast to B, until that flag flips. Sync acceptance from B is also implicated: a peer who is busy doing reconnect cannot usefully receive further gossip from this node, and the comment in the code is literally "don't spam remote side if it is going to reconnect."
- **Other per-peer guards in the same shape** — a per-peer sync cool-down (do not start a new sync with this peer until enough time has passed since the last one), a "peer is still streaming events from the previous sync" gate, an "I already have a sync in progress with this peer" gate, and the fair selector's "this peer is not the right one to sync with next" gate. Each is condition-driven and all-or-nothing; the catalogue lives in [`architecture/topics/reasons-not-to-gossip.md`](../../architecture/topics/reasons-not-to-gossip.md).

Two things to notice at role level:

- **Categorical guards are independent of stress.** None of the above reads the unhealthy-duration signal. The peer-fallen-behind suppression on edge A→B would fire whether the rest of node A is healthy or unhealthy; the persist-before-gossip rule holds even in the most idle network. Stress and categorical refusals layer on top of each other.
- **The shapes are different and recover differently.** A graded backpressure response (permits revoked at five per second, returned at one per second) climbs back as the signal drops. A categorical refusal flips when its triggering condition flips — the peer-fallen-behind flag clears when reconnect completes; the cool-down clears when the timer elapses; the gossip-globally-halted flag clears when reconnect releases it. The recovery curves are not the same.

### Stop 7 — Queues drain, the signal returns to zero, reactions unwind on their own clocks

Eventually whatever was saturating the watched scheduler relieves. The scheduler's unprocessed-task count drops below capacity. The health monitor observes it healthy, resets that scheduler's timer, and recomputes the per-instant longest-unhealthy-duration. Because the signal is a max, if any *other* watched scheduler is still over capacity, the signal is non-zero with a different magnitude; otherwise it is `Duration.ZERO`.

Each reaction site unwinds on its own clock:

- **Event creator.** The health rule reports permitted again as soon as the reported duration falls at or below its threshold. The next time the manager is asked to create, the tipset algorithm is consulted normally and self-events resume.
- **Sync permits.** Permits return at the configured rate. The minimum-floor rule guarantees at least one un-revoked permit is available immediately on becoming healthy, so the node is not stuck at zero permits waiting for the gradual climb.
- **Transaction acceptance.** Resumes as soon as the reported duration falls back below the Hedera-side threshold.
- **Broadcast overload.** Per-peer pauses time out independently; broadcast resumes per peer.
- **Categorical guards layered on top.** Unaffected by the unhealthy-duration signal. Peer B remains `peerIsBehind` until reconnect (or whatever upstream signal flips that flag) completes; the persist-before-gossip rule remains in force on every self-event regardless.

The platform does not transition through a coordinated "now-degraded → now-recovering → now-recovered" sequence; each surface re-enters its open state when its own condition relaxes. From outside, the network's apparent recovery is the envelope of those independent recoveries.

## Consolidation

The trace had four pivots worth naming explicitly, in case your prediction missed them.

- **Soft signal, not hard backpressure.** The platform does not block enqueues at capacity. Hard wire-level backpressure is off by default. Instead the health monitor publishes a duration and four reaction sites independently consume it. If your prior was "downstream queue is full, so upstream blocks until it drains", the codebase's choice goes the other way for a reason — hard backpressure couples producers and consumers in a way that, in this pipeline, would more easily deadlock the wiring graph than throttle it.
- **The detector and the enforcers are different modules.** The health monitor is **only** a detector. It does not revoke permits, does not refuse transactions, does not stop event creation. Each reaction site owns its own threshold and its own response. If your prior was "the health monitor throttles the system", that combines two layers the codebase deliberately separates.
- **Graded backpressure and categorical refusals are different shapes.** Permit revocation is graded — it ramps with the signal and unwinds along a recovery curve. The persist-before-gossip rule, the peer-fallen-behind suppression, the platform-status allow-list, the per-peer sync cool-down: those are categorical — a condition is true and a behaviour stops, the condition flips and the behaviour resumes. Same effect category ("gossip slowed down"), different mechanics. Telling them apart is one of the things this orientation exists to plant.
- **Asymmetry around self-events under stress.** Even while unhealthy, the default posture is to *keep sending* this node's own events to peers (`sync.keepSendingEventsWhenUnhealthy`); what stops is taking in new peer events that would deepen the overload. If your prior was "the network plane shuts down when the node is unhealthy", the codebase's choice is the opposite: keep informing peers of your self-events; throttle the inbound that is overloading you.

The transaction-pipeline mental model from `pass1-01` still applies for events that this node has admitted; what's new here is the second layer on top of it — two layers of throttling (graded queue health, categorical refusals) that are independent of each other and that recover on independent clocks.

## Close-out

You should now be able to:

- Sketch the trace from a single queue's overflow to four reaction sites and back to recovery, naming each surface as a box and the wires between them.
- Name the five event-creator permission gates and identify which one consumes the health-monitor signal.
- Distinguish, at role level, graded backpressure from a categorical refusal — and place sync-permit revocation, transaction acceptance refusal, peer-fallen-behind suppression, and persist-before-gossip on the right side of that line.
- Explain at role level why the platform keeps sending self-events while unhealthy, and which categorical rules nonetheless cause gossip to stop along specific edges.
- Point to the absence of a central coordinator — four independent thresholds, four independent recoveries — and say why that design choice is deliberate.

The Pass 2 lessons listed under Forward pointers below take each component apart at mechanism depth. The Pass 3 deep version of this scenario (`pass3-04-event-creation-under-stress-deep`) returns to this trace with code anchors, invariants, deltas, and the cross-cluster stitch points the Pass 2 lessons could not teach in isolation. A Pass 3 edge scenario — `pass3-edge-02-fall-behind-during-heavy-gossip` — perturbs this same trace by combining the health-monitor reaction with a fall-behind detection.

## Forward pointers

Each component touched in this trace has a dedicated Pass 2 cluster. After you have a steady sketch from this lesson, walk through them in roughly this order:

- **Health monitor and reaction sites** — cluster B: `b-01-health-monitor-detection` (the unhealthy-duration signal itself), `b-02-reaction-sites` (the four soldered reactors), `b-04-backpressure-vs-categorical` (the distinction this lesson plants), `b-syn-stability-synthesis` (a saturated subgraph recovering under both regimes).
- **Reasons not to gossip — the catalogue** — cluster B: `b-03-reasons-not-to-gossip-catalog`. The full enumeration of categorical guards (`gossipHalted`, the platform-status allow-list, `peerIsBehind`, the sync cool-down, the broadcast-not-running composite, the fair-selector veto, and the rest).
- **Event-creator permission rules** — cluster A.4: `a4-04-creation-rules-and-health-gates`. The full rule chain: rate, health, platform status, sync lag, quiescence, plus the ordering and the `FREEZING` carve-out.
- **Gossip side — broadcast and overload** — cluster A.3: `a3-03-simple-broadcast-and-overload` (the per-peer overload monitor), `a3-04-fair-sync-selector` (the per-peer cool-down and the round-robin fairness layer).
- **Persist-before-gossip** — cluster C: `c-03-inline-pces-write-path` (the synchronous waypoint that makes the categorical durability rule enforceable).
- **Steady-state synthesis** — `a5-syn-steady-state-synthesis` integrates A.1–A.4 once each is established and is the natural pre-read before cluster B's synthesis.
- **Deep return** — `pass3-04-event-creation-under-stress-deep` revisits this trace at mechanism level.
- **Edge** — `pass3-edge-02-fall-behind-during-heavy-gossip` overlays the trace with a fall-behind detection and surfaces the cross-cluster stitch between B and C.

Cluster 0 (Wiring Framework Foundation) underlies the health-monitor heartbeat, the soldering of the unhealthy-duration signal to its four consumers, and the queue-capacity declarations that make the detector meaningful. If "scheduler", "capacity", or "soldered" felt under-defined here, take cluster 0 before cluster B.

## Open questions

The four source topic files this lesson rests on flag several mechanism-level questions whose answers shape the trace above; at orientation altitude the trace is correct as written, but a handful of details are surfaced here so the Pass 2 cluster B lessons (and the reviewer) can close them.

- [TBD: precise order in which the event creator's five permission rules are evaluated.] — Named in the KB but not pinned down at role level; the cluster A.4 mechanism lesson will resolve it.
- [TBD: contract between the wire-soldered consumers of the unhealthy-duration signal — edge-driven or level-driven?] — Flagged in `health-monitor-and-backpressure.md`. The health monitor suppresses repeat reports of the same duration; PCES (which polls) always sees the current level; the other three consumers (event creator, gossip permits, transaction acceptance) are wire-soldered and the contract per consumer is open in the source KB.
- [TBD: mechanical interaction between `sync.keepSendingEventsWhenUnhealthy` and the permit revoke/return accounting.] — Flagged in `health-monitor-and-backpressure.md`; whether permits are still revoked at the same rate during send-only mode is open.
- [TBD: why the transaction-acceptance threshold is a Hedera-side configuration (`transaction.maximumPermissibleUnhealthySeconds`) while event creation uses a platform-side configuration (`event.creation.maximumPermissibleUnhealthyDuration`).] — Flagged in `health-monitor-and-backpressure.md`; the two default to the same value today but are tunable independently, and whether operators are expected to keep them in lockstep is open.
