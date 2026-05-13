---
id: c0-01-task-schedulers
cluster: c0
title: Task schedulers and threading policies
pass: 2
prerequisites: []
kb_topics:
  - architecture/topics/wiring-framework.md
kb_concepts: []
kb_glossary_terms: []
kb_invariants: []
kb_deltas:
  - architecture/topics/wiring-framework.md  # inline "Delta vs. componentFramework.md" callout; no per-topic delta-map file yet
kb_decisions: []
learning_objectives:
  - Name the six TaskSchedulerType values and pair each with the concurrency property it guarantees (or refuses to guarantee).
  - Read a `model.schedulerBuilder(name)...build()` call site and predict which thread the bound handler will run on, whether consecutive tasks on the same scheduler are happens-before-ordered, and whether the scheduler has a queue at all.
  - Explain why the framework forbids more than one SEQUENTIAL upstream into a DIRECT scheduler, and why a CONCURRENT upstream into a DIRECT scheduler is illegal at all — i.e. why these are graph-validation rules and not runtime checks.
  - Distinguish "the scheduler owns a queue" from "queue depth is observable" — i.e. why `getUnprocessedTaskCount()` returns `COUNT_UNDEFINED` by default and what makes it report a real number.
  - Pick the right TaskSchedulerType for a candidate component given (a) whether its handler state is private to one component, (b) whether the handler is fast or slow, and (c) what its upstream/downstream wiring shape will be.
threshold_concepts:
  - Thread confinement via scheduler choice (the SEQUENTIAL contract is what lets business-logic state in this codebase go unsynchronized; this reframes how every later Pass 2 lesson should be read)
estimated_session_minutes: 40
status: drafted
last_verified_against: 1b26efc7698950c1f1d52c9e1a32213c8d422b12
---

# Task schedulers and threading policies

## Prerequisites

> None — first Pass 2 substrate lesson. Assumes only general JVM concurrency background (`ForkJoinPool`, happens-before, thread confinement as a synchronization-avoidance technique). Pass 1 orientation scenarios used the words "scheduler" and "queue" at role level; this lesson is where those words acquire their precise meaning, so a learner who skipped Pass 1 can still take this lesson cold.

## Incoming retrieval probes

None — first Pass 2 substrate lesson, no incoming probes.

## Misconception watchlist

Senior JVM and distributed-systems schemas import several mental models that *almost* fit a `TaskScheduler` and break in load-bearing places. Listen for these utterances and correct them as they surface.

- **"A scheduler is an `ExecutorService`."** It looks like one at the put-side (`buildInputWire(...).put(data)` resembles `executor.submit(...)`), but the contract is stronger and the API is narrower. A `SEQUENTIAL` scheduler guarantees **happens-before between consecutive tasks** ([`TaskSchedulerType.java#L9-L13`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L9-L13)); a generic `ExecutorService` does not. And the only legal handler shape is the one supplied to `bind(Function)` or `bindConsumer(Consumer)` — you do not `submit(Runnable)` arbitrary work to a scheduler. The mental model "scheduler = `ExecutorService` with one extra ordering knob" misses that the scheduler also owns the type-level data-flow contract via input and output wires.

- **"`CONCURRENT` is the parallel one; `SEQUENTIAL` is the serial one; everything else is a niche."** It sounds right because the names are reassuring. It misses that there are *two* "sequential" types — `SEQUENTIAL` (fork-join pool, serial coordination per scheduler, work-stealing across schedulers) and `SEQUENTIAL_THREAD` (one dedicated OS thread per scheduler) — and that the difference is observable when handlers do thread-local work or block. It also misses that `DIRECT` and `DIRECT_THREADSAFE` are not niches: every transformer and every filter built via `OutputWire.buildTransformer(...)` / `buildFilter(...)` runs on a `DIRECT_THREADSAFE` scheduler, so a typical consensus subgraph touches `DIRECT_THREADSAFE` constantly.

- **"`DIRECT` is synchronous, like calling a method."** True in part — the handler runs on the caller's thread, no queueing — but the *legality* rules around `DIRECT` are not the rules around method calls. A method can be called from any thread; a `DIRECT` scheduler cannot be reached from more than one `SEQUENTIAL` or `SEQUENTIAL_THREAD` upstream, and cannot be reached from a `CONCURRENT` upstream at all. The wiring framework enforces this by graph walk at assembly time, not by lock or barrier at runtime ([`TaskSchedulerType.java#L37-L50`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L37-L50)). Confusing "DIRECT is synchronous" with "DIRECT is just a method call" hides why this rule has to exist.

- **"Queue depth tells me how loaded the system is."** Only if you opted in. `TaskScheduler.getUnprocessedTaskCount()` returns `ObjectCounter.COUNT_UNDEFINED` unless one of three builder calls is made: `withUnhandledTaskMetricEnabled(true)`, `withUnhandledTaskCapacity(>0)`, or `withOnRamp(non-noop)` ([`TaskScheduler.java#L186-L201`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L186-L201)). This matters in Pass 2 / Cluster B: the health monitor watches schedulers that opt in to capacity tracking; schedulers that opt out are skipped at registration time and can never be reported as unhealthy. The framework choice "instrument only schedulers that asked to be instrumented" is deliberate and worth saying out loud.

- **"`NO_OP` is testing-only."** It is also the canonical way to disable a component without removing its wiring — flip the type in the builder and the wires stay connected, the data just gets discarded. The enum doc spells this out and links to the rationale ([`TaskSchedulerType.java#L62-L68`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L62-L68)). Treating `NO_OP` as test-only misses a production technique.

## Productive impasse

A `GossipWiring` is being constructed. The relevant lines are:

```java
scheduler = model.<Void>schedulerBuilder("gossip")
        .configure(configuration.getConfigData(GossipWiringConfig.class).gossip())
        .build();
```

That is the entirety of the scheduler construction for the gossip component ([`GossipWiring.java#L92-L94`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/GossipWiring.java#L92-L94)). The `.configure(...)` call pulls every other setting — type, capacity, flushing, squelching, exception handler, metric toggles — out of typed configuration. None of those settings is named in this snippet.

**Predict, thinking-aloud style, before we look at the configuration:**

1. There is no `.withType(...)` call here. What scheduler type does this gossip scheduler end up with? Why might the framework's authors have chosen *that* as the default, given that "gossip" is a component that has to (a) accept events from intake at one rate, (b) initiate syncs to peers at another rate, and (c) deliver received peer events into intake at yet another rate?

2. There is no `.withUnhandledTaskCapacity(...)` call here either. What is the capacity if you don't set one? And given that gossip's input wires include `events to gossip`, `event window`, and `health info` — three quite different traffic patterns multiplexed on the same scheduler — does that default sound right to you, or do you expect `.configure(...)` to be overriding it?

3. Many input wires share *one* scheduler in this snippet — `eventInput`, `eventWindowInput`, `startInput`, `stopInput`, `clearInput`, `systemHealthInput`, `platformStatusInput`, `pauseInput`, `resumeInput`, all built via `scheduler.buildInputWire(...)`. The handlers bound to those wires run on what — one thread? Several threads in parallel? Several threads but with happens-before across consecutive ones? *What's your gut here, and how confident are you?* — this is the question whose answer reorganises how every later Pass 2 lesson should be read, so it's worth pausing on.

**Reveal.** Default `TaskSchedulerType` is `SEQUENTIAL` ([`TaskSchedulerType.java#L5-L7`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L5-L7) — javadoc on the enum itself: "If unspecified, the default scheduler type is `SEQUENTIAL`."). Default `unhandledTaskCapacity` is `1` ([`TaskSchedulerBuilder.java#L42-L49`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerBuilder.java#L42-L49) — "Default is 1"). The `.configure(...)` call almost certainly overrides both via `GossipWiringConfig.gossip()`; that override is part of the gossip topic's content, not this lesson's. What this lesson cares about is the contract the default establishes: every handler bound to any of those nine input wires runs **on a fork-join pool, one task at a time across the entire scheduler, with happens-before between consecutive tasks**. There is exactly one logical thread of execution per scheduler at a time, regardless of how many input wires it owns.

**Consolidation.** If your prediction was "the scheduler is something like an executor and inputs run concurrently on a pool", this is the gap: the default mode of *every* scheduler in the consensus layer is serial-per-scheduler, and that is the entire reason business logic on the gossip component (and on every other component built this way) does not need synchronization on state that is private to the component. Thread confinement is engineered at scheduler granularity, not at handler granularity. If your prediction was "well, sequential, so it's a single-threaded actor", you're close, but `SEQUENTIAL` ≠ "the same thread every time" — it's "one task at a time with happens-before"; the *thread* that executes a given task is whichever fork-join worker the pool hands out. That distinction matters in two places: thread-local state (do not use it), and pre-existing libraries that demand a stable executing thread (use `SEQUENTIAL_THREAD` instead). Hold that — the next section names the load-bearing lines that make this true.

## Mechanism

### Pre-training

Three terms ground everything that follows. One sentence each; depth lives in the linked files.

- **TaskScheduler** — the wrapper around a queue + a thread-execution policy + a built-in primary `OutputWire`; you obtain one from the wiring model via `schedulerBuilder(name).....build()`. See the topic prose at [`architecture/topics/wiring-framework.md` § Core abstractions / TaskScheduler](../../architecture/topics/wiring-framework.md#taskscheduler--taskschedulertype).
- **TaskSchedulerType** — the six-value enum that chooses the threading policy. The default if unspecified is `SEQUENTIAL`.
- **TaskSchedulerBuilder** — the fluent builder; every knob (type, capacity, flushing, squelching, metric toggles, exception handler, hyperlink) sits on this interface, and `build()` produces a concrete `TaskScheduler<OUT>`.

Two further terms come up below and are pre-defined here so the integration chunks don't stall on them:

- **Primary output wire** — every scheduler owns exactly one; the bound handler's return value is forwarded onto it automatically.
- **Bound handler** — the `Function<I,O>` (or `Consumer<I>`) attached to an input wire via `bind` / `bindConsumer`; this is where the component's business logic lives. *This lesson does not cover binding* — that's `c0-02-wires-and-bindings`. Mentioned here because the threading policy you pick affects what the handler can assume about concurrent execution.

### Chunk 1 — The TaskScheduler shape

The `TaskScheduler` class header names the lifecycle the framework guarantees for every task ([`TaskScheduler.java#L22-L36`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L22-L36)):

1. Unscheduled — not yet passed to the scheduler.
2. Scheduled but not processed — accepted by the scheduler, handler not yet returned.
3. Processed — handler called and returned.

That three-state lifecycle is the basis of every queue-depth signal the rest of the system reads. **Load-bearing line** for the cluster: `getUnprocessedTaskCount()` ([`TaskScheduler.java#L186-L201`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L186-L201)) — the source of the queue-depth metric. Returns `ObjectCounter.COUNT_UNDEFINED` unless one of three builder calls opted in: `withUnhandledTaskMetricEnabled(true)`, `withUnhandledTaskCapacity(>0)`, or `withOnRamp(non-noop)`.

> Self-explanation prompt: *which invariant does the framework's choice to make `getUnprocessedTaskCount()` opt-in protect, and what would break in Cluster B (the health monitor) if it returned a real number for every scheduler regardless?*

Skim past, do not invest self-explanation on: `getInflightTaskCount()` (a different signal, only meaningful for `CONCURRENT`), `flush()` / `throwIfFlushDisabled()` (covered in `c0-04-wire-level-backpressure`), `startSquelching()` / `stopSquelching()` (covered in `c0-04`).

### Chunk 2 — The default: SEQUENTIAL

`SEQUENTIAL` is the default, and it is the type the vast majority of consensus-layer components use. The enum doc reads: *"Tasks are executed in a fork join pool one at a time in the order they were enqueued. There is a happens before relationship between each task."* ([`TaskSchedulerType.java#L9-L13`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L9-L13)).

The two clauses — one-at-a-time, and happens-before — are both load-bearing.

**Load-bearing line.** "One at a time in the order they were enqueued" — this is the rule that lets handler-owned state be read and written without synchronization. The component's invariant "my state is single-threaded" is implemented by the scheduler's invariant "I never start task N+1 until task N has returned".

**Load-bearing line.** "Happens before relationship between each task" — this is the rule that lets task N+1 *see* writes that task N did to handler-owned state without needing volatiles, locks, or `synchronized`. Without happens-before, single-threaded execution alone would not be enough; the JMM permits arbitrary reordering across thread boundaries when no synchronization edge exists. `SEQUENTIAL` provides that edge.

> Self-explanation prompt at the load-bearing pair: *if you stripped just the "happens-before" half of the contract (kept "one at a time", dropped happens-before), what consensus-layer invariant breaks first? Name a specific component whose handler-owned state would race.*

### Chunk 3 — The other five types, and what each is for

The enum source ([`TaskSchedulerType.java#L8-L69`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L8-L69)) carries the authoritative descriptions. Here's what the picker actually looks like:

- **`SEQUENTIAL_THREAD`** ([L14-L19](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L14-L19)) — same external semantics as `SEQUENTIAL` (one at a time, happens-before) but executes every task on a dedicated thread rather than dispatching to the fork-join pool. The enum doc notes the *implementation and performance characteristics are not identical*. You reach for this when something downstream of your handler is thread-affine (a library that holds thread-locals, a native handle, a JNI context, a profiler annotation that needs stable thread identity), and you want a stable executing thread per scheduler.

- **`CONCURRENT`** ([L20-L24](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L20-L24)) — fork-join pool, **no ordering guarantee, parallel execution permitted**. The opposite end of the spectrum from `SEQUENTIAL`: pick this when the handler is genuinely stateless or maintains only thread-safe state, and the workload is large enough that the per-component serialisation would be the bottleneck.

- **`DIRECT`** ([L25-L51](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L25-L51)) — executes on the caller's thread. No queue, no dispatch. The use case is "task is so small the scheduling overhead would dominate". This one comes with graph-walk legality rules — covered as a load-bearing chunk on its own below.

- **`DIRECT_THREADSAFE`** ([L52-L61](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L52-L61)) — like `DIRECT` but the handler is required to be threadsafe, which lifts the legality rules around upstream type. The enum doc notes the framework has *no enforcement mechanism* — the threadsafety is the author's responsibility. Every transformer and filter built via `OutputWire.buildTransformer(...)` / `buildFilter(...)` is a `DIRECT_THREADSAFE` scheduler under the hood, so this type is used heavily even where it isn't named.

- **`NO_OP`** ([L62-L68](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L62-L68)) — discards everything. *Useful for testing and debugging, or for when the ability to toggle a scheduler on/off via configuration is desired.* The enum doc itself links to the rationale; treat this as a production technique, not a test fixture.

`DiagramLegendCommand` happens to instantiate one scheduler of each non-`NO_OP` type in a row — useful as a concrete reference point if you want to see what each one looks like at the call site ([`DiagramLegendCommand.java#L37-L53`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-cli/src/main/java/org/hiero/consensus/pcli/DiagramLegendCommand.java#L37-L53)).

> Self-explanation prompt: *the framework offers two "serial with happens-before" types (`SEQUENTIAL`, `SEQUENTIAL_THREAD`) and two "no-queue, execute on caller" types (`DIRECT`, `DIRECT_THREADSAFE`). Why split each pair? — i.e. which invariant does the second member of each pair preserve that the first one does not, and at what cost?*

### Chunk 4 — DIRECT's graph-walk legality rules

Load-bearing chunk for senior engineers' transfer of `synchronized`/lock-based intuition into this codebase. The enum doc spells the rules out, and they are enforced statically at assembly time by the wiring model, not at runtime ([`TaskSchedulerType.java#L37-L50`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L37-L50)):

1. Walk the directed graph of schedulers and wires.
2. Follow edges into `DIRECT` and `DIRECT_THREADSAFE` vertices; stop at `SEQUENTIAL`, `SEQUENTIAL_THREAD`, `CONCURRENT`.
3. A `DIRECT` vertex reachable from a `CONCURRENT` vertex → **illegal**.
4. A `DIRECT` vertex reachable from more than one `SEQUENTIAL` or `SEQUENTIAL_THREAD` vertex → **illegal**.

> Self-explanation prompt at the load-bearing rule: *rule (4) — a `DIRECT` scheduler reachable from more than one `SEQUENTIAL` upstream is forbidden. Which property of `SEQUENTIAL`'s contract does this preserve? Concretely: if two `SEQUENTIAL` upstreams could both reach the same `DIRECT` scheduler, what could a handler bound to that `DIRECT` scheduler stop assuming about its own state?*

Note that these are graph-validation rules and produce errors when `WiringModel`'s graph-validation methods (`checkForIllegalDirectSchedulerUsage`, etc.) run — that wire-up is `c0-05-wiring-model-and-health-wire`. Here, what matters is the conceptual move: the framework moves the synchronization argument *off the runtime path and onto the assembly graph*. You do not lock; you do not declare volatile; you arrange the graph so the JMM-level reasoning about "could this state be touched by two threads" is decided at validation time. That is the threshold-concept payoff.

### Chunk 5 — How the type meets the capacity

`TaskSchedulerBuilder` ([`TaskSchedulerBuilder.java#L19-L192`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerBuilder.java#L19-L192)) is the surface every scheduler is built through. Two methods of it interact with the type choice and matter to this lesson; the rest are deferred to later c0 lessons.

- **`withType(TaskSchedulerType)`** ([`TaskSchedulerBuilder.java#L32-L40`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerBuilder.java#L32-L40)) — the javadoc explicitly says *"Alters the semantics of the scheduler (i.e. this is not just an internal implementation detail)."* That comment is the framework authors warning you that swapping types is not a tuning knob; it changes contract.

- **`withUnhandledTaskCapacity(long)`** ([`TaskSchedulerBuilder.java#L42-L49`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerBuilder.java#L42-L49)) — the maximum number of permitted unhandled tasks. **Default is 1.** A `>0` capacity opts the scheduler into queue-depth tracking; the special value `UNLIMITED_CAPACITY = -1` ([`TaskSchedulerBuilder.java#L21`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerBuilder.java#L21)) means no cap and — by the rule in chunk 1 — no queue-depth metric either (so the health monitor will skip this scheduler at registration time).

Bookkeeping (not load-bearing here): `configure(TaskSchedulerConfiguration)` ([L23-L30](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerBuilder.java#L23-L30)) — pulls all knobs from typed config in one call, used heavily in `GossipWiring` and similar. `withFlushingEnabled` / `withSquelchingEnabled` — deferred to `c0-04`. `withOnRamp` / `withOffRamp` — deferred to `c0-04` (spanning-multiple-schedulers backpressure). `withPool` / `withUncaughtExceptionHandler` — operational knobs, not lesson-load-bearing.

> Self-explanation prompt: *the default capacity is 1, but `UNLIMITED_CAPACITY = -1` is also available. Pick one consensus-layer component (gossip, event intake, hashgraph engine, signed-state hasher) and decide which side of that choice you'd put it on, and why. Pay attention to whether you want this scheduler to be observable by the health monitor — that's a downstream consequence of the choice.*

## Contrasting cases

This lesson establishes the threshold concept "thread confinement via scheduler choice", so three contrasting cases follow. Walk through them with explicit comparison prompts; do not let the learner skip to the next section without naming the invariant that survives.

### Case A — `SEQUENTIAL` vs `java.util.concurrent.ExecutorService`

Both accept tasks and run them on a pool. The differences:

|                          | `SEQUENTIAL` `TaskScheduler` | generic `ExecutorService` |
|--------------------------|------------------------------|---------------------------|
| Tasks at a time          | exactly one                  | up to pool parallelism    |
| Order                    | enqueue order                | unordered                 |
| Happens-before across consecutive submissions | yes                          | no (without explicit synchronization) |
| Handler shape            | `Function<I,O>` / `Consumer<I>` bound at assembly | any `Runnable` / `Callable` per submit |
| Capacity model           | declared at build, observable via opt-in | per-executor, depends on queue choice |

> Comparison prompt: *the surface difference is "ordering and happens-before"; what is the deep invariant that the codebase relies on, and what would change about how components are written if you replaced every `SEQUENTIAL` scheduler with an unordered `ExecutorService`?*

### Case B — `SEQUENTIAL` vs `SEQUENTIAL_THREAD`

Same external contract. Different internal mechanism — fork-join pool with serial-execution coordination, versus a dedicated OS thread per scheduler.

- Performance characteristics differ. `SEQUENTIAL` benefits from work-stealing across many lightly-loaded schedulers; `SEQUENTIAL_THREAD` does not.
- Thread identity differs. `SEQUENTIAL` rotates through fork-join workers; `SEQUENTIAL_THREAD` pins to one OS thread for the scheduler's lifetime.
- Thread-local state, JNI contexts, profiler thread-tagging, and other thread-affine machinery behave differently. `SEQUENTIAL` is wrong for any of these; `SEQUENTIAL_THREAD` is right.

> Comparison prompt: *both types claim to be "serial with happens-before". What invariant survives across both? — and what would you actually observe at runtime if you swapped one for the other in a component whose handler does not touch thread-affine state at all?*

### Case C — `DIRECT` vs `SEQUENTIAL`

Both serialise tasks; neither permits parallel execution of the bound handler. The differences:

|                          | `DIRECT` | `SEQUENTIAL` |
|--------------------------|----------|-------------|
| Queue                    | none     | yes (declared capacity) |
| Executing thread         | the calling thread | a fork-join worker |
| Upstream constraints     | ≤ 1 `SEQUENTIAL`/`SEQUENTIAL_THREAD` upstream; no `CONCURRENT` upstream | any |
| Observable queue depth   | no       | yes (with opt-in) |
| Health-monitor visible   | no       | yes (with opt-in capacity) |
| When you reach for it    | tiny, scheduling overhead dominates work | every steady-state business-logic component |

> Comparison prompt: *`DIRECT` and `SEQUENTIAL` both deliver "the handler runs serially". What invariant survives that surface similarity? — name it, and then name a specific consensus-layer component where you'd be wrong to swap one for the other, and explain which property of the wrong choice would break.*

## Completion problems

Three problems, with scaffolding fading. Each ships with the hint ladder the tutor escalates through.

### Problem 1 — Reading a scheduler call site (scaffolded)

A new component is being added with this assembly:

```java
final TaskScheduler<MyOutput> sched = model.<MyOutput>schedulerBuilder("myComponent")
        .withUnhandledTaskCapacity(1_000)
        .withUnhandledTaskMetricEnabled(true)
        .build();
```

State the following without running the code:

(a) Which `TaskSchedulerType` does this scheduler get?
(b) Will `sched.getUnprocessedTaskCount()` return real numbers, or `COUNT_UNDEFINED`?
(c) On how many threads will any single bound handler ever execute concurrently?
(d) Will the health monitor be able to observe this scheduler's queue depth?

**Hint ladder** the tutor escalates through if the learner is stuck:

1. *"Look at `TaskSchedulerType`'s class-level javadoc. What does it say about the default?"* → points to [L5-L7](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L5-L7).
2. *"For (b) and (d), re-read `TaskScheduler.getUnprocessedTaskCount()` and list the three opt-ins; check which the snippet hits."* → [`TaskScheduler.java#L186-L201`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L186-L201).
3. *"For (c), what does `SEQUENTIAL`'s contract say about how many tasks run at once?"*
4. **Full answer:** (a) `SEQUENTIAL` — default per the enum class javadoc. (b) Real numbers — the call hits both `withUnhandledTaskCapacity(>0)` *and* `withUnhandledTaskMetricEnabled(true)`; either alone would suffice. (c) One — `SEQUENTIAL` is one-at-a-time. (d) Yes — capacity is declared and `>0`, so the scheduler is eligible for health-monitor registration; the monitor watches schedulers that report unprocessed-task counts.

**Invariant exercised:** the `SEQUENTIAL` contract (one-at-a-time + happens-before) is the default that every reader of consensus-layer wiring assumes when no `.withType(...)` appears. The capacity declaration is what makes the scheduler observable to the rest of the system.

### Problem 2 — Picking a type from requirements (medium scaffolding)

A new transformer is being designed. The handler is a pure `Function<Hash, Hash>` — given an input hash, returns a derived hash. Many input wires across the graph will solder their outputs into this transformer's input wire. The transformer has no state. Throughput is high enough that serialising every call through one thread is likely to be a bottleneck.

(a) Which `TaskSchedulerType` should this transformer use?
(b) If a `CONCURRENT` upstream is one of the soldered inputs, is your choice still legal? What if the soldered inputs include several `SEQUENTIAL` upstreams?
(c) What changes about your reasoning if the handler started maintaining a small mutable cache?

**Hint ladder:**

1. *"List the types whose contract permits the handler to run on multiple threads at once."*
2. *"Now check the legality rules for `DIRECT` vs `DIRECT_THREADSAFE` when multiple upstreams are involved."* → [`TaskSchedulerType.java#L37-L60`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L37-L60).
3. *"`OutputWire.buildTransformer(...)` already does this for you — what type does it pick under the hood, and what does that tell you about the convention for stateless transformations?"* → [`OutputWire.java` around L183](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L180-L195), [`WireTransformer.java#L44`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/transformers/WireTransformer.java#L44).
4. **Full answer:** (a) `DIRECT_THREADSAFE`. The handler is stateless and pure, so threadsafety is free; running on the caller's thread saves the scheduling overhead; the lifted legality rules mean any number of `SEQUENTIAL` or `CONCURRENT` upstreams are allowed. (b) Yes — `DIRECT_THREADSAFE` lifts the constraints `DIRECT` imposes. `DIRECT` would be illegal under a `CONCURRENT` upstream and illegal under multiple `SEQUENTIAL` upstreams. (c) The cache makes the handler stateful; threadsafety is no longer free. Either make the cache threadsafe (`ConcurrentHashMap`, atomics, etc.) and keep `DIRECT_THREADSAFE`, or switch to `CONCURRENT` and accept the queue overhead, or — if the cache hit rate is high enough that single-thread serialisation pays — `SEQUENTIAL`.

**Invariant exercised:** the type choice and the handler's thread-safety contract are *the same choice expressed twice*. The legality rules ensure that if you say "I'll run on the caller's thread without synchronization", the graph cannot put you in a position where that promise is unmeetable.

### Problem 3 — From scratch, light scaffolding

Sketch the `schedulerBuilder(...)` call for a hypothetical `signedStateHasher` component with the following properties:

- Receives signed-state envelopes on a single input wire.
- Hashes each envelope; the work is CPU-bound and non-trivial (~100ms per item).
- State during hashing is private to one call (no cross-call mutation).
- The component must be visible to the health monitor — queue depth, capacity, and unhealthy-duration must all be observable.
- A capacity of 8 is desired so that brief bursts of input queue without stalling the producer.
- Throughput is the target; bottlenecking on one thread is unacceptable.

Write the call. Justify each `.with...(...)` chosen. State which `TaskSchedulerType` you picked and what contract that gives the handler.

**Hint ladder:**

1. *"Walk the requirements one at a time. Which contradicts which? — pay attention to 'visible to the health monitor' and 'throughput is the target'."*
2. *"Re-read the `CONCURRENT` contract ([L20-L24](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L20-L24)) and the capacity opt-in mechanics ([`TaskScheduler.java#L186-L201`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L186-L201)). Is the capacity setting compatible with `CONCURRENT`? Why or why not?"*
3. *"`getInflightTaskCount()` ([`TaskScheduler.java#L204-L212`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L204-L212)) — when is that meaningful? When is it 0?"*
4. **Full answer:**

   ```java
   final TaskScheduler<HashedState> sched = model.<HashedState>schedulerBuilder("signedStateHasher")
           .withType(TaskSchedulerType.CONCURRENT)
           .withUnhandledTaskCapacity(8)
           .withUnhandledTaskMetricEnabled(true)
           .withInflightTaskMetricEnabled(true)
           .build();
   ```

   `CONCURRENT` because hashing is CPU-bound and state is per-call, so parallel execution is safe and beneficial; one-at-a-time would bottleneck on a single core. `withUnhandledTaskCapacity(8)` declares the cap *and* opts the scheduler into unprocessed-task tracking, which is what the health monitor reads. `withUnhandledTaskMetricEnabled(true)` exposes the same number to the metrics subsystem. `withInflightTaskMetricEnabled(true)` is allowed *only* for `CONCURRENT` schedulers (see [`TaskSchedulerBuilder.java#L124-L132`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerBuilder.java#L124-L132) — the javadoc explicitly says "Only supported for CONCURRENT schedulers"), and it captures how many tasks are *actually executing* in parallel — useful when diagnosing whether the pool is the bottleneck or the queue is.

**Invariant exercised:** `CONCURRENT` is the only choice where queue depth and in-flight task count are *both* informative — for `SEQUENTIAL` the in-flight count is always 0 or 1 ([`TaskScheduler.java#L204-L212`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L204-L212)). The capacity opt-in is what makes the scheduler legible to the health monitor; without it, the monitor cannot see this component.

## Delta callout

The proposed-redesign source document (`platform-sdk/docs/components/componentFramework.md`) describes a smaller surface: only `SEQUENTIAL`, `DIRECT`, and `CONCURRENT`, and a single-step construction `TaskScheduler<String> fooTaskScheduler = WiringModel.schedulerBuilder("Foo");` that returns the scheduler directly. Current code differs on three points: (a) the type enum has six values, not three — `SEQUENTIAL_THREAD`, `DIRECT_THREADSAFE`, and `NO_OP` are absent in the source doc; (b) `WiringModel` is an interface and `schedulerBuilder(...)` is an instance method returning a `TaskSchedulerBuilder<O>`, not a `TaskScheduler<O>`; (c) the fluent builder always terminates in `.build()`. The delta status is **divergent and largely irrelevant** — current code is what every component uses; the source-doc shape is historical.

The delta is described inline at [`architecture/topics/wiring-framework.md` § Delta vs. componentFramework.md](../../architecture/topics/wiring-framework.md#taskscheduler--taskschedulertype) — no per-topic `delta-map/wiring-framework.md` exists yet. [TBD: delta-map entry once `delta-map/` populates.]

## Transfer prompt

The health monitor (Cluster B, lessons `b-01` and `b-02`) reads `getUnprocessedTaskCount()` from each watched scheduler on a heartbeat and turns the largest continuously-unhealthy duration into a wire signal that four reaction sites consume. Predict, before that cluster:

- Given today's lesson, which schedulers in a consensus-layer process can the health monitor see at all, and which are invisible to it by design? Phrase your answer in terms of the opt-ins.
- A scheduler declared `UNLIMITED_CAPACITY` — what does the health monitor do with it? Why is that the right behaviour?
- A scheduler declared with `withUnhandledTaskCapacity(1_000_000)` — the cap is huge, but the metric is on. Is this scheduler observable? Useful? What would the health monitor signal look like if the scheduler is mildly backed up at ~100 tasks for a long time?

Hold those predictions for `b-01`; the cluster will confirm or correct them.

## Close-out retrieval

**Free-recall summary.** *"In your own words: explain why a consensus-layer component author rarely needs to write a `synchronized` block on handler-owned state, and which scheduler-level invariant makes that possible. Name a specific scheduler type whose contract would *not* support that pattern, and the type the author should reach for instead."*

Canonical answer for the tutor to consolidate against: the `SEQUENTIAL` (or `SEQUENTIAL_THREAD`) contract — one task at a time with happens-before — is what makes single-threaded handler-owned state safe without synchronization. `CONCURRENT` would not support that pattern; an author who needs parallel handling either makes the state threadsafe and stays on `CONCURRENT`, or steps back to `SEQUENTIAL` and accepts the serialisation. `DIRECT_THREADSAFE` does not support unsynchronized handler-owned state either — it relies on the handler being threadsafe.

**Successive-relearning tags.** Threshold concept "thread confinement via scheduler choice" — probe at day 1 (recall the `SEQUENTIAL` contract and the synchronization-avoidance argument), day 3 (have the learner read a fresh scheduler call site and predict the threading contract), and ~2 weeks (have the learner predict what would change if a `CONCURRENT` scheduler in a Pass 2 lesson were swapped for `SEQUENTIAL`, and vice versa).

## Open questions

- [TBD: which `TaskSchedulerType` is canonical for new consensus-layer components, or is the choice always topic-specific?] — Flagged in `architecture/topics/wiring-framework.md` § TaskScheduler / TaskSchedulerType. The default is `SEQUENTIAL` and the topic prose says "the vast majority of consensus-layer components use it", but a documented selection policy would replace the "vast majority" hedge with a rule.
- [TBD: per-topic `delta-map/wiring-framework.md` file does not exist; the delta is captured inline only. Reviewer to decide whether to lift it into the delta-map directory.]
- [TBD: glossary entries for "wire", "scheduler", "soldering", and "transformer" are absent from `platform-sdk/docs/hashgraphGlossary.md` per the topic file's own note. This lesson's `kb_glossary_terms` is empty as a result; future glossary additions should be reflected in the frontmatter.]
- [TBD: `invariants.md` does not yet exist (KB README marks it pending). The frontmatter `kb_invariants` is empty; once the catalog populates, the SEQUENTIAL-handler-state-confinement claim probably warrants an INV-NNN that this lesson can cite directly.]
