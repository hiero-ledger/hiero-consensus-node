---
id: c0-03-backpressure-and-queue-health
cluster: c0
title: "Backpressure modes and the health-monitor wire"
pass: 2
prerequisites:
  - c0-01-schedulers-and-types
  - c0-02-wires-and-soldering
kb_topics_touched:
  - architecture/topics/wiring-framework.md
  - architecture/topics/health-monitor-and-backpressure.md
kb_concepts: []
kb_glossary_terms:
  - Backpressure
  - Health monitor
  - Scheduler
  - Wire
kb_invariants: []
kb_deltas:
  - delta-map/wiring-framework.md
kb_decisions: []
learning_objectives:
  - Explain that capacity is a property of the consumer scheduler — set via withUnhandledTaskCapacity (default 1, overridden in production), shared per-scheduler across all its input wires, and compared against the unhandled-task count from c0-01 — and that an UNLIMITED_CAPACITY scheduler is skipped by the health monitor entirely.
  - State that platform.wiring.hardBackpressureEnabled is off by default, so in production a full queue does not block its producer, and locate where the operative (soft) backpressure actually lives — the health monitor reacting to backlog.
  - Describe what changes when hard backpressure is on — a bounded, non-DIRECT scheduler gets a blocking BackpressureObjectCounter, the c0-02 SolderType contracts become real (PUT blocks, OFFER drops, INJECT bypasses), and a blocked PUT producer propagates backpressure upstream.
  - Explain why an all-PUT cycle is a runtime deadlock hazard only under hard backpressure, that the model detects cycles at assembly and logs an error, and that the defensive remedy is to flip exactly one cycle edge to INJECT.
  - Walk the health-monitor wire: getHealthMonitorWire() exposes an OutputWire<Duration> fed from a heartbeat-driven check, a scheduler is unhealthy when its unprocessed count exceeds capacity, the wire carries the longest continuous unhealthy duration, and named reaction sites consume it — their behaviour being Cluster B.
threshold_concepts:
  - "Soft backpressure: hard wire-level backpressure is off by default, so a full queue does not block its producer; production throttling instead emerges from the health monitor observing sustained backlog and reaction sites responding to the unhealthy-duration signal."
estimated_session_minutes: 35
status: drafted
last_verified_against: eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2
---

# Backpressure modes and the health-monitor wire

## Prerequisites

- **`c0-01-schedulers-and-types`** — A task scheduler owns a queue and a threading policy; a monitored scheduler counts a task as *unprocessed* (the on-ramp count) from the moment it arrives until the handler returns, and that count measures backlog. `DIRECT` and `DIRECT_THREADSAFE` run inline with no queue. This lesson is what that on-ramp count is *compared against*, and what happens when it grows.
- **`c0-02-wires-and-soldering`** — An edge's `SolderType` (`PUT`, `INJECT`, `OFFER`) declares an at-capacity contract: `PUT` blocks, `OFFER` drops, `INJECT` bypasses — but those reactions are *latent* in production because hard backpressure is off by default. This lesson is where that caveat is cashed out: when the contracts actually fire, and what operates instead when they don't.

If the learner hedges on the **on-ramp / unhandled-task count** (`c0-01`) or on the **default-off caveat** (`c0-02`), those are the two prerequisites worth a bounded recall probe before starting — the whole lesson rests on them.

## Incoming retrieval probes

This section is an authorial signal, not a session-open quiz. The tutor watches for these concepts at the entry self-assessment and consolidates them when they resurface in the spine; it does not open with a recall drill. Each entry names the concept, the prior lesson, and the one-line statement to consolidate against.

- **On-ramp / unhandled-task count** (`c0-01`) — *Canonical:* a task counts as unprocessed from arrival until its handler returns; the count is the scheduler's backlog. This lesson opens by comparing that count to a capacity, so if the learner is shaky here, consolidate it first.
- **`SolderType` contracts + default-off caveat** (`c0-02`) — *Canonical:* `PUT` blocks / `OFFER` drops / `INJECT` bypasses *at capacity*, but in production those reactions never fire because hard backpressure is off by default. Resurfaces in chunk 2, where this lesson makes the contracts concrete by turning the flag on.
- **`DIRECT` / `DIRECT_THREADSAFE` have no queue** (`c0-01`) — *Canonical:* direct schedulers run inline on the caller's thread with no queue. Resurfaces in chunk 2: a direct scheduler never gets a backpressure counter, so it is never a backpressure point.
- **Declarative handoff** (`c0-02`, threshold concept) — *Canonical:* an edge's backpressure participation is selected by its SolderType at wiring time, a property of the connection. This lesson establishes the sibling idea that *whether any of it blocks at all* is a single deployment flag; name the parallel explicitly when the soft/hard split lands. Its day-3 successive-relearning interval plausibly falls in this session — watch for it.

## Misconception watchlist

- **A full queue blocks the producer.** *(Import from bounded-queue systems where a full queue always blocks the writer.)* Sounds like: "so once the consumer's queue hits capacity, the producer stalls?" Correction, in line: not in production — hard backpressure (`platform.wiring.hardBackpressureEnabled`) is **off by default**, so a bounded scheduler gets a non-blocking `StandardObjectCounter` whose `put` just increments the count and returns ([StandardObjectCounter.java#L31-L43](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/counters/StandardObjectCounter.java#L31-L43), [WiringConfig.java#L29-L39](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/WiringConfig.java#L29-L39)). The capacity is only a *health threshold*; the backpressure that operates is *soft*.
- **The health monitor throttles the producers.** *(Assuming the monitor enforces.)* Sounds like: "the health monitor slows event creation down when queues back up." Correction: the health monitor is a *detector, not an enforcer* — it only publishes a `Duration` on its wire ([glossary: Health monitor](../../glossary.md#health-monitor), [StandardWiringModel.java#L163-L167](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L163-L167)). The reaction sites downstream decide what to do with that number; their behaviour is Cluster B.
- **Backpressure always propagates upstream on its own.** Sounds like: "if B is overwhelmed, A automatically slows down." Correction: automatic upstream propagation happens only under **hard** backpressure, where a blocked `PUT` producer cannot off-ramp its own task so its own counter fills ([wiring-framework.md](../../architecture/topics/wiring-framework.md)). With the flag off (production), nothing blocks; the "propagation" is the *health signal* travelling to reaction sites, not queues backing into each other.
- **Capacity is set per input wire.** *(Over-generalization: "each wire has its own queue, so each has its own limit.")* Sounds like: "which of this component's input wires hit the limit?" Correction: capacity is **per-scheduler** — every input wire on the same scheduler shares one on-ramp counter, so the limit applies to total backlog across all inputs, not independently per wire ([wiring-framework.md](../../architecture/topics/wiring-framework.md), [AbstractTaskSchedulerBuilder.java#L345-L357](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/internal/AbstractTaskSchedulerBuilder.java#L345-L357)).

## Mechanism

`c0-02` left every edge carrying a `SolderType` whose at-capacity reaction was *declared but dormant*. This lesson turns the dormancy into the lesson's spine: **what a full queue actually does**. The answer is a switch — `platform.wiring.hardBackpressureEnabled` — and in production it is off, which is why the operative backpressure is not a wire blocking at all but the *health monitor* reacting to backlog. The lesson walks capacity (what the limit is), the soft-vs-hard split (what crossing it does), cyclic backpressure (the one place the hard path bites even defensively), and the health-monitor wire (where soft backpressure comes from).

**Pre-training — terms this lesson integrates** (definitions live in the glossary and topic file; named here to set vocabulary):

- **Capacity** — a per-scheduler limit on the unhandled-task count, set with `withUnhandledTaskCapacity` ([glossary: Scheduler](../../glossary.md#scheduler), [wiring-framework.md](../../architecture/topics/wiring-framework.md)).
- **Hard backpressure** — the `platform.wiring.hardBackpressureEnabled` flag; when on, the capacity limit is *enforced* by blocking. Off by default ([wiring-framework.md](../../architecture/topics/wiring-framework.md)).
- **Soft backpressure** — the production mechanism: the health monitor detects sustained backlog and reaction sites throttle in response; nothing blocks at the wire ([glossary: Backpressure](../../glossary.md#backpressure)).
- **Health monitor** — a detector that compares each scheduler's backlog to its capacity and publishes the longest unhealthy duration; it does not itself throttle anything ([glossary: Health monitor](../../glossary.md#health-monitor)).
- **Health-monitor wire** — `WiringModel.getHealthMonitorWire()`, the `OutputWire<Duration>` that carries the unhealthy-duration signal to its consumers ([wiring-framework.md](../../architecture/topics/wiring-framework.md)).

### Chunk 1 — Capacity is a property of the consumer `{moment: m1-capacity}`

Capacity belongs to the *consumer*: each scheduler is built with an unhandled-task limit via `TaskSchedulerBuilder.withUnhandledTaskCapacity(...)`, supplied through the `TaskSchedulerConfiguration` passed to `ComponentWiring` ([wiring-framework.md](../../architecture/topics/wiring-framework.md), [TaskSchedulerBuilder.java#L42-L49](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerBuilder.java#L42-L49)). The framework default is `1` ([AbstractTaskSchedulerBuilder.java#L44](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/internal/AbstractTaskSchedulerBuilder.java#L44)), but consensus-layer schedulers always override it through configuration, so the default is effectively never used in production.

Two structural facts to hold:

- **The limit is compared against the on-ramp count from `c0-01`.** A scheduler is "over capacity" when its unprocessed-task count exceeds its capacity — `getUnprocessedTaskCount() > getCapacity()` ([wiring-framework.md](../../architecture/topics/wiring-framework.md), [HealthMonitor.java#L121-L138](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/monitor/HealthMonitor.java#L121-L138)). Capacity is meaningful only relative to that count.
- **Capacity is per-scheduler, not per-wire.** Every input wire on the same scheduler shares a single on-ramp counter, so the limit governs total backlog across all of a scheduler's inputs ([wiring-framework.md](../../architecture/topics/wiring-framework.md)).

One special value: `UNLIMITED_CAPACITY = -1` ([TaskSchedulerBuilder.java#L21](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerBuilder.java#L21)). A scheduler built with it is **skipped by the health monitor at registration** and is never reported as unhealthy ([HealthMonitor.java#L92-L97](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/monitor/HealthMonitor.java#L92-L97)).

**Load-bearing line (signal):** capacity is a *number to compare backlog against*, not yet a reaction. What the comparison *does* — block, drop, or merely flag — is the next chunk, and it turns entirely on one flag.

### Chunk 2 — Soft by default, hard by flag `{moment: m2-soft-vs-hard}`

What the capacity limit *does* depends on `platform.wiring.hardBackpressureEnabled`, which defaults to `false` and is left off in production ([WiringConfig.java#L29-L39](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/WiringConfig.java#L29-L39)). The flag is read once, at scheduler-build time, and it selects which on-ramp counter the scheduler gets ([AbstractTaskSchedulerBuilder.java#L345-L355](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/internal/AbstractTaskSchedulerBuilder.java#L345-L355)):

- **Off (the default).** The scheduler gets a `StandardObjectCounter`: `onRamp` just increments the count and `attemptOnRamp` always returns `true` ([StandardObjectCounter.java#L31-L43](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/counters/StandardObjectCounter.java#L31-L43)). The limit is **not enforced** — `put` does not block and `offer` does not drop. Capacity serves only as a **health threshold**. Production backpressure is therefore *soft*: it comes from the health monitor (chunk 4) detecting the growing backlog, not from any wire blocking.
- **On.** A bounded, non-`DIRECT` scheduler instead gets a `BackpressureObjectCounter`, whose `onRamp` blocks via `ForkJoinPool.managedBlock` until the count falls below capacity and whose `attemptOnRamp` declines once over capacity ([BackpressureObjectCounter.java#L62-L141](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/counters/BackpressureObjectCounter.java#L62-L141)). Now the `c0-02` `SolderType` reactions become real: `PUT` blocks the producer at capacity, `OFFER` drops, `INJECT` bypasses the limit ([SolderType.java#L9-L24](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/SolderType.java#L9-L24), [InputWire.java#L66-L95](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java#L66-L95)). And a `PUT` producer blocked on a full consumer cannot off-ramp *its own* task, so its counter fills and **backpressure propagates upstream** ([wiring-framework.md](../../architecture/topics/wiring-framework.md)).

Note the exclusions in the counter selection: a scheduler with `UNLIMITED_CAPACITY`, or a `DIRECT` / `DIRECT_THREADSAFE` scheduler, never gets a blocking counter even with the flag on ([AbstractTaskSchedulerBuilder.java#L345-L355](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/internal/AbstractTaskSchedulerBuilder.java#L345-L355)) — consistent with `c0-01`, a direct scheduler has no queue to bound.

**Load-bearing line — the threshold idea (signal):** the capacity limit is *always* a health threshold; whether crossing it *also* blocks is the one flag, set off in production. So in production the producer never stalls at the wire, and the thing that actually protects the system from runaway backlog is the soft loop — detect-and-react — not the queue. That reframes "what does a full queue do?" from "it blocks until space frees" to "it gets *noticed*, and something elsewhere reacts." Where it gets noticed is chunk 4; the one place the hard path still shapes the graph even while dormant is chunk 3.

### Chunk 3 — Cyclic backpressure and the defensive `INJECT` `{moment: m3-cycles}`

A cycle of `PUT` edges is a *runtime* deadlock hazard, but only when hard backpressure is on: a producer blocked waiting on a consumer that is (transitively) waiting on it can never make progress ([wiring-framework.md](../../architecture/topics/wiring-framework.md)). With the flag off, no `PUT` blocks, so the cycle is harmless at runtime — the hazard is *conditional on the flag*, exactly the chunk-2 split applied to a loop.

The consensus layer handles this in two ways, both independent of the flag's current value:

- **Detection at assembly.** When the model starts, `checkForCyclicalBackpressure()` walks the graph and, if it finds a backpressure cycle, **logs an error** naming the cycle path ([StandardWiringModel.java#L205-L222](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L205-L222), [TraceableWiringModel.java#L100-L103](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java#L100-L103), [CycleFinder.java#L98-L122](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/analysis/CycleFinder.java#L98-L122)). *(The topic file calls this a "warning"; the code logs it at error level — the lesson follows the code.)*
- **Defensive remedy.** The standard fix is to flip **exactly one** edge of the cycle to `INJECT`. `INJECT` bypasses capacity, so that edge's producer can never block, which means the loop can never be an all-blocking cycle. `PlatformWiring` does this on the genuine feedback loops between modules — for example the event-creator → event-intake edge, where created events flow back into intake and would otherwise close a backpressure cycle ([PlatformWiring.java#L129-L132](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java#L129-L132)). It is applied **defensively** — even though nothing blocks today — so the graph stays deadlock-safe if the flag is ever enabled.

**Load-bearing line (signal):** this is the one place the dormant hard path still constrains how the graph is *built*. The `INJECT` cycle-breaker you saw in `c0-02` was an edge-role choice; here is *why* that role exists — it is the structural insurance that the loop cannot become an all-`PUT` deadlock.

### Chunk 4 — The health-monitor wire `{moment: m4-health-wire}`

Soft backpressure has a concrete carrier: `WiringModel.getHealthMonitorWire()`, which returns an `OutputWire<Duration>` ([StandardWiringModel.java#L163-L167](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L163-L167)). The health monitor is itself a wired component: it runs on its own scheduler, and a **heartbeat wire** is soldered into it at the health-monitor period — `1ms` by default — so its check runs on each heartbeat rather than on a thread of its own ([StandardWiringModel.java#L108-L124](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L108-L124), [WiringConfig.java#L29-L39](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/WiringConfig.java#L29-L39)). Each heartbeat fires `checkSystemHealth`, whose returned `Duration` is forwarded automatically onto the scheduler's primary output wire — the same primary-wire mechanism from `c0-01`/`c0-02` ([StandardWiringModel.java#L205-L214](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L205-L214)).

What the check computes ([HealthMonitor.java#L118-L138](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/monitor/HealthMonitor.java#L118-L138)):

- For each watched scheduler (recall: `UNLIMITED_CAPACITY` schedulers are not watched), it tests `unprocessed > capacity`. A scheduler observed healthy has its timer reset; an unhealthy one accrues a duration since its first unhealthy observation.
- It publishes the **longest** such duration across all watched schedulers — the single worst current offender — not a sum and not a per-scheduler list.
- To avoid spamming consumers it re-emits only when that duration changes, or at a fixed `healthyReportThreshold` interval (`1s` by default) while healthy ([HealthMonitor.java#L140-L160](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/monitor/HealthMonitor.java#L140-L160)). The full detection treatment is `b-01`; here it is the *wire* that matters.

The wire is soldered to its consumers in `PlatformWiring` — the event creator, the gossip module, and the execution layer — and there is also a poll accessor, `getUnhealthyDuration()`, used by PCES replay ([PlatformWiring.java#L99-L110](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java#L99-L110), [StandardWiringModel.java#L172-L177](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L172-L177)).

**Load-bearing line (signal):** the health monitor *detects and publishes*; it does not throttle. What each reaction site *does* with the duration — pause event creation, stop gossiping, hold back transactions, defer PCES replay — is the reaction side ([health-monitor-and-backpressure.md](../../architecture/topics/health-monitor-and-backpressure.md)), and it is Cluster B. This lesson's claim ends at the wire.

## Engagement moves

### Moment `m1-capacity`

*Why load-bearing (exposition):* the whole soft/hard story depends on the learner holding "capacity is a per-scheduler number compared against the c0-01 backlog count" — if capacity is mis-modelled as per-wire or as an inherent blocking limit, chunk 2 lands wrong.

**Move A — direct walk with cued check.** *Diagnosis tag:* the learner is fluent on `c0-01`'s on-ramp count and needs only a verify.
- Prompt (verbatim): "From `c0-01`, a monitored scheduler counts a task as unprocessed from arrival until its handler returns. A scheduler is also built with a capacity. What does the framework compare against that capacity to decide a scheduler is over capacity — and is the capacity counted per input wire or per scheduler?"
- `canonical_answer`: It compares the scheduler's unprocessed-task count (the on-ramp count from `c0-01`) against the capacity; capacity is per-scheduler, so all of a scheduler's input wires share one on-ramp counter and the limit is on total backlog across them.
- `alternative_correct_answers`:
  - "The unhandled-task count vs the capacity; per-scheduler — one shared counter for all its inputs."
  - "Backlog (unprocessed tasks) against capacity; the limit is on the whole scheduler, not each wire."
  - "`getUnprocessedTaskCount()` vs `getCapacity()`, and it's per-scheduler."
- `followup` (if the learner says "per input wire"): "Re-check: how many on-ramp counters does one scheduler have, no matter how many input wires feed it?"

**Move B — worked example with self-explanation.** *Diagnosis tag:* the learner is new to capacity configuration or unsure what `UNLIMITED_CAPACITY` is for.
- Walk (exposition): capacity is set with `withUnhandledTaskCapacity` (framework default 1, but consensus schedulers override it via configuration); a scheduler built with `UNLIMITED_CAPACITY` (`-1`) is skipped by the health monitor at registration ([HealthMonitor.java#L92-L97](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/monitor/HealthMonitor.java#L92-L97)). **Load-bearing line:** unlimited capacity makes the scheduler invisible to the monitor.
- Self-explanation prompt (verbatim): "A scheduler built with `UNLIMITED_CAPACITY` is never reported as unhealthy. Given that — as you'll see next — the health signal is the only backpressure that operates in production, what does giving a scheduler unlimited capacity actually opt it out of?"
- `canonical_answer`: It opts the scheduler out of the health signal entirely — with no finite capacity to exceed, the monitor skips it, so its backlog can never contribute to the unhealthy-duration signal and no reaction site will ever throttle on its behalf. Unlimited capacity is opting out of soft backpressure.
- `alternative_correct_answers`:
  - "Out of monitoring — it can never be flagged unhealthy, so it never drives any throttling."
  - "Out of the soft-backpressure loop; its queue can grow without ever raising the signal."
  - "Out of the health check; the monitor never watches it, so it contributes nothing to the duration."
- `followup` (if the learner says "it removes the limit" without the monitoring consequence): "Right that the limit is gone — now say what that does to whether the *health monitor* ever notices this scheduler's backlog."

### Moment `m2-soft-vs-hard`

*Why load-bearing (exposition):* this is the threshold-concept moment. The learner must come away seeing the capacity limit as a *health threshold by default*, with blocking as a flag-gated alternative — not as an inherent stall point. The "a full queue blocks the producer" import is surfaced and corrected here via prediction; the contrasting cases produce the transfer; the worked example is the fallback for novelty.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is strong on backpressure fundamentals and likely holds the confidently-wrong "a full queue blocks the producer" intuition; elicit it before the default-off reveal (hypercorrection).
- `answer_shape`: a behavior-gated-on-condition statement (does it block, *and under what flag*), not a flat "yes, it blocks."
- Framing + prompt (verbatim): "A `PUT`-soldered edge feeds a consumer whose queue is at capacity, and more work keeps arriving. From a bounded-queue system you'd expect the producer to block. Before I show what this framework does in production: what's your gut prediction — does the producer block on that `PUT`, and what would have to be true for your answer to hold?"
- Confidence elicitation (verbatim, optional): "Quick gut number before the reveal — how confident, low to high?"
- `canonical_answer`: In production it does **not** block. Hard backpressure is off by default, so a bounded scheduler gets a non-blocking `StandardObjectCounter` — `put` increments the count and returns, and capacity is only a health threshold. It would block only if hard backpressure were enabled, when the scheduler instead gets a `BackpressureObjectCounter` ([StandardObjectCounter.java#L31-L43](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/counters/StandardObjectCounter.java#L31-L43), [WiringConfig.java#L29-L39](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/WiringConfig.java#L29-L39)).
- `alternative_correct_answers`:
  - "No block in production — blocking happens only *if* hard backpressure is enabled, which it isn't by default."
  - "It would block only under the hard-backpressure flag; with it off (the default), `put` just enqueues and returns."
  - "Doesn't stall normally; the blocking is conditional on `hardBackpressureEnabled` being on."
- `followup` (correct outcome — "it blocks" — but reasoning assumes enforcement): "You named the bounded-queue contract. Now state the exact condition this framework requires before that block happens — and say what it is set to by default."

**Move B — contrasting cases with comparison prompt.** *Diagnosis tag:* threshold concept; transfer is the goal. Uses the three cases in the Contrasting cases material below.
- Prompt (verbatim): "Here are three ways a full queue can behave: (1) a textbook bounded queue blocks the producer until space frees; (2) this framework in production, with hard backpressure off; (3) this framework with hard backpressure on. Across the three, what stays the same about the capacity limit, and what differs about what happens when the backlog crosses it?"
- `canonical_answer`: What stays the same is that capacity is a real threshold the backlog can exceed, and crossing it is a meaningful event in all three. What differs is the consequence: (1) and (3) block the producer at the limit; (2) — the production default — neither blocks nor drops, so the only effect of crossing the limit is that the health monitor starts reporting the scheduler unhealthy and reaction sites respond (soft backpressure). The limit's *meaning* (a threshold) is constant; whether crossing it *blocks* is a deployment flag, and in production the protective response is the health-monitor loop, not the queue.
- `alternative_correct_answers`:
  - "Same: capacity is a threshold everywhere. Different: (1)/(3) block at it, (2) just flags it as unhealthy and lets reaction sites handle it."
  - "The limit is always a threshold; only whether it blocks changes — off-by-default means soft (health-driven), on means hard (blocking)."
  - "All three treat capacity as the line; (2) turns crossing it into a health signal rather than a stall."
- `followup` (if the learner stops at "(2) doesn't block" without naming what does the work): "You've got that (2) doesn't block — now name what *does* react to the backlog in case (2), given the wire stays open."
- *Deep invariant (exposition for consolidation, not read as a question):* the capacity limit is always a health threshold; whether crossing it *also* blocks is the `hardBackpressureEnabled` flag, off in production; the throttling that protects production is the health-monitor reaction loop, not the queue blocking. This is the sibling of `c0-01`'s declarative concurrency and `c0-02`'s declarative handoff — there the scheduler type and the SolderType were wiring-time declarations; here whether *any* of it enforces is a single deployment-time flag.

**Move C — worked example with self-explanation.** *Diagnosis tag:* the learner is new to the counter mechanism and unsure how the same `put` can behave two ways.
- Walk (exposition): with the flag off, a bounded scheduler's on-ramp counter is a `StandardObjectCounter` whose `attemptOnRamp` always returns true and `onRamp` just increments — it never blocks ([StandardObjectCounter.java#L31-L43](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/counters/StandardObjectCounter.java#L31-L43)); with the flag on (and bounded, non-`DIRECT`), it is a `BackpressureObjectCounter` whose `onRamp` blocks via `managedBlock` until the count drops ([BackpressureObjectCounter.java#L62-L141](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/counters/BackpressureObjectCounter.java#L62-L141)). **Load-bearing line:** the same `put` call sits behind the edge either way; the counter object — chosen once at build time from the flag — is what differs.
- Self-explanation prompt (verbatim): "The counter object is chosen once, at scheduler-build time, from the flag, and `put` calls the same on-ramp method in both modes. Given *where* the block-or-don't decision lives, why does a component's handler code never have to know which backpressure mode it is running in?"
- `canonical_answer`: Because the decision lives in the counter the builder installed, not in the handler or the producer — `put` calls the same on-ramp method, and either the `StandardObjectCounter` returns immediately or the `BackpressureObjectCounter` blocks inside it. The blocking behaviour is encapsulated in the assembly-time choice, so handler code is identical in both modes; the mode is a wiring/deployment property, not application logic.
- `alternative_correct_answers`:
  - "The counter encapsulates blocking; the handler just hands off, so the mode is invisible to it."
  - "Block-or-not is decided at build time by which counter is installed, not at call time, so the handler is the same either way."
  - "It's a wiring concern, not application logic — `put` is identical; only the counter behind it changes."
- `followup` (restatement of "the counter handles it" without the encapsulation point): "That's *which* object does it — I'm asking *why that placement* keeps it out of the handler. What would have to be true instead for the handler to need to know the mode?"

### Moment `m3-cycles`

*Why load-bearing (exposition):* this closes `c0-02`'s open thread — *why* a `PUT` cycle is dangerous and why exactly one edge is flipped. It is the one place the dormant hard path still shapes the graph, so the learner should see the deadlock as flag-conditional and the `INJECT` fix as defensive insurance.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is solid on chunk 2's blocked-`PUT`-propagation and can reason a cycle through; surface the deadlock as conditional.
- `answer_shape`: a failure-condition pairing (deadlocks under X, safe under Y), not a flat "it deadlocks."
- Framing + prompt (verbatim): "Two components are wired in a cycle: A's output is a `PUT` edge into B, and B's output is a `PUT` edge back into A. You just saw that under hard backpressure a `PUT` producer blocked on a full consumer can't drain its own queue. Before I show the remedy: under what condition does this cycle deadlock, and under what condition is it completely fine?"
- `canonical_answer`: It deadlocks only when hard backpressure is **on**: if both queues fill, A blocks trying to `PUT` into B while B blocks trying to `PUT` into A — each waits on the other to drain and neither can. With hard backpressure **off** (the production default), neither `PUT` blocks, so the cycle never deadlocks at runtime. The hazard is conditional on the flag.
- `alternative_correct_answers`:
  - "Deadlocks under hard backpressure (both block in a circular wait); harmless with the flag off, since nothing blocks."
  - "Only when blocking is enabled — then it's a classic circular wait; otherwise the `PUT`s just enqueue."
  - "Hazard exists iff `hardBackpressureEnabled` is on; off means no block, no deadlock."
- `followup` (says "it deadlocks" without the condition): "You've got the deadlock shape — now name the one setting that has to be on for it to actually happen, and say what the cycle does when that setting is off."

**Move B — direct walk with cued check.** *Diagnosis tag:* the learner is fluent and needs only a verify on the remedy and the detection.
- Prompt (verbatim): "For the real event-creator → event-intake feedback edge, the consensus layer breaks the cycle even though nothing blocks in production today. What single change breaks it, why is it applied defensively, and what does the model do at assembly if a backpressure cycle is left in?"
- `canonical_answer`: Flip exactly one edge of the cycle to `INJECT` (the event-creator → event-intake edge is soldered `INJECT`); `INJECT` bypasses capacity, so that producer can never block and the loop can never be all-blocking. It's applied defensively so the graph stays deadlock-safe if hard backpressure is ever enabled. At assembly, `checkForCyclicalBackpressure` (via `CycleFinder`) detects the cycle and logs an error naming the path ([PlatformWiring.java#L129-L132](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java#L129-L132), [CycleFinder.java#L98-L122](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/analysis/CycleFinder.java#L98-L122)).
- `alternative_correct_answers`:
  - "One edge → `INJECT` so it can't block; done defensively for if the flag turns on; the model logs an error for any remaining cycle at start."
  - "Set a single cycle edge to `INJECT` (bypasses capacity); insurance against a future flag flip; `checkForCyclicalBackpressure` errors on leftover cycles."
  - "Flip one edge to `INJECT`; even though nothing blocks now, it keeps the loop deadlock-safe; assembly-time cycle detection logs an error."
- `followup` (names `INJECT` but not why one edge suffices): "Why is flipping *one* edge enough — what does that single non-blocking edge guarantee about the whole loop?"

### Moment `m4-health-wire`

*Why load-bearing (exposition):* this is where soft backpressure becomes concrete, and the moment most prone to the "periodically" and "the monitor throttles" errors. The learner should leave seeing the wire as carrying a heartbeat-driven duration, with the monitor as a detector whose consumers' behaviour is Cluster B.

**Move A — worked example with self-explanation.** *Diagnosis tag:* the learner is new to the health wire or imagines the monitor as a self-polling thread.
- Walk (exposition): `getHealthMonitorWire()` returns an `OutputWire<Duration>`; the health monitor is a wired component whose `checkSystemHealth` runs because a heartbeat wire is soldered into it at the health-monitor period (`1ms` default), and its returned `Duration` is forwarded onto its scheduler's primary output wire — the `c0-01`/`c0-02` primary-wire mechanism ([StandardWiringModel.java#L108-L124](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L108-L124), [WiringConfig.java#L29-L39](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/WiringConfig.java#L29-L39)). **Load-bearing line:** the monitor has no thread of its own polling — a heartbeat drives it.
- Self-explanation prompt (verbatim): "The health monitor's check runs because a heartbeat wire is soldered into it at a 1 ms period — it's a wired component, not a thread polling on its own. Using only how a scheduler's primary output wire is fed, what is actually flowing on `getHealthMonitorWire()`, and what makes a new value appear on it?"
- `canonical_answer`: What flows is the `Duration` that `checkSystemHealth` returns — the longest time any single watched scheduler has been continuously over capacity — forwarded automatically onto the health-monitor scheduler's primary output wire. A new value appears each time the 1 ms heartbeat fires the check and it returns a (changed) duration; the heartbeat drives it, not the monitor waking itself.
- `alternative_correct_answers`:
  - "The longest unhealthy `Duration`, pushed onto the primary output wire when the heartbeat fires the check."
  - "A `Duration` (worst scheduler's continuous unhealthy time); it updates on each 1 ms heartbeat-triggered check."
  - "The handler's returned duration on the primary wire; the heartbeat is what makes the check run and forward a value."
- `followup` (says "the unhealthy duration" without the heartbeat mechanism): "Right value — now say what makes the check run and a fresh value get forwarded, given the monitor has no thread of its own polling."

**Move B — direct walk with cued check.** *Diagnosis tag:* the learner is fluent and needs the detector/enforcer and altitude boundary verified.
- Prompt (verbatim): "The health-monitor wire is soldered to the event creator, the gossip module, and the execution layer. Does the health monitor itself slow any of those components down — and where in the curriculum do you find what each one actually does with the duration it receives?"
- `canonical_answer`: No — the health monitor is a detector, not an enforcer: it only publishes the `Duration` on its wire. The reaction sites (event creator, gossip, execution; PCES via the poll accessor) each decide what to do with it, and that behaviour is Cluster B, not this lesson ([glossary: Health monitor](../../glossary.md#health-monitor), [PlatformWiring.java#L99-L110](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java#L99-L110)).
- `alternative_correct_answers`:
  - "No; it just publishes the signal. What the consumers do with it is Cluster B."
  - "The monitor only detects and reports; reaction sites throttle, and that's covered later (Cluster B)."
  - "It doesn't throttle anything — it's a detector; the reactions live in the consumers, taught in Cluster B."
- `followup` (says "no" without naming where the behaviour is taught): "Good — now place it: which cluster covers what the event creator and gossip actually *do* when the duration climbs?"

## Contrasting cases material

The threshold concept is *soft backpressure*: in production a full queue does not block its producer, and the throttling that protects the system comes from the health monitor reacting to backlog. The three cases vary what a full queue does; comparing them isolates what is constant (capacity is a threshold) from what is a deployment choice (whether crossing it blocks).

- **Case 1 — Textbook bounded blocking queue.** A producer that writes to a full bounded queue blocks until space frees. *Surface:* synchronous, immediate, local to the two endpoints — the backpressure *is* the block.
- **Case 2 — This framework, production default (hard backpressure off).** The bounded scheduler gets a `StandardObjectCounter`; `put` never blocks and `offer` never drops, so the queue simply grows ([StandardObjectCounter.java#L31-L43](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/counters/StandardObjectCounter.java#L31-L43)). *Surface:* crossing capacity has no local effect at all; the only consequence is that the health monitor begins reporting the scheduler unhealthy, and reaction sites elsewhere throttle ([WiringConfig.java#L29-L39](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/WiringConfig.java#L29-L39)).
- **Case 3 — This framework, hard backpressure on.** A bounded, non-`DIRECT` scheduler gets a `BackpressureObjectCounter`; `PUT` blocks at capacity, `OFFER` drops, `INJECT` bypasses, and a blocked `PUT` propagates upstream ([BackpressureObjectCounter.java#L62-L141](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/counters/BackpressureObjectCounter.java#L62-L141)). *Surface:* synchronous and local again, like Case 1.

**The deep invariant that survives the surface differences:** capacity is always a health threshold; whether crossing it *also* blocks is the `hardBackpressureEnabled` flag, off in production; the throttling that protects production is the health-monitor reaction loop, not the queue. *(This is the sibling of `c0-01`'s declarative concurrency and `c0-02`'s declarative handoff — concurrency and handoff were declared at wiring time; whether enforcement happens at all is a deployment-time flag.)*

## Completion problems

**Problem 1** *(small step blanked).*
- Statement (verbatim): "Production config: `platform.wiring.hardBackpressureEnabled` is at its default. A bounded scheduler's queue is full and more work arrives on a `PUT` edge. Fill in the blank: the producer's `put` ____ (blocks / returns immediately), because the scheduler was built with a ____ counter."
- Hint ladder:
  - Rung 1 (verbatim): "Open `AbstractTaskSchedulerBuilder.java` and find the if/else that picks the counter; read the first condition."
  - Rung 2 (verbatim): "With `hardBackpressureEnabled` at its default (`false`), which branch runs, and what does that counter's `onRamp`/`attemptOnRamp` do?"
  - Rung 3 (verbatim): "The flag defaults to false, so the model isn't backpressure-enabled and the scheduler gets a `StandardObjectCounter`, whose `onRamp` just increments — `put` returns immediately."
  - Rung 4 (verbatim, gated on effort): "`put` returns immediately; the scheduler was built with a `StandardObjectCounter`. With the flag off there is no enforcement — the queue just grows and the limit acts only as a health threshold."
- `canonical_answer`: `put` returns immediately; the scheduler was built with a `StandardObjectCounter`. The default-off flag means the limit is not enforced — the queue grows and capacity serves only as a health threshold ([AbstractTaskSchedulerBuilder.java#L345-L355](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/internal/AbstractTaskSchedulerBuilder.java#L345-L355), [StandardObjectCounter.java#L31-L43](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/counters/StandardObjectCounter.java#L31-L43)).
- `alternative_correct_answers`:
  - "Returns immediately; `StandardObjectCounter` (non-blocking)."
  - "Doesn't block — the standard (non-blocking) counter is installed because the flag is off."
- *Invariant exercised (exposition):* with hard backpressure off, capacity is a health threshold, not a block point.
- `followup` (if the learner says "blocks" assuming enforcement): "Check the flag's default — which counter does the builder install when hard backpressure is off, and does its `onRamp` ever block?"

**Problem 2** *(more blanked — from a scenario).*
- Statement (verbatim): "A scheduler is built with `UNLIMITED_CAPACITY`, and under load its queue grows without bound. Answer three things: (a) is it ever reported unhealthy; (b) why or why not; (c) what does that mean for soft backpressure on this scheduler?"
- Hint ladder:
  - Rung 1 (verbatim): "Read the `HealthMonitor` constructor in `HealthMonitor.java` — the loop that decides which schedulers it watches."
  - Rung 2 (verbatim): "What does the constructor do with a scheduler whose `getCapacity()` equals `UNLIMITED_CAPACITY`?"
  - Rung 3 (verbatim): "The constructor adds a scheduler to its watch list only if its capacity isn't `UNLIMITED_CAPACITY`, so an unlimited scheduler is skipped and never compared."
  - Rung 4 (verbatim, gated on effort): "(a) No; (b) the `HealthMonitor` constructor skips any scheduler whose capacity is `UNLIMITED_CAPACITY`, so it is never watched or compared; (c) it is opted out of soft backpressure entirely — its backlog never raises the unhealthy-duration signal, so no reaction site throttles for it."
- `canonical_answer`: (a) No. (b) The `HealthMonitor` constructor only watches schedulers whose capacity is not `UNLIMITED_CAPACITY`, so an unlimited scheduler is skipped at registration and never compared. (c) It is opted out of soft backpressure entirely — its growing queue never contributes to the unhealthy-duration signal, so no reaction site will ever throttle on its behalf ([HealthMonitor.java#L92-L97](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/monitor/HealthMonitor.java#L92-L97)).
- `alternative_correct_answers`:
  - "No; the monitor skips unlimited-capacity schedulers; so it never contributes to the signal and never triggers throttling."
  - "Never unhealthy — unwatched by construction — so it's outside the soft-backpressure loop."
- *Invariant exercised (exposition):* unlimited capacity removes a scheduler from the health signal, hence from soft backpressure.
- `followup` (if the learner gives (a) and (b) but not the soft-backpressure consequence): "You have that it's never flagged — now say what that means for whether anything ever throttles because of *this* scheduler's backlog."

**Problem 3** *(most faded — produce the full answer from a scenario).*
- Statement (verbatim): "You're wiring a feedback cycle, component A → B → A, and you want it safe if hard backpressure is ever enabled. Specify four things: (a) what goes wrong if all edges are `PUT` and the flag is on; (b) the minimal change to make it safe; (c) which `InputWire` method that change uses; (d) how the framework would flag an all-`PUT` cycle at assembly."
- Hint ladder:
  - Rung 1 (verbatim): "Re-read the cycle section of `wiring-framework.md`, and look at the event-creator → event-intake solder in `PlatformWiring.java`."
  - Rung 2 (verbatim): "Which `SolderType` breaks the cycle and which `InputWire` method does it map to — and what does `checkForCyclicalBackpressure` / `CycleFinder` do when it finds a cycle?"
  - Rung 3 (verbatim): "Flipping one edge to `INJECT` (mapped to `InputWire.inject`, accepted over capacity) makes the loop non-blocking; under the flag, an all-`PUT` cycle is a circular wait; `checkForCyclicalBackpressure` logs an error at start naming the cycle."
  - Rung 4 (verbatim, gated on effort): "(a) under the flag both queues fill and A blocks `PUT`ting into B while B blocks `PUT`ting into A — a circular wait, deadlock; (b) flip exactly one cycle edge to `INJECT`; (c) `INJECT` maps to `InputWire.inject`, accepted even over capacity; (d) `checkForCyclicalBackpressure` (via `CycleFinder`) detects the cycle at assembly and logs an error naming the path."
- `canonical_answer`: (a) Under hard backpressure, both queues fill and A blocks trying to `PUT` into B while B blocks trying to `PUT` into A — a circular wait that deadlocks; (b) flip exactly one cycle edge to `INJECT`; (c) `INJECT` maps to `InputWire.inject`, accepted even when over capacity; (d) at start, `checkForCyclicalBackpressure` (via `CycleFinder`) detects the cycle and logs an error naming the cycle path ([PlatformWiring.java#L129-L132](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java#L129-L132), [InputWire.java#L86-L95](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java#L86-L95), [CycleFinder.java#L98-L122](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/analysis/CycleFinder.java#L98-L122)).
- `alternative_correct_answers`:
  - "(a) circular wait/deadlock under the flag; (b) one edge → `INJECT`; (c) `inject`; (d) assembly-time cycle detection logs an error."
  - "(a) both block waiting on each other; (b) make one edge `INJECT`; (c) `InputWire.inject`; (d) `checkForCyclicalBackpressure` errors at start."
- *Invariant exercised (exposition):* one `INJECT` edge makes a cycle non-blocking and therefore deadlock-safe even under hard backpressure.
- `followup` (if the learner gives (a)–(c) but not the assembly-time detection): "You have the fix — now say what the model itself does at start if someone leaves the cycle all-`PUT`."

## Delta callout

`[TBD: delta-map/wiring-framework.md is not yet authored — the delta-map/ directory currently holds only README.md, so there is no current-versus-proposed entry for backpressure modes or the health-monitor wire to summarize.]` Status: **not started**. When that delta lands it will be linked here as `../../delta-map/wiring-framework.md`. Separately, the topic file's forward note describes a planned **module-API-level** backpressure that will sit *above* wire-level backpressure at the Consensus/Execution boundary — a `nextRound` pull throttling Consensus end to end ([consensus-execution-boundary.md](../../architecture/interfaces/consensus-execution-boundary.md)); that composes with, rather than replaces, the soft/hard wire-level mechanism this lesson covers, and it is followed in Cluster B and the Pass 3 scenarios. *(Exposition; no learner-facing prompt.)*

## Transfer prompt

Forward framing (motivational only): the next lessons go two directions from here — `c0-04` covers how the whole graph is assembled and validated (where `checkForCyclicalBackpressure` actually runs), and Cluster B covers what each reaction site does with the health signal. The question below is answerable now, from how the monitor computes and reports the duration.

- Prompt (verbatim): "The health monitor reports the *longest continuous* unhealthy duration across all watched schedulers, and resets a scheduler's timer the moment it is observed healthy again. Suppose scheduler X is unhealthy for 200 ms, then healthy for one heartbeat, then unhealthy again; meanwhile scheduler Y is continuously unhealthy for 500 ms the whole time. Using only that rule, what duration is on the wire at the 500 ms mark, and what has happened to X's earlier 200 ms?"
- `canonical_answer`: At the 500 ms mark the wire reports about 500 ms — Y's continuous duration, because the signal is the single worst *continuously* unhealthy scheduler. X's earlier 200 ms is discarded: the one healthy observation reset X's timer, so X's second unhealthy spell is timed from its restart, not added to the first. The signal is "worst current offender's continuous time," not a sum and not a history ([HealthMonitor.java#L118-L138](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/monitor/HealthMonitor.java#L118-L138)).
- `alternative_correct_answers`:
  - "~500 ms (Y's); X's 200 ms was wiped when it went healthy, so X is timed afresh from its restart."
  - "Y's 500 ms is on the wire; the brief healthy blip reset X, discarding its first spell."
  - "500 ms — the longest continuous one; X's earlier time doesn't accumulate because the healthy moment cleared its timer."
- `followup` (if the learner gives 500 ms but not X's reset): "Right on the wire value — now say what happened to X's first 200 ms, and why one healthy heartbeat matters."

## Close-out retrieval

- Free-recall summary (verbatim): "In your own words: in production, what actually happens when a scheduler's queue exceeds its capacity, and why doesn't the producer just block?"
  - `canonical_answer`: In production hard backpressure is off, so the scheduler's `StandardObjectCounter` never blocks — `put` returns immediately and the queue grows. Exceeding capacity instead makes the scheduler unhealthy; the health monitor, driven by a 1 ms heartbeat, publishes the longest continuous unhealthy duration on its wire, and reaction sites throttle in response. That soft detect-and-react loop is the backpressure that operates. The producer doesn't block because blocking is the hard-backpressure path, disabled by default.
  - `alternative_correct_answers`:
    - "Nothing blocks — the queue grows, the monitor flags it unhealthy, and reaction sites throttle; blocking is the off-by-default hard path."
    - "The limit is just a health threshold in production; crossing it raises the unhealthy-duration signal, which reaction sites act on, rather than stalling the producer."
    - "`put` returns (non-blocking counter); soft backpressure via the health monitor handles the backlog, because hard backpressure is off."
- Successive-relearning tags (exposition; added to the learner's relearning queue): threshold concept *soft backpressure* —
  - Day 1: recall that `hardBackpressureEnabled` is off by default and `put` does not block in production; name the two counters (`StandardObjectCounter` / `BackpressureObjectCounter`).
  - Day 3: apply it — given a config (flag on/off, bounded vs `UNLIMITED_CAPACITY`, direct vs non-direct), predict whether a full queue blocks, drops, is merely flagged, or is unmonitored.
  - ~2 weeks: state the invariant — capacity is always a health threshold; whether crossing it blocks is a deployment flag; production throttling is the health-monitor reaction loop, the sibling of declarative concurrency and declarative handoff.

## Open questions

- **`[TBD]` Delta callout.** `delta-map/wiring-framework.md` does not yet exist (the `delta-map/` directory holds only `README.md`), so there is no current-versus-proposed difference for backpressure modes or the health-monitor wire to summarize. Deferred until the wiring-framework delta-map entry is authored; the callout links the file once it lands.
