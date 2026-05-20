---
id: pass1-04-event-creation-under-stress
cluster: pass1
title: Event creation under stress
pass: 1
depth: orientation
prerequisites: []
kb_topics_touched:
  - architecture/topics/event-creator.md
  - architecture/topics/health-monitor-and-backpressure.md
  - architecture/topics/event-intake.md
  - architecture/topics/gossip.md
  - architecture/topics/reasons-not-to-gossip.md
  - architecture/topics/restart-and-pces.md
  - architecture/topics/wiring-framework.md
kb_concepts:
  - concepts/event-lifecycle.md
  - concepts/branching.md
kb_glossary_terms:
  - event
  - self-event
  - other-parent
  - broadcast
  - sync
  - backpressure
  - health-monitor
  - signed-state
  - platform-status
  - ancient
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name each component involved in the stress detection and response path and state its role in one sentence
  - Sketch the fan-out from the single health signal to the independent reaction sites, including the order in which their thresholds engage
  - Articulate why blocked event creation is correct behaviour rather than a bug — the node stops contributing fresh material but continues forwarding what it has already persisted, and the safety property (no branching) is preserved throughout
threshold_concepts: []
estimated_session_minutes: 25
status: drafted
last_verified_against: 9db53cdbe89215743387f536b7dfb4f84878f7af
---

# Event creation under stress

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in.

## Incoming retrieval probes

None — orientation scenario, no incoming probes.

## Components in scope

These are the components the trace walks through. One-sentence semantics per component; the deep mechanism is the subject of the Pass 2 lessons listed under Forward pointers.

- **Wiring framework** ([wiring-framework.md](../../architecture/topics/wiring-framework.md)) — the substrate that connects every component. Each component sits behind a bounded scheduler with a declared capacity. The scheduler's unprocessed-task count rising past its capacity is the raw observation from which everything in this trace follows.
- **Health monitor** ([health-monitor-and-backpressure.md](../../architecture/topics/health-monitor-and-backpressure.md)) — a detector built on top of the wiring substrate. It polls each watched scheduler's queue depth against its capacity on a heartbeat, tracks how long each scheduler has been continuously over capacity, and publishes a single duration: the longest continuously-unhealthy interval across all watched schedulers — the worst single offender. The monitor is a detector, not an enforcer; it does not throttle anything itself.
- **Event creator** ([event-creator.md](../../architecture/topics/event-creator.md)) — decides when this node should mint a new self-event. The decision passes through a chain of permission rules; one rule in that chain reads the health-monitor signal and blocks new self-events whenever the reported unhealthy duration exceeds the rule's threshold. This is the central reaction site of the scenario.
- **Event intake** ([event-intake.md](../../architecture/topics/event-intake.md)) — the validation and topological-ordering pipeline. Its stages run on bounded schedulers, so intake is often the first place stress shows up: when peers pour events in faster than the pipeline can release them, the orphan buffer or one of the validation stages fills, and that depth is what the health monitor first sees.
- **Gossip** ([gossip.md](../../architecture/topics/gossip.md)) — exchanges events with peers. Gossip is a parallel reaction site: it consumes the same health-monitor signal on its own wire and gradually revokes the sync permits that authorize new outbound syncs, slowing the rate at which this node starts new conversations with peers.
- **Preconsensus event stream (PCES)** ([restart-and-pces.md](../../architecture/topics/restart-and-pces.md)) — primarily the durability gate for self-events; on this trace it appears once more as a *polled* reaction site whose replay loop checks the health signal before each replayed event. PCES replay only runs at startup, so the polled reaction is dormant in steady state but lives in the same fan-out picture.
- **Execution layer / transaction pool** — Hedera-side; the wiring framework solders the health signal to `ExecutionLayer.reportUnhealthyDuration`, which forwards to the application transaction pool. Above its threshold, the pool rejects new application transactions submitted by clients. The transaction pool is outside the consensus layer proper but sits on the fan-out and is named here so the picture is complete; see [health-monitor-and-backpressure.md, Transaction acceptance gate](../../architecture/topics/health-monitor-and-backpressure.md#transaction-acceptance-gate).

Two notes about what this trace is *not*. First, hard backpressure is not the mechanism in current code: by default, queue insertion never blocks (the wiring framework's `hardBackpressureEnabled` flag is off), so an over-capacity queue can keep accepting items until the system runs out of memory. The health monitor is what makes "over capacity" into a signal that downstream sites can react to; without it, nothing would. Second, the catalog of *reasons a node may stop gossiping* — of which stress-driven throttling is one — lives in [reasons-not-to-gossip.md](../../architecture/topics/reasons-not-to-gossip.md). The other entries in that catalog (platform-status blocks, fallen-behind, broadcast holdback, sync cooldown, and so on) are not on this trace.

## Scenario setup

A four-node network in steady state. Rounds are advancing, gossip is healthy, the event creator is minting self-events at the normal cadence. On node N, something starts to overload one component — the most common stressor is downstream consumers slowing under load, but it could equally be a flood of incoming events from peers catching up after a transient partition, a long stop-the-world garbage collection, or the disk briefly stalling under fsync pressure. The other three nodes — N's peers — remain healthy. The trace follows N from the moment its first scheduler crosses capacity through the moment N returns to steady state.

This is orientation altitude. The trace deliberately does not unpack the exact heartbeat cadence of the health monitor, the precise threshold values at each reaction site, the permit-accounting arithmetic, or the wire-level mechanics of how the duration is published. Each of those is the subject of a Pass 2 lesson under Forward pointers.

## Trace

### Stop 1 — A scheduler crosses capacity {#stop-1-pressure}

Some component on N falls behind its inputs. Concretely: the unprocessed-task count on one of the consensus-layer schedulers — most often inside event intake, where the orphan buffer and the deduplicator are the narrowest queues — climbs past the scheduler's declared capacity. Nothing dramatic happens at this moment from a backpressure standpoint, because by default the wiring framework does not block enqueues at capacity. The queue is simply allowed to grow; the depth is now greater than its preferred size.

`moment_id: trace-open`

### Stop 2 — Backpressure begins to propagate, but it is the *signal* that does the work {#stop-2-backpressure}

The wiring framework's normal queue-to-queue backpressure does propagate upstream — a slow consumer holds up its producer's wire — but that propagation alone does not stop anything from happening that needs to be stopped. What turns this into a coordinated response is a separate mechanism: the health monitor polls every watched scheduler on a fast heartbeat and converts queue depth into a single signal that the rest of the system can subscribe to.

The trace from here forward is about that signal.

### Stop 3 — The health monitor detects, records, and continues to watch {#stop-3-detect}

Each watched scheduler is compared against its capacity on every heartbeat. The first time the scheduler is observed over capacity, the health monitor records the timestamp of that observation. While the scheduler remains over capacity, the recorded timestamp stays put; the monitor takes successive heartbeats as evidence that the unhealthy interval is lengthening. When a previously unhealthy scheduler is observed healthy again, its timer resets, so a transient blip — a single heartbeat over capacity, then back below — does not leave a lingering signal.

Across the full set of watched schedulers, the monitor reports the longest continuously-unhealthy interval — the worst single offender. The value is a `Duration`. A `Duration` of zero means every watched scheduler is healthy; a non-zero `Duration` means at least one scheduler is over capacity, with the magnitude indicating how long.

### Stop 4 — The signal fans out {#stop-4-fanout}

The health monitor publishes the duration two ways. The first is a wire publication: the monitor's output is soldered to a handful of consumers in the platform wiring, each of which receives the value as it is updated. The second is a polled accessor: a value-reading hook the consumer calls when it wants the current duration.

The wire-soldered consumers are the event creator, gossip, and the execution layer (which forwards to the Hedera-side transaction pool). The polled consumer is PCES replay, which is naturally a polling loop and benefits from the freshest possible value.

**Load-bearing transition.** This is the load-bearing shape of the entire scenario. There is one signal and several independent consumers; the consumers do not coordinate with each other and do not know about each other. Each one reads the signal, applies its own threshold, and decides its own response. The picture is a *fan-out*, not a sequential cascade. Holding this picture — rather than thinking "queue fills → event creator stops" as a single line — is what makes everything later in the trace make sense.

### Stop 5 — Each reaction site engages its own threshold {#stop-5-reactions}

The four reaction sites engage independently. Their thresholds differ; their reactions differ.

- **Event-creation throttle.** The event creator's permission chain includes a health-driven rule. When the reported unhealthy duration exceeds the rule's threshold (default `1s`), the rule denies new self-event creation and reports an "overloaded" status. The event creator does not stop because something blocked it; it stops because its own rule chain says it should.
- **Gossip permit throttle.** Gossip uses a permit pool to authorize new outbound syncs. After a configurable grace period (default `1s`), if the system is still unhealthy, the permit provider begins revoking permits at a configured rate. Existing in-flight syncs are not interrupted; new syncs slow down because there are fewer permits to acquire. By default, a separate config (`sync.keepSendingEventsWhenUnhealthy = true`) preserves outbound message dispatch even when the inbound side is suppressed, so this node still sends its own already-persisted events to peers; only the inbound processing is reduced.
- **Transaction acceptance gate.** The Hedera-side transaction pool consults the duration when a client submits an application transaction. Above its threshold (default 1 second), submission is rejected; below, it is accepted. System and priority transactions are not gated.
- **PCES replay throttle.** Only relevant during startup replay, but for completeness: the replay loop polls the duration before every event it would replay and waits until the value drops below a much tighter threshold (default `1ms`). PCES replay can issue events faster than the rest of the pipeline can absorb them, so it watches the signal more aggressively than the wire-soldered consumers.

The thresholds, the grace periods, and the revoke and return rates are all configurable. At this altitude, the load-bearing facts are that the thresholds are independent and that each site reads the same signal but reacts on its own schedule.

### Stop 6 — Effects on the network, and why this is correct {#stop-6-effects}

The node's contribution to the network is reduced, not stopped. New self-events from N are not produced while the rule fires. Already-created self-events that have been persisted through PCES continue to flow out to peers: under the default `sync.keepSendingEventsWhenUnhealthy = true`, the gossip-side reaction is to slow inbound event processing rather than outbound dispatch, so N still forwards what it has persisted while it is shedding inbound work. New application transactions submitted to N's client port are rejected and the client must retry. Peers continue to advance through their own self-events. If the three peers between them hold quorum weight, the network keeps reaching consensus without N's contributions.

**Load-bearing transition.** The safety story here is the point of the whole trace. With event creation paused, N stops adding new material to the network until its local pipeline drains, but it does not become a black hole — it continues to forward what it has already durably persisted. The discipline that prevented the consensus layer from branching in [pass1-01](./pass1-01-tx-to-consensus.md) is the same one that keeps this safe under stress: a self-event reaches gossip only after PCES has flushed it, which holds across the throttled period exactly as it holds in steady state. Stopping event creation is the *intended* response, not the system failing — it is the mechanism the platform uses to give itself time to drain rather than spiral into branching or unbounded memory growth.

### Stop 7 — The signal drops, the sites release {#stop-7-recovery}

As the offending queue drains, the health monitor sees it fall below capacity on a subsequent heartbeat and resets its timer for that scheduler. If no other scheduler is unhealthy, the reported duration drops to zero. The sites release independently. The event-creator rule starts permitting new self-events on its next decision. The gossip permit pool stops revoking and begins returning permits at the configured return rate; a configured floor of un-revoked permits is enforced as soon as the system is healthy, so a recovered system is not stuck at zero permits. The transaction pool starts accepting application transactions again. PCES replay (if it were running) takes its next event off the replay queue.

N is back in steady state. The next round in which N's self-events qualify as other-parents for peer events is the round in which N's contribution to consensus has resumed; the round in which the peers were operating without N's contribution is part of the history but does not need to be undone.

## Engagement moves

### Moment `trace-open` — before Stop 1

This moment sits before the trace begins. Stress and backpressure are easy to think about wrongly — the audience may carry instincts from systems with hard backpressure or coordinated rate-limiters — so a prediction first surfaces whatever model the learner has and gives the trace something concrete to contrast against.

**Move A — prediction-and-reveal.** Diagnosis tag: the learner is willing to commit to a sketch from prior Hedera-team familiarity and is likely to hold an opinion about how an overloaded distributed system protects itself. Stress responses are commonly designed with a fan-out shape (one detector, many independent reaction sites), but they are also commonly designed with a sequential or coordinated shape (a single throttle whose decision propagates), and prior experience may point either way.

- Prompt (verbatim):
  > Before I walk it: a node is in steady state and something starts to overload it. Sketch what you think the system does about it. What part notices it's getting overloaded, what reacts, and what does the reaction prevent? A graph is welcome here — if you think more than one thing reacts, draw it that way and don't worry about ordering them in a line.
- `answer_shape`: graph from a detector to multiple independent reaction sites; a sequential answer ("queue fills → detector notices → event creator stops") is acceptable as a shape the trace will sharpen into a fan-out.
- `canonical_answer`: a component on the node falls behind its inputs and its queue depth grows past capacity. A health detector polls the queue depths and produces a single signal — typically expressed as how long the worst scheduler has been continuously over capacity. The signal fans out to several independent reaction sites, each with its own threshold and its own response. The most visible reaction is that the event creator stops minting new self-events; in parallel, gossip slows its outbound sync rate by revoking permits, the application transaction pool rejects new client transactions, and (during startup only) PCES replay throttles itself against a tighter threshold. The reactions are not coordinated. The point of the response is to give the local pipeline time to drain without branching the node's history or letting queues grow without bound.
- `alternative_correct_answers`:
  - A sequential answer that names a single reaction ("the event creator stops creating self-events") is correct at orientation altitude; the fan-out to other reaction sites is a refinement the trace will surface.
  - A graph-shaped answer that lists three or four reaction sites but does not commit to which threshold engages first is correct; the trace shows the thresholds are independent rather than ordered.
  - An answer that names "backpressure" as the mechanism, without distinguishing the queue-to-queue wiring-framework backpressure from the health-monitor signal, is correct as a sketch and the trace separates the two.
  - An answer that frames the response as "the node goes silent" is partially correct; the trace will correct it — under default config the node continues to send its already-persisted events, so it does not go silent, only stops adding new material.
  - An answer that names the wiring framework, schedulers, or queue capacities as the substrate underneath the response is correct as additional detail; treat it as a refinement, not a different model.
- `followup` (verbatim, delivered when the learner produces a sequential answer or one that treats the response as a single coordinated throttle, rather than a fan-out):
  > You described the reactions as sequenced — happening one after another, or driven from a single decision point. Suppose I tell you instead that it's one signal published once and several different sites read it independently, each with its own threshold. What would change in your sketch — would the reactions still happen in the same order, or could they engage at different times?
- `followup_canonical_answer`: any of the following counts — "the reactions wouldn't be ordered; each site would engage when its own threshold tripped, so a site with a tighter threshold reacts first"; "the sites could be tuned independently for different deployments without the others noticing"; "if one site's threshold were misconfigured the others would still react correctly, since they're not waiting on it"; or any answer that articulates the independence of the thresholds and the implication that the response shape is fan-out rather than chain.

**Move B — free recall.** Diagnosis tag: the learner is not confident enough to commit to a graph or chain, asks for the canonical picture first, or has not previously worked in this part of the codebase. This move lowers the stakes — it asks for the names of the pieces, not the choreography — and lets the trace itself supply the shape.

- Prompt (verbatim):
  > Before we walk it: just by name, which components or subsystems do you think get involved when a node starts to feel overloaded — both the part that notices, and the parts that respond? Order isn't important here, I'm checking which pieces you're holding in your head.
- `canonical_answer`: the set {the wiring framework's bounded schedulers (where stress first shows up as queue depth), the health monitor (the detector), the event creator (a reaction site whose rule chain blocks new self-events), gossip (a reaction site whose sync permits are revoked), the application transaction pool reached through the execution layer (a reaction site that rejects new client transactions), PCES (a polled reaction site relevant during replay)}.
- `alternative_correct_answers`:
  - Any non-empty subset that includes the health monitor and the event creator — the two load-bearing components for this scenario. Other reaction sites are refinements at the set-naming altitude.
  - Sets that additionally name event intake (where stress most often originates), the orphan buffer (the narrowest queue), the platform status machine, or PCES inline write are correct; treat the extras as refinements.
  - Sets that name "backpressure" as a single thing without distinguishing queue-level propagation from the health-monitor signal are correct as a sketch; the trace separates the two.

## Consolidation

After the trace, the learner should be able to name each of the components in scope and state in one sentence what each one does on the stress-detection-and-response path. They should hold the trace as a fan-out from one signal to several independent reaction sites — not a sequential cascade — and understand that the lack of coordination between sites is what makes them independently tunable.

They should be able to say, in their own words, *why* paused event creation is correct rather than broken: the node stops contributing fresh material while its local pipeline drains, but it continues forwarding what it has already durably persisted, and the durability rule that prevents branching at steady state continues to hold throughout the throttled period. The whole point of the response is to give the pipeline time to drain *without* compromising safety.

If the learner ran Move A at the trace-open moment, name in plain words the gap between their prediction and the canonical trace: whether they predicted a fan-out or a chain, whether they spotted the independence of the thresholds, whether they treated the throttled node as silent or as continuing-to-forward. The point of the contrast is not to score the prediction — it is to make the corrections explicit so they consolidate against the trace rather than fade after the reveal.

## Close-out

The learner now holds the complete-but-low-fidelity sketch of how the platform protects itself under stress. Every Pass 2 lesson under Cluster B — the health signal and its consumers, the event-creator throttle, the gossip permit throttle, the transaction and PCES replay reactions, and the catalog of reasons not to gossip — will assume that the learner can place its component on this trace; the trace itself does not need to be re-explained inside those lessons. The Pass 3 deep version of this scenario, [`pass3-04-event-creation-under-stress-deep`](./pass3-04-event-creation-under-stress-deep.md), revisits the same path once the relevant Pass 2 clusters are taught.

**Free-recall summary** — delivered verbatim at session close:
> In your own words, sketch what happens on a node when it starts to fall behind its own inputs: name each component as you go, and say one sentence about what it does at that step. Highlight the shape of the response — is it a chain or a fan-out — and say one sentence about why the node stops creating new self-events rather than letting them keep flowing.

`canonical_answer`: a coherent retelling of the seven stops, naming the components in scope, with the health-monitor signal called out as a single value that fans out to several independent reaction sites with their own thresholds, and a statement that the node stops creating new self-events to give the local pipeline time to drain without branching or unbounded queue growth (and that it continues forwarding already-persisted events through the throttled period).

`alternative_correct_answers`: any retelling that hits at least Stops 1, 3, 4, 5, and 6, names the health monitor and the event creator explicitly, identifies the fan-out at Stop 4 as the load-bearing shape (under any phrasing — "one signal, many readers", "the detector publishes once and the reactions read independently", "it's not a chain"), and articulates one of the safety reasons paused event creation is correct rather than broken. Compressing Stops 1–3 into a single step is fine at this altitude. Omitting the recovery at Stop 7 is fine if the learner notes that the sites release independently as the signal drops.

**Successive-relearning tags:** none — this lesson establishes no threshold concept. The mental sketch it plants is consolidated by the Pass 2 lessons under Cluster B, each of which exercises one of its components at depth, and by the Pass 3 deep version of the scenario.

## Forward pointers

Each component in scope is covered at depth by a Pass 2 cluster or sub-cluster:

- **Wiring framework** (substrate at every stop; bounded schedulers are where stress first manifests) — Cluster 0, starting at [`c0-01-task-schedulers-and-queues`](./c0-01-task-schedulers-and-queues.md). The wire-level mechanics of how the health-monitor signal is delivered are in [`c0-03-backpressure-and-health-monitor-mechanics`](./c0-03-backpressure-and-health-monitor-mechanics.md).
- **Health monitor and its consumers** (Stops 3 and 4) — Cluster B, starting at [`b-01-health-signal-and-consumers`](./b-01-health-signal-and-consumers.md).
- **Event creator** (Stop 5, the central reaction site; Stop 7, its release) — Cluster A.4, starting at [`a4-01-tipset-and-advancement-score`](./a4-01-tipset-and-advancement-score.md). The throttle rule specifically is [`b-02-event-creator-throttle`](./b-02-event-creator-throttle.md); the creation gates that interact with it are [`a4-05-creation-gates`](./a4-05-creation-gates.md).
- **Event intake** (Stop 1, the most common origin of queue saturation) — Cluster A.2, starting at [`a2-01-hashing-and-internal-validation`](./a2-01-hashing-and-internal-validation.md). The orphan buffer specifically is [`a2-03-orphan-buffer`](./a2-03-orphan-buffer.md).
- **Gossip** (Stop 5, the permit-throttle reaction site) — Cluster A.3, starting at [`a3-01-protocol-stack`](./a3-01-protocol-stack.md). The permit-revocation arithmetic specifically is [`b-03-gossip-permit-throttle`](./b-03-gossip-permit-throttle.md).
- **Transaction acceptance and PCES replay** (Stop 5, the remaining reaction sites in the fan-out) — [`b-04-tx-and-pces-replay-reactions`](./b-04-tx-and-pces-replay-reactions.md).
- **Reasons not to gossip** (the catalog of which stress-driven throttling is one entry) — [`b-05-reasons-not-to-gossip`](./b-05-reasons-not-to-gossip.md).

The Cluster B synthesis lesson [`b-syn-stress-response-synthesis`](./b-syn-stress-response-synthesis.md) exercises the same components collaborating on stress responses across the cluster. The deep version of this scenario at full mechanism altitude is [`pass3-04-event-creation-under-stress-deep`](./pass3-04-event-creation-under-stress-deep.md), once the Cluster B lessons (and their A-cluster prerequisites) are taught. The Pass 3 edge case [`pass3-edge-02-fall-behind-during-heavy-gossip`](./pass3-edge-02-fall-behind-during-heavy-gossip.md) revisits this trace from a different starting condition — where the stress originates in gossip itself and the health monitor's detection interacts with the fallen-behind path from [`pass1-02-node-falls-behind`](./pass1-02-node-falls-behind.md).

## Open questions

- [TBD: should this orientation surface that the health-monitor wire is edge-driven vs level-driven?] [health-monitor-and-backpressure.md](../../architecture/topics/health-monitor-and-backpressure.md) carries an open question about whether wire-soldered consumers see only deltas or a persisted last-value level. At orientation altitude the trace describes the signal as a value the sites *read*, which lands closer to a level-driven contract; if the underlying contract is edge-driven the wording at Stop 4 may need a small adjustment. Reviewer: confirm whether the orientation altitude should pin one model or stay neutral until [health-monitor-and-backpressure.md](../../architecture/topics/health-monitor-and-backpressure.md)'s [TBD at lines 41] is resolved.
- [TBD: send-only mode under unhealthy — permit accounting interaction] The trace states at Stop 5 and Stop 6 that under the default `sync.keepSendingEventsWhenUnhealthy = true`, the node continues to send its own events while unhealthy. [health-monitor-and-backpressure.md](../../architecture/topics/health-monitor-and-backpressure.md) carries a [TBD at lines 67] about how send-only mode interacts with the `SyncPermitProvider` revoke/return accounting — whether permits are still revoked at the same rate while only inbound processing is skipped, or whether permit accounting is effectively bypassed for send-only syncs. The orientation trace describes the safety property (the node still forwards what it has persisted) without committing to which is true; reviewer should confirm the orientation phrasing remains correct once the deeper interaction is settled.
- [TBD: glossary path mismatch — inherited from pass1-01 and pass1-02] The lesson-authoring meta-prompt references `consensus-layer/glossary.md`, which does not exist in the repo at present; the canonical glossary is at `platform-sdk/docs/hashgraphGlossary.md`. The `kb_glossary_terms` listed in frontmatter resolve against that file. This is the same open question raised by the prior pass1 lessons; flagging here so the reviewer fix at the prompt level closes it across all four orientation scenarios.
- [TBD: invariants and delta-map remain stubs — inherited from pass1-01 and pass1-02] `consensus-layer/invariants.md` and the entries under `consensus-layer/delta-map/` do not exist yet (only README placeholders). Orientation depth does not require them; this open question carries forward so subsequent Pass 2 and Pass 3 work in this area can ground its structural and delta claims.
