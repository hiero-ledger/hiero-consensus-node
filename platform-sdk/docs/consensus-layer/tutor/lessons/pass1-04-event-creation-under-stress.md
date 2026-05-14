---
id: pass1-04-event-creation-under-stress
cluster: pass1
title: "Event creation under stress — orientation walkthrough"
pass: 1
depth: orientation
prerequisites: []
kb_topics_touched:
  - architecture/topics/event-creator.md
  - architecture/topics/health-monitor-and-backpressure.md
  - architecture/topics/gossip.md
  - architecture/topics/wiring-framework.md
kb_concepts: []
kb_glossary_terms: []
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name the components an episode of local stress passes through, in order — from queue saturation on some downstream scheduler through Health Monitor detection, the event-creation throttle, the gossip permit revocation, and the application-transaction-acceptance gate — and what each reaction does at role level.
  - State that the Health Monitor is a detector, not an enforcer — it publishes a single "longest continuously unhealthy duration" signal and every reaction site reads it independently with its own threshold and its own response shape.
  - Describe at role level the asymmetric default at the gossip layer — a stressed node continues to send its own events outbound while it stops accepting new syncs — and the reason this lets the local pipeline drain without disconnecting the node from the network.
threshold_concepts: []
estimated_session_minutes: 30
status: drafted
last_verified_against: 1b26efc7698950c1f1d52c9e1a32213c8d422b12
---

# Event creation under stress — orientation walkthrough

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in.

## Incoming retrieval probes

None — orientation scenario, no incoming probes.

## Components in scope

Six roles carry this node through a transient overload — a downstream component falls behind its arrival rate, several local reactions engage in parallel to let it drain, and the system returns to steady state without escalating to a reconnect. Each is named with one-sentence semantics so the trace can integrate them without further explanation.

- **Wiring framework (substrate)** — every component runs on a `TaskScheduler` with a queue and a configured `unhandledTaskCapacity`; the framework reports each scheduler's queue depth to the Health Monitor and is the layer where "the system is overloaded" first becomes observable ([`wiring-framework.md` — Backpressure (wire level)](../../architecture/topics/wiring-framework.md#backpressure-wire-level)).
- **Health Monitor** — polls every watched scheduler against its capacity on a heartbeat and publishes a single "longest continuously unhealthy duration" signal; explicitly a detector, not an enforcer ([`health-monitor-and-backpressure.md` — Detection](../../architecture/topics/health-monitor-and-backpressure.md#detection)).
- **Event creator (with its rule chain)** — runs a permission chain — `MaximumRateRule`, `PlatformStatusRule`, `PlatformHealthRule`, `SyncLagRule`, `QuiescenceRule` — before delegating to the tipset algorithm; `PlatformHealthRule` is the rule that reads the Health Monitor's duration signal and stops self-event creation when it exceeds the configured threshold ([`event-creator.md` — Backpressure interaction](../../architecture/topics/event-creator.md#backpressure-interaction)).
- **Gossip's sync permit provider** — meters concurrent syncs through a permit pool; reads the Health Monitor's signal and revokes permits when the system stays unhealthy past a grace period, but by default keeps the node sending its own events outbound while only inbound processing is suppressed ([`health-monitor-and-backpressure.md` — Gossip permits](../../architecture/topics/health-monitor-and-backpressure.md#gossip-permits); [`gossip.md` — Backpressure interaction](../../architecture/topics/gossip.md#backpressure-interaction)).
- **Application-transaction acceptance gate** — on the execution side, the `TransactionPoolNexus` reads the same signal and rejects new application transactions while unhealthy; priority transactions (system-side, e.g. state signatures) are not gated ([`health-monitor-and-backpressure.md` — Transaction acceptance gate](../../architecture/topics/health-monitor-and-backpressure.md#transaction-acceptance-gate)).
- **The unhealthy duration signal itself** — the one numeric value (`Duration`) the Health Monitor publishes on a wire; consumed independently by event creator, gossip, and the execution layer's transaction pool, plus polled by the PCES replayer at restart ([`health-monitor-and-backpressure.md` — Detection](../../architecture/topics/health-monitor-and-backpressure.md#detection)).

## Scenario setup

The node has been running steadily. The hashgraph is current, gossip connections are up, PCES is writing self-events through to disk in the usual way. Then a downstream scheduler — the orphan buffer is the typical narrowest link in steady state, on a sequential scheduler with capacity 500 — gets temporarily slower than the rate at which upstream is feeding it. Its queue climbs past 500 and stays there. Nothing has fallen over; the queue grows but does not refuse work, because hard backpressure is off by default and inserts never block at the queue boundary. The Health Monitor's next heartbeat observes the scheduler over capacity. We are about to follow what happens next, from the first non-zero signal through the local pipeline draining and the node returning to steady state — without falling so far behind that gossip alone cannot recover it (that escalation is the territory of `pass1-02-node-falls-behind`).

## Trace

The trace is six stops. Each stop names the component the stress sits in, what happens there, and links to the topic file that owns the mechanism for readers who want to go deeper after the session.

### Stop 1 — Local stress builds; the Health Monitor surfaces it

**moment_id**: `moment-pre-trace` (before this stop)

A downstream scheduler is now continuously over its `unhandledTaskCapacity`. Queue inserts have not blocked — soft backpressure is the default — so upstream keeps producing into a queue that is already too deep ([`wiring-framework.md` — Backpressure (wire level)](../../architecture/topics/wiring-framework.md#backpressure-wire-level)).

The Health Monitor's heartbeat fires every `platform.wiring.healthMonitorHeartbeatPeriod` (default 1 ms). On each tick it compares every watched scheduler's `getUnprocessedTaskCount()` against its `getCapacity()`, records the time of the first unhealthy observation for any newly-unhealthy scheduler, and reports the longest such continuous duration on its output wire ([`health-monitor-and-backpressure.md` — Detection](../../architecture/topics/health-monitor-and-backpressure.md#detection)). A transient blip below a tick clears the timer; sustained pressure sets the wire to a non-zero `Duration` that grows as long as the scheduler stays over capacity.

The signal has one consumer pattern — three reaction sites soldered to the wire (event creator, gossip, execution layer), plus PCES replay which polls the same value during restart. The Health Monitor itself blocks nothing and throttles nothing. The reactions live elsewhere.

### Stop 2 — Event creation stops first

**moment_id**: `moment-event-creator-trips`

The event creator's `DefaultEventCreationManager` does not let the tipset algorithm run unconditionally. It consults an aggregated rule chain — `MaximumRateRule`, `PlatformStatusRule`, `PlatformHealthRule`, `SyncLagRule`, `QuiescenceRule` — and any rule returning "not permitted" causes the manager to skip event creation for this cycle ([`event-creator.md` — Backpressure interaction](../../architecture/topics/event-creator.md#backpressure-interaction)).

The rule that reads the Health Monitor's signal is `PlatformHealthRule`. When the reported unhealthy duration exceeds `event.creation.maximumPermissibleUnhealthyDuration` (default 1 s), the rule returns false from `isEventCreationPermitted()` and the event creator stops minting new self-events for as long as the duration stays above the threshold; while engaged it reports `EventCreationStatus.OVERLOADED` ([`health-monitor-and-backpressure.md` — Event-creation throttling](../../architecture/topics/health-monitor-and-backpressure.md#event-creation-throttling)).

This is the first independent reaction to engage. The threshold is per-site — the event creator's 1 s is not the only threshold attached to this signal — and the response shape is binary: either no self-event is created, or the rule chain passes and the tipset algorithm runs as usual.

### Stop 3 — Gossip permits revoke, but the node keeps sending

**moment_id**: `moment-asymmetric-gossip-reaction`

Gossip's reaction to the same signal lives in `SyncPermitProvider`. The reaction is gated by `sync.unhealthyGracePeriod` (default 1 s): brief blips below the grace period leave permits untouched, so the gossip layer ignores the kind of millisecond-scale spike that would still trip a hypothetical zero-grace threshold ([`health-monitor-and-backpressure.md` — Gossip permits](../../architecture/topics/health-monitor-and-backpressure.md#gossip-permits)).

Once the duration crosses the grace period, the permit provider revokes permits at `sync.permitsRevokedPerSecond` (default 5 / s). A revoked permit prevents a new sync from starting; in-flight syncs are not interrupted. The structural fact for orientation is that `sync.keepSendingEventsWhenUnhealthy` (default `true`) softens the reaction further: the node continues to send its own events outbound while only inbound processing is suppressed. The asymmetry is by design — the local pipeline drains because new work stops arriving, but peers still see this node's self-events so the rest of the network is not starved of input from it ([`gossip.md` — Backpressure interaction](../../architecture/topics/gossip.md#backpressure-interaction)).

The grace period and the asymmetric send/receive split are both load-bearing for orientation. They are the two ways the gossip reaction differs in shape from the event creator's binary throttle, and both choices reflect that stress reactions are local and per-site rather than coordinated.

### Stop 4 — Application transactions are rejected at the gate

The third independent reaction is on the execution side. `TransactionPoolNexus.submitApplicationTransaction` consults the same unhealthy-duration signal, forwarded to it by the execution layer's `reportUnhealthyDuration` rather than read directly off the platform wire. When the duration meets or exceeds `transaction.maximumPermissibleUnhealthySeconds` (default 1) — a separate config key from the event-creator threshold, even though both currently default to one second — the gate rejects new application transactions immediately. Priority transactions (system transactions like state signatures) are not gated and continue to be accepted ([`health-monitor-and-backpressure.md` — Transaction acceptance gate](../../architecture/topics/health-monitor-and-backpressure.md#transaction-acceptance-gate)).

The independence of the configs is the orientation-altitude point: operators can tune the gossip-side reaction, the event-creation reaction, and the transaction-acceptance reaction separately. The three sites coincidentally share defaults around one second but do not share a threshold.

### Stop 5 — The pipeline drains

The over-capacity scheduler catches up. Its queue size drops below its capacity. The Health Monitor observes the recovery on its next heartbeat and resets that scheduler's unhealthy timer; if it was the longest-running offender, the published duration falls — and once every watched scheduler has been continuously healthy for `platform.wiring.healthyReportThreshold` (default 1 s), the monitor re-asserts a healthy state and reports `Duration.ZERO` on the wire ([`health-monitor-and-backpressure.md` — Detection](../../architecture/topics/health-monitor-and-backpressure.md#detection)).

The signal has flipped from non-zero to zero — but each consumer reacts to that flip independently and not necessarily symmetrically with how it engaged.

### Stop 6 — Reactions unwind on independent timelines

Each reaction site reads the new value and unwinds on its own schedule ([`health-monitor-and-backpressure.md` — Reactions](../../architecture/topics/health-monitor-and-backpressure.md#reactions)):

- **Event creator** — `PlatformHealthRule` flips back to permitted as soon as the duration drops at or below its threshold; the next cycle of `DefaultEventCreationManager.maybeCreateEvent` runs the tipset algorithm again and self-event creation resumes.
- **Transaction acceptance gate** — the `healthy` flag on `TransactionPoolNexus` returns to true on the next report at or below threshold; application transactions are accepted again.
- **Gossip permits** — permits return at `sync.permitsReturnedPerSecond` (default 1 / s), much slower than the revoke rate of 5 / s. A floor of `sync.minimumHealthyUnrevokedPermitCount` (default 1) is enforced as soon as the system becomes healthy, so a system that just recovered is not stuck at zero permits while it waits for the gradual return.

The asymmetric return rate is structural. Fast to engage protection, slow to relax it — the gossip layer holds onto the conservative posture for some time after the local pipeline has caught up, because the cost of re-tripping under marginal load is higher than the cost of running with a few fewer concurrent syncs for a few seconds. The node is back to steady state on the event-creation and transaction-acceptance sides immediately; its sync concurrency rises gradually back to the configured pool.

## Engagement moves

Two moments along the trace are load-bearing enough to warrant a choice of teaching technique. The tutor picks contingent on what the learner shows and varies move type across the two moments so the session does not become monotonous.

### Moment `moment-pre-trace` — eliciting the role-level prediction

Sits before Stop 1. Load-bearing because the orientation's first job is to surface the learner's existing mental model of how the consensus layer responds to local overload before walking the canonical version on top of it.

**Move A — prediction-and-reveal (role level).**

- **Diagnosis tag**: opening of an orientation session; the learner has general distributed-systems and consensus-layer familiarity and can produce a sketch.
- **Framing**: "A node has been running steadily, then one of its downstream components gets temporarily slower than its arrival rate. Its queue climbs and stays over capacity for several seconds. After a while the queue drains and the node returns to steady state, no reconnect needed. Before we walk the canonical reaction, what's your gut prediction — when this happens, what stops, what slows, what continues? And which component decides any of it?"
- **Confidence elicitation (optional)**: "On a one-to-five scale, how confident are you about which component decides?" Useful when the learner has implementation history on one subsystem and may import an intuition (e.g. "the wiring framework throttles upstream automatically when a queue fills") that turns out not to match the actual mechanism.
- **`answer_shape`**: fan-out from a single detector signal to several independent reaction sites, each with its own threshold and response shape. Canonical shape is `queue over capacity → Health Monitor publishes a single Duration → fan-out to { event creator stops creating, gossip revokes permits but keeps sending, transaction-acceptance gate rejects app txns } → pipeline drains → reactions unwind independently`.
- **`alternative_correct_answers`**:
  - "A coordinated controller pauses or throttles components together when one of them is overloaded." Incorrect; consolidate against the detector-not-enforcer design — the Health Monitor publishes one number and reaction policy lives in each subsystem.
  - "Queue fills → wire-level backpressure blocks the upstream component → propagates back through the pipeline until the source stops." Half — the wiring framework does support that mode (`SolderType.PUT` blocks the producer at capacity), but hard backpressure is off by default and the actual stress-response mechanism is the Health Monitor signal plus per-site reactions. Consolidate by naming both layers and noting which is on by default.
  - "Multiple independent reactions, but with a single shared threshold." Credit the fan-out shape; consolidate that each site reads the signal and applies its own threshold and its own response shape — the defaults happen to cluster around one second but are independent config keys.
- **Canonical answer**: a fan-out from one Health Monitor `Duration` signal to at least three reaction sites — event creator (binary throttle, default 1 s), gossip permits (revoke after a 1 s grace at 5 / s, but keep sending), transaction gate (binary reject, default 1 s). Anchored to [`health-monitor-and-backpressure.md` — Reactions](../../architecture/topics/health-monitor-and-backpressure.md#reactions).
- **Consolidation**: name three structural choices the canonical reaction makes that a typical first prediction misses or compresses — (a) the Health Monitor is a detector that publishes one number, not a controller that throttles components; (b) reaction policy lives at each site with its own threshold and its own response shape, with no shared coordination; (c) the gossip-side reaction is asymmetric — outbound continues while inbound is suppressed — which means the network is not blinded to this node during local stress.

**Move B — direct walk.**

- **Diagnosis tag**: the learner shows fluency on the wiring substrate and the Health Monitor's role; eliciting a prediction would be redundant.
- **Move**: skip the prediction and walk Stop 1 through Stop 6 directly, with the cued check at Moment `moment-asymmetric-gossip-reaction` providing the only check on the trace.

### Moment `moment-asymmetric-gossip-reaction` — the keep-sending default

Sits at Stop 3. Load-bearing because this is the orientation insight that distinguishes the canonical reaction from a naïve "stop gossiping when overloaded" model. A learner who internalises the asymmetric default has the spine of every subsequent topic that touches stress, peer interaction under load, or the difference between local backpressure and the categorical reasons-not-to-gossip catalog.

**Move A — prediction-and-reveal.**

- **Diagnosis tag**: the learner has heard "we revoke sync permits when the system is unhealthy" in the trace so far and is positioned to predict the receive side; the send side, by default kept on, is the non-obvious half.
- **Framing**: "We're at the point in the trace where the gossip layer has decided to revoke sync permits. Before I tell you what that does to the rest of gossip, what's your gut prediction — does this node stop talking to its peers entirely while it's overloaded, or does some of the gossip continue? If some continues, which direction?"
- **`answer_shape`**: directional pair — send-side and receive-side treated separately, each with its own state under stress. The canonical answer carries two pieces: the receive side stops accepting new syncs (and existing ones drain), and the send side keeps pushing this node's own self-events outbound by default.
- **`alternative_correct_answers`**:
  - "Gossip stops entirely while unhealthy." Incorrect, and instructively so — this is the model the asymmetric default exists to refute. Consolidate by naming `sync.keepSendingEventsWhenUnhealthy` (default `true`) and the reason: peers still seeing this node's events keeps the rest of the network advancing while the local pipeline drains.
  - "Gossip slows down — fewer syncs per second." Partial. Permits cap concurrency rather than rate, so the right shape is "fewer concurrent syncs, with new ones blocked once the cap drops to zero." Consolidate that the actual mechanism is permit revocation, not rate-limiting, and add the asymmetric send-vs-receive default on top.
  - "Send continues, receive stops." Correct in substance and locus. Consolidation names the specific config key, the revoke and return rates (5 / s vs 1 / s), and the grace period (1 s) that protects against brief blips.
- **Canonical answer**: by default (`sync.keepSendingEventsWhenUnhealthy = true`), the node continues to send its own events outbound while inbound processing is suppressed. Permits revoke at `sync.permitsRevokedPerSecond` (5 / s) past a `sync.unhealthyGracePeriod` (1 s); they return at `sync.permitsReturnedPerSecond` (1 / s) once the system is healthy, with a floor of `sync.minimumHealthyUnrevokedPermitCount` (1) re-instated immediately on recovery. Anchored to [`health-monitor-and-backpressure.md` — Gossip permits](../../architecture/topics/health-monitor-and-backpressure.md#gossip-permits).
- **Consolidation**: name the structural choice — stress reactions are local and per-site, and the gossip-side reaction's shape is "drain by stopping work intake, don't disconnect from the network." Then connect forward: the same per-site, asymmetric, detector-driven model is what Pass 2 cluster B (Stress, health, and self-throttling) deepens; the distinction between this kind of queue-driven reaction and the categorical reasons-not-to-gossip catalog is the load-bearing split that `b-03-distinction-from-backpressure` establishes.

**Move B — direct walk with cued check.**

- **Diagnosis tag**: the learner has not encountered the keep-sending default before and is unlikely to predict it; a worked walk is more useful than a prediction that would just frustrate.
- **Move**: walk Stop 3 directly, naming the grace period, the revoke / return rates, and the asymmetric send / receive split. Then cue: "Given that the node keeps sending self-events outbound while suppressing inbound processing, what does the asymmetry buy the local pipeline, and what does it preserve for the network?" Canonical answer for the cued check: locally, new work stops arriving so the over-capacity queue can drain; for the network, peers still see this node's self-events so the rest of the network's consensus is not starved of input from this node during the recovery.

## Consolidation

The orientation succeeds when the learner can hold three things at once: the trace's component sequence end to end, the design choice that the Health Monitor is a detector publishing one number and every reaction site is independent, and the asymmetric gossip default that drains the local pipeline without disconnecting the node from the network. The tutor consolidates explicitly against the predictions made during the two moments — naming, in particular, any prediction that placed coordination at the Health Monitor itself rather than at the reaction sites, and any prediction that had gossip stopping entirely under stress.

The trace also surfaces, by what it does not say, the boundaries the orientation respects: how exactly each scheduler's capacity is configured and how `withUnhandledTaskCapacity` interacts with `SolderType.PUT`, `INJECT`, and `OFFER`; what each of the event-creator rules other than `PlatformHealthRule` does; how `SyncPermitProvider.computeRevokedPermits()` blends the revoke and return rates around the grace period; how PCES replay applies a much tighter health threshold (1 ms) during restart on the same signal; what the categorical reasons-not-to-gossip catalog covers that is *not* queue-driven and therefore not part of this scenario. These are the topics Pass 2's cluster 0 and cluster B deepen, and `pass3-04-event-creation-under-stress-deep` re-walks at full depth.

## Close-out

A brief mental-sketch consolidation. The tutor asks: "If a colleague asked you in the hallway what happens on a Hedera consensus node when one of its downstream components temporarily gets slower than its arrival rate, what would you draw on a whiteboard?" The canonical sketch is the fan-out from above — a single queue saturating, the Health Monitor publishing one `Duration`, three independent reaction sites engaging with their own thresholds and shapes, the local pipeline draining, and the reactions unwinding on independent timelines with the permit return being the slowest. The asymmetric gossip default — keep sending, stop receiving — gets explicit billing. The tutor consolidates against whatever the learner draws and names the components the sketch should reach by way of Pass 2.

No threshold concepts; no successive-relearning tags for this lesson.

## Forward pointers

The Pass 2 lessons that deepen each component in this scenario:

- **Wiring substrate** — `c0-03-backpressure-modes` covers the three `SolderType` values (`PUT`, `INJECT`, `OFFER`) and the per-scheduler capacity model that turns queue depth into the signal this scenario opens with; `c0-04-health-monitor-mechanics` covers the heartbeat polling and the per-scheduler unhealthy-timer accounting.
- **Health Monitor in its stress-response role** — `b-01-health-monitor-role` covers the detector-vs-enforcer split at the role level; `b-02-backpressure-pacing-and-throttling` covers Execution↔Consensus pacing, dynamic throttling, and the cross-site interaction between the reactions named in this trace.
- **Reasons not to gossip vs. backpressure** — `b-03-distinction-from-backpressure` covers the categorical / queue-driven split that puts the gossip-permit revocation in this scenario in the backpressure category and out of the reasons-not-to-gossip catalog; `b-04-reasons-not-to-gossip-catalog` covers the categorical guards that *are* in that catalog.
- **Stress-response synthesis** — `b-syn-stress-response-synthesis` revisits the Health Monitor and self-throttling together; this scenario is its orientation sketch.
- **Event creator's rule chain** — `a4-01-when-to-create` covers `MaximumRateRule` and the cadence rule, and the surrounding rule chain (`PlatformStatusRule`, `PlatformHealthRule`, `SyncLagRule`, `QuiescenceRule`) is part of the cadence story; `a4-04-vetoes-and-self-event-persistence` covers the rest of the rule-chain mechanics.
- **Gossip permits and backpressure** — `a3-04-fair-selector-and-buffer-management` covers the fair sync selector that the permit provider sits behind; `a3-05-falling-behind-and-roster` covers the boundary where local stress escalates to a fall-behind signal exchanged with peers.
- **Pass 3 deepening** — `pass3-04-event-creation-under-stress-deep` walks this same scenario at full mechanism depth; `pass3-edge-02-fall-behind-during-heavy-gossip` is the edge variant where the stress in this scenario escalates past the point where local reactions are enough and the node tips into the recovery path covered by `pass1-02-node-falls-behind`.

This is the spiral the curriculum exists to walk: orientation here, mechanism in Pass 2, full-depth stitch in Pass 3.

## Open questions

- `[TBD: glossary path]` — the authoring prompt names `platform-sdk/docs/consensus-layer/glossary.md` as a canonical input, but that file does not exist; the term-definition source for this layer is `hashgraphGlossary.md` one directory up. Open question carried over from `pass1-01-tx-to-consensus` and `pass1-02-node-falls-behind`; populate `kb_glossary_terms` on this lesson's frontmatter once the glossary path is decided.
