---
id: c0-04-wiring-model-lifecycle
cluster: c0
title: "The WiringModel: assembly, validation, and lifecycle"
pass: 2
prerequisites:
  - c0-01-schedulers-and-types
  - c0-02-wires-and-soldering
  - c0-03-backpressure-and-queue-health
kb_topics_touched:
  - architecture/topics/wiring-framework.md
kb_concepts: []
kb_glossary_terms:
  - Scheduler
  - Wire
  - Soldering
  - Squelching
kb_invariants: []
kb_deltas:
  - delta-map/wiring-framework.md
kb_decisions: []
learning_objectives:
  - Describe the WiringModel as the single object that owns every scheduler and wire in the process — constructed once via WiringModelBuilder.create(...).build() and threaded through the whole consensus layer — with StandardWiringModel in production and DeterministicWiringModel reserved for testing.
  - Explain that the construct-solder-bind pattern from c0-01/c0-02 registers schedulers, edges, and input wires onto the model, and that after start() every register call throws IllegalStateException, so the graph is grown entirely before start and cannot grow after.
  - Name the three static-analysis checks start() runs in order — checkForCyclicalBackpressure, checkForIllegalDirectSchedulerUsage, checkForUnboundInputWires — say what each catches, and state that they only log errors (which fail standard platform tests) rather than aborting startup.
  - State that the lifecycle is one-shot — start() runs the checks and then starts the heartbeat and dedicated-thread schedulers, a second start() throws, and stop() halts those threads without draining in-flight or queued work, which is the separate flush()/squelching path from c0-01.
  - Locate generateWiringDiagram as the model's Mermaid-diagram facility, including how it surfaces unsoldered input wires as a "Mystery Input" source.
threshold_concepts: []
estimated_session_minutes: 30
status: drafted
last_verified_against: eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2
---

# The WiringModel: assembly, validation, and lifecycle

## Prerequisites

- **`c0-01-schedulers-and-types`** — A scheduler owns a queue, a threading policy, and a primary output wire; a component is mounted with the construct-solder-bind pattern, and binding is deferred so the graph's topology is described before any instance exists. A monitored scheduler keeps an on-ramp (unhandled-task) count, and `flush()` (opt-in) plus squelching are how a scheduler is *drained*. `DIRECT` runs inline with a restricted wiring rule whose enforcement was deferred to this lesson.
- **`c0-02-wires-and-soldering`** — `solderTo` joins an output wire to one or more input wires and *records the edge, with its `SolderType`, in the model*; that recorded type is what graph validation reads. Soldering is one-shot at assembly.
- **`c0-03-backpressure-and-queue-health`** — `checkForCyclicalBackpressure` runs when the model starts and logs an error on a backpressure cycle; the defensive remedy is one `INJECT` edge. `getHealthMonitorWire()` and the heartbeat wire are model-owned infrastructure wires.

This lesson is the container the previous three described pieces of: the `WiringModel` that owns every scheduler and wire, validates the assembled graph, and drives its one-shot lifecycle. If the learner hedges on **deferred binding** (`c0-01`) or on **the recorded edge/`SolderType`** (`c0-02`), those are the two prerequisites worth a bounded recall probe before starting — assembly and validation rest on them.

## Incoming retrieval probes

This section is an authorial signal, not a session-open quiz. The tutor watches for these concepts at the entry self-assessment and consolidates them when they resurface in the spine; it does not open with a recall drill. Each entry names the concept, the prior lesson, and the one-line statement to consolidate against.

- **Construct-solder-bind + deferred binding** (`c0-01`) — *Canonical:* a component's wiring is constructed and soldered against an interface before its instance exists, and `bind()` resolves the method references later. Resurfaces in chunk 2, where each of those steps is shown to *register* with the model.
- **The recorded edge and its `SolderType`** (`c0-02`) — *Canonical:* `solderTo` records the edge together with its `SolderType` in the model. Resurfaces in chunk 3 — that recorded graph is exactly what the static checks walk.
- **`checkForCyclicalBackpressure` at start** (`c0-03`) — *Canonical:* at start the model walks the graph and logs an error if it finds a backpressure cycle; flip one edge to `INJECT` to break it. Resurfaces in chunk 3 as the first of the three checks.
- **`flush()` / squelching as the drain** (`c0-01`) — *Canonical:* `flush()` (opt-in) waits for a scheduler's on-ramp count to reach zero; squelching drops new tasks; together they drain a backlog. Resurfaces in chunk 4 as the contrast to `stop()`, which does *not* drain.
- **The `DIRECT` wiring restriction** (`c0-01`, watch-for) — *Canonical:* a `DIRECT` scheduler permits only one logical sequential sender; the "graph-walk that enforces this" was named as this lesson. It lands in chunk 3 as `checkForIllegalDirectSchedulerUsage`.
- *(Threshold concepts from earlier in the cluster — declarative concurrency (`c0-01`), declarative handoff (`c0-02`), soft backpressure (`c0-03`) — have relearning intervals that plausibly fall in this session. This lesson is where all three become a single validated graph; watch for them and consolidate in line.)*

## Misconception watchlist

- **Graph validation aborts startup.** *(Import from schema validators and DI containers that throw on a bad configuration.)* Sounds like: "so if I leave a cycle in, the node refuses to start?" Correction, in line: no — the three checks only *log* (at ERROR), and `start()` discards their return values and proceeds; its own javadoc says it "performs static analysis of the wiring topology and writes errors to the logs if problems are detected" ([WiringModel.java#L121-L126](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModel.java#L121-L126), [StandardWiringModel.java#L218-L222](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L218-L222)). The check javadocs say each logged message "will fail standard platform tests" — so the gate is *test-time* (CI), not a runtime refusal to start.
- **`stop()` drains the queues / lets in-flight work finish.** *(Import from `ExecutorService.shutdown()` + `awaitTermination`, or graceful-shutdown patterns.)* Sounds like: "`stop()` waits for the queued tasks to complete first, right?" Correction: `stop()` stops the heartbeat scheduler and the dedicated-thread (`SEQUENTIAL_THREAD`) schedulers and returns; it does *not* wait for in-flight or queued tasks ([StandardWiringModel.java#L236-L251](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L236-L251)). Draining is the separate `flush()`/squelching mechanism from `c0-01`, applied per component, not something `stop()` does.
- **Each module builds its own `WiringModel`.** *(Over-generalization: modules look self-contained, so each must own its graph.)* Sounds like: "where does event-intake create its model?" Correction: there is exactly one `WiringModel` for the whole process, built once in `PlatformBuilder` and threaded into every module's `initialize(model, ...)` and into `PlatformWiring`; modules build their `ComponentWiring`s *on* the shared model ([PlatformBuilder.java#L541-L545](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java#L541-L545), [wiring-framework.md](../../architecture/topics/wiring-framework.md)). That single ownership is what lets one static analysis see the entire graph.
- **`DeterministicWiringModel` is a production option or a tuning knob.** Sounds like: "should we run deterministic mode in prod for reproducibility?" Correction: production always uses `StandardWiringModel`; deterministic mode is "much slower" and exists for simulations and testing, selected only by `WiringModelBuilder.deterministic()` ([WiringModelBuilder.java#L74-L78](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModelBuilder.java#L74-L78), [WiringModelBuilder.java#L214-L222](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModelBuilder.java#L214-L222)).

## Mechanism

`c0-01` built schedulers, `c0-02` connected them with wires, `c0-03` said what a full queue does. This lesson is the object that has owned all of it the whole time — the `WiringModel` — and the three things it does that the earlier lessons kept pointing forward to: it is *assembled* (the construct-solder-bind steps register onto it), it is *validated* (three static checks at start), and it has a *lifecycle* (a one-shot start/stop that is not a drain).

**Pre-training — terms this lesson integrates** (definitions live in the glossary and topic file; named here to set vocabulary):

- **`WiringModel`** — "a collection of task schedulers and the wires connecting them"; the single object that owns every scheduler and wire, hands out scheduler builders, validates the graph, and drives the lifecycle ([WiringModel.java#L17-L21](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModel.java#L17-L21)).
- **`WiringModelBuilder`** — builds the model: `create(metrics, time)` → options → `build()`; `deterministic()` switches to the test-only implementation ([WiringModelBuilder.java#L39-L42](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModelBuilder.java#L39-L42)).
- **Vertex / edge** — the model's internal record of a scheduler (a vertex) and a soldered connection (an edge, carrying its `c0-02` `SolderType`); the checks walk these ([TraceableWiringModel.java#L42-L67](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java#L42-L67)).
- **Static analysis** — the three at-start checks: `checkForCyclicalBackpressure`, `checkForIllegalDirectSchedulerUsage`, `checkForUnboundInputWires`.
- **Drain** — `flush()` + squelching from `c0-01`; named here only to contrast with `stop()` ([glossary: Squelching](../../glossary.md#squelching)).

### Chunk 1 — One model owns the whole graph

The `WiringModel` is "a collection of task schedulers and the wires connecting them" ([WiringModel.java#L17-L21](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModel.java#L17-L21)). It owns every scheduler and wire, hands out scheduler builders (`schedulerBuilder(name)`), exposes the process-wide heartbeat and health-monitor wires from `c0-03` (`buildHeartbeatWire`, `getHealthMonitorWire`), and drives `start()` / `stop()` — it `extends Startable, Stoppable` ([WiringModel.java#L17-L32](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModel.java#L17-L32), [WiringModel.java#L86-L139](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModel.java#L86-L139)).

There is exactly one such model for the process. It is built once via `WiringModelBuilder.create(...).build()` in `PlatformBuilder`, then threaded into every module's `initialize(model, ...)` and into `PlatformWiring`, which reaches into the shared `components.model()` to solder inter-module edges and the model-owned heartbeat/health wires ([PlatformBuilder.java#L541-L545](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java#L541-L545), [PlatformWiring.java#L99-L110](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java#L99-L110), [wiring-framework.md](../../architecture/topics/wiring-framework.md)).

`build()` returns one of two implementations: `StandardWiringModel`, "suitable for production use," or — when `deterministic()` was called — `DeterministicWiringModel`, which is "much slower" and exists for simulations and testing ([WiringModelBuilder.java#L214-L222](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModelBuilder.java#L214-L222), [StandardWiringModel.java#L33-L36](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L33-L36)). Production always uses `StandardWiringModel`.

**Load-bearing line (signal):** the single ownership is the whole point of this chunk — "build a `ComponentWiring`" from `c0-01` means build it *on this one shared model*, which is precisely why the model can later see, and validate, the entire graph at once. The rest of the lesson is what it does with that vantage point.

### Chunk 2 — Assembly registers onto the model, and `start()` freezes it `{moment: m1-assembly}`

The construct-solder-bind pattern from `c0-01`/`c0-02` is how a component joins the graph; under the hood each step *registers* with the model. Constructing a `ComponentWiring` registers the scheduler as a vertex (`registerScheduler`); `solderTo` records the edge and its `SolderType` (`registerEdge`, the recording step from `c0-02`); obtaining and binding an input wire records its creation and binding (`registerInputWireCreation` / `registerInputWireBinding`) ([TraceableWiringModel.java#L166-L229](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java#L166-L229)). After assembly the model holds the complete topology: its vertices, edges, created input wires, and bound input wires ([TraceableWiringModel.java#L42-L67](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java#L42-L67)).

Every one of those `register*` methods begins with `throwIfStarted()`, which throws `IllegalStateException` once `start()` has run ([TraceableWiringModel.java#L278-L282](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java#L278-L282)). So the graph is grown entirely *before* `start()` and cannot grow after: no new schedulers, no new solders, no new input wires or bindings.

`c0-02` described the started graph as "immutable by convention." This chunk sharpens that: on the *growth* side it is enforced — the `register*` guard throws — while the "convention" covers the rest, since there is no API to remove or rewire an existing edge and nothing stops code from continuing to push data onto wires it already holds.

**Load-bearing line (signal):** assembly is a build phase that ends at `start()`; by the time the graph runs it is a static artifact. That is exactly the precondition that makes the next chunk possible — a *one-time* static analysis of a graph that will not change underneath it.

### Chunk 3 — `start()` runs three static checks, then starts the threads `{moment: m2-validation}`

When `start()` runs, after marking the model started it runs three checks in a fixed order — `checkForCyclicalBackpressure()`, then `checkForIllegalDirectSchedulerUsage()`, then `checkForUnboundInputWires()` — and then starts the heartbeat scheduler and the dedicated-thread schedulers ([StandardWiringModel.java#L204-L231](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L204-L231)). Each check walks the registered graph from chunk 2:

- **`checkForCyclicalBackpressure`** *(from `c0-03`)* — walks the vertices for a backpressure cycle and logs an error naming the path ([TraceableWiringModel.java#L100-L103](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java#L100-L103), [CycleFinder.java#L98-L122](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/analysis/CycleFinder.java#L98-L122)).
- **`checkForIllegalDirectSchedulerUsage`** — the graph-walk `c0-01` promised for `DIRECT`. It does a DFS over edges into direct destinations and flags two illegal shapes: a `CONCURRENT` scheduler calling into a `DIRECT` one, and a `DIRECT` scheduler called into by more than one `SEQUENTIAL`/`SEQUENTIAL_THREAD` scheduler (a `DIRECT`→`DIRECT` chain counts as a call from all of the chain's upstream callers) ([DirectSchedulerChecks.java#L28-L118](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/analysis/DirectSchedulerChecks.java#L28-L118)).
- **`checkForUnboundInputWires`** — compares the *created* input wires against the *bound* ones; an input wire obtained via `getInputWire` but never bound to a handler (a component you wired up but forgot to `bind()`) is flagged ([InputWireChecks.java#L28-L52](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/analysis/InputWireChecks.java#L28-L52)).

**Load-bearing line — the structural correction (signal):** each check only *logs* — at ERROR when it finds a problem — and `start()` ignores the booleans they return (the code comment: "We don't have to do anything with the output of these sanity checks. The methods below will log errors if they find problems") ([StandardWiringModel.java#L218-L222](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L218-L222), [DirectSchedulerChecks.java#L111-L112](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/analysis/DirectSchedulerChecks.java#L111-L112), [InputWireChecks.java#L49](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/analysis/InputWireChecks.java#L49)). A node with a cyclic or illegal graph *still starts*. The interface javadoc says each logged message "will fail standard platform tests" ([WiringModel.java#L34-L66](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModel.java#L34-L66)) — so the gate is CI, not a runtime refusal. Validation protects the codebase, not the running node.

The model's other static-analysis output is the wiring diagram: `generateWiringDiagram(...)` renders the graph as Mermaid, and even synthesizes a "Mystery Input" vertex feeding any input wire that was created but never soldered — the diagram counterpart of the unbound-wire check ([TraceableWiringModel.java#L124-L158](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java#L124-L158), [WiringModel.java#L68-L84](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModel.java#L68-L84)).

### Chunk 4 — A one-shot lifecycle: `start()` vs `stop()` `{moment: m3-lifecycle}`

`start()` is one-shot. It opens with `throwIfStarted()`, so a second call throws `IllegalStateException` ([StandardWiringModel.java#L204-L206](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L204-L206), [TraceableWiringModel.java#L278-L282](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java#L278-L282)). After the checks it starts the heartbeat scheduler and each `SEQUENTIAL_THREAD` scheduler — those are the only schedulers with a dedicated thread to start; `SEQUENTIAL` and `CONCURRENT` run on the shared fork-join pool and `DIRECT` runs inline (all from `c0-01`), so none of them needs starting ([StandardWiringModel.java#L224-L231](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L224-L231), [StandardWiringModel.java#L182-L188](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L182-L188)).

`stop()` is the mirror, and it is **not a graceful drain**. It stops the heartbeat scheduler, stops the dedicated-thread schedulers, and releases the JVM anchor — then returns. It does not wait for in-flight tasks and does not let queued work finish ([StandardWiringModel.java#L236-L251](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L236-L251)). Draining is a different mechanism entirely — the `flush()` + squelching path from `c0-01`, applied per component (for example `PlatformCoordinator.flushTransactionHandler()`), not something `stop()` performs. The model's lifecycle is itself driven from one place: `PlatformCoordinator.start()` ("Start the wiring framework") and `PlatformCoordinator.stop()` ([PlatformCoordinator.java#L165-L177](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformCoordinator.java#L165-L177)).

**Load-bearing line (signal):** `stop()` is an abrupt halt of the moving parts — the threads and the heartbeat — not an orderly drain of the queues. If a clean drain is needed (reconnect, freeze), the component is squelched and flushed first; that is the `c0-01` mechanism, and it is the caller's responsibility, not `stop()`'s.

## Engagement moves

### Moment `m1-assembly`

*Why load-bearing (exposition):* the learner must connect the familiar construct-solder-bind steps to a single fact — they register onto one model, and `start()` slams the door on growth. If that lands, the static analysis in the next chunk is obviously possible; if it doesn't, validation reads as magic.

**Move A — direct walk with cued check.** *Diagnosis tag:* the learner is fluent on `c0-01`/`c0-02` assembly and needs only a verify.
- Prompt (verbatim): "Each `ComponentWiring` you construct, and each `solderTo` you call, registers something on the one shared model. Suppose you call `model.start()`, and only afterwards try to solder one more edge. What happens — does the edge get added, get silently ignored, or something else?"
- `canonical_answer`: Something else — it throws. The recording step behind `solderTo` (`registerEdge`) begins with `throwIfStarted()`, which throws `IllegalStateException` once `start()` has run, so the new edge is neither added nor silently dropped; the call fails loudly.
- `alternative_correct_answers`:
  - "It throws `IllegalStateException` — you can't register a new edge after start."
  - "Neither added nor ignored; `throwIfStarted` rejects it with an exception."
  - "An exception — the graph can't grow after `start()`, so the late solder fails."
- `followup` (if the learner says "ignored" or "added"): "Re-check the register path: what is the first thing every `register*` method does, and what does that do once the model is started?"

**Move B — free recall.** *Diagnosis tag:* a mid-spine retrieval cycle to make the registration fact explicit before validation.
- Prompt (verbatim): "In your own words: by the time `start()` is called, what does the model actually hold about the system, and why does that make a one-time check of the whole graph possible?"
- `canonical_answer`: It holds the complete topology — every scheduler as a vertex, every soldered edge with its `SolderType`, and which input wires were created and bound. Because growth is closed off at `start()`, that topology is a static artifact, so the model can analyze the entire graph once without it changing underneath the analysis.
- `alternative_correct_answers`:
  - "The full graph — all vertices, edges, and input-wire bindings — frozen at start, so a single static pass is well-defined."
  - "Every scheduler and connection is registered on it; since nothing more can be added, the graph is fixed and checkable in one pass."
  - "The whole assembled topology; it's immutable on the growth side after start, so one analysis covers all of it."

### Moment `m2-validation`

*Why load-bearing (exposition):* this is the lesson's center of gravity. It collects the three checks the earlier lessons pointed at (`c0-03`'s cycle check, `c0-01`'s `DIRECT` rule, plus unbound wires) and corrects the strong, natural assumption that validation gates startup. The checks are new material on this codebase, so the primary move is a worked example, with a direct walk as the lighter fallback.

**Move A — worked example with self-explanation.** *Diagnosis tag:* the learner is new to the checks and may assume a failed check stops the node.
- Walk (exposition): inside `start()`, after `markAsStarted()`, the model calls the three checks in order and then starts the threads; the code comment notes it does nothing with their return values, and each check logs at ERROR when it finds a problem ([StandardWiringModel.java#L218-L222](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L218-L222), [DirectSchedulerChecks.java#L111-L112](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/analysis/DirectSchedulerChecks.java#L111-L112)). **Load-bearing line:** the return values are discarded; the checks log, they do not abort.
- Self-explanation prompt (verbatim): "`start()` calls all three checks but ignores the booleans they return, and the checks only write to the log. Given that, what failure does this validation actually protect against — and where would a developer find out they broke the graph?"
- `canonical_answer`: It protects the codebase, not the running node: a bad graph (a cycle, an illegal `DIRECT` call, an unbound wire) still starts at runtime, but the logged error "fails standard platform tests," so the developer finds out in CI/test. The check is a test-time gate, not a runtime guard that refuses to start.
- `alternative_correct_answers`:
  - "It catches wiring mistakes in CI — the error fails platform tests; production still starts, so the protection is test-time."
  - "Against shipping a broken graph; the node boots anyway, but the test suite goes red on the logged error."
  - "It's a developer/test safeguard — the failing test is the signal, not a startup abort."
- `followup` (restatement of "it logs an error" without the where/what): "That's what it does — I'm asking what it *prevents*. Given the node starts regardless, who or what is actually stopped by that logged error, and when?"

**Move B — direct walk with cued check.** *Diagnosis tag:* the learner is fluent and needs only the `DIRECT` check tied back to `c0-01`.
- Prompt (verbatim): "`c0-01` said a `DIRECT` scheduler may have only one logical sequential sender, and that the enforcing graph-walk was this lesson. Which of the three checks is that walk, and name one wiring shape it flags as illegal."
- `canonical_answer`: It is `checkForIllegalDirectSchedulerUsage`. It flags, for example, a `CONCURRENT` scheduler that calls into a `DIRECT` scheduler, or a `DIRECT` scheduler that is called into by more than one `SEQUENTIAL`/`SEQUENTIAL_THREAD` scheduler.
- `alternative_correct_answers`:
  - "`checkForIllegalDirectSchedulerUsage`; e.g. two sequential schedulers both feeding one `DIRECT` scheduler."
  - "The illegal-direct-usage check; a `CONCURRENT`→`DIRECT` call is one illegal shape."
  - "`checkForIllegalDirectSchedulerUsage` — more than one sequential caller into a `DIRECT` is illegal."
- `followup` (if the learner names the check but no shape): "Good — now name one concrete shape it rejects, in terms of the sender's scheduler type and the `DIRECT` target."

### Moment `m3-lifecycle`

*Why load-bearing (exposition):* the `stop()`-drains assumption is a clean import from `ExecutorService` shutdown, and it is wrong here. Surfacing it as a prediction (the learner can reason from `c0-01`'s separate flush/squelch drain and from general shutdown intuition) makes the correction stick.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is solid on `c0-01`'s flush/squelch drain and likely holds the "shutdown drains the queue" intuition; elicit it before the reveal.
- `answer_shape`: a does-it-or-not-plus-what-actually-happens statement (does `stop()` drain, and if not, what does it stop), not a flat yes/no.
- Framing + prompt (verbatim): "A node is shutting down and several schedulers still have queued, unprocessed tasks. Someone calls `model.stop()`. Before I show what `stop()` does: what's your gut prediction — does it wait for that queued work to finish, and what would have to be true for your answer to hold?"
- Confidence elicitation (verbatim, optional): "Quick gut number before the reveal — how confident, low to high?"
- `canonical_answer`: No — `stop()` does not drain. It stops the heartbeat scheduler and the dedicated-thread (`SEQUENTIAL_THREAD`) schedulers, releases the JVM anchor, and returns; in-flight and queued tasks are not awaited. Waiting for work to finish is the separate `flush()`/squelching path from `c0-01`, done per component, not by `stop()` ([StandardWiringModel.java#L236-L251](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L236-L251)).
- `alternative_correct_answers`:
  - "It doesn't drain — it just halts the threads/heartbeat and returns; draining is `flush()`/squelch, a different mechanism."
  - "No wait for queued work; `stop()` stops the dedicated threads, and you'd `flush()` a component yourself to drain it."
  - "Queued tasks are abandoned; `stop()` only stops the moving threads, unlike `flush()`."
- `followup` (correct outcome — "it doesn't drain" — but no mechanism named): "Right that it doesn't wait. Now name what *does* drain a scheduler in this framework, and say why that is a separate call from `stop()`."

**Move B — direct walk with cued check.** *Diagnosis tag:* the learner is fluent and needs only the one-shot fact verified.
- Prompt (verbatim): "You've started the model once. A later code path calls `model.start()` a second time. What happens, and which guard is responsible?"
- `canonical_answer`: It throws `IllegalStateException`; `start()` begins with `throwIfStarted()`, which throws once the model has been marked started, so the lifecycle is one-shot.
- `alternative_correct_answers`:
  - "`IllegalStateException`, from the `throwIfStarted()` guard at the top of `start()`."
  - "It throws — start is one-shot, enforced by `throwIfStarted`."
  - "Exception; you can't start twice because of the started-state guard."
- `followup` (if the learner says "nothing" or "it restarts"): "Check the first line of `start()` — what guard runs, and what does it do when the model is already started?"

## Completion problems

**Problem 1** *(small step blanked).*
- Statement (verbatim): "A developer leaves a backpressure cycle in the graph — every edge of the loop is `PUT`. The node is deployed and `model.start()` runs. Fill in the blanks: at start the node ____ (refuses to start / starts normally), and the developer's signal that something is wrong is ____."
- Hint ladder:
  - Rung 1 (verbatim): "Read the `start()` method in `StandardWiringModel.java` — the comment just above the three `checkFor...` calls — and the class javadoc on `checkForCyclicalBackpressure` in `WiringModel.java`."
  - Rung 2 (verbatim): "Does `start()` do anything with the value the check returns, and what does the check itself do — throw, or log? And where does the javadoc say that logged message lands?"
  - Rung 3 (verbatim, gated on effort): "`start()` discards the return values and the check only logs an error, so the node starts normally; the javadoc says that error message 'will fail standard platform tests,' so the signal is a failing CI test, not a startup crash."
- `canonical_answer`: The node starts normally; the signal is a logged ERROR that "fails standard platform tests" (a red CI test), because `start()` ignores the checks' return values and the checks only log ([StandardWiringModel.java#L218-L222](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L218-L222), [WiringModel.java#L34-L43](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModel.java#L34-L43)).
- `alternative_correct_answers`:
  - "Starts normally; the signal is a logged error that fails the platform tests."
  - "It boots anyway — the only sign is the error log / failing CI check, not a refusal to start."
- *Invariant exercised (exposition):* the static checks are a test-time gate; `start()` logs and proceeds.
- `followup` (if the learner says "refuses to start"): "Re-read what `start()` does with the check's return value — does anything in `start()` act on it, or does the check just write to the log?"

**Problem 2** *(more faded — from a scenario).*
- Statement (verbatim): "A node is being taken down. There is still queued work in a `SEQUENTIAL` scheduler that you want fully processed before the process exits. Answer three things: (a) does calling `model.stop()` accomplish that on its own; (b) what does `stop()` actually do; (c) what would you do to that one scheduler's component first to drain it cleanly, using the `c0-01` mechanism."
- Hint ladder:
  - Rung 1 (verbatim): "Read `stop()` in `StandardWiringModel.java`, and recall the `flush()` / squelching section of `c0-01`."
  - Rung 2 (verbatim): "List exactly what `stop()` stops — does any step wait for queued tasks? Then: which `c0-01` call waits for a scheduler's on-ramp count to reach zero, and what is paired with it to stop new arrivals?"
  - Rung 3 (verbatim, gated on effort): "`stop()` only stops the heartbeat and dedicated-thread schedulers and returns — it never waits for queued work. To drain cleanly you squelch the component (stop new tasks) and `flush()` it (wait for the backlog to finish) before stopping, exactly the `c0-01` drain."
- `canonical_answer`: (a) No — `stop()` does not drain queued work. (b) It stops the heartbeat scheduler and the dedicated-thread (`SEQUENTIAL_THREAD`) schedulers, releases the JVM anchor, and returns, without awaiting in-flight or queued tasks. (c) Drain the component with the `c0-01` mechanism first — `startSquelching()` to stop new tasks and `flush()` to wait for the on-ramp count to reach zero — then stop ([StandardWiringModel.java#L236-L251](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java#L236-L251)).
- `alternative_correct_answers`:
  - "(a) no; (b) it just halts the heartbeat and dedicated threads and returns, abandoning queued work; (c) squelch + `flush()` the component first."
  - "(a) `stop()` alone won't; (b) it stops the moving threads only; (c) use `flush()` (with squelching) on that component to drain before stopping."
- *Invariant exercised (exposition):* `stop()` halts threads but does not drain; draining is the separate per-component flush/squelch path.
- `followup` (if the learner gives (a)/(b) but not the drain call): "You've got that `stop()` won't do it — now name the two `c0-01` calls that actually drain a scheduler, and which one waits for the backlog."

## Delta callout

`[TBD: delta-map/wiring-framework.md is not yet authored — the delta-map/ directory currently holds only README.md, so there is no current-versus-proposed entry for the WiringModel's assembly, validation, or lifecycle to summarize.]` Status: **not started**. When that delta lands it will be linked here as `../../delta-map/wiring-framework.md`. The topic file's own forward note describes a planned module-API-level backpressure layered above the wire level at the Consensus/Execution boundary ([consensus-execution-boundary.md](../../architecture/interfaces/consensus-execution-boundary.md)); that is a separate cross-module throttle and does not change the model's assembly/validation/lifecycle this lesson covers. *(Exposition; no learner-facing prompt.)*

## Transfer prompt

Forward framing (motivational only): the next lesson, `c0-syn`, assembles and validates a whole component graph end to end — the synthesis of everything in this cluster. The question below is answerable now, from how `start()` runs its checks and what it does with the results.

- Prompt (verbatim): "Suppose a graph has a `CONCURRENT` scheduler soldered directly to a `DIRECT` scheduler — an illegal shape — and the node is deployed to production and started. Using only what you've seen about the three checks and what `start()` does with their results: does the node start, what happens during `start()`, and how would this same mistake be caught before it ever reached production?"
- `canonical_answer`: The node starts. During `start()`, `checkForIllegalDirectSchedulerUsage` walks the graph, detects the `CONCURRENT`→`DIRECT` call, and logs an ERROR — but `start()` ignores the check's return value and continues, so startup is not aborted. The mistake is caught before production because that logged message "fails standard platform tests," so CI goes red on it; the runtime does not refuse to start, the test suite does.
- `alternative_correct_answers`:
  - "It starts anyway; `checkForIllegalDirectSchedulerUsage` logs an error during `start()`, and the failing platform test in CI is what catches it pre-production."
  - "Startup proceeds (the check only logs); the guard is the failing CI test, not a runtime abort."
  - "Yes it starts — the illegal-direct check errors to the log, `start()` doesn't act on it, and CI catches the error before deploy."
- `followup` (if the learner says the node refuses to start): "Re-apply the chunk-3 point: what does `start()` do with the boolean each check returns, and given that, what is the *only* place this illegal shape actually stops anything?"

## Close-out retrieval

- Free-recall summary (verbatim): "In your own words: when you call `start()` on the wiring model, what does it check, what does it do with the results of those checks, and what does it actually start running?"
  - `canonical_answer`: It runs three static checks once, in order — cyclic backpressure, illegal `DIRECT` usage, unbound input wires — over the now-frozen graph. It ignores their return values; the checks only log errors (which fail standard platform tests), so a bad graph still starts. It then starts the heartbeat scheduler and the dedicated-thread (`SEQUENTIAL_THREAD`) schedulers. `start()` is one-shot — a second call throws — and `stop()` later halts those threads without draining queued work.
  - `alternative_correct_answers`:
    - "Three checks (cycle, illegal direct, unbound wires) that only log; then it starts the heartbeat and the dedicated-thread schedulers. The log is the gate (CI), not an abort, and start is one-shot."
    - "It statically analyzes the frozen graph (logging any problems, not aborting) and starts the heartbeat plus `SEQUENTIAL_THREAD` schedulers; calling it twice throws."
    - "Validates the graph by logging errors, then boots the dedicated threads and heartbeat; the checks don't stop startup, and the lifecycle can't be restarted."
- Successive-relearning tags (exposition; added to the learner's relearning queue): this lesson establishes **no new threshold concept** — it is the container that turns `c0-01`'s declarative concurrency, `c0-02`'s declarative handoff, and `c0-03`'s soft backpressure into one assembled, validated graph. Those three threshold concepts keep their existing relearning intervals; the tutor should consolidate them here as "the model is where all three live as a single graph," rather than scheduling new tags.

## Open questions

- **`[TBD]` Delta callout.** `delta-map/wiring-framework.md` does not yet exist (the `delta-map/` directory holds only `README.md`), so there is no current-versus-proposed difference for the WiringModel's assembly, validation, or lifecycle to summarize. Deferred until the wiring-framework delta-map entry is authored; the callout links the file once it lands.
