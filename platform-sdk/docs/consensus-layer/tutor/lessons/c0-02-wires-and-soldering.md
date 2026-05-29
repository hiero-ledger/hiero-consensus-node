---
id: c0-02-wires-and-soldering
cluster: c0
title: "Wires, soldering, and in-line transformers"
pass: 2
prerequisites:
  - c0-01-schedulers-and-types
kb_topics_touched:
  - architecture/topics/wiring-framework.md
kb_concepts: []
kb_glossary_terms:
  - Wire
  - Soldering
  - Transformer
  - Backpressure
  - Scheduler
kb_invariants: []
kb_deltas:
  - delta-map/wiring-framework.md
kb_decisions: []
learning_objectives:
  - Distinguish an output wire from an input wire, and explain why a scheduler's primary output wire is fed automatically from the handler's return while a secondary output wire is pushed explicitly by component code.
  - Name the three SolderTypes (PUT, INJECT, OFFER), the InputWire method each maps to, and the at-capacity contract each one declares.
  - Explain why, in production, the three handoffs do not differ in runtime blocking or dropping — hard backpressure is off by default — and locate where the operative (soft) backpressure actually lives.
  - Choose a SolderType for an edge from its role: regular data path, out-of-band control signal, cycle-breaker, or lossy/heartbeat handoff.
  - Identify the four in-line transformers (filter, transformer, advanced transformer, list-splitter), state what each produces, and explain that they run inline on an internal DIRECT_THREADSAFE scheduler and so must be fast.
threshold_concepts:
  - "Declarative handoff: an edge's backpressure participation and handoff semantics are selected by its SolderType at wiring-assembly time — a property of the connection, not of either component it joins."
estimated_session_minutes: 35
status: drafted
last_verified_against: eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2
---

# Wires, soldering, and in-line transformers

## Prerequisites

- **`c0-01-schedulers-and-types`** — A task scheduler owns a queue, a threading policy, and a built-in primary output wire; its `TaskSchedulerType` sets the handler's concurrency contract at wiring-assembly time, not in the handler. The scheduler forwards the handler's return value onto its primary output wire automatically, and a monitored scheduler counts a task as unprocessed (the on-ramp count) from arrival until the handler returns. `DIRECT_THREADSAFE` runs inline on the caller's thread with no queue and requires a thread-safe handler.

This lesson assumes that mental model solid: it is the wires hanging off the schedulers from `c0-01` that this lesson connects. If the learner hedges on what a *primary output wire* is or on `DIRECT_THREADSAFE`, those are the two prerequisites worth a bounded recall probe before starting, because the whole lesson rests on them.

## Incoming retrieval probes

This section is an authorial signal, not a session-open quiz. The tutor watches for these `c0-01` concepts at the entry self-assessment and consolidates them when they resurface in the spine; it does not open with a recall drill. Each entry names the concept, the prior lesson, and the one-line statement to consolidate against.

- **Primary output wire** (`c0-01`) — *Canonical:* a scheduler owns exactly one primary `OutputWire`, and the scheduler forwards the handler's return value onto it automatically. This lesson opens on it directly; if the learner is shaky here, that is where to consolidate first.
- **On-ramp / unhandled-task count** (`c0-01`) — *Canonical:* a task is counted from the moment it enters a scheduler until its handler returns; the count measures backlog, and at capacity what happens is backpressure. Resurfaces when this lesson explains what `PUT` would do "at capacity."
- **`DIRECT_THREADSAFE`** (`c0-01`) — *Canonical:* runs inline on the caller's thread with no queue, and the handler must be thread-safe because the framework does not verify it. Resurfaces in the transformers chunk, where every in-line transformer runs on one.
- **Declarative concurrency** (`c0-01`, threshold concept) — *Canonical:* a component's concurrency contract is selected by its scheduler type at wiring time, not written into the handler. This lesson establishes the sibling idea for *edges*; if the learner has internalized the scheduler-side version, name the parallel explicitly when the edge-side version lands. Its day-3 successive-relearning interval plausibly falls in this session — watch for it.

## Misconception watchlist

- **`PUT` blocks the producer when the consumer's queue is full.** *(Import from bounded-queue / backpressure systems where a full queue always blocks the writer.)* Sounds like: "so once the consumer fills up, the producer stalls on the `PUT`?" or "isn't `PUT` the blocking one?" Correction, in line: that is the *contract* `PUT` declares, but in production it is dormant — hard backpressure (`platform.wiring.hardBackpressureEnabled`) is **off by default**, so `put` does not block and `offer` does not drop ([InputWire.java#L66-L95](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java#L66-L95); [wiring-framework.md](../../architecture/topics/wiring-framework.md), [health-monitor-and-backpressure.md](../../architecture/topics/health-monitor-and-backpressure.md)). The backpressure that actually operates in production is *soft* — the health monitor reacting to backlog — and is `c0-03`. This lesson's claim is about what the SolderType *declares*, not what fires at runtime today.
- **`INJECT` is a faster `put` — an optimization.** Sounds like: "I'll `INJECT` to skip the queueing overhead." Correction: `INJECT` is about *correctness*, not speed — it bypasses the capacity limit so an out-of-band signal is never blocked or dropped, and so a dependency cycle stays deadlock-safe. When backpressure is off it is *logically equivalent to `put`* ([InputWire.java#L86-L95](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java#L86-L95)); it buys nothing in throughput.
- **A filter or transformer is a component with its own thread and queue.** *(Over-generalization from `c0-01`: "everything runs on a scheduler, so a transformer must have a real one.")* Sounds like: "which scheduler type should I give the filter?" Correction: `buildFilter` and `buildTransformer` create an internal `DIRECT_THREADSAFE` scheduler — no queue, run inline on whichever thread forwarded the data — and `buildAdvancedTransformer` has no scheduler of its own at all ([wiring-framework.md](../../architecture/topics/wiring-framework.md), [WireTransformer.java#L36-L50](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/transformers/WireTransformer.java#L36-L50)). That is why the framework demands the transform function "be very fast."
- **An output wire goes to exactly one input wire.** Sounds like: "I already soldered that output — I'd need a copy to send it elsewhere." Correction: a single output wire can be soldered to many input wires; each added destination receives the same value, and that is exactly how fan-out is built ([OutputWire.java#L136-L149](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L136-L149)) — e.g. one event-window dispatcher feeds the deduplicator, the signature validator, and the orphan buffer ([DefaultEventIntakeModule.java#L109-L128](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java#L109-L128)).

## Mechanism

This lesson connects the schedulers from `c0-01` into a graph. `c0-01` left each scheduler standing alone with one primary output wire and a way to receive work; this lesson is the edges — how an output wire is joined to an input wire (*soldering*), what the per-edge handoff choice (*SolderType*) declares, and how data can be reshaped *in line* along a wire without a dedicated component (*transformers*). What a full backlog actually does to a producer is `c0-03`; assembling and validating the whole graph is `c0-04`.

**Pre-training — terms this lesson integrates** (definitions live in the glossary and topic file; named here to set vocabulary):

- **Output wire** — a component's source of outbound data; every connection *out* of a component starts at an `OutputWire`. A scheduler owns one *primary* output wire ([glossary: Wire](../../glossary.md#wire), [wiring-framework.md](../../architecture/topics/wiring-framework.md)).
- **Input wire** — a component's entry point for inbound data; an `InputWire` exposes three put-paths into the scheduler's queue ([glossary: Wire](../../glossary.md#wire)).
- **Soldering** — joining an output wire to one or more input wires; the verb for building an edge ([glossary: Soldering](../../glossary.md#soldering)).
- **SolderType** — the per-edge choice of handoff semantics: `PUT`, `INJECT`, or `OFFER` ([glossary: Soldering](../../glossary.md#soldering)).
- **Transformer** — an in-line reshaping of data along a wire — filter, type-change, or list-split — with no dedicated component ([glossary: Transformer](../../glossary.md#transformer)).
- **Backpressure** — a slow consumer slowing its producers; in production it is *soft*, driven by the health monitor, not by wires blocking ([glossary: Backpressure](../../glossary.md#backpressure)). This lesson uses only the word; the mechanism is `c0-03`.

### Chunk 1 — Output wires and input wires `{moment: m1-wires}`

Every connection *out* of a component begins at an `OutputWire<OUT>`; soldering, filters, transformers, and splitters are all methods on it ([wiring-framework.md](../../architecture/topics/wiring-framework.md), [OutputWire.java#L30-L51](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L30-L51)). A scheduler always owns exactly one **primary** output wire, returned by `getOutputWire()`; from `c0-01`, the scheduler forwards the handler's return value onto it automatically ([TaskScheduler.java#L123-L126](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L123-L126)).

A **secondary** output wire is the exception: created with `buildSecondaryOutputWire()`, it is not fed from the return value — the component's own code pushes onto it explicitly, and it is "a violation of convention to push data into a secondary output wire from any code that is not executing within this task scheduler" ([TaskScheduler.java#L128-L145](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L128-L145)). It is used only when business logic must emit something a single return value cannot carry ([wiring-framework.md](../../architecture/topics/wiring-framework.md)). "Primary" and "secondary" are output-wire terms; there is no secondary input wire.

An `InputWire<IN>` is a component's entry point. It exposes three put-paths into the scheduler's queue — `put(data)`, `offer(data)`, and `inject(data)` — and an edge selects between them via its SolderType ([InputWire.java#L66-L95](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java#L66-L95)). Modules rarely create input wires by hand: they obtain one from `componentWiring.getInputWire(MethodReference)`, and `ComponentWiring` builds and binds it under the hood ([wiring-framework.md](../../architecture/topics/wiring-framework.md)).

**Load-bearing line (signal):** the asymmetry is the thing to hold — *out* is one primary wire fed automatically from the return; *in* is a choice of three put-paths into the queue. The next two chunks are entirely about (a) joining an out to an in and (b) which of the three put-paths that join uses.

### Chunk 2 — Soldering: joining an output wire to an input wire `{moment: m2-solder-op}`

**Soldering** is the act of connecting one output wire to one or more input wires. `outputWire.solderTo(inputWire)` builds the edge and defaults to `SolderType.PUT`; `outputWire.solderTo(inputWire, solderType)` builds it with an explicit type ([wiring-framework.md](../../architecture/topics/wiring-framework.md), [OutputWire.java#L99-L101](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L99-L101)).

Three structural facts about the operation, each grounded in the code:

- **One output, many inputs.** Each `solderTo` *adds* a forwarding destination; the same output wire can be soldered to several input wires, and every destination receives the same value ([OutputWire.java#L136-L149](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L136-L149)). This is how fan-out is expressed — no copying, no separate "broadcast" primitive.
- **Order is explicit when it matters.** Plain `solderTo` makes no promise about the order multiple destinations are notified in. When the order is load-bearing, `orderedSolderTo(List<InputWire>)` makes it explicit and requires at least two wires, throwing otherwise; its javadoc instructs every caller to document *why* the order matters ([OutputWire.java#L115-L120](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L115-L120)).
- **Soldering is one-shot.** Edges are built at wiring assembly; there is no API for unsoldering, and the consensus layer treats the graph as immutable once `WiringModel.start()` has run (immutability is convention, not enforced) ([wiring-framework.md](../../architecture/topics/wiring-framework.md)).

Two asides worth knowing but not load-bearing: soldering to an input wire whose scheduler is `NO_OP` returns immediately and registers nothing — the `NO_OP` toggle from `c0-01` makes the edge vanish at assembly ([OutputWire.java#L136-L139](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L136-L139)). And `solderForMonitoring(consumer)` taps an output wire for metrics without registering an edge in the model, so monitoring taps never participate in the graph ([OutputWire.java#L157-L159](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L157-L159)).

When an edge *is* registered, `solderTo` records it in the model together with its SolderType ([OutputWire.java#L141](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L141)). That recorded SolderType is what graph validation reads later (`c0-04`) — which is the bridge into the next chunk.

### Chunk 3 — The three SolderTypes and what they declare `{moment: m3-soldertype}`

`SolderType` has three values, and each maps to one of the InputWire put-paths from chunk 1 — the `solderTo` switch dispatches `PUT → put`, `INJECT → inject`, `OFFER → offer` ([SolderType.java#L9-L24](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/SolderType.java#L9-L24), [OutputWire.java#L143-L148](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L143-L148)). The *contract* each declares, at the consumer's capacity limit:

- **`PUT`** — handoff via `put`; **blocks** the producer when the consumer is at capacity. The default, and the only type that participates in backpressure.
- **`INJECT`** — handoff via `inject`; **bypasses** capacity, always accepted.
- **`OFFER`** — handoff via `offer`; **drops** the item when the consumer is at capacity, rather than blocking.

**Load-bearing line — the caveat that scopes the whole chunk (signal):** those at-capacity reactions are *latent* in production. They take effect only when **hard backpressure is enabled, and it is off by default** — so `put` does not block, `offer` does not drop, and the three handoffs differ only in how graph validation treats them ([wiring-framework.md](../../architecture/topics/wiring-framework.md)). The code says so directly: `put` "may block *if* back pressure is enabled," `offer` declines "*if* backpressure is enabled," and `inject` "if backpressure is disabled, is logically equivalent to `put`" ([InputWire.java#L66-L95](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java#L66-L95)), and the neighbor topic confirms insertion "never applies hard backpressure" by default ([health-monitor-and-backpressure.md](../../architecture/topics/health-monitor-and-backpressure.md)). So what does a SolderType *operatively* decide today? Two things: how graph validation treats the edge (the recorded type from chunk 2), and the documented *intent* of the handoff — plus readiness to behave correctly if the flag is ever turned on. The backpressure that actually fires in production is *soft* — the health monitor reacting to backlog — and that is `c0-03`.

So the SolderType is a **declaration about the edge**, not a runtime switch you feel under normal load. That reframes "which SolderType?" from "how fast / how lossy is this connection at runtime" to "what is this edge's role, and how should the graph treat it." The four edges below are that question answered four ways — and they are the contrasting cases in the section that follows.

- **`PUT` — the regular data path.** The deduplicator's output soldered to the signature validator's input; ordinary work, enrolled in backpressure ([DefaultEventIntakeModule.java#L115-L117](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java#L115-L117)).
- **`INJECT` — out-of-band control.** A clear command soldered into the deduplicator with `INJECT`, so it always arrives even if the queue is full; clear and event-window updates are control signals that must not be backpressured against the data path ([DefaultEventIntakeModule.java#L112-L114](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java#L112-L114)).
- **`INJECT` — cycle-breaker.** The event creator's output feeds back into event intake, closing a cycle; that edge is `INJECT` so the feedback loop stays deadlock-safe ([PlatformWiring.java#L129-L132](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java#L129-L132)). *Why* a `PUT` cycle could deadlock — and why exactly one edge is flipped — is `c0-03`/`c0-04`; here the point is that the cycle-breaking choice lives on the edge.
- **`OFFER` — lossy by design.** The heartbeat soldered to the platform monitor with `OFFER`, where a missed beat is preferable to a backed-up queue ([PlatformWiring.java#L127](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java#L127)).

### Chunk 4 — In-line transformers and splitters `{moment: m4-transformers}`

A transformer reshapes data *along a wire* without a dedicated component. Each builder method on an output wire returns a **new** output wire, so transformers chain ([wiring-framework.md](../../architecture/topics/wiring-framework.md)):

- **`buildFilter(name, inputName, predicate)`** — predicate-based gating; same type in and out. Internally it is just a transformer whose function returns the item when the predicate passes and `null` when it does not, and null is not forwarded — so a filter is "transform-to-null-when-rejected" ([OutputWire.java#L202-L215](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L202-L215), [WireFilter.java#L34-L54](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/transformers/WireFilter.java#L34-L54)).
- **`buildTransformer(name, inputName, fn)`** — a type change `OUT → NEW_OUT`, called **once per data item**, and `null` returned by `fn` is not forwarded ([OutputWire.java#L252-L266](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L252-L266)).
- **`buildAdvancedTransformer(transformation)`** — like a transformer but the function is called **once per output per data item** (i.e. once per listener), with `inputCleanup` run on the original after it has been forwarded to all listeners and `outputCleanup` run on a transformed value a listener *rejects* — which is possible only under `OFFER` soldering ([OutputWire.java#L280-L295](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L280-L295), [AdvancedTransformation.java#L15-L58](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/transformers/AdvancedTransformation.java#L15-L58)). That cleanup-on-rejection is the one place this lesson's `OFFER` reappears.
- **`buildSplitter(...)` / `WireListSplitter<T>`** — a `List<T>` arriving on the input wire produces **one task per element** on the output wire; the bound consumer simply loops the list and forwards each element. `ComponentWiring` exposes it as `getSplitOutput()` for any component whose output is a list ([OutputWire.java#L227-L237](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L227-L237), [WireListSplitter.java#L32-L48](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/transformers/WireListSplitter.java#L32-L48)). The orphan buffer emits a `List<PlatformEvent>`; `validatedEventsOutputWire()` returns its split output so downstream sees one event at a time, not a batch ([DefaultEventIntakeModule.java#L183-L185](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java#L183-L185)).

**Load-bearing line (signal):** `buildFilter`, `buildTransformer`, and `WireListSplitter` each run on an internal `DIRECT_THREADSAFE` scheduler, and `buildAdvancedTransformer` has no scheduler of its own — so a transformer executes **inline on whichever thread forwarded the data**, with no queue of its own ([WireFilter.java#L42-L44](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/transformers/WireFilter.java#L42-L44), [WireTransformer.java#L43-L45](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/transformers/WireTransformer.java#L43-L45), [WireListSplitter.java#L36-L38](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/transformers/WireListSplitter.java#L36-L38)). That is *why* the framework requires the transform function to "be very fast": a slow transform stalls the upstream thread directly ([WireTransformer.java#L31-L34](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/transformers/WireTransformer.java#L31-L34)).

A free-standing `WireTransformer<A, B>` is also used as a **fan-out dispatcher**: one input wire whose single output wire is soldered to many consumers (chunk 2's one-output-many-inputs). `DefaultEventIntakeModule` builds identity dispatchers for event-window updates and clear commands so each value reaches every subscriber ([DefaultEventIntakeModule.java#L87-L90](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java#L87-L90)).

## Engagement moves

### Moment `m1-wires`

*Why load-bearing (exposition):* the out-is-one-primary-wire / in-is-three-put-paths asymmetry is the frame for the whole lesson; if it lands, soldering and SolderType are variations on it rather than new facts.

**Move A — direct walk with cued check.** *Diagnosis tag:* the learner is fluent on `c0-01`'s primary output wire and just needs a verify before moving on.
- Prompt (verbatim): "From `c0-01`, a scheduler forwards its handler's return value onto its primary output wire automatically. So when one component's output should reach another component, which wire do you start the connection from, and which end of the other component do you connect it to?"
- `canonical_answer`: You start from the producer's (primary) output wire and connect it to the consumer's input wire — output wire is the source of every outbound connection, input wire is the entry point.
- `alternative_correct_answers`:
  - "From the producing scheduler's output wire, into the consuming component's input wire."
  - "Output wire on the sender side, input wire on the receiver side."
  - "`getOutputWire()` on the producer, soldered to a `getInputWire(...)` on the consumer."
- `followup` (if the learner says "you connect the schedulers" without naming the wires): "Be specific about the two ends — name the wire you start from and the wire you land on."

**Move B — worked example with self-explanation.** *Diagnosis tag:* the learner is new to the primary/secondary distinction or unsure what a secondary output wire is for.
- Walk (exposition): the primary output wire is fed automatically from the handler's return; a secondary output wire is pushed explicitly by the component's own code and only when a single return value cannot carry what must be emitted ([TaskScheduler.java#L128-L145](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L128-L145)). **Load-bearing line:** the framework fills the primary wire for you; only the secondary wire is the component's job to push.
- Self-explanation prompt (verbatim): "A scheduler fills its primary output wire from the handler's return value with no extra code. What does that tell you about when a component would ever need a *secondary* output wire instead?"
- `canonical_answer`: Only when the component must emit something its single return value cannot carry — a second, independent output stream — does it need a secondary wire it pushes onto itself; otherwise the primary wire already covers it.
- `alternative_correct_answers`:
  - "When one return value isn't enough — it has a second kind of output to emit on its own."
  - "When business logic must push an extra stream that the return value can't represent."
  - "Rarely — only for an additional output the automatic primary-wire forwarding can't express."
- `followup` (if the learner restates "it pushes the secondary wire" without saying when): "That's how it's fed — I'm asking when. Name the situation that forces a second output wire rather than just using the primary."

### Moment `m2-solder-op`

*Why load-bearing (exposition):* fan-out (one output, many inputs) is the misconception most likely to derail later reasoning about dispatchers and the graph; a quick verify here prevents it.

**Move A — direct walk with cued check.** *Diagnosis tag:* the learner is fluent on the soldering mechanics and needs only a verify.
- Prompt (verbatim): "You've soldered a component's output wire to one consumer. You now need that same output to also reach a second, unrelated consumer. Do you need to duplicate the output or build a broadcast object — and why or why not?"
- `canonical_answer`: Neither — you call `solderTo` again with the second input wire. Each solder adds a forwarding destination, so one output wire can feed many input wires, each receiving the same value; that is how fan-out is expressed ([OutputWire.java#L136-L149](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L136-L149)).
- `alternative_correct_answers`:
  - "Just solder the same output wire to the second input too — destinations are additive."
  - "No duplication; one output wire fans out to as many inputs as you solder it to."
  - "Call `solderTo` once more; every soldered destination gets the same value."
- `followup` (if the learner hedges "maybe a copy"): "Commit — when you call `solderTo` a second time on the same output wire, what happens to the first destination?"

**Move B — free recall.** *Diagnosis tag:* a mid-spine retrieval cycle on the soldering facts before SolderType.
- Prompt (verbatim): "In your own words: once you've soldered the wiring graph and called `start()`, what can you no longer do to an edge, and what does `orderedSolderTo` give you that plain `solderTo` does not?"
- `canonical_answer`: After `start()` the graph is treated as immutable — there is no unsoldering, edges are not changed at runtime. `orderedSolderTo` makes the notification order of multiple destinations explicit (and requires at least two wires), where plain `solderTo` makes no ordering promise ([OutputWire.java#L115-L120](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L115-L120)).
- `alternative_correct_answers`:
  - "Can't unsolder or rewire after start; `orderedSolderTo` pins the order destinations are notified in."
  - "Soldering is one-shot/assembly-time; ordered soldering documents and fixes a required order across ≥2 wires."
  - "No edge changes post-start; ordered soldering guarantees the sequence plain soldering leaves unspecified."

### Moment `m3-soldertype`

*Why load-bearing (exposition):* this is the threshold-concept moment — the learner must come away seeing the SolderType as a per-edge *declaration* (validation treatment + intent, runtime reaction latent in production), not a felt runtime switch. The big misconception ("`PUT` blocks") is most efficiently surfaced and corrected here via prediction; the contrasting cases produce the transfer; the worked example is the fallback for novelty.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is strong on backpressure fundamentals and likely holds the confidently-wrong "`PUT` blocks the producer at capacity" intuition; elicit it before revealing the default-off caveat (hypercorrection).
- `answer_shape`: a behavior-under-a-condition statement (what `PUT` does, *gated on* whether hard backpressure is enabled) — not a flat "it blocks."
- Framing + prompt (verbatim): "A `PUT`-soldered edge feeds a consumer, and the consumer's queue fills to its configured capacity. Before I show you what the framework actually does in production: what's your gut prediction — does the producer block on that `PUT`, and what would have to be true for your answer to hold?"
- Confidence elicitation (verbatim, optional): "How confident are you before I reveal it — gut number, low to high?"
- `canonical_answer`: In production it does **not** block: hard backpressure is off by default, so the capacity limit is not enforced and `put` proceeds without blocking. `PUT` *would* block only if hard backpressure were enabled — that is the contract it declares, dormant by default. The backpressure that actually operates is soft (the health monitor reacting to backlog), covered in `c0-03` ([InputWire.java#L66-L95](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java#L66-L95), [wiring-framework.md](../../architecture/topics/wiring-framework.md)).
- `alternative_correct_answers`:
  - "No block in production — blocking only happens *if* hard backpressure is enabled, which it isn't by default."
  - "It would block only under hard backpressure; with the flag off (the default) `put` just enqueues."
  - "Doesn't stall normally; `PUT`'s blocking is conditional on the backpressure flag being on."
- `followup` (correct outcome — "it blocks" — but reasoning assumes enforcement): "You named the contract. Now state the condition the framework requires before that block actually happens — and say what it is set to by default."

**Move B — contrasting cases with comparison prompt.** *Diagnosis tag:* threshold concept; transfer is the goal. Uses the four edges in the Contrasting cases material below.
- Prompt (verbatim): "Here are four real edges from the codebase: the deduplicator→signature-validator data path (`PUT`), a clear command into the deduplicator (`INJECT`), the event-creator→event-intake feedback edge (`INJECT`), and the heartbeat→platform-monitor edge (`OFFER`). The same `solderTo` call builds all four. Across them, what is the same, what differs, and what is the SolderType actually choosing — given that in production none of them blocks or drops?"
- `canonical_answer`: The same `solderTo` API and the same kind of data-handoff build all four; what differs is the *role* of the edge, expressed as its SolderType. Since hard backpressure is off, none differs in runtime blocking/dropping today; the SolderType instead declares how graph validation treats the edge and the intent of the handoff — `PUT` enrolls the edge in backpressure (a normal data path), `INJECT` exempts it (so a control signal is never blocked and a cycle stays deadlock-safe), `OFFER` marks it disposable (a missed beat beats a backlog). The choice is a property of the *edge*, not of either component it joins ([SolderType.java#L9-L24](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/SolderType.java#L9-L24)).
- `alternative_correct_answers`:
  - "Same wiring call throughout; only the edge's role changes — `PUT` data path, `INJECT` control/cycle-safe, `OFFER` droppable — and that role lives on the edge, not the components."
  - "All four are solders; the SolderType declares validation treatment and intent (none blocks in prod), with `INJECT` for must-not-block signals and cycle edges, `OFFER` for losable ones."
  - "The components don't change; what changes is which backpressure contract the connection declares, chosen per edge at wiring time."
- `followup` (if the learner stops at "they use different SolderTypes" without naming what that buys): "You've matched each edge to a type — now say what the type *declares* about the edge, given that none of them actually blocks under production load."
- *Deep invariant (exposition for consolidation, not read as a question):* backpressure participation and handoff semantics are a property of the edge, selected by its SolderType at wiring time and decoupled from what either component computes — the sibling of `c0-01`'s declarative concurrency, now for connections instead of components.

**Move C — worked example with self-explanation.** *Diagnosis tag:* the learner is new to `INJECT` and hesitating on why a control signal would not just use the default.
- Walk (exposition): the clear command is soldered into the deduplicator with `INJECT` so it arrives even if the queue is full ([DefaultEventIntakeModule.java#L112-L114](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java#L112-L114)). **Load-bearing line:** the clear command is out-of-band — it must not be backpressured *against* the very data path it is meant to clear.
- Self-explanation prompt (verbatim): "The clear command uses `INJECT`, not the default `PUT`. If hard backpressure were enabled and the deduplicator's queue were full of events, what would go wrong with delivering a clear command over a `PUT` edge — and why does that make `INJECT` the right choice for a control signal?"
- `canonical_answer`: Under hard backpressure a `PUT` clear command would block (or queue) behind the very backlog it is supposed to clear — the control signal would be held up by the data congestion it exists to resolve. `INJECT` bypasses capacity so the clear always lands, keeping control signals independent of data-path congestion.
- `alternative_correct_answers`:
  - "The clear would be stuck behind the full queue it's meant to flush; `INJECT` lets it jump the capacity limit."
  - "`PUT` would couple the control signal to the data backlog; `INJECT` decouples it so it can't be blocked by what it's clearing."
  - "A blocked/queued clear can't do its job during congestion; bypassing capacity (`INJECT`) guarantees delivery."
- `followup` (restatement of "`INJECT` ignores backpressure" without the why): "That's what `INJECT` does — I'm asking why a *clear command specifically* needs it. What is special about a signal whose job is to clear the thing that's congested?"

### Moment `m4-transformers`

*Why load-bearing (exposition):* the "transformers are inline, not components" fact is the one that prevents a learner from mis-modelling them as queued stages; pitching it as a prediction off `c0-01`'s `DIRECT_THREADSAFE` lands it as a consequence rather than a new rule.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is solid on `c0-01`'s scheduler types and can reason about where a lightweight inline conversion would run.
- `answer_shape`: a scheduler-type-plus-obligation pairing (which type, and what it demands), not a single word.
- Framing + prompt (verbatim): "A filter or transformer does a tiny conversion on data already flowing along a wire — no backlog of its own, meant to be near-free. From the six scheduler types in `c0-01`, what's your gut prediction for which type the framework runs a transformer on, and what does that choice demand of the transform function?"
- `canonical_answer`: `DIRECT_THREADSAFE` — it runs inline on the caller's (forwarding) thread with no queue, so the transform function must be thread-safe and very fast, since a slow transform stalls the upstream thread directly ([WireTransformer.java#L31-L45](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/transformers/WireTransformer.java#L31-L45)).
- `alternative_correct_answers`:
  - "`DIRECT_THREADSAFE`: inline, no queue, so the function has to be fast and thread-safe."
  - "A direct (threadsafe) scheduler — runs on the forwarding thread, demands a cheap, thread-safe transform."
  - "The threadsafe direct type; the obligation is a fast, side-effect-light, thread-safe function."
- `followup` (correct type, missing the obligation): "Right type — now say what running inline on the forwarding thread *requires* of the transform function, and what happens if it's slow."

**Move B — worked example with self-explanation.** *Diagnosis tag:* the learner is new to transformers and unsure how a filter differs from a transformer.
- Walk (exposition): a filter is implemented as a transformer whose function returns the item when the predicate passes and `null` when it fails; null is not forwarded, so the rejected item simply disappears ([WireFilter.java#L34-L54](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/transformers/WireFilter.java#L34-L54)). **Load-bearing line:** "filtering" is "transform-to-null-when-rejected" plus "null is not forwarded."
- Self-explanation prompt (verbatim): "A filter is built as a transformer that returns the value to keep it and `null` to drop it. Given that, what single rule about how transformers treat a `null` return makes a filter work at all?"
- `canonical_answer`: The rule that a `null` returned by a transform function is not forwarded — so returning `null` for rejected items is exactly what drops them; without that rule a filter could not discard anything.
- `alternative_correct_answers`:
  - "Transformers don't forward `null`, so returning `null` = drop; that's the whole filter."
  - "`null` is swallowed by the transformer, which is what makes returning `null` reject an item."
  - "Because null outputs aren't passed on, a predicate-to-null function gates the stream."
- `followup` (restatement without naming the null rule): "You described the predicate — name the framework rule about `null` returns that turns 'return null' into 'drop this item.'"

**Move C — direct walk with cued check.** *Diagnosis tag:* the learner is fluent and needs only a verify on the splitter.
- Prompt (verbatim): "The orphan buffer's handler returns a `List<PlatformEvent>`, but downstream components are wired to receive a single `PlatformEvent`. What does `getSplitOutput()` give you, and how many tasks does one list of three events produce downstream?"
- `canonical_answer`: `getSplitOutput()` returns the output wire of a `WireListSplitter`, which emits one task per list element; a list of three events produces three downstream tasks, one per event ([WireListSplitter.java#L32-L48](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/transformers/WireListSplitter.java#L32-L48)).
- `alternative_correct_answers`:
  - "The split output wire — it fans a list into individual elements; three events → three tasks."
  - "One-event-at-a-time output; a 3-element list becomes 3 separate downstream tasks."
  - "A splitter's output that loops the list and forwards each element, so three items, three tasks."
- `followup` (if the learner says "three" without naming the per-element mechanism): "Right count — now say what the splitter actually does to the list to produce that number, so it generalizes to a list of any size."

## Contrasting cases material

The threshold concept is *declarative handoff*: the same `solderTo` call, the same kind of data crossing the edge, and the only thing that changes is the SolderType — a property of the *edge*, chosen at wiring time. The four cases are real edges from the codebase; in production (hard backpressure off) none of them blocks or drops, which is exactly what forces the comparison onto *intent and validation treatment* rather than runtime behavior.

- **Case 1 — `PUT` (data path).** Deduplicator output → signature-validator input ([DefaultEventIntakeModule.java#L115-L117](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java#L115-L117)). *Surface:* the default; ordinary work. Enrolled in backpressure — *would* block at capacity if the flag were on.
- **Case 2 — `INJECT` (control + cycle).** A clear command into the deduplicator ([DefaultEventIntakeModule.java#L112-L114](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java#L112-L114)), and the event-creator→event-intake feedback edge ([PlatformWiring.java#L129-L132](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java#L129-L132)). *Surface:* bypasses capacity, always accepted. One use is an out-of-band signal that must never be blocked or dropped; the other is breaking a cycle so the graph stays deadlock-safe.
- **Case 3 — `OFFER` (lossy).** The heartbeat → platform monitor ([PlatformWiring.java#L127](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java#L127)). *Surface:* drops at capacity rather than blocking — a missed beat is preferable to a backed-up queue.

**The deep invariant that survives the surface differences:** backpressure participation and handoff semantics belong to the *edge*, selected by its SolderType at wiring-assembly time and decoupled from what either component computes. *(This is the sibling of `c0-01`'s declarative concurrency — there the scheduler type declared a component's concurrency contract; here the SolderType declares an edge's handoff contract. `c0-03` adds when those contracts actually fire; `c0-04` adds how validation reads them.)*

## Completion problems

**Problem 1** *(small step blanked).*
- Statement (verbatim): "Component A produces events and component B consumes them on the ordinary data path; there is nothing out-of-band about this connection. Fill in the blank: `aWiring.getOutputWire().solderTo(bWiring.getInputWire(B::handle)____);` — does this edge need a SolderType argument at all, and if not, which type is it using?"
- Hint ladder:
  - Rung 1 (verbatim): "Open `OutputWire.java` and compare the one-argument `solderTo(InputWire)` with the two-argument `solderTo(InputWire, SolderType)` — read what the one-argument version delegates to."
  - Rung 2 (verbatim): "If you call `solderTo` with no SolderType, what type does it pick for you?"
  - Rung 3 (verbatim): "The one-argument `solderTo` calls the two-argument one with `SolderType.PUT`. So a plain data-path edge needs no argument — leaving it blank *is* choosing `PUT`."
  - Rung 4 (verbatim, gated on effort): "No argument needed — leave it blank. The one-argument `solderTo` defaults to `SolderType.PUT`, which is exactly right for a regular data path."
- `canonical_answer`: No SolderType argument is needed; the blank stays empty. The one-argument `solderTo` defaults to `SolderType.PUT`, the correct choice for an ordinary data-path edge ([OutputWire.java#L99-L101](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L99-L101)).
- `alternative_correct_answers`:
  - "Leave it blank — default is `PUT`, which is what a data path wants."
  - "No second argument; `solderTo(inputWire)` already means `PUT`."
- *Invariant exercised (exposition):* the default edge is `PUT`, the backpressure-participating data path.
- `followup` (if the learner adds `, PUT` explicitly without realizing it's the default): "That works — but is the argument necessary here? Say what the one-argument `solderTo` does on its own."

**Problem 2** *(more blanked — from a scenario).*
- Statement (verbatim): "A `clear` command must reach a component even when its queue is full of the very data the clear is meant to discard. Choose the SolderType for that edge, name the InputWire method it maps to, and say in one sentence why the default would be wrong here."
- Hint ladder:
  - Rung 1 (verbatim): "Read the `SolderType` javadoc in `SolderType.java` (all three values) and the `inject` javadoc in `InputWire.java`."
  - Rung 2 (verbatim): "Which SolderType bypasses the capacity limit, and which InputWire method does the `solderTo` switch route it to?"
  - Rung 3 (verbatim): "`INJECT` maps to `inject`, which is accepted even over capacity. The default `PUT` is wrong because, under hard backpressure, the clear would be held behind the backlog it exists to clear."
  - Rung 4 (verbatim, gated on effort): "`INJECT`, mapped to `InputWire.inject`. The default `PUT` would couple the clear to the data backlog — under hard backpressure it could be blocked by the congestion it is supposed to resolve — so the control signal must bypass capacity."
- `canonical_answer`: `INJECT`; it maps to `InputWire.inject`, which is accepted even when over capacity. The default `PUT` would be wrong because a control signal sent over a backpressure-participating edge can be held behind the very backlog it exists to clear ([SolderType.java#L9-L24](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/SolderType.java#L9-L24), [InputWire.java#L86-L95](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java#L86-L95)).
- `alternative_correct_answers`:
  - "`INJECT` → `inject`; `PUT` could let the clear be blocked by the backlog it's clearing."
  - "Use `INJECT` (the `inject` path) so the control signal bypasses capacity instead of queueing behind the data."
- *Invariant exercised (exposition):* out-of-band control signals are exempted from backpressure on the edge, via `INJECT`.
- `followup` (if the learner names `INJECT` but not the method or the why): "Good — now name the InputWire method it routes to, and say in one line what goes wrong with `PUT` here."

**Problem 3** *(most faded — produce the full answer from a scenario).*
- Statement (verbatim): "A component's handler returns a `List<PlatformEvent>`, but every downstream consumer is wired to handle a single `PlatformEvent`. Specify three things: (a) the transformer you'd put between them and where you obtain it, (b) how many downstream tasks a returned list of four events produces, and (c) which scheduler type that transformer runs on, and the one consequence of that choice for the work it does."
- Hint ladder:
  - Rung 1 (verbatim): "Read `WireListSplitter.java` (the constructor and the bound consumer) and `OutputWire.buildSplitter`; then look at how `ComponentWiring` surfaces it as `getSplitOutput()`."
  - Rung 2 (verbatim): "What does the splitter's bound consumer do with each element of the list, and what scheduler type does its constructor build?"
  - Rung 3 (verbatim): "`getSplitOutput()` returns a `WireListSplitter`'s output wire; its consumer loops the list and forwards each element, so four events → four tasks. The splitter builds a `DIRECT_THREADSAFE` scheduler, so it runs inline with no queue of its own."
  - Rung 4 (verbatim, gated on effort): "(a) a `WireListSplitter`, obtained as `componentWiring.getSplitOutput()`; (b) four tasks — one per element; (c) `DIRECT_THREADSAFE`, so it runs inline on the forwarding thread with no queue, and the per-element forwarding must therefore be cheap."
- `canonical_answer`: (a) a `WireListSplitter`, obtained via `componentWiring.getSplitOutput()` (built by `OutputWire.buildSplitter`); (b) four downstream tasks — one per list element; (c) `DIRECT_THREADSAFE`, which means it runs inline on the forwarding thread with no queue of its own, so the splitting/forwarding must stay cheap ([OutputWire.java#L227-L237](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L227-L237), [WireListSplitter.java#L32-L48](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/transformers/WireListSplitter.java#L32-L48)).
- `alternative_correct_answers`:
  - "`getSplitOutput()` / `WireListSplitter`; four tasks; runs on `DIRECT_THREADSAFE`, i.e. inline, no queue, so keep it fast."
  - "A list-splitter from `getSplitOutput()`; one task per element (four); `DIRECT_THREADSAFE` inline execution on the upstream thread."
- *Invariant exercised (exposition):* a list-splitter turns one list into one-task-per-element, running inline on `DIRECT_THREADSAFE`.
- `followup` (if the learner gives (a) and (b) but not the scheduler consequence): "You have the transformer and the count — now name its scheduler type and the one thing inline execution demands of it."

## Delta callout

`[TBD: delta-map/wiring-framework.md is not yet authored — the delta-map/ directory currently holds only README.md, so there is no current-versus-proposed entry for soldering, SolderTypes, or transformers to summarize.]` Status: **not started**. When that delta lands it will be linked here as `../../delta-map/wiring-framework.md`. Separately, the topic file's forward note describes a planned **module-API-level** backpressure that will sit *above* wire-level backpressure at the Consensus/Execution boundary — a `nextRound` pull throttling Consensus end to end ([consensus-execution-boundary.md](../../architecture/interfaces/consensus-execution-boundary.md)); that is a different layer from the per-edge SolderType this lesson covers, and it is followed in cluster B and the Pass 3 scenarios. *(Exposition; no learner-facing prompt.)*

## Transfer prompt

Forward framing (motivational only): the next lesson, `c0-03`, covers backpressure modes and the health-monitor wire — *when* the at-capacity reactions in this lesson actually fire, and where the soft backpressure comes from. The question below is answerable now, from the SolderType contracts and the cycle-breaker pattern you've already seen.

- Prompt (verbatim): "You saw the event-creator→event-intake feedback edge soldered with `INJECT` to keep that cycle deadlock-safe. Suppose you're adding a new edge from component A to component B, and B already feeds back into A — so your new edge closes a second cycle. Using only how `PUT` and `INJECT` differ as handoff contracts, which SolderType would you put on at least one edge of that cycle, and what property of the assembled graph are you protecting by doing so?"
- `canonical_answer`: Put `INJECT` on at least one edge of the cycle (as with the event-creator→event-intake edge). `INJECT` bypasses capacity, so a producer on that edge can never end up blocked waiting on a consumer that is transitively waiting on it — which keeps the assembled graph deadlock-safe. A cycle made entirely of `PUT` edges is the unsafe shape this avoids ([SolderType.java#L9-L24](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/SolderType.java#L9-L24), [PlatformWiring.java#L129-L132](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java#L129-L132)). *(Exactly when that blocking would occur — hard vs soft backpressure — is `c0-03`.)*
- `alternative_correct_answers`:
  - "`INJECT` on one edge of the cycle; you're keeping the graph deadlock-safe by ensuring not every edge in the loop can block."
  - "Flip one cycle edge to `INJECT` so no all-`PUT` loop exists — protecting against a circular wait/deadlock in the graph."
  - "At least one `INJECT` in the loop; it stops the cycle from being a chain of blockable `PUT` edges, which is the deadlock hazard."
- `followup` (if the learner says "`INJECT`" but names no graph property): "You've got the type — now name what about the *whole cycle* you're protecting, and what the dangerous version of that cycle looks like."

## Close-out retrieval

- Free-recall summary (verbatim): "In your own words: what does an edge's SolderType decide, and why — given that in production nothing blocks or drops — does the choice between `PUT`, `INJECT`, and `OFFER` still matter?"
  - `canonical_answer`: The SolderType picks the handoff path (`put`/`inject`/`offer`) and thereby declares the edge's backpressure contract — enrolled (`PUT`), exempt (`INJECT`), or lossy (`OFFER`). It is a property of the edge, chosen at wiring time, not of either component. Even with hard backpressure off, the choice still matters because it determines how graph validation treats the edge, documents the handoff's intent (data path vs out-of-band control vs disposable), keeps cycles deadlock-safe, and makes the graph behave correctly if the flag is ever enabled.
  - `alternative_correct_answers`:
    - "It declares the edge's role/contract — data, control/cycle-safe, or droppable — on the edge itself; it matters for validation treatment, intent, and cycle-safety even when runtime reactions are dormant."
    - "Which put-path the edge uses and so its backpressure participation; with the flag off it still drives graph validation, documents intent, and breaks cycles."
    - "A per-edge handoff declaration; the runtime block/drop is latent, but the type still governs how the graph is validated and kept deadlock-safe."
- Successive-relearning tags (exposition; added to the learner's relearning queue): threshold concept *declarative handoff* —
  - Day 1: recall the three SolderTypes and their contracts, plus the default-off caveat (none blocks/drops in production).
  - Day 3: apply it — pick a SolderType for a described edge (data path, control signal, cycle edge, heartbeat) and justify it.
  - ~2 weeks: state the invariant — backpressure participation and handoff semantics are a property of the edge, chosen at wiring time, the sibling of declarative concurrency.

## Open questions

- **`[TBD]` Delta callout.** `delta-map/wiring-framework.md` does not yet exist (the `delta-map/` directory holds only `README.md`), so there is no current-versus-proposed difference for soldering, SolderTypes, or transformers to summarize. Deferred until the wiring-framework delta-map entry is authored; the callout links the file once it lands.
