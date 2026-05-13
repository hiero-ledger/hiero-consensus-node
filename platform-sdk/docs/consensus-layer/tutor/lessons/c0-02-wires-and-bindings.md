---
id: c0-02-wires-and-bindings
cluster: c0
title: Input wires, output wires, and bindings
pass: 2
prerequisites:
  - c0-01-task-schedulers
kb_topics:
  - architecture/topics/wiring-framework.md
kb_concepts: []
kb_glossary_terms: []
kb_invariants: []
kb_deltas:
  - architecture/topics/wiring-framework.md  # inline "Delta vs. componentFramework.md" callout; no per-topic delta-map file yet
kb_decisions: []
learning_objectives:
  - Distinguish an input wire (a typed handoff into a scheduler's handler) from the scheduler's queue (one queue per scheduler shared by all of its input wires).
  - Explain the difference between `bind(Function<IN,OUT>)` and `bindConsumer(Consumer<IN>)` in terms of what reaches the primary output wire and what does not — including the null-return-drop rule.
  - Predict what happens to data delivered to an input wire when the scheduler is squelching, and what happens to data delivered to an input wire whose scheduler is `NO_OP`.
  - Read a wiring assembly that creates many input wires on one scheduler plus a primary output and one or more secondary output wires (e.g. `GossipWiring`), and say what each piece is for.
  - Choose `put` vs `offer` vs `inject` at a call site based on what the *producer* needs from backpressure — and recognise that the choice at the soldering edge (covered in `c0-03`) is what really decides this for soldered handoffs.
threshold_concepts:
  - Binding selects a component's data-flow shape (the `bind` vs `bindConsumer` decision is an architectural choice — does this component fan out via the primary output wire, or terminate? — not a stylistic one).
estimated_session_minutes: 35
status: drafted
last_verified_against: 1b26efc7698950c1f1d52c9e1a32213c8d422b12
---

# Input wires, output wires, and bindings

## Prerequisites

- `c0-01-task-schedulers` — establishes the `TaskScheduler` + `TaskSchedulerType` mental model: a scheduler is a queue plus a threading policy, the default is `SEQUENTIAL` (one task at a time with happens-before), and queue-depth observability is opt-in. This lesson assumes the learner is solid on that. If `SEQUENTIAL`'s contract is still vague, return to `c0-01` first — what binding *means* for handler-owned state depends on it.

## Incoming retrieval probes

One probe before the new content lands; the rest of the cluster is too early for successive-relearning windows on prior threshold concepts.

- **Concept:** the `SEQUENTIAL` contract. **Recall prompt:** *"From last lesson — when a handler bound to a `SEQUENTIAL` scheduler reads and writes a private field, why doesn't it need a `synchronized` block or a `volatile` declaration?"* **Canonical answer for the tutor to consolidate against:** the scheduler runs one task at a time in enqueue order *and* establishes a happens-before edge between consecutive tasks. The two clauses together let the handler treat scheduler-private state as if it were single-threaded; the JMM-level reasoning is decided by the scheduler's contract, not by per-field synchronization. This matters in this lesson because every input wire on the scheduler funnels into that same single-threaded contract — many input wires, one execution context.

## Misconception watchlist

The senior-engineer schemas that import most cleanly from "wires are like channels" or "binding is like subscribing" break in a few load-bearing places. Listen for these.

- **"Each input wire has its own queue."** No — the queue lives on the scheduler, not on the wire. `GossipWiring` builds nine input wires on a single scheduler ([`GossipWiring.java#L96-L107`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/GossipWiring.java#L96-L107)); the nine wires share one queue and one single-task-at-a-time execution context. The handlers for `events to gossip`, `event window`, `health info`, `PlatformStatus`, and the lifecycle wires all execute serially relative to one another. That is the whole point: a component's *state* is private to one scheduler, not private to one input wire.

- **"`bindConsumer` is just `bind` with a `Void` return."** Almost — but the difference is observable. `bind(Function<IN,OUT>)` forwards the handler's return value onto the primary output wire when it is non-null ([`BindableInputWire.java#L86-L102`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/BindableInputWire.java#L86-L102)); `bindConsumer(Consumer<IN>)` does not. Picking one over the other is a data-flow architectural choice — does this input wire feed the primary output, or terminate? Not all of a component's input wires need to. `GossipWiring` is an example: its scheduler is `TaskScheduler<Void>`, so the primary output wire is unused; events flow out through *secondary* output wires (`eventOutput`, `syncProgressOutput`) instead.

- **"`put`, `offer`, and `inject` are convenience overloads."** They are contracts, not overloads. `put` blocks the caller when the consumer is at capacity; `offer` returns `false`; `inject` bypasses the cap entirely ([`InputWire.java#L71-L95`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java#L71-L95)). For wires reached by *direct caller code*, the caller picks. For wires reached by *soldering*, the producer's solder type decides which of the three the runtime will use — and the three `SolderType` values exist precisely to expose that choice edge by edge. That handoff is the bridge into `c0-03`.

- **"If I bind twice, I just replace the previous handler."** No — `setHandler` throws `IllegalStateException` if a handler is already set ([`InputWire.java#L102-L107`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java#L102-L107)). The binding is one-shot. This is why every component is assembled in a two-phase shape: build the wires first (constructor of `GossipWiring`), then bind them once (`GossipWiring.bind(Gossip)` at [`GossipWiring.java#L115-L129`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/GossipWiring.java#L115-L129)). The wiring framework leans on this single-binding invariant when validating the graph (covered in `c0-05`).

- **"If a scheduler is `NO_OP`, the data just goes nowhere."** True at the data level, but the framework goes further: on a `NO_OP` scheduler, `bind` and `bindConsumer` return early without ever installing the handler, and `BindableInputWire`'s constructor skips registering the wire with the model entirely ([`BindableInputWire.java#L55-L80`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/BindableInputWire.java#L55-L80)). The wire still *exists* in the assembly so its callers compile; nothing it forwards reaches the diagram or the rest of the model. That is the "toggle a component on/off via configuration" idiom from `c0-01` made concrete.

## Productive impasse

Open `GossipWiring`'s assembly. The constructor builds nine input wires and two secondary output wires against a single `TaskScheduler<Void>`:

```java
scheduler = model.<Void>schedulerBuilder("gossip")
        .configure(configuration.getConfigData(GossipWiringConfig.class).gossip())
        .build();

eventInput = scheduler.buildInputWire("events to gossip");
eventWindowInput = scheduler.buildInputWire("event window");
eventOutput = scheduler.buildSecondaryOutputWire();
syncProgressOutput = scheduler.buildSecondaryOutputWire();

startInput = scheduler.buildInputWire("start");
stopInput = scheduler.buildInputWire("stop");
clearInput = scheduler.buildInputWire("clear");
systemHealthInput = scheduler.buildInputWire("health info");
platformStatusInput = scheduler.buildInputWire("PlatformStatus");
pauseInput = scheduler.buildInputWire("pause");
resumeInput = scheduler.buildInputWire("resume");
```

([`GossipWiring.java#L89-L108`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/GossipWiring.java#L89-L108).)

**Predict, thinking-aloud — confidence call on (b) and (c):**

(a) Each input wire is typed differently — `PlatformEvent`, `EventWindow`, `NoInput`, `Duration`, `PlatformStatus`. The scheduler is `TaskScheduler<Void>`. What does the `<Void>` mean about the scheduler's primary output wire, and how does that square with `eventOutput` and `syncProgressOutput` carrying real types (`PlatformEvent`, `SyncProgress`)?

(b) `eventInput`, `eventWindowInput`, and `systemHealthInput` carry three completely different kinds of traffic at three different rates. When `gossip.bind(...)` runs and attaches handlers to all of them, will those three handlers be able to run in parallel — say, event-window updates landing while an event-to-gossip is mid-handler? *How confident are you?*

(c) `eventOutput` is built via `buildSecondaryOutputWire()`. Its type is `StandardOutputWire<PlatformEvent>`. Who *writes* to it? Look at the `Gossip` interface contract that `GossipWiring.bind(...)` calls — what's the architectural rule about who is allowed to push data into a secondary output wire?

**Reveal.**

(a) `TaskScheduler<Void>` means the primary output wire carries `Void`; no `bind(Function<IN,Void>)` is ever going to forward anything useful, so the gossip component does not use the primary output channel at all. The real outputs are the two secondary wires (`eventOutput`, `syncProgressOutput`), which are *independently typed* ([`TaskScheduler.java#L140-L145`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L140-L145)). Secondary wires exist precisely so a component can fan out to multiple downstream consumers with different types without forcing the scheduler's `<OUT>` parameter to be a union.

(b) No — and this is the load-bearing answer. Per `c0-01`, the scheduler is `SEQUENTIAL` by default and `GossipWiringConfig.gossip()` overrides nothing that changes that contract. All nine input wires share one queue and one single-task-at-a-time execution context. An event-window update and an event-to-gossip can be enqueued from different threads, but the bound handlers run serially relative to one another, in enqueue order, with happens-before between them.

(c) The javadoc on `buildSecondaryOutputWire` ([`TaskScheduler.java#L128-L145`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L128-L145)) spells the rule out: *"It is considered a violation of convention to push data into a secondary output wire from any code that is not executing within this task scheduler."* The convention is unenforced — the framework will not catch a violation — and it exists because pushing from outside the scheduler's execution context breaks the same thread-confinement argument that lets the handler avoid synchronization. `GossipWiring` hands `eventOutput` and `syncProgressOutput` to the `Gossip` implementation, which pushes events received from peers onto `eventOutput` *from within the scheduler's handler* ([`GossipWiring.java#L115-L129`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/GossipWiring.java#L115-L129)).

**Consolidation.** If your prediction on (b) had the three handlers running in parallel, the gap is the same as last lesson's: the *scheduler*, not the input wire, owns the threading contract. Each new input wire on a `SEQUENTIAL` scheduler does not buy you another execution context — it buys you another typed handoff into the existing one. If your prediction on (c) had downstream code pushing into `eventOutput`, the gap is subtler: a secondary output wire is *not* an open publish channel. It is a fan-out side-channel for the component itself, and the convention is what preserves the thread-confinement story. Hold both — the Mechanism section names where the framework enforces what it does and trusts the author for the rest.

## Mechanism

### Pre-training

Three terms — leaning on `c0-01` for the scheduler half.

- **Input wire** — a typed entry point into a scheduler's handler. Built via `scheduler.buildInputWire(name)`. Carries data of type `IN` from any caller into the scheduler's queue (or directly to the handler for `DIRECT`/`DIRECT_THREADSAFE` schedulers). The wire exposes three put-paths (`put`, `offer`, `inject`); the handler is attached separately via `bind` or `bindConsumer`.
- **Binding** — the act of attaching a handler to an input wire. Once per wire; throws if attempted twice. Two shapes: `bind(Function<IN,OUT>)` for handlers whose return value should flow onto the scheduler's primary output, and `bindConsumer(Consumer<IN>)` for handlers that terminate.
- **Output wire** — the source side of every connection out of a component. A scheduler always owns exactly one **primary** output wire of type `OUT` (the scheduler's type parameter); the scheduler can additionally own zero or more **secondary** output wires of arbitrary types built via `buildSecondaryOutputWire()`. Output wires are where soldering (`c0-03`) attaches; this lesson is about how data gets *onto* an output wire, not how it gets *off* one.

### Chunk 1 — One scheduler, many input wires

The relationship between input wires and the scheduler that owns them is one of typed multiplexing.

`buildInputWire` ([`TaskScheduler.java#L98-L101`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L98-L101)) takes a `name` and returns a `BindableInputWire<I, OUT>` where `I` is the input type (chosen at the call site, may differ from wire to wire) and `OUT` is the scheduler's primary-output type (fixed for the scheduler). Multiple wires built from the same scheduler share its queue, its threading contract, and its primary output wire.

**Load-bearing line.** The wire's three put-paths — `put`, `offer`, `inject` — are forwarded straight to `taskSchedulerInput` at [`InputWire.java#L71-L95`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java#L71-L95). The wire itself does *not* hold tasks. It is a typed view onto the scheduler's queue, paired with the bound handler the scheduler will invoke when this wire's data reaches the front of the queue.

> Self-explanation prompt: *gossip's nine input wires all funnel into one `SEQUENTIAL` scheduler. Pick a scenario — say, a peer event arriving on `eventInput` while a `PlatformStatus` change is enqueued on `platformStatusInput`. What property does the wiring framework guarantee about how the two handlers observe gossip's private state, and which mechanism from `c0-01` actually delivers that guarantee?*

### Chunk 2 — The three put-paths

`put` / `offer` / `inject` exist as a triad because there are three things a producer may want when the consumer is at capacity:

- **`put`** ([L71-L73](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java#L71-L73)) — wait. The producing thread blocks until the consumer drains. This is the path that participates in backpressure.
- **`offer`** ([L82-L84](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java#L82-L84)) — refuse. Returns `false` if capacity is exhausted; producer decides what to do (typically: discard the item, log a counter).
- **`inject`** ([L93-L95](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java#L93-L95)) — bypass. Adds the item regardless of capacity. The Javadoc spells out that if backpressure is disabled on this scheduler, `inject` is equivalent to `put`.

**Load-bearing distinction.** *Who* picks among these three depends on how the input wire is reached.

- If the wire is called directly (a caller invokes `wire.put(data)` from non-wiring code — typical for ingress at the system boundary), the caller picks per-call.
- If the wire is reached by soldering from an upstream output wire, the *producer's* `SolderType` decides — `PUT`, `OFFER`, or `INJECT` are the three values, mapping onto the three put-paths one-for-one. Soldering itself is `c0-03`; the relevant takeaway here is that the same three put-paths are exposed at the API surface for direct callers and via the `SolderType` enum for soldered edges, and the `INJECT` path's reason for existing — break a cycle that would otherwise deadlock under `put` — is what makes the triad a contract rather than a tuning knob.

> Self-explanation prompt: *the `inject` path exists primarily to break cycles. If every soldered edge used `put`, what specific runtime failure would happen the first time a downstream consumer in a cycle filled its queue? Name the invariant the cycle violates.*

### Chunk 3 — The binding contract

`BindableInputWire<IN, OUT>` ([`BindableInputWire.java`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/BindableInputWire.java)) exposes two ways to attach a handler. Both wrap the handler in a squelching check; both register the binding with the wiring model when the scheduler is not `NO_OP`; both delegate the one-shot-handler enforcement to `InputWire.setHandler`.

**`bindConsumer(Consumer<IN>)`** ([L67-L80](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/BindableInputWire.java#L67-L80)) — no return value. Whatever the handler does (state mutation, logging, side effects) terminates inside the handler. The scheduler's primary output wire receives nothing from this wire.

**`bind(Function<IN, OUT>)`** ([L86-L102](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/BindableInputWire.java#L86-L102)) — return value is forwarded to the scheduler's primary output wire *if non-null*. The exact line is:

```java
final OUT output = handler.apply((IN) i);
if (output != null) {
    taskSchedulerInput.forward(output);
}
```

**Load-bearing line.** The null check is part of the contract, not an implementation detail. A handler that returns `null` is the canonical way to express "this input did not produce an output" — i.e. the handler is a filter as well as a transformer. The framework promises that null returns are silently dropped, not forwarded as a literal `null` downstream. Authors of `bind`-style components rely on this; flipping it (always forwarding, including null) would change the contract under every component that uses null-as-filter.

**Load-bearing line.** Squelching is enforced inside the bound wrapper. `currentlySquelching = taskScheduler::currentlySquelching;` ([L53](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/BindableInputWire.java#L53)); the wrapper checks it at every invocation ([L73-L74](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/BindableInputWire.java#L73-L74) for `bindConsumer`, [L92-L93](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/BindableInputWire.java#L92-L93) for `bind`). When squelching is on, the handler is not invoked; for `bind`, no value is forwarded either. Squelching is the lever Cluster D uses to drop in-flight work during shutdown / freeze; here, what matters is that the lever sits inside the binding, so data that has *already entered the wire* can be silently dropped — entry is not a commitment to delivery.

> Self-explanation prompt: *every input wire registers its binding with the model via `model.registerInputWireBinding(...)` ([L79, L101](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/BindableInputWire.java#L101)) — but only when the scheduler is not `NO_OP`. Why register at binding time rather than at wire-build time, and what does Cluster B's health monitor lose if it reads the registry before any handlers have been bound?*

### Chunk 4 — Primary vs secondary output wires

A scheduler owns exactly one **primary** output wire ([`TaskScheduler.java#L123-L126`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L123-L126)). Its type is the scheduler's `<OUT>` parameter; calling `getOutputWire()` repeatedly returns the same instance. Data reaches it exclusively via `bind`'s return-value forwarding — there is no public push API on the primary wire.

A scheduler can own any number of **secondary** output wires built via `buildSecondaryOutputWire()` ([`TaskScheduler.java#L140-L145`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L140-L145)). Each has its own type parameter, independent of the scheduler's `<OUT>`. Unlike the primary wire, secondary wires *are* writable from the component's handler — that is their purpose. `GossipWiring`'s `eventOutput` carries `PlatformEvent`, `syncProgressOutput` carries `SyncProgress`, both on the same `TaskScheduler<Void>`.

**Convention, not enforcement.** The javadoc on `buildSecondaryOutputWire` ([L128-L139](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L128-L139)) reads: *"It is considered a violation of convention to push data into a secondary output wire from any code that is not executing within this task scheduler."* The framework does not check this. The convention exists because thread-confinement of state — the whole point of `SEQUENTIAL` from `c0-01` — is only as safe as the rule that all writes to a component's outgoing wires originate from inside the component's scheduler.

> Self-explanation prompt: *the framework declines to enforce the "only push from inside the scheduler" rule for secondary output wires. What would have to change about the framework to enforce it, and what cost would that impose on the binding API? Speculate on which design pressure tipped the decision toward convention.*

**Bookkeeping (skim past, do not invest self-explanation):** `OutputWire`'s methods for filters, transformers, and splitters ([`OutputWire.java`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java)) — covered in `c0-03`. The `solderTo` family — also `c0-03`. The fact that `OutputWire` requires forwarding to be configured before data flows (per the class javadoc at [L92-L95](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java#L92-L95) — *"Forwarding should be fully configured prior to data being inserted into the system"*) is the assembly-then-bind-then-start lifecycle that `c0-05` walks through end to end.

## Contrasting cases

This lesson establishes the threshold concept *binding selects a component's data-flow shape*, so two contrasting cases follow. The first surfaces the bind/bindConsumer architectural call; the second surfaces the primary/secondary output-wire call.

### Case A — `bind` vs `bindConsumer` on the same input wire

A hypothetical event-deduplicator component receives `PlatformEvent`s, drops duplicates, and forwards the rest.

|                                    | `bind(Function<PlatformEvent, PlatformEvent>)`                                | `bindConsumer(Consumer<PlatformEvent>)`                                  |
|------------------------------------|-------------------------------------------------------------------------------|--------------------------------------------------------------------------|
| Return value forwarded?            | Yes, on the primary output wire (the scheduler's `OUT`)                       | No — primary output wire receives nothing                                |
| How to drop a duplicate            | Return `null` — framework drops it silently                                   | Call nothing — the side-effect-only handler is the drop                  |
| How to forward a non-duplicate     | Return the event                                                              | Push to a secondary output wire from inside the handler                  |
| Scheduler type at the call site    | `TaskScheduler<PlatformEvent>`                                                | `TaskScheduler<Void>` (or whatever the primary wire is used for elsewhere) |
| Downstream consumer attachment     | `scheduler.getOutputWire().solderTo(...)`                                     | `secondaryOutputWire.solderTo(...)`                                      |

> Comparison prompt: *both designs work. The deep invariant that survives is "the component decides which input feeds which output by where the data leaves the handler" — return value vs secondary-wire push. Name a concrete situation where `bind` is the wrong choice (think: a component with multiple typed outputs that don't share `OUT`), and a concrete situation where `bindConsumer` is the wrong choice (think: a component whose handler is a pure transformation and downstream wants null-as-filter semantics).*

### Case B — Primary vs secondary output wires on the same component

Same hypothetical: now imagine the deduplicator also publishes a `DedupStats` summary every N events.

|                              | Primary output (`bind` return)                                                   | Secondary output wire                                                       |
|------------------------------|----------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| Number per scheduler         | Exactly one                                                                      | Zero or more                                                                |
| Type                         | The scheduler's `<OUT>` — fixed                                                  | Independently typed per wire                                                |
| Push API                     | None public; populated by `bind`'s return forwarding only                        | Push from inside the handler (`outputWire.forward(value)`)                  |
| Null-as-filter semantics     | Yes — built into `bind`                                                          | No — author chooses when to push                                            |
| Backpressure participation   | Same as scheduler's outgoing solder edges                                        | Same as scheduler's outgoing solder edges                                   |
| Thread-confinement promise   | Automatic — forwarding only happens from the handler's return                    | By convention — author must push only from inside the scheduler            |

> Comparison prompt: *the surface difference is "one vs many" and "implicit forward vs explicit push". The deep invariant: a primary output wire's contract makes the thread-confinement story self-enforcing, where a secondary output wire's contract relies on author discipline. Pick a component that would be unsafe to express purely on secondary output wires (because the discipline would have to span multiple modules), and a component where multiple secondary wires are obviously the right shape (because the outputs have unrelated types).*

## Completion problems

Two problems. Tighter scaffolding than `c0-01` — the first lesson in the cluster carried three; this lesson sits second and the cluster's fading curve drops a rung.

### Problem 1 — Reading a binding pair

A new component `RoundCertifier` is being assembled like this:

```java
final TaskScheduler<RoundCertificate> sched =
        model.<RoundCertificate>schedulerBuilder("roundCertifier")
                .withUnhandledTaskCapacity(64)
                .build();

final BindableInputWire<RoundProposal, RoundCertificate> proposalInput =
        sched.buildInputWire("proposals");
final BindableInputWire<Roster, RoundCertificate> rosterInput =
        sched.buildInputWire("roster");

// ... elsewhere, in a `bind(Certifier)` method:
proposalInput.bind(certifier::certifyProposal);
rosterInput.bindConsumer(certifier::onRosterChange);
```

State the following:

(a) Where do values returned by `certifier::certifyProposal` go?
(b) Where do values returned by `certifier::onRosterChange` go?
(c) `certifier::certifyProposal` returns `null` for a proposal whose round number is below the current event window. What happens to that `null`?
(d) An external caller invokes `proposalInput.put(p)` while the scheduler is at capacity 64. What happens, and what would change if it had used `proposalInput.offer(p)` instead?

**Hint ladder:**

1. *"Skim [`BindableInputWire.java#L86-L102`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/BindableInputWire.java#L86-L102). What does the framework do with the value returned by the function passed to `bind`?"*
2. *"For (c), look two lines down at the null-check."*
3. **Full answer:** (a) Onto `sched.getOutputWire()`, the primary `OutputWire<RoundCertificate>`, via `bind`'s return-value forwarding. (b) Nowhere — `bindConsumer` does not forward. The roster handler's side effects (updating `certifier`'s internal state) are the whole point. (c) Dropped silently — the null check at [`BindableInputWire.java#L97-L99`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/BindableInputWire.java#L97-L99) skips forwarding when the return is null. (d) `put` blocks the calling thread until the scheduler's unhandled-task count drops below 64. `offer` would return `false` and the caller would decide what to do — typically drop and increment a counter.

**Invariant exercised:** the choice of `bind` vs `bindConsumer` at assembly time, and the choice of `put` vs `offer` at the call site, are independent. Both decisions show up in the resulting data-flow shape.

### Problem 2 — From requirements, light scaffolding

A component `SignatureGatherer` should:

- Receive `EventSignature`s on one input wire and `PlatformStatus`-change notifications on another, both serialised through the same handler-private state.
- Emit a `SignedEvent` for every event that reaches the signature threshold.
- Additionally emit a `GatherStats` summary every N events on a separate side-channel.
- Be silently disabled by a single configuration flag, with all wires staying connected so the rest of the graph is unaffected.

Sketch the `GathererWiring` constructor and `bind(Gatherer)` method. State which `TaskSchedulerType` you would pick, which binding shape you would attach to each input wire, where each output goes, and how the configuration-flag-disable is implemented.

**Hint ladder:**

1. *"Use `c0-01`'s scheduler-type picker. The two input handlers share private state — what does that say about the threading contract?"*
2. *"Two outputs of different types — what API gives you the second one?"*
3. *"For the disable-flag: re-read `BindableInputWire`'s `NO_OP` early-return — what type, exactly, do you flip to to silence the component without removing it from the graph?"* → [`BindableInputWire.java#L55-L80`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/BindableInputWire.java#L55-L80) and the `NO_OP` enum entry from `c0-01`.
4. **Full answer:**

   ```java
   scheduler = model.<SignedEvent>schedulerBuilder("signatureGatherer")
           .configure(configuration.getConfigData(GathererConfig.class).gatherer())
           .build();

   signatureInput = scheduler.buildInputWire("signatures");
   statusInput   = scheduler.buildInputWire("PlatformStatus");
   statsOutput   = scheduler.buildSecondaryOutputWire(); // StandardOutputWire<GatherStats>

   // bind(Gatherer):
   signatureInput.bind(gatherer::handleSignature);       // null-on-below-threshold → drop
   statusInput.bindConsumer(gatherer::onPlatformStatus); // pure side effect
   // signed events flow on scheduler.getOutputWire(); stats flow on statsOutput.
   ```

   Scheduler type: `SEQUENTIAL` (the default; both handlers share private state). Binding shape: `bind` on signatures (return is the next `SignedEvent` or `null` to filter), `bindConsumer` on status (terminating). Outputs: `SignedEvent` on the primary wire via `bind`'s return forwarding; `GatherStats` on the secondary wire via explicit push from inside the handler. Disable: set the scheduler type to `NO_OP` via configuration (`GathererConfig` exposes a `type` field that `configure(...)` consumes). On `NO_OP`, `BindableInputWire.bind` / `bindConsumer` return early without installing handlers, and the model doesn't register the input wires; everything that was soldered to those wires stays compiled but inert.

**Invariant exercised:** the four building blocks — input wires (with three put-paths), bindings (`bind` vs `bindConsumer`), the primary output wire (one, typed `<OUT>`, fed by `bind`'s return), secondary output wires (many, independently typed, pushed from inside the handler) — compose into the data-flow shape of every component in the consensus layer. The `NO_OP` toggle preserves the shape while silencing the data.

## Delta callout

The proposed-redesign source document (`platform-sdk/docs/components/componentFramework.md`) describes wires and bindings at a similar level to current code, but predates the secondary-output-wire surface (`buildSecondaryOutputWire`) being a first-class part of the API, and predates `bind`'s null-as-filter contract being load-bearing. The delta status is **partial / largely irrelevant**: current code is what every consensus-layer component is built against; the source-doc shape is historical. The wiring-framework topic file ([`architecture/topics/wiring-framework.md` § InputWire and OutputWire](../../architecture/topics/wiring-framework.md#inputwire-and-outputwire)) carries the same inline-delta convention as the task-scheduler section; no per-topic `delta-map/wiring-framework.md` file exists yet. [TBD: delta-map entry once `delta-map/` populates.]

## Transfer prompt

Two questions, both forward-pointing:

- The three put-paths exposed by `InputWire` (`put`, `offer`, `inject`) map one-for-one onto the three `SolderType` values (`PUT`, `OFFER`, `INJECT`). When `c0-03` introduces soldering, predict where each `SolderType` shows up at runtime — which method of `InputWire` does each ultimately call? — and predict why the framework exposes the choice as an enum on the soldering edge rather than as a setting on the input wire itself.
- A scheduler at capacity 64 is being saturated by an upstream producer using `put`. Two of its bound handlers occasionally call slow operations (~100ms). Predict what `WiringModel.getHealthMonitorWire()` will eventually see, and predict what would change if exactly one of the soldered edges into this scheduler were switched from `PUT` to `INJECT`. Hold those predictions for `c0-04` (wire-level backpressure) and Cluster B (the health monitor's reaction side).

## Close-out retrieval

**Free-recall summary.** *"In your own words: a component is built with one scheduler and several input wires. Walk me through what happens to a single piece of data from the moment a caller invokes `wire.put(data)` to the moment something appears on a downstream consumer's input wire. Name every framework boundary it crosses — the wire, the queue, the handler invocation, the return-value forwarding (or the secondary-wire push), the soldered edge — and which of those boundaries enforces what."*

Canonical answer for the tutor to consolidate against: `wire.put(data)` enqueues onto the scheduler's queue (the wire is a typed view, not a queue of its own; if capacity is exhausted, the call blocks). The scheduler pops one task at a time per `SEQUENTIAL`'s contract and invokes the bound wrapper. The wrapper checks `currentlySquelching` and either short-circuits (data dropped silently) or calls the handler. For `bind`, the handler's non-null return value is forwarded via `taskSchedulerInput.forward(...)` onto the primary output wire of type `<OUT>`; null returns are dropped. For `bindConsumer`, nothing is forwarded — any outgoing data must have been explicitly pushed by the handler onto a secondary output wire. From the output wire (primary or secondary), soldered edges (covered in `c0-03`) carry the data into downstream input wires via one of the three put-paths chosen by `SolderType`.

**Successive-relearning tags.** Threshold concept *"binding selects a component's data-flow shape"*: probe at day 1 (have the learner state the `bind` vs `bindConsumer` distinction and the null-as-filter rule), day 3 (have the learner read an unfamiliar `*Wiring.java` constructor and name what's on the primary vs the secondary outputs), and ~2 weeks (have the learner predict how a component's wiring would have to change if a single output stream had to split into two typed outputs — i.e. when to reach for a secondary wire).

## Open questions

- [TBD: the secondary-output-wire / `flush()` / squelching interaction is not documented — the topic file flags this as a question for the engineer (`OutputWire` § secondary). Carrying that `[TBD]` through: do secondary output wires participate in `flush()` and squelching the same way the primary wire does? This lesson states what `bind`'s wrapper does (squelching short-circuits the handler before any forwarding happens), but does not claim coverage for explicit pushes onto secondary wires.]
- [TBD: per-topic `delta-map/wiring-framework.md` file does not exist; the delta is captured inline only. Reviewer to decide whether to lift it into the delta-map directory.]
- [TBD: glossary entries for "input wire", "binding", "primary output wire", "secondary output wire" are absent from `platform-sdk/docs/hashgraphGlossary.md` per the topic file's own note. This lesson's `kb_glossary_terms` is empty as a result.]
- [TBD: `invariants.md` does not yet exist (KB README marks it pending). Two claims in this lesson likely warrant INV-NNN once the catalog populates: (a) the one-shot binding rule (`InputWire.setHandler` throws if rebound), and (b) the "push to a secondary output wire only from inside the scheduler" convention. The first is enforced at runtime; the second is convention. Both are load-bearing for the thread-confinement argument the cluster builds.]
