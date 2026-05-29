---
id: c0-syn-wiring-synthesis
cluster: c0
title: "Synthesis: assembling and validating a component graph"
pass: 2
prerequisites:
  - c0-01-schedulers-and-types
  - c0-02-wires-and-soldering
  - c0-03-backpressure-and-queue-health
  - c0-04-wiring-model-lifecycle
kb_topics_touched:
  - architecture/topics/wiring-framework.md
kb_concepts: []
kb_glossary_terms:
  - Scheduler
  - Wire
  - Soldering
  - Transformer
  - Squelching
  - Backpressure
  - Health monitor
kb_invariants: []
kb_deltas:
  - delta-map/wiring-framework.md
kb_decisions: []
learning_objectives:
  - Trace a single component graph end to end on one shared WiringModel — construct-solder-bind registers each scheduler as a vertex and each solderTo as an edge carrying its SolderType, start() freezes further growth, and the model then validates and runs exactly what was assembled.
  - Explain how the four Cluster 0 pieces interlock in that trace — a scheduler's TaskSchedulerType (c0-01) decides what start() actually starts, an edge's SolderType (c0-02) is what the cycle check reads and what one INJECT edge fixes, per-scheduler capacity and the health-monitor wire (c0-03) govern run-time behaviour, and the WiringModel (c0-04) owns and validates all of it.
  - State that the three start()-time checks — cyclic backpressure, illegal DIRECT usage, unbound input wires — run once over the frozen graph, only log errors (their return values discarded), and never abort startup, so a graph defect surfaces as a failing platform test rather than a refusal to start.
  - Distinguish the two runtime regimes the assembled graph can run under — soft backpressure by default (capacity is a health threshold; the health monitor publishes an unhealthy-duration signal) versus hard backpressure when the flag is on (PUT blocks at capacity and propagates upstream) — and identify that production runs soft.
  - Describe taking the graph down — start() and stop() bracket the run, stop() halts the heartbeat and SEQUENTIAL_THREAD schedulers without draining queued work, and a clean drain is the separate per-component squelch + flush() path from c0-01.
threshold_concepts: []
estimated_session_minutes: 30
status: drafted
last_verified_against: eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2
---

# Synthesis: assembling and validating a component graph

## Prerequisites

- **`c0-01-schedulers-and-types`** — A task scheduler owns a queue, a threading policy (its `TaskSchedulerType`), and a primary output wire; a component is mounted construct-solder-bind with binding deferred. A monitored scheduler keeps an on-ramp (unhandled-task) count that measures backlog, and squelching + `flush()` are how a scheduler is *drained*.
- **`c0-02-wires-and-soldering`** — `solderTo` joins an output wire to one or more input wires and *records the edge, with its `SolderType` (`PUT`/`INJECT`/`OFFER`), on the model*; that recorded type is what graph validation reads. With hard backpressure off, the three handoffs are logically equivalent at runtime.
- **`c0-03-backpressure-and-queue-health`** — Capacity is a per-scheduler health threshold; `platform.wiring.hardBackpressureEnabled` is off by default, so production backpressure is *soft* — the health monitor publishes an unhealthy duration rather than wires blocking. An all-`PUT` cycle is a deadlock hazard only under hard backpressure; the model detects cycles at start and the defensive remedy is one `INJECT` edge.
- **`c0-04-wiring-model-lifecycle`** — One `WiringModel` owns every scheduler and wire; `start()` freezes growth, runs three static checks that only *log*, then starts the heartbeat and dedicated-thread schedulers; `stop()` halts those threads without draining; the lifecycle is one-shot.

This is the cluster's synthesis: it puts all four to work on a single small graph, from assembly through validation, a run under load, and shutdown. If the learner hedges on **the three checks only log** (`c0-04`) or on **soft versus hard backpressure** (`c0-03`), those are the two worth a bounded recall probe before starting — the whole trace turns on them.

## Incoming retrieval probes

This section is an authorial signal, not a session-open quiz. The tutor watches for these concepts at the entry self-assessment and consolidates them when they resurface in the trace; it does not open with a recall drill. Each entry names the concept, the prior lesson, and the one-line statement to consolidate against.

- **Construct-solder-bind registers onto one model** (`c0-01`/`c0-02`/`c0-04`) — *Canonical:* constructing a `ComponentWiring` registers a scheduler vertex, `solderTo` records an edge with its `SolderType`, and obtaining/binding an input wire records its creation and binding — all on the single shared model. Resurfaces in chunk 1 as the three components are assembled.
- **The recorded edge and its `SolderType`** (`c0-02`) — *Canonical:* `registerEdge` records the edge and marks it blocking exactly when its `SolderType` is `PUT`. Resurfaces in chunk 2, because that blocking flag is what the cycle check walks.
- **The three checks only log; `start()` discards their results** (`c0-04`) — *Canonical:* `start()` runs the three checks over the frozen graph, ignores the booleans they return, and the checks only log at ERROR — so a bad graph still starts and the gate is a failing platform test in CI. The lesson's center of gravity in chunk 2.
- **Soft backpressure + the one-`INJECT` cycle remedy** (`c0-03`) — *Canonical:* with hard backpressure off, a full queue does not block its producer and capacity is only a health threshold; an all-`PUT` cycle deadlocks only when the flag is on, and flipping one cycle edge to `INJECT` keeps it deadlock-safe. Resurfaces across chunks 2 and 3.
- **`stop()` is not a drain** (`c0-04`/`c0-01`) — *Canonical:* `stop()` halts the heartbeat and `SEQUENTIAL_THREAD` schedulers and returns; draining a backlog is the separate per-component squelch + `flush()` path. Resurfaces in chunk 4.
- *(Threshold concepts from earlier in the cluster — declarative concurrency (`c0-01`), declarative handoff (`c0-02`), soft backpressure (`c0-03`) — plausibly have a successive-relearning interval falling in this session. This lesson is where all three operate together on one graph; watch for them and consolidate in line rather than scheduling new tags.)*

## Misconception watchlist

- **A failing check aborts startup.** *(Import from schema validators and DI containers that throw on bad configuration.)* Sounds like: "so if I ship the graph with a cycle, the node refuses to boot?" Correction, in line: no — the three checks only *log* at ERROR and `start()` discards the booleans they return, so the node starts with the defect present; the interface javadoc says each logged message "will fail standard platform tests," so the gate is CI, not a runtime refusal ([StandardWiringModel.java#L218-L222](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L218-L222), [WiringModel.java#L34-L66](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModel.java#L34-L66)).
- **A full queue blocks its producer in production.** *(Import from bounded-queue systems where a full queue always stalls the writer.)* Sounds like: "once Worker's queue fills, Source stalls on the `PUT`, right?" Correction: not in production — `hardBackpressureEnabled` is **off by default**, so `put` does not block and capacity is only a health threshold ([WiringConfig.java#L29-L39](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/WiringConfig.java#L29-L39)). The operative throttle is *soft*: the health monitor observing the backlog, with reaction sites in Cluster B responding.
- **`stop()` drains the queues.** *(Import from `ExecutorService.shutdown()` + `awaitTermination`.)* Sounds like: "`stop()` lets the queued tasks finish first." Correction: `stop()` stops the heartbeat and the `SEQUENTIAL_THREAD` schedulers and returns; it does not wait for in-flight or queued work ([StandardWiringModel.java#L236-L251](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L236-L251)). Draining is the separate squelch + `flush()` path from `c0-01`, applied per component by the caller.
- **Freezing growth at `start()` makes the running graph fully immutable.** *(Over-generalization from "start() locks the model.")* Sounds like: "after `start()` nothing about the graph can change." Correction: only *growth* is enforced — every `register*` method throws once started, so no new scheduler, edge, or wire ([TraceableWiringModel.java#L278-L282](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java#L278-L282)). Data keeps flowing along the existing wires, and there is no API to unsolder; the rest of the "immutability" is convention, not enforcement ([wiring-framework.md](../../architecture/topics/wiring-framework.md)).

## Mechanism

This lesson does not introduce a new primitive. It takes the four pieces the cluster built — schedulers and their types (`c0-01`), wires and `SolderType`s (`c0-02`), capacity and the health wire (`c0-03`), and the model's assembly/validation/lifecycle (`c0-04`) — and runs one small graph through its whole life so the pieces are seen *collaborating* rather than described in turn.

**Pre-training — the pieces, by link** (definitions live in the glossary, the topic file, and the prior lessons; named here only to set the trace's vocabulary):

- **`WiringModel`** — the single object that owns every scheduler and wire, validates the assembled graph, and drives the lifecycle (`c0-04`; [glossary: Scheduler](../../glossary.md#scheduler)).
- **Scheduler + `TaskSchedulerType`** — a queue, a threading policy, and a primary output wire; the type decides concurrency and, at start, whether the scheduler needs a thread started (`c0-01`; [glossary: Scheduler](../../glossary.md#scheduler)).
- **Wire + `SolderType`** — a typed connection recorded on the model with `PUT`/`INJECT`/`OFFER` (`c0-02`; [glossary: Wire](../../glossary.md#wire), [Soldering](../../glossary.md#soldering)).
- **Capacity + health-monitor wire** — a per-scheduler health threshold and the `OutputWire<Duration>` the model publishes backlog on (`c0-03`; [glossary: Backpressure](../../glossary.md#backpressure), [Health monitor](../../glossary.md#health-monitor)).
- **Drain** — squelching + `flush()`, the per-component way to empty a backlog; contrasted with `stop()` in chunk 4 (`c0-01`; [glossary: Squelching](../../glossary.md#squelching)).

Our running graph is three components on **one** shared model: **`Source`** (a `SEQUENTIAL` scheduler), **`Worker`** (a `SEQUENTIAL_THREAD` scheduler), and **`Sink`** (a `DIRECT` scheduler). They are soldered into a loop — `Source` → `Worker` → `Worker` → `Sink`, and a feedback edge `Sink` → `Source` — every edge a default `PUT` for now. It is deliberately small, but it exercises all four lessons: a dedicated-thread scheduler, a direct scheduler, and a feedback cycle.

### Chunk 1 — Assemble: construct-solder-bind onto one model, then `start()` freezes it `{moment: m1-assemble-freeze}`

There is exactly one `WiringModel` for the process, built once in `PlatformBuilder` and its lifecycle driven from `PlatformCoordinator.start()` / `stop()` ([PlatformBuilder.java#L541-L545](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java#L541-L545), [PlatformCoordinator.java#L165-L177](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformCoordinator.java#L165-L177)). `Source`, `Worker`, and `Sink` are all built on that one model — the construct-solder-bind pattern from `c0-01`/`c0-02`.

Each assembly step registers something on the model. Constructing each component registers its scheduler as a vertex (`registerScheduler` / `registerVertex`); each `solderTo` records an edge and marks it blocking exactly when its `SolderType` is `PUT` (`registerEdge`); obtaining and binding each input wire records its creation and its binding (`registerInputWireCreation` / `registerInputWireBinding`) ([TraceableWiringModel.java#L166-L229](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java#L166-L229), [TraceableWiringModel.java#L238-L273](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java#L238-L273)). After assembly the model holds the complete topology of our graph: its vertices, edges, created input wires, and bound input wires ([TraceableWiringModel.java#L42-L67](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java#L42-L67)).

Every one of those `register*` methods begins with `throwIfStarted()`, which throws `IllegalStateException` once `start()` has run ([TraceableWiringModel.java#L278-L282](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java#L278-L282)). So our three-component graph is grown entirely *before* `start()` and cannot grow after.

**Load-bearing line (signal):** the instant `start()` runs, the topology of `Source`/`Worker`/`Sink` is complete and frozen on the growth side. That is the precondition that makes the next chunk's one-time static analysis well-defined — the graph cannot change underneath the checks.

### Chunk 2 — Validate: `start()` runs three checks over the frozen graph, logs, and does not abort `{moment: m2-validate}`

When `PlatformCoordinator` calls `model.start()`, the model marks itself started, then runs three checks in a fixed order — `checkForCyclicalBackpressure()`, `checkForIllegalDirectSchedulerUsage()`, `checkForUnboundInputWires()` — and only then starts the threads ([StandardWiringModel.java#L204-L231](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L204-L231)). Walk each over our graph:

- **`checkForCyclicalBackpressure`** *(from `c0-03`)* — walks the vertices and finds the `Source` → `Worker` → `Sink` → `Source` loop. Because every edge is `PUT`, every edge is blocking, so the loop is a backpressure cycle; the check logs an ERROR naming the path ([TraceableWiringModel.java#L100-L103](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java#L100-L103), [CycleFinder.java#L92-L122](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/analysis/CycleFinder.java#L92-L122)). The remedy from `c0-03`: flip exactly one edge — say `Sink` → `Source` — to `INJECT`, which `registerEdge` does not mark blocking, breaking the cycle.
- **`checkForIllegalDirectSchedulerUsage`** *(the `DIRECT` rule from `c0-01`)* — `Sink` is `DIRECT`, called into by `Worker`, a single `SEQUENTIAL_THREAD` scheduler, so it is **legal**. It would be flagged if a *second* sequential scheduler also soldered into `Sink`, or if a `CONCURRENT` scheduler did ([TraceableWiringModel.java#L108-L111](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java#L108-L111), [DirectSchedulerChecks.java#L28-L118](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/analysis/DirectSchedulerChecks.java#L28-L118)).
- **`checkForUnboundInputWires`** — compares the *created* input wires against the *bound* ones; if `Worker`'s input wire was obtained via `getInputWire` but its component was never `bind()`-ed, the counts differ and the check logs an ERROR ([TraceableWiringModel.java#L116-L119](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java#L116-L119), [InputWireChecks.java#L28-L52](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/analysis/InputWireChecks.java#L28-L52)).

**Load-bearing line — the structural correction (signal):** each check only *logs* — at ERROR when it finds a problem — and `start()` ignores the booleans they return (the code comment: "We don't have to do anything with the output of these sanity checks. The methods below will log errors if they find problems") ([StandardWiringModel.java#L218-L222](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L218-L222)). Our graph with a cycle and an unbound wire **still starts**. The interface javadoc says each logged message "will fail standard platform tests" ([WiringModel.java#L34-L66](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModel.java#L34-L66)) — the gate is CI, not the runtime.

### Chunk 3 — Run: the assembled graph under load, soft versus hard `{moment: m3-run}`

With the checks done, `start()` starts the heartbeat scheduler and each `SEQUENTIAL_THREAD` scheduler — for our graph that is `Worker`. `Source` (`SEQUENTIAL`) runs on the shared fork-join pool and `Sink` (`DIRECT`) runs inline, so neither needs starting ([StandardWiringModel.java#L224-L231](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L224-L231), [StandardWiringModel.java#L182-L188](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L182-L188)). Now work flows, and the `c0-03` regime decides what backlog does.

In production, `hardBackpressureEnabled` is **off** ([WiringConfig.java#L29-L39](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/WiringConfig.java#L29-L39)). So if `Worker`'s on-ramp count climbs past its capacity, nothing blocks `Source` from handing it more work — capacity is only a health threshold. The model's health-monitor wire (`getHealthMonitorWire()`, an `OutputWire<Duration>`) carries the longest continuous unhealthy duration, and reaction sites elsewhere — Cluster B — decide what to do with it ([StandardWiringModel.java#L163-L167](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L163-L167), [wiring-framework.md](../../architecture/topics/wiring-framework.md)). That is *soft* backpressure.

If an operator turned the flag on, the picture changes: `PUT` would block `Worker`'s producer at capacity, and a blocked producer cannot off-ramp its own task, so backpressure would propagate upstream ([wiring-framework.md](../../architecture/topics/wiring-framework.md)). Our `Source` → `Worker` → `Sink` → `Source` loop would then be a live deadlock hazard — except that we already flipped `Sink` → `Source` to `INJECT` in chunk 2, which bypasses capacity and breaks the cycle.

**Load-bearing line (signal):** the cycle warning and the deadlock safety are two views of the *same* edge choice. The single `INJECT` we added to clear a start-time check is also exactly the deadlock-safety the system would need if the flag were ever flipped — which is why the framework's convention is to apply it defensively even though hard backpressure is off today.

### Chunk 4 — Take down: `stop()` halts threads, drain is a separate call

`PlatformCoordinator.stop()` calls `model.stop()`, which stops the heartbeat scheduler and the `SEQUENTIAL_THREAD` schedulers — `Worker` — releases the JVM anchor, and returns. It does **not** wait for in-flight or queued tasks ([StandardWiringModel.java#L236-L251](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L236-L251), [PlatformCoordinator.java#L165-L177](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformCoordinator.java#L165-L177)). If `Worker` still has queued work that must finish first — at reconnect or freeze, say — that is the separate squelch + `flush()` drain from `c0-01`, applied to the component deliberately by the caller before stopping. `start()` and `stop()` bracket the run; neither one drains.

This chunk is delivered as exposition — it closes the lifecycle the trace has been walking and carries no engagement moment of its own.

## Engagement moves

### Moment `m1-assemble-freeze`

*Why load-bearing (exposition):* the synthesis rests on the learner seeing that the familiar construct-solder-bind steps, applied to a concrete three-component graph, leave the model holding the whole topology and that `start()` freezes it. If that lands, the one-time validation in chunk 2 is obviously possible; if not, validation reads as magic. This is a recap moment — keep the moves light.

**Move A — free recall.** *Diagnosis tag:* a mid-trace retrieval cycle to make the "frozen topology" fact explicit before validation; the learner has shown the assembly recap landed.
- Prompt (verbatim): "We just built `Source`, `Worker`, and `Sink` on one shared model and soldered them into a loop. In your own words: by the time someone calls `start()`, what does the model hold about these three components, and what can no longer be added?"
- `canonical_answer`: The model holds the complete topology — each of the three schedulers as a vertex, every soldered edge with its `SolderType`, and which input wires were created and which were bound. After `start()` nothing more can be added: every `register*` method begins with `throwIfStarted()`, so no new scheduler, edge, input wire, or binding.
- `alternative_correct_answers`:
  - "The full graph of the three — vertices, edges with their solder types, and input-wire bindings — and after `start()` the topology can't grow, because the register methods throw."
  - "Every scheduler and connection is registered on the one model; once started, `throwIfStarted` rejects any new vertex or edge, so growth is closed."
  - "All three components' wiring, frozen at `start()` on the growth side; you can't solder or register anything new afterward."
- `followup` (if the learner lists the components but not the freeze): "You've named what it holds — now say what changes at `start()`: can you solder a fourth edge after it, and what enforces that?"

**Move B — direct walk with cued check.** *Diagnosis tag:* the learner is fluent on `c0-01`/`c0-02` assembly and you only need to connect the recorded `SolderType` forward to validation.
- Prompt (verbatim): "For our loop `Source` → `Worker` → `Sink` → `Source`, each `solderTo` recorded an edge on the model. What property of each edge did it record, and which property will the cycle check read in the next step?"
- `canonical_answer`: Each `solderTo` recorded the edge together with its `SolderType`, and `registerEdge` marks the edge *blocking* exactly when the `SolderType` is `PUT`. The cycle check reads that blocking flag — an all-`PUT` loop is a loop of blocking edges, which is what it flags.
- `alternative_correct_answers`:
  - "It recorded the edge and its solder type; PUT edges are marked blocking, and the cycle check looks for a cycle of blocking edges."
  - "The `SolderType` per edge — and since all ours are `PUT`, all are blocking, which is the cycle the check finds."
- `followup` (if the learner says "the edge" without the blocking/`SolderType` link): "Which `SolderType` makes an edge count as blocking, and why does that matter to a *backpressure* cycle check specifically?"

### Moment `m2-validate`

*Why load-bearing (exposition):* this is the lesson's center of gravity — the three checks run on the concrete graph, and the strong, natural assumption that a bad graph stops the node must be corrected. The fact is fresh from `c0-04`, so offer a prediction (the learner can reason it from `c0-03`'s cycle check and `c0-04`'s logs-not-aborts) with a direct walk as the lighter fallback.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is solid on the pieces and likely holds the confident "a broken graph won't start" intuition; elicit it before the reveal.
- `answer_shape`: a does-it-start + which-checks-fire + where-it-is-caught statement (three linked parts), not a flat yes/no.
- Framing + prompt (verbatim): "Here is our graph as a careless developer left it: `Source` → `Worker` → `Sink` → `Source` with every edge a default `PUT`, and `Worker`'s input wire obtained but never bound. The node ships to production and `model.start()` runs. Before I show what happens — what's your gut prediction: does the node start, and if any checks fire, what do they actually do?"
- Confidence elicitation (verbatim, optional): "Quick gut number before the reveal — how confident, low to high?"
- `canonical_answer`: The node starts. During `start()`, after `markAsStarted()`, `checkForCyclicalBackpressure` finds the all-`PUT` `Source` → `Worker` → `Sink` → `Source` loop and logs an ERROR, and `checkForUnboundInputWires` finds `Worker`'s created-but-unbound wire and logs an ERROR. But `start()` discards the booleans the checks return, so startup is not aborted — it goes on to start the heartbeat and the `SEQUENTIAL_THREAD` scheduler. The defects are caught because each logged ERROR "fails standard platform tests," so CI goes red; the runtime does not refuse to start.
- `alternative_correct_answers`:
  - "It starts anyway; two checks fire — the cycle check and the unbound-wire check — each logs an ERROR, and the failing platform test in CI is what catches it."
  - "Startup proceeds (the checks only log, and `start()` ignores their results); the cyclic-backpressure and unbound-input-wire checks both log errors, caught by a red CI test, not a boot failure."
  - "Yes it boots; the cycle and the unbound wire each produce a logged ERROR during `start()`, and pre-production that error fails the test suite."
- `followup` (correct outcome "it starts" but no mechanism named): "You've got that it starts — now name which two checks fire on this graph, and say precisely what stops the developer from shipping it, given the running node doesn't."

**Move B — direct walk with cued check.** *Diagnosis tag:* the learner is fluent and you only need the load-bearing "logs, doesn't abort" fact verified once.
- Prompt (verbatim): "On this same graph, `checkForCyclicalBackpressure` returns `true`. What does `start()` do with that returned `true`, and what is the observable consequence for the running node?"
- `canonical_answer`: Nothing acts on it — `start()` ignores the return values of all three checks (the code comment says so); the only effect is the logged ERROR. The node starts and runs with the cycle present, and in production the all-`PUT` cycle does not even deadlock because hard backpressure is off. The consequence is a failing platform test in CI, not a startup abort or a runtime stall.
- `alternative_correct_answers`:
  - "`start()` discards it; the `true` just means an ERROR was logged. The node runs normally, and the only fallout is a red CI test."
  - "It's ignored — the check already logged the error. Production starts and runs (no deadlock, backpressure being soft); CI is what fails."
- `followup` (if the learner says the node stops or refuses to start): "Re-check the lines right after `markAsStarted()` — does `start()` read those booleans at all, and given that, what is the *only* thing the logged error actually stops?"

### Moment `m3-run`

*Why load-bearing (exposition):* this is the most synthesis-heavy point — it ties `c0-02`'s "`INJECT` is logically equivalent to `put` when backpressure is off" to `c0-03`'s hard-backpressure deadlock and the defensive `INJECT`. The learner can reason it from those two prior lessons, so lead with a prediction; free recall is the fallback for a learner shaky on what actually throttles in production.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is solid on `c0-02`'s `SolderType` semantics and `c0-03`'s soft/hard split; elicit the double-duty of the one `INJECT` edge.
- `answer_shape`: a two-condition statement — runtime effect with the flag off, and runtime effect with the flag on — not a single answer.
- Framing + prompt (verbatim): "To clear the cycle warning we flipped the `Sink` → `Source` edge from `PUT` to `INJECT`. Two questions before the reveal: in production today, with hard backpressure off, does changing that one edge to `INJECT` alter how data actually flows at runtime? And if an operator turned `platform.wiring.hardBackpressureEnabled` on, what would that same `INJECT` now be doing for us?"
- `canonical_answer`: With the flag off (production today), `INJECT` is logically equivalent to `put` at runtime — nothing blocks under either, so flipping the edge changes the validation result but not the runtime data flow. With the flag on, `PUT` blocks at capacity and a blocked producer propagates backpressure upstream, so the all-`PUT` loop would deadlock; the `INJECT` edge bypasses capacity and breaks the cycle, keeping it deadlock-safe. So the single `INJECT` does double duty — it clears the cycle warning and it is the deadlock safety the flag would require.
- `alternative_correct_answers`:
  - "Off: no runtime change — `INJECT` and `PUT` behave the same when nothing blocks. On: the `INJECT` is what stops the loop from deadlocking, by bypassing capacity on that edge."
  - "Today it only changes what validation says, not the data flow; if the flag were enabled, that edge is the cycle-breaker that prevents the `PUT` loop from deadlocking."
- `followup` (if the learner says `INJECT` is faster or changes flow with the flag off): "With hard backpressure off, what does `put` actually do at capacity — does it block? Given that, what runtime difference can `INJECT` make today?"

**Move B — free recall.** *Diagnosis tag:* the learner signaled uncertainty about what throttles in production; retrieve soft backpressure before moving on.
- Prompt (verbatim): "Our graph is running in production and `Worker`'s queue is filling past its capacity. In your own words: does anything block `Source` from handing `Worker` more work — and if not, how does the rest of the system find out `Worker` is overloaded?"
- `canonical_answer`: Nothing blocks — hard backpressure is off by default, so a full queue does not stall its producer; capacity is only a health threshold. The health monitor, polling on the heartbeat, sees `Worker`'s unprocessed count exceed its capacity and publishes a rising unhealthy duration on `getHealthMonitorWire()`; reaction sites elsewhere (Cluster B) read that signal and throttle. That is soft backpressure.
- `alternative_correct_answers`:
  - "No — `Source` keeps handing work over; the health monitor notices the backlog and publishes an unhealthy duration that downstream reaction sites act on."
  - "Capacity doesn't block in production; it's just a threshold the health monitor watches, and it emits a `Duration` signal that Cluster B reacts to."
- `followup` (if the learner says the queue blocks or the monitor throttles directly): "Two checks: with the flag off, does `put` block at capacity? And is the health monitor itself an enforcer, or does it only publish a signal for someone else to act on?"

## Completion problems

**Problem 1** *(small step blanked).*
- Statement (verbatim): "We flip the `Sink` → `Source` edge from `PUT` to `INJECT` to clear the cyclic-backpressure warning. Fill in the two blanks: (a) at the next `start()`, the cyclic-backpressure check now ____ (still logs the cycle / no longer logs the cycle), because ____; and (b) at runtime in production today, flipping that edge to `INJECT` ____ (does / does not) change how data flows, because ____."
- Hint ladder:
  - Rung 1 (verbatim): "Look at `registerEdge` in `TraceableWiringModel.java` for what makes an edge count toward a cycle, and recall the `c0-02` point about how `INJECT` and `PUT` compare at runtime when hard backpressure is off."
  - Rung 2 (verbatim): "Which `SolderType` does `registerEdge` mark as *blocking*? If one loop edge is no longer blocking, is the loop still a *backpressure* cycle? And with the flag off, does `put` block at capacity at all?"
  - Rung 3 (verbatim, gated on effort): "`registerEdge` marks only `PUT` edges blocking, so an `INJECT` edge isn't part of a backpressure cycle — the check no longer logs it. And with hard backpressure off, neither `put` nor `inject` blocks, so the runtime data flow is unchanged; only the validation result changed."
- `canonical_answer`: (a) The check **no longer logs** the cycle, because `registerEdge` marks an edge blocking only when its `SolderType` is `PUT`; the `INJECT` edge is not blocking, so the loop is no longer a cycle of blocking edges. (b) It **does not** change runtime data flow, because with hard backpressure off neither `put` nor `inject` blocks at capacity — `INJECT` is logically equivalent to `put` today, so only the validation result changed.
- `alternative_correct_answers`:
  - "(a) no longer logs it — an `INJECT` edge isn't blocking, so there's no blocking cycle; (b) no runtime change — nothing blocks with the flag off, so `INJECT` behaves like `put`."
  - "(a) the warning goes away because only `PUT` edges are blocking; (b) data flows exactly as before in production, since hard backpressure is off."
- *Mechanism exercised (exposition):* the `SolderType` recorded at assembly is what the cycle check reads, and the soft-backpressure default makes the same edge change a no-op at runtime today.
- `followup` (if the learner says the runtime flow changes): "With the flag off, what does `put` do when the consumer is at capacity? Given that answer, what can `INJECT` change about today's data flow?"

**Problem 2** *(more faded — from a new graph).*
- Statement (verbatim): "A different graph is deployed to production: a `CONCURRENT` scheduler is soldered directly into a `DIRECT` scheduler, and one component's input wire was obtained but never bound. Answer three things: (a) does the node start; (b) which two checks fire during `start()` and what do they do; (c) where does this get caught?"
- Hint ladder:
  - Rung 1 (verbatim): "Read the `start()` method and the comment above the three `checkFor...` calls in `StandardWiringModel.java`, and the rules javadoc on `checkForIllegalDirectSchedulerUse` in `DirectSchedulerChecks.java`."
  - Rung 2 (verbatim, gated on effort): "The node starts — `start()` discards the checks' return values and they only log. `checkForIllegalDirectSchedulerUsage` flags the `CONCURRENT`-into-`DIRECT` call and `checkForUnboundInputWires` flags the unbound wire, each logging an ERROR; both 'fail standard platform tests,' so CI catches them, not a startup abort."
- `canonical_answer`: (a) Yes, the node starts — `start()` ignores the checks' return values and they only log. (b) `checkForIllegalDirectSchedulerUsage` detects the `CONCURRENT` scheduler calling into a `DIRECT` scheduler (an illegal shape) and logs an ERROR; `checkForUnboundInputWires` detects the created-but-unbound input wire and logs an ERROR. (c) In CI — each logged ERROR "fails standard platform tests," so the failing test catches the mistake before production; the runtime does not refuse to start.
- `alternative_correct_answers`:
  - "(a) it starts; (b) the illegal-direct-usage check (CONCURRENT→DIRECT) and the unbound-input-wire check both log errors; (c) the failing platform test in CI."
  - "(a) boots normally; (b) two ERRORs logged — illegal direct usage and an unbound wire; (c) caught by a red CI test, not a boot failure."
- *Mechanism exercised (exposition):* the three checks log over the frozen graph and `start()` proceeds regardless; the gate is test-time.
- `followup` (if the learner says the node refuses to start): "Re-read what `start()` does with each check's boolean — does it act on it? Given the node starts regardless, what is the only thing actually stopped by those logged errors?"

## Delta callout

`[TBD: delta-map/wiring-framework.md is not yet authored — the delta-map/ directory currently holds only README.md, so there is no current-versus-proposed entry for the wiring framework's assembly, validation, run-time backpressure, or lifecycle to summarize.]` Status: **not started**. When that delta lands it will be linked here as `../../delta-map/wiring-framework.md`. The topic file's own forward note describes a planned module-API-level backpressure layered *above* the wire level at the Consensus/Execution boundary ([wiring-framework.md](../../architecture/topics/wiring-framework.md)); that is a separate cross-module throttle and does not change the assemble/validate/run/shutdown trace this lesson walks. *(Exposition; no learner-facing prompt.)*

## Transfer prompt

Forward framing (motivational only): the next cluster opens the hashgraph — a component that consumes events on its own scheduler and emits consensus rounds. You do not need to know anything about how it decides consensus to answer the question below; it is answerable now, purely from how a scheduler consumes its queue and how the health wire signals backlog.

- Prompt (verbatim): "Picture the hashgraph as just one more component on a `SEQUENTIAL` scheduler in a graph like the one we built. A flood of events arrives and it falls behind — its queue grows well past its capacity, in production with hard backpressure off. Using only what you know about how a scheduler's on-ramp count and the health-monitor wire behave: what would you expect to observe at the wiring level, and — just as important — what would *not* happen?"
- `canonical_answer`: Its unprocessed-task count climbs above its capacity, so the health monitor (polling on the heartbeat) marks it unhealthy and publishes a rising unhealthy duration on the health-monitor wire, which reaction sites read and use to throttle upstream. What would *not* happen: nothing blocks the producers feeding it — soft backpressure means a full queue does not stall its upstream — and the node does not stop or refuse to run; the backlog is a signal, not a halt.
- `alternative_correct_answers`:
  - "The health monitor sees its queue over capacity and emits a growing unhealthy duration for reaction sites to act on; what doesn't happen is any producer blocking or the node halting — capacity is just a threshold in production."
  - "You'd see a non-zero, rising unhealthy-duration signal on the health wire; you would *not* see the upstream stall on a full queue or the node crash, because hard backpressure is off."
- `followup` (if the learner says the queue blocks its producer or the node halts): "That is the hard-backpressure picture. With the flag off in production, what does a full queue actually do to its producer — and so what is the *only* effect of the backlog?"

## Close-out retrieval

- Free-recall summary (verbatim): "In your own words, walk one component graph through its whole life on the wiring model: what the model holds once you've assembled it, what `start()` does, how the graph behaves under load in production, and what happens when you shut it down."
  - `canonical_answer`: After construct-solder-bind, the one shared model holds the complete frozen topology — vertices, edges with their `SolderType`s, created and bound input wires. `start()` freezes growth, runs three static checks (cyclic backpressure, illegal `DIRECT`, unbound wires) that only log and never abort, then starts the heartbeat and the `SEQUENTIAL_THREAD` schedulers. Under load in production, hard backpressure is off, so a full queue does not block its producer; the health monitor publishes the backlog as an unhealthy duration for reaction sites to act on. `stop()` halts the heartbeat and dedicated-thread schedulers without draining; a clean drain is the separate per-component squelch + `flush()`.
  - `alternative_correct_answers`:
    - "Assemble onto one model (it ends up holding the whole frozen graph); `start()` validates by logging-only and boots the heartbeat plus dedicated threads; under load nothing blocks (soft backpressure, health signal); `stop()` halts threads without draining, and you `flush()` to drain."
    - "The model owns the assembled topology; start runs the three log-only checks and starts the threads that need starting; production backpressure is the health signal, not blocking queues; shutdown stops the threads, and draining is a separate squelch/flush."
- Successive-relearning tags (exposition; added to the learner's relearning queue): this lesson establishes **no new threshold concept** — it is where `c0-01`'s declarative concurrency, `c0-02`'s declarative handoff, and `c0-03`'s soft backpressure operate together on a single assembled, validated graph. Those three threshold concepts keep their existing relearning intervals; the tutor consolidates them here as "all three at work on one graph," rather than scheduling new tags.

## Open questions

- **`[TBD]` Delta callout.** `delta-map/wiring-framework.md` does not yet exist (the `delta-map/` directory holds only `README.md`), so there is no current-versus-proposed difference for the wiring framework's assembly, validation, run-time backpressure, or lifecycle to summarize. Deferred until the wiring-framework delta-map entry is authored; the callout links the file once it lands.
