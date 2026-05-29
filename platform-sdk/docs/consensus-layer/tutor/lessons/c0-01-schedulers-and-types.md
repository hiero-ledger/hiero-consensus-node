---
id: c0-01-schedulers-and-types
cluster: c0
title: "Task schedulers and the six scheduler types"
pass: 2
prerequisites: []
kb_topics_touched:
  - architecture/topics/wiring-framework.md
kb_concepts: []
kb_glossary_terms:
  - Scheduler
  - Wire
  - Soldering
  - Transformer
  - Squelching
kb_invariants: []
kb_deltas:
  - delta-map/wiring-framework.md
kb_decisions: []
learning_objectives:
  - Describe a task scheduler as the unit that owns a queue, a threading policy, and a primary output wire, and explain why a component's handler logic is written independently of that policy.
  - Name the six TaskSchedulerType values and state the threading and ordering guarantee each gives the handler that runs on it.
  - Choose an appropriate scheduler type for a component from its ordering and concurrency requirements.
  - Walk the construct-solder-bind assembly pattern and explain what binding resolves and why it is deferred.
  - Explain the unhandled-task (on-ramp) count and how flush() and squelching drain a scheduler.
threshold_concepts:
  - "Declarative concurrency: a component's threading and ordering contract is selected by its scheduler type at wiring-assembly time, not written into the handler."
estimated_session_minutes: 35
status: drafted
last_verified_against: eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2
---

# Task schedulers and the six scheduler types

## Prerequisites

> None — this lesson assumes only general distributed-systems background (threads, queues, happens-before, thread-safety). It is the first mechanism lesson in the curriculum and teaches the substrate everything else is built on; the Pass 1 orientation scenarios planted a role-level sketch of components wired into a graph, but nothing here depends on that at mechanism altitude.

## Incoming retrieval probes

None at mechanism altitude. This is the first Pass 2 lesson, so there is no prior mechanism for the tutor to consolidate against. The Pass 1 scenarios (`pass1-01` … `pass1-04`) referred to components as wired boxes at role level; if the learner reaches for that sketch the tutor can affirm it, but there is no canonical prior-lesson statement to retrieve here.

## Misconception watchlist

- **Concurrency control lives inside the component.** *(Over-generalization from codebases where locks and `synchronized` are woven into business logic.)* Sounds like: "so where does the handler lock its state?" or "does this method need a `synchronized` block?" Correction, in line: a `SEQUENTIAL` or `SEQUENTIAL_THREAD` scheduler runs one task at a time with a happens-before relationship between tasks, so a handler on such a scheduler needs no locks for its own state; thread-safety is required only for `CONCURRENT` and `DIRECT_THREADSAFE`. The contract is set by the scheduler *type* ([`TaskSchedulerType.java`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java)), not written into the handler. This is the lesson's threshold concept seen from the wrong side.
- **"Sequential" means a dedicated thread; "concurrent" just means faster.** Sounds like: "`SEQUENTIAL` must own its own thread," or treating `CONCURRENT` as a drop-in speed-up. Correction: `SEQUENTIAL` runs on the shared fork-join pool one task at a time — `SEQUENTIAL_THREAD` is the dedicated-thread variant ([`TaskSchedulerType.java#L9-L19`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L9-L19)). `CONCURRENT` trades the ordering guarantee for parallelism; it is correct only where the handler is thread-safe and order does not matter, not "the fast `SEQUENTIAL`."
- **`DIRECT` is a free optimization you can drop in anywhere.** Sounds like: "I'll make it `DIRECT` to skip the queue." Correction: `DIRECT` runs on the caller's thread with no queue, and the framework restricts how it may be wired — only one logical sequential sender, with concurrent senders forbidden ([`TaskSchedulerType.java#L25-L51`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L25-L51)). It is checked, not free.
- **A scheduler's capacity blocks producers by default.** Sounds like: "if its queue fills, the upstream blocks." Correction: in production the unhandled-task count is a health *threshold*, not an enforced limit — what happens at capacity is backpressure, configured separately and covered in `c0-03`. Keep this lesson's claims to *what the count measures*, not what it enforces.

## Mechanism

The wiring framework is the substrate the consensus layer is built on; it owns concurrency, queueing, and graph validation so that per-topic components do not ([wiring-framework.md](../../architecture/topics/wiring-framework.md)). This lesson covers the first primitive — the task scheduler and its six types — and the pattern by which a component is mounted on one. Soldering wires between schedulers is the next lesson (`c0-02`); what a scheduler does when its backlog reaches capacity is `c0-03`; assembling and validating the whole graph is `c0-04`.

**Pre-training — terms this lesson integrates** (definitions live in the glossary and the topic file; named here to set vocabulary):

- **Scheduler** — a wiring primitive (`TaskScheduler`) that owns a queue, a threading policy, and a primary output wire; a component runs as a *handler* on a scheduler ([glossary: Scheduler](../../glossary.md#scheduler)).
- **Wire** — a typed connection: an *output wire* is a component's data source, an *input wire* its entry point ([glossary: Wire](../../glossary.md#wire)). Their mechanics are `c0-02`; this lesson uses only "the scheduler owns one primary output wire."
- **Soldering** — joining an output wire to an input wire ([glossary: Soldering](../../glossary.md#soldering)). Named as step 2 of assembly below; the handoff semantics are `c0-02`.
- **Transformer** — in-line reshaping of data along a wire ([glossary: Transformer](../../glossary.md#transformer)). Named for completeness; `c0-02`.
- **Squelching** — a scheduler's "drop new tasks" toggle, paired with `flush()` to drain a backlog ([glossary: Squelching](../../glossary.md#squelching)). Introduced in the last chunk.
- **`ComponentWiring`** — the standard wrapper that pairs a scheduler with a component and creates its input/output wires from method references ([`ComponentWiring.java`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java)).
- **`TaskSchedulerType`** — the enum that selects the threading policy ([`TaskSchedulerType.java`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java)).
- **`TaskSchedulerConfiguration`** — the per-component config record carrying the type, capacity, flushing, squelching, and metric options ([`TaskSchedulerConfiguration.java`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerConfiguration.java)).

### Chunk 1 — What a scheduler is `{moment: m1-what-is-a-scheduler}`

A `TaskScheduler<OUT>` owns three things: a queue of pending tasks, a threading/execution policy, and a built-in primary `OutputWire<OUT>` ([wiring-framework.md](../../architecture/topics/wiring-framework.md), [`TaskScheduler.java#L22-L36`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L22-L36)). A component's method is the *handler*. The handler is plain logic — it takes an input and returns a value; the scheduler decides *when* and *on which thread* the handler runs, and forwards the handler's return value onto the primary output wire automatically ([`TaskScheduler.java#L116-L126`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L116-L126)).

**Load-bearing line (signal):** the split between *what the handler computes* and *when and where it runs* is the seam the whole framework turns on — the handler is written once, and its execution policy is chosen elsewhere. The rest of this lesson is what "elsewhere" means.

(A scheduler can also have secondary output wires, pushed explicitly by the component rather than fed from the return value — a rare case, [`TaskScheduler.java#L128-L145`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L128-L145). The default path is the primary wire.)

### Chunk 2 — The six scheduler types `{moment: m2-six-types}`

`TaskSchedulerType` chooses the threading policy; six values exist, and the enum's javadoc is the authoritative description ([`TaskSchedulerType.java#L8-L69`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L8-L69)):

- **`SEQUENTIAL`** — fork-join pool, one task at a time in enqueue order, with a happens-before relationship between consecutive tasks ([`#L9-L13`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L9-L13)). The handler is effectively single-threaded: it may read and write its own state without synchronization.
- **`SEQUENTIAL_THREAD`** — the same one-at-a-time, in-order, happens-before guarantee, but on a dedicated thread rather than the shared pool; same semantics, different performance profile ([`#L14-L19`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L14-L19)).
- **`CONCURRENT`** — fork-join pool, tasks may run in parallel, **ordering is not guaranteed** ([`#L20-L24`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L20-L24)). The handler **must** be thread-safe.
- **`DIRECT`** — runs immediately on the caller's thread, no queue; useful when tasks are tiny and not worth scheduling overhead. The framework restricts the wiring: only one logical sequential sender, and concurrent senders are forbidden ([`#L25-L51`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L25-L51)). *(The graph-walk that enforces this is `c0-04`.)*
- **`DIRECT_THREADSAFE`** — like `DIRECT`, but the handler is required to be thread-safe, which lifts `DIRECT`'s single-sender restriction; the framework does not verify the thread-safety ([`#L52-L61`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L52-L61)).
- **`NO_OP`** — discards everything; wires into and out of it are effectively non-existent at runtime. Used to toggle a component off via configuration without removing its wiring ([`#L62-L68`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L62-L68)).

**Load-bearing lines (signal):** the *ordering guarantee* (happens-before for the two `SEQUENTIAL` types) and the *thread-safety obligation* (`CONCURRENT`, `DIRECT_THREADSAFE`) are the two axes that distinguish the types and the two places a wrong choice causes a race or a lost ordering.

If the type is left unspecified, the framework builder defaults to `SEQUENTIAL` ([`TaskSchedulerType.java#L4-L7`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L4-L7), [`AbstractTaskSchedulerBuilder.java#L42`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/internal/AbstractTaskSchedulerBuilder.java#L42)). In practice consensus-layer components always state the type explicitly in their `TaskSchedulerConfiguration`, so the default is rarely the operative value; there is no preferred type for new components — the choice is made per component ([wiring-framework.md](../../architecture/topics/wiring-framework.md)).

### Chunk 3 — Assembly: construct, solder, bind `{moment: m3-assembly}`

A component is mounted on a scheduler in three steps ([wiring-framework.md](../../architecture/topics/wiring-framework.md)). The example below is from the event-intake module's `initialize`, representative throughout the consensus layer:

**Step 1 — construct a `ComponentWiring` from the component's interface and its config.** The wrapper builds the scheduler for you, taking the type and capacity from the supplied `TaskSchedulerConfiguration`:

```java
// consensus-event-intake-impl :: DefaultEventIntakeModule.initialize
this.eventDeduplicatorWiring =
        new ComponentWiring<>(model, EventDeduplicator.class, wiringConfig.eventDeduplicator());
```

The first argument is the component's **interface** (`EventDeduplicator.class`), not an instance — `ComponentWiring` requires an interface so it can resolve handler method references reflectively and create input wires before the implementation exists ([`ComponentWiring.java#L113-L156`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L113-L156)). An input wire is obtained with `getInputWire(MethodReference)` ([`#L176`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L176)); the primary output wire is `getOutputWire()` ([`#L163-L166`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L163-L166)).

**Step 2 — solder** the component's wires to its neighbours. *(Covered in `c0-02`; named here only so the three-step shape is complete.)*

**Step 3 — bind the component instance.** Binding resolves the method references attached during soldering to the actual instance; from this point the wiring is live:

```java
// consensus-event-intake-impl :: DefaultEventIntakeModule.initialize
eventDeduplicatorWiring.bind(eventDeduplicator);
```

([`ComponentWiring.java#L662`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L662); call site [`DefaultEventIntakeModule.java#L170`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java#L170).)

**Load-bearing line (signal):** binding is *deferred* — the graph's topology is described and connected before any component instance exists, and the instances are supplied later. That deferral is what lets the whole graph be assembled (and, in `c0-04`, validated) independently of constructing the components.

### Chunk 4 — Backlog, draining, and exceptions

A monitored scheduler keeps a single **unhandled-task count** (the "on-ramp" counter): a task is counted from the moment it enters the scheduler until its handler has been called *and has returned* — the task's lifecycle is unscheduled → scheduled-but-not-processed → processed ([`TaskScheduler.java#L25-L32`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L25-L32), [`#L186-L201`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L186-L201)). It measures backlog; what happens when backlog reaches the configured capacity is backpressure, deferred to `c0-03`.

Two operations drain a scheduler. `flush()` blocks until the scheduler's in-flight work has been processed; it is opt-in, and calling it on a scheduler not built with flushing enabled throws `UnsupportedOperationException` ([`TaskScheduler.java#L222-L246`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L222-L246)). **Squelching** is the scheduler's "drop new tasks" toggle — `startSquelching()` / `stopSquelching()` ([`#L248-L266`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L248-L266)) — paired with `flush()` to drain a backlog during operations such as reconnect ([glossary: Squelching](../../glossary.md#squelching)). The orchestration of squelch-then-flush across a whole module is `c0-03`/`c0-syn`.

The default uncaught-exception handler logs the exception at ERROR via log4j and does not rethrow, so the scheduler proceeds with the next task; rethrowing and no-op handlers exist as alternatives ([`ExceptionHandlers.java#L72-L82`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/ExceptionHandlers.java#L72-L82), [`#L15-L25`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/ExceptionHandlers.java#L15-L25)). `DIRECT` and `DIRECT_THREADSAFE` schedulers, having no queue, invoke the handler on the calling thread but behave the same otherwise ([wiring-framework.md](../../architecture/topics/wiring-framework.md)).

## Engagement moves

### Moment `m1-what-is-a-scheduler`

*Why load-bearing (exposition):* this is where the handler/scheduler split — the seed of the threshold concept — is first put to the learner; getting it here makes the six types land as variations on one idea rather than six facts.

**Move A — direct walk with cued check.** *Diagnosis tag:* the learner is fluent on queues and executors and just needs a verify before moving on.
- Prompt (verbatim): "A component's handler is a plain method that takes an input and returns a value. Once the handler returns, where does that returned value go — and what part of the system decided which thread the handler ran on?"
- `canonical_answer`: The returned value is forwarded automatically onto the scheduler's primary output wire; the scheduler — specifically its threading policy — decided the thread, not the handler.
- `alternative_correct_answers`:
  - "It goes to the scheduler's primary/default output wire; the scheduler's type chose the thread."
  - "Onto `getOutputWire()`'s wire; the threading policy of the scheduler picked the thread."
  - "The scheduler pushes the return onto its output wire; the scheduler owns the thread choice, the handler doesn't."
- `followup` (if the learner names where the value goes but not what controls the thread): "You've got where the value goes. Now point at the part of the wiring — not the handler — that controls which thread the handler ran on."

**Move B — worked example with self-explanation.** *Diagnosis tag:* the learner is new to the handler/scheduler separation or hesitates on what a scheduler owns.
- Walk (exposition): the scheduler owns the queue, the threading policy, and the primary output wire; the handler is just a method that returns a value, and the scheduler forwards that return onward ([`TaskScheduler.java#L116-L126`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L116-L126)). **Load-bearing line:** the handler contains no thread or queue logic of its own.
- Self-explanation prompt (verbatim): "The handler is a plain method and the scheduler forwards its return value automatically. Given that, what about a component can you change *without editing the handler at all*?"
- `canonical_answer`: How and where the handler runs — its threading and ordering policy — because that lives in the scheduler, not the handler.
- `alternative_correct_answers`:
  - "The scheduler it runs on, so its concurrency/ordering behaviour, while the handler code stays the same."
  - "The threading policy — whether it's serialized or parallel — without touching the method."
  - "Which thread/queue discipline applies to it; that's the scheduler's job, not the handler's."
- `followup` (if the learner restates "the scheduler does it" without naming the changeable thing): "That's where it lives — name the specific property of the wiring you would change to make the same handler run differently."

### Moment `m2-six-types`

*Why load-bearing (exposition):* this is the threshold-concept moment — the learner must come away seeing the six types as six settings of one concurrency dial, not a vocabulary list. Offers a prediction (for the confident), the contrasting cases (to surface the invariant), and a worked example (for the novice).

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is strong on concurrency fundamentals and may hold a confidently-wrong "sequential = its own thread" or "concurrent = just faster" intuition; elicit a prediction before revealing the enum.
- `answer_shape`: a requirement-to-guarantee mapping (what the framework must provide, and the failure mode if it doesn't) — not a ranking of types and not a single enum name.
- Framing + prompt (verbatim): "Before I show you the six scheduler types: suppose a component must process events in the exact order they arrive, and it keeps a private counter it reads and updates on every call. What's your gut prediction — what does the framework have to guarantee that component, and what would break if two of its tasks ran at the same time?"
- Confidence elicitation (verbatim, optional): "How confident are you in that before I reveal it — gut number, low to high?"
- `canonical_answer`: It needs one-task-at-a-time execution in arrival order, with a happens-before relationship between consecutive tasks — i.e. a `SEQUENTIAL` (or `SEQUENTIAL_THREAD`) scheduler. If two tasks ran at once, the counter would race (lost updates) and the arrival ordering would be lost ([`TaskSchedulerType.java#L9-L19`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L9-L19)).
- `alternative_correct_answers`:
  - "Serialized execution with happens-before so it needs no locks; parallel tasks would corrupt the counter and reorder events."
  - "A sequential, one-at-a-time scheduler; concurrent execution would break its read-modify-write and lose ordering."
  - "In-order single-task processing (a sequential type); otherwise a data race on the counter and out-of-order events."
- `followup` (correct outcome, shaky reasoning — e.g. "you'd just lock it"): "You landed on ordered execution — name the specific guarantee *between consecutive tasks* that lets the handler skip locks on its own state entirely."

**Move B — contrasting cases with comparison prompt.** *Diagnosis tag:* threshold concept; transfer is the goal. Uses the three cases in the Contrasting cases material below.
- Prompt (verbatim): "Here is the same component handler — a method that takes an event and increments a counter — run under three scheduler types: `SEQUENTIAL`, `CONCURRENT`, and `DIRECT`. Across the three, what stays the same, what differs, and what (if anything) must the handler's author change about the handler's own code in each case?"
- `canonical_answer`: The handler's signature and business logic stay identical across all three. What differs is the concurrency contract — `SEQUENTIAL` gives one-at-a-time execution with happens-before (the handler may touch its counter freely); `CONCURRENT` runs tasks in parallel with no ordering (the handler must be made thread-safe or the counter races); `DIRECT` runs inline on the caller's thread with no queue and with restricted wiring. The author changes the *scheduler type in configuration*, not the handler — the one exception being that `CONCURRENT` (and `DIRECT_THREADSAFE`) require the handler to be written thread-safe ([`TaskSchedulerType.java#L9-L51`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L9-L51)).
- `alternative_correct_answers`:
  - "Same handler code throughout; only the threading/ordering guarantee changes, picked by the type — and `CONCURRENT` additionally demands thread-safety."
  - "Logic identical; `SEQUENTIAL` = ordered/serial/no-locks, `CONCURRENT` = parallel/unordered/must-be-threadsafe, `DIRECT` = inline-on-caller/no-queue; the choice is configuration, not code."
  - "Nothing in the method body changes except that the parallel cases (`CONCURRENT`, `DIRECT_THREADSAFE`) force the author to guard shared state."
- `followup` (if the learner stops at "same code, different threading" and misses the obligation): "You've got that the code is identical and the threading differs — name the one thing the author *does* have to change for the parallel case, and say where that choice is recorded."
- *Deep invariant (exposition for consolidation, not read as a question):* the scheduler type is the only thing that sets a handler's concurrency contract, and it is chosen at wiring-assembly time, decoupled from what the handler computes.

**Move C — worked example with self-explanation.** *Diagnosis tag:* the learner is new to this and is hesitating on the type definitions; walk one type carefully.
- Walk (exposition): `CONCURRENT` runs tasks on a fork-join pool, possibly in parallel, with no ordering guarantee ([`TaskSchedulerType.java#L20-L24`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L20-L24)). **Load-bearing line:** "ordering is not guaranteed" + "may run in parallel" together impose the thread-safety obligation.
- Self-explanation prompt (verbatim): "`CONCURRENT` says tasks may run in parallel and ordering is not guaranteed. What must be true of a handler before it is safe on a `CONCURRENT` scheduler, and what would you look for in its code to confirm it?"
- `canonical_answer`: The handler must be thread-safe — no unsynchronized shared mutable state across invocations. You'd check that it does not read-modify-write instance fields without synchronization (or that it keeps no cross-call mutable state at all).
- `alternative_correct_answers`:
  - "It has to tolerate concurrent invocation; look for unguarded mutable fields it writes."
  - "No shared mutable state without synchronization; scan for instance fields mutated in the handler."
  - "Thread-safe by construction — ideally stateless; the red flag is an unsynchronized field update."
- `followup` (restatement of "it must be thread-safe" without saying how to check): "That's the requirement — name the concrete thing in the code you'd look at to decide whether it actually holds."

### Moment `m3-assembly`

*Why load-bearing (exposition):* the deferred-binding shape is unfamiliar to engineers used to constructing an object and wiring it in one step; the worked example earns the "why describe the graph before the instances exist" insight.

**Move A — worked example with self-explanation.** *Diagnosis tag:* the learner is new to this codebase's wiring (the common case here).
- Walk (exposition): step 1 constructs `new ComponentWiring<>(model, EventDeduplicator.class, wiringConfig.eventDeduplicator())` from the *interface* and a config; step 3, separately and later, calls `eventDeduplicatorWiring.bind(eventDeduplicator)` with the instance ([`DefaultEventIntakeModule.java#L97`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java#L97), [`#L170`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java#L170)). **Load-bearing line:** the instance arrives at `bind`, long after construction and soldering.
- Self-explanation prompt (verbatim): "The component instance is passed to `bind()` in a separate step, after the `ComponentWiring` was constructed and its wires were soldered. What does the framework gain by letting the whole graph be described and connected before any component instance exists?"
- `canonical_answer`: The graph's topology — schedulers and their wire connections — can be assembled (and later validated) independently of building the components, and components can be constructed in any order, including ones that reference each other cyclically, because the handler method references are resolved to a concrete instance only at bind time.
- `alternative_correct_answers`:
  - "You can build the connection graph first and the objects later, so construction order and cycles between components stop being a problem."
  - "Topology is decoupled from instantiation — the graph can be checked before instances exist and instances can be created in any order."
  - "It lets wiring and validation happen against interfaces, with real objects slotted in afterward at bind."
- `followup` (if the learner gives the benefit but not what bind does): "That's the payoff — now name the concrete thing `bind()` resolves at that later moment."

**Move B — direct walk with cued check.** *Diagnosis tag:* the learner is fluent on dependency-injection / deferred-wiring patterns and needs only a verify.
- Prompt (verbatim): "The first argument to `new ComponentWiring<>(...)` is `EventDeduplicator.class` — the interface, not an instance. Why does it have to be the interface?"
- `canonical_answer`: Because `ComponentWiring` resolves the handler method references reflectively against the interface to create the input wires before any implementation instance exists, and it explicitly requires the class to be an interface ([`ComponentWiring.java#L128-L156`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L128-L156)).
- `alternative_correct_answers`:
  - "So method references like `EventDeduplicator::handleEvent` can be resolved to build the wires before the instance is bound."
  - "`ComponentWiring` works off the interface reflectively and rejects a non-interface class; the instance comes at bind."
  - "The wires are created from the interface's methods, decoupled from the concrete implementation."
- `followup` (if the learner restates "because it has to be an interface" without the reason): "That's the rule — say what `ComponentWiring` does with the interface at construction time that it could not do with a concrete instance."

### Moment `m4-backlog-and-drain`

*Why load-bearing (exposition):* a light retrieval/verify before close-out; it confirms the on-ramp count and the flush opt-in without expanding into backpressure (which is `c0-03`).

**Move A — free recall.** *Diagnosis tag:* a mid-session retrieval cycle on something just covered.
- Prompt (verbatim): "In your own words: what does it mean for a task to be counted as 'unprocessed' by a scheduler, and at exactly what moment does it stop being counted?"
- `canonical_answer`: A task counts as unprocessed from when it enters the scheduler until its handler method has been called and has returned; it stops being counted the moment the handler returns ([`TaskScheduler.java#L186-L201`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L186-L201)).
- `alternative_correct_answers`:
  - "It's in the count from arrival until the handler finishes; handler return clears it."
  - "Counted while queued or running; off the count once the handle method returns."
  - "From enqueue to handler-return — return is the moment it's no longer unprocessed."

**Move B — direct walk with cued check.** *Diagnosis tag:* the learner is fluent and needs only a verify on the flush contract.
- Prompt (verbatim): "What happens if you call `flush()` on a scheduler that was not built with flushing enabled?"
- `canonical_answer`: It throws `UnsupportedOperationException` — flushing is opt-in via the scheduler's configuration, and the default is disabled ([`TaskScheduler.java#L222-L246`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L222-L246)).
- `alternative_correct_answers`:
  - "It throws — flushing has to be opted into in the `TaskSchedulerConfiguration`."
  - "`UnsupportedOperationException`, because flushing defaults to off."

## Contrasting cases material

The threshold concept is *declarative concurrency*: the same handler, three scheduler types, and the only thing that changes is the contract — chosen at wiring time, not in the handler. The three cases use one handler: a method that takes an event and increments a private counter.

- **Case 1 — `SEQUENTIAL`.** Runs on the shared fork-join pool, one task at a time in arrival order, with happens-before between tasks ([`TaskSchedulerType.java#L9-L13`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L9-L13)). *Surface:* serialized, ordered, runs on a pool thread. The counter is safe with no synchronization.
- **Case 2 — `CONCURRENT`.** Runs on the fork-join pool, possibly in parallel, no ordering guarantee ([`#L20-L24`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L20-L24)). *Surface:* multiple tasks at once, no happens-before. The identical counter now races unless the handler is made thread-safe.
- **Case 3 — `DIRECT`.** Runs inline on the caller's thread, no queue, with restricted wiring (one logical sequential sender) ([`#L25-L51`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L25-L51)). *Surface:* no scheduling at all — the work happens synchronously inside whoever sent the event.

**The deep invariant that survives the surface differences:** the handler's code is the same in all three; what changes is the concurrency contract it must honour, and that contract is a property of the *scheduler type* selected at assembly time — concurrency is declared in the wiring, not woven into the component. *(This lesson scopes the invariant to threading policy; `c0-syn` revisits it once soldering and backpressure are in view.)*

## Completion problems

**Problem 1** *(small step blanked).*
- Statement (verbatim): "A component's handler must see events in arrival order and updates a private field on every call. Fill in the blank: this component should be configured with scheduler type `______`. Name the type and say in one sentence why."
- Hint ladder:
  - Rung 1 (verbatim): "Open `TaskSchedulerType.java` and read the javadoc on the first two enum values — look for the phrase 'one at a time in the order they were enqueued' and 'happens before relationship'."
  - Rung 2 (verbatim): "Which type or types guarantee one task at a time, in enqueue order, with happens-before between tasks?"
  - Rung 3 (verbatim): "Two qualify — `SEQUENTIAL` and `SEQUENTIAL_THREAD`. They differ only in whether the handler runs on the shared pool or a dedicated thread; both give the ordering and happens-before this component needs."
  - Rung 4 (verbatim, gated on effort): "`SEQUENTIAL` (or `SEQUENTIAL_THREAD`). One-at-a-time, in-order execution with happens-before means the private field is read and written safely with no locks and events are handled in arrival order."
- `canonical_answer`: `SEQUENTIAL` (or `SEQUENTIAL_THREAD`); the one-at-a-time, in-order, happens-before guarantee makes the field safe without synchronization and preserves arrival order.
- `alternative_correct_answers`:
  - "`SEQUENTIAL_THREAD` — same ordering/happens-before guarantee on a dedicated thread."
  - "Either of the two sequential types; both serialize the handler and order the tasks."
- *Invariant exercised (exposition):* ordering + happens-before is a property the type supplies, not the handler.
- `followup` (if the learner answers "`SEQUENTIAL`" by elimination without the reason): "Right type — now state the guarantee between consecutive tasks that makes the unsynchronized field access safe."

**Problem 2** *(more blanked — two parts from a scenario).*
- Statement (verbatim): "A component is a tiny, stateless function called on a hot path, and you want to avoid scheduling overhead. Choose its scheduler type, and state the one wiring restriction you must respect for that choice."
- Hint ladder:
  - Rung 1 (verbatim): "Read the `DIRECT` and `DIRECT_THREADSAFE` javadoc in `TaskSchedulerType.java`, including the paragraph about which schedulers may send to a direct scheduler."
  - Rung 2 (verbatim): "Which type runs on the caller's thread with no queue — and how many logical senders is a plain `DIRECT` scheduler allowed to have?"
  - Rung 3 (verbatim): "`DIRECT` removes the queue and runs inline. Its restriction: only one logical sequential sender may feed it, and concurrent senders are forbidden. If you need multiple or concurrent senders, `DIRECT_THREADSAFE` lifts that — at the cost of requiring the handler to be thread-safe."
  - Rung 4 (verbatim, gated on effort): "`DIRECT`, because the function is tiny and not worth scheduling overhead; the restriction is that only one logical sequential sender may send to it (concurrent senders are illegal). Since the function is stateless, `DIRECT_THREADSAFE` is the alternative if more than one sender is needed."
- `canonical_answer`: `DIRECT` (runs inline on the caller's thread, no queue); the restriction is that only a single logical sequential sender may feed it and concurrent senders are forbidden — or use `DIRECT_THREADSAFE` if multiple/concurrent senders are required.
- `alternative_correct_answers`:
  - "`DIRECT`; one sequential sender only, no concurrent senders — `DIRECT_THREADSAFE` if you need more."
  - "`DIRECT_THREADSAFE` if there are several senders (the function is stateless so it's safe); plain `DIRECT` if there's a single sequential sender."
- *Invariant exercised (exposition):* `DIRECT` trades the queue for caller-thread execution under a wiring constraint the framework enforces.
- `followup` (if the learner names `DIRECT` but omits the restriction): "Good — now state the constraint the framework places on how a `DIRECT` scheduler may be wired."

**Problem 3** *(most faded — produce the full step from a scenario).*
- Statement (verbatim): "You are adding a component that fans incoming events out to several workers in parallel and holds no mutable state of its own. Specify three things: (a) its scheduler type, (b) the obligation that type places on the handler, and (c) where in the wiring you express that type choice — which object carries it."
- Hint ladder:
  - Rung 1 (verbatim): "Read the `CONCURRENT` javadoc in `TaskSchedulerType.java`, then look at `TaskSchedulerConfiguration.java` for where the type is recorded."
  - Rung 2 (verbatim): "Which type allows parallel execution, and what does 'ordering is not guaranteed' demand of the handler? Then: which record does a component pass to `ComponentWiring` to choose its type?"
  - Rung 3 (verbatim): "Parallel + unordered points at `CONCURRENT`, whose obligation is a thread-safe handler. The type is set in the component's `TaskSchedulerConfiguration`, the record handed to `new ComponentWiring<>(model, Iface.class, config)`."
  - Rung 4 (verbatim, gated on effort): "(a) `CONCURRENT`; (b) the handler must be thread-safe — here trivially met because it is stateless; (c) the type is carried by the component's `TaskSchedulerConfiguration`, passed as the third argument to its `ComponentWiring` constructor."
- `canonical_answer`: (a) `CONCURRENT`; (b) the handler must be thread-safe (no unsynchronized shared mutable state — satisfied here since it is stateless); (c) the type is set in the component's `TaskSchedulerConfiguration`, supplied to `new ComponentWiring<>(model, Interface.class, config)` ([`TaskSchedulerConfiguration.java#L27-L34`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerConfiguration.java#L27-L34), [`ComponentWiring.java#L113-L156`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L113-L156)).
- `alternative_correct_answers`:
  - "`CONCURRENT`; needs a thread-safe handler (trivial when stateless); chosen in the `TaskSchedulerConfiguration` passed to `ComponentWiring`."
  - "Type `CONCURRENT`, obligation thread-safety, expressed via the config record (`TaskSchedulerConfiguration`) the component hands to its `ComponentWiring`."
- *Invariant exercised (exposition):* the parallelism choice and its thread-safety obligation travel together, and the choice lives in configuration, not the handler.
- `followup` (if the learner gives type and obligation but not where it's expressed): "You have the type and the obligation — now name the object that actually carries the type into the wiring."

## Delta callout

`[TBD: delta-map/wiring-framework.md is not yet authored — the delta-map/ directory currently holds only README.md, so there is no current-versus-proposed entry for the wiring framework to summarize.]` Status: **not started**. When that delta lands it will be linked here as `../../delta-map/wiring-framework.md`. The topic file's own forward note describes a planned module-API-level backpressure layered *above* the wire level at the Consensus/Execution boundary ([consensus-execution-boundary.md](../../architecture/interfaces/consensus-execution-boundary.md)); that concerns cross-module throttling, not the scheduler types this lesson covers, and is followed in cluster B and the Pass 3 scenarios. *(Exposition; no learner-facing prompt.)*

## Transfer prompt

Forward framing (motivational only): the next lesson, `c0-02`, connects schedulers by soldering one scheduler's output wire to another's input wire. The question below is answerable now, from how a single scheduler consumes its own queue.

- Prompt (verbatim): "You've seen that a `SEQUENTIAL` scheduler processes one task at a time, and that a monitored scheduler counts a task as unprocessed until its handler returns. Suppose a fast `SEQUENTIAL` producer feeds events to a slow `SEQUENTIAL` consumer faster than the consumer can handle them. Using only how a single scheduler consumes its own queue: where does work pile up, and whose unhandled-task count grows?"
- `canonical_answer`: Work piles up at the slow consumer; the *consumer's* unhandled-task count grows, because tasks arrive faster than its one-at-a-time handler can process and return them, so they accumulate as unprocessed in its queue ([`TaskScheduler.java#L186-L201`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L186-L201)). *(Whether that backlog eventually pushes back on the producer is backpressure, which is `c0-03`.)*
- `alternative_correct_answers`:
  - "At the consumer; its on-ramp / unhandled-task count climbs because one-at-a-time processing can't keep pace with arrivals."
  - "Downstream, at the slow `SEQUENTIAL` consumer — its queue depth / unprocessed count rises."
  - "The consumer side accumulates; the producer's count stays low since it keeps emitting and returning."
- `followup` (if the learner says "it backs up" without locating the count): "Be specific about the bookkeeping — which scheduler's unhandled-task count is the one that climbs, and why that one?"

## Close-out retrieval

- Free-recall summary (verbatim): "In your own words: what does a scheduler's *type* decide for the component running on it, and why can the same handler code run under different types unchanged?"
  - `canonical_answer`: The type decides the handler's concurrency contract — how many tasks run at once, in what order, and on which thread. The same handler runs unchanged under different types because that contract lives in the scheduler (chosen at wiring time), not in the handler; the only handler-side consequence is that `CONCURRENT` and `DIRECT_THREADSAFE` require the handler to be thread-safe.
  - `alternative_correct_answers`:
    - "The type sets ordering/parallelism/threading; the handler is logic-only, so swapping the type doesn't touch it — except parallel types demand thread-safety."
    - "It picks the execution policy (serial vs parallel vs inline); handler code is independent of that policy, with thread-safety the lone obligation for the parallel types."
    - "Concurrency and ordering guarantees come from the type; the handler stays the same because concurrency is declared in the wiring."
- Successive-relearning tags (exposition; added to the learner's relearning queue): threshold concept *declarative concurrency* —
  - Day 1: recall the `SEQUENTIAL` vs `CONCURRENT` contract (ordering + thread-safety obligation).
  - Day 3: apply it — pick a scheduler type for a newly described component and justify it.
  - ~2 weeks: state the invariant — the type sets the concurrency contract at wiring time and the handler is unchanged across types.

## Open questions

- **`[TBD]` Delta callout.** `delta-map/wiring-framework.md` does not yet exist (the `delta-map/` directory holds only `README.md`), so there is no current-versus-proposed difference for scheduler types and assembly to summarize. Deferred until the wiring-framework delta-map entry is authored; the callout links the file once it lands.
