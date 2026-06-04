---
id: ADR-005
title: Embed a future-event buffer inside each consuming component instead of one standalone buffering component
topics: [hashgraph, event-creator, event-intake, wiring-framework]
related:
  invariants: []
  decisions: []
  scenarios: []
  heuristics: []
  rules: [RUL-002]
status: accepted
date: 2026-06-03
deciders:
  - Kelly Greco (@poulok)
  - Lazar Petrovic (@lpetrovic05)
curated_by: Kelly Greco (@poulok)
---

# ADR-005 — Embed a future-event buffer inside each consuming component instead of one standalone buffering component

## Context

Events carry a **birth round**. An event whose birth round is greater than
what a consumer is currently ready to process is a **future event**: it has
been received and fully validated, but it cannot be acted on yet. It must be
held until the consumer's **event window** advances far enough to "catch up"
to it, at which point it is released for processing. (Far-future events beyond
the event horizon, which cannot be validated, are never stored — only
near-future events are buffered.)

Two components in the consensus layer need this future-event holding behavior,
and they need it relative to **different thresholds**:

- The **hashgraph** (`DefaultConsensusEngine`) must not feed an event into the
  consensus algorithm until the pending consensus round has reached the event's birth
  round. Its threshold is the **pending consensus round**
  (`FutureEventBufferingOption.PENDING_CONSENSUS_ROUND`).
- The **event creator** (`DefaultEventCreationManager`) must not register an
  event as a potential parent until the event is non-future relative to the
  birth round it would assign to a newly created event. Its threshold is the
  **new-event birth round** (`FutureEventBufferingOption.EVENT_BIRTH_ROUND`).

The shared holding logic is implemented once, in the reusable
`FutureEventBuffer` class (`consensus-utility`), which is parameterized by a
`FutureEventBufferingOption` so the same data structure serves both thresholds.

### The pressure: where does the buffer live?

The structural question is whether `FutureEventBuffer` should be (a) a single
**standalone wiring component** placed upstream of both the hashgraph and the
event creator, releasing events to both as the event window advances, or (b)
**embedded as a private helper inside each of the two components** that needs
it.

This interacts directly with the intake-pipeline flush
([RUL-002](../rules/RUL-002-intake-flush-ordering.md)). The flush walks the
pipeline component-by-component in topological order, waiting for each
component's queue to drain and its last task to complete before advancing to
the next. That single ordered pass is only guaranteed to leave every event
"as far along as it can go" if the pipeline has **no feedback edges** — no
component whose progress causes an *upstream* component to emit more events.

A standalone future-event buffer feeding the hashgraph creates exactly such a
feedback loop:

```
        ┌──────────────────────────────────────┐
        │                                       │
        ▼                                       │
events released by the buffer                   │
        │                                       │
        ▼                                       │
hashgraph reaches more consensus rounds         │
        │                                       │
        ▼                                       │
event window advances  ─────────────────────────┘   (loop)
```

As consensus makes progress the event window advances; as the event window
advances the standalone buffer would release more events into the hashgraph;
those events can cause more rounds to reach consensus; which advances the
event window again. A single ordered flush pass does not converge over a loop
like this — the flush logic would have to iterate the buffer/hashgraph pair to
a fixed point, becoming substantially more complex and more fragile, just to
restore the guarantee that no event is left stranded mid-pipeline.

## Decision

**Give each consuming component its own `FutureEventBuffer` as a private,
in-process helper data structure, rather than introducing a single standalone
buffering component upstream of both consumers.**

- `DefaultConsensusEngine` constructs its own `FutureEventBuffer` with
  `PENDING_CONSENSUS_ROUND` and drives it inside `addEvent(...)`. The
  consensus-advance → event-window-advance → release-buffered-events loop is
  closed **within the handling of a single `addEvent` task**: the method's
  internal `while (!eventsToAdd.isEmpty())` loop calls
  `futureEventBuffer.updateEventWindow(eventWindow)` each time a round reaches
  consensus and feeds the released events back into the same loop until no
  more rounds reach consensus.
- `DefaultEventCreationManager` constructs its own `FutureEventBuffer` with
  `EVENT_BIRTH_ROUND`. `registerEvent(...)` buffers future events;
  `setEventWindow(...)` releases the now-current events directly to the
  underlying creator.

Because each loop is contained inside a single component's task handling, the
loop never crosses a wiring edge. When that component's task queue is empty,
no further rounds will reach consensus and no further buffered events will be
released **on the component's own initiative**. That is precisely the property
[RUL-002](../rules/RUL-002-intake-flush-ordering.md) relies on: the
intake-pipeline flush remains a single ordered, component-by-component pass,
with no special fixed-point iteration.

## Consequences

### Positive

- **Keeps the flush simple and correct.** `PlatformCoordinator
  .flushIntakePipeline()` stays a fixed, linear sequence of `flush()` calls.
  No fixed-point iteration over a buffer/hashgraph loop is needed to guarantee
  every event has advanced as far as it can.
- **No feedback edge in the wiring graph.** The consensus-progress loop is an
  implementation detail internal to `DefaultConsensusEngine.addEvent(...)`,
  invisible to the wiring topology and to the flush logic.
- **Each consumer buffers against its own correct threshold.** Embedding lets
  each component pick the `FutureEventBufferingOption` it actually needs
  (`PENDING_CONSENSUS_ROUND` vs `EVENT_BIRTH_ROUND`) without a shared
  component having to serve two different release policies.
- **Logic stays reusable without being centralized.** The holding mechanism is
  still written once in `FutureEventBuffer`; only its *placement* is
  distributed.

### Negative

- **The buffering mechanism exists in two places.** Two components each
  instantiate and drive a `FutureEventBuffer`, so a reader must know to look in
  both `DefaultConsensusEngine` and `DefaultEventCreationManager` to see the
  full picture, and lifecycle concerns (clear-on-reset, event-window updates,
  metrics naming) are handled in each independently.
- **The consensus-progress loop is hidden inside `addEvent`.** The feedback
  loop still exists; it has been moved from the wiring graph into a method
  body. A maintainer editing `addEvent(...)` must preserve the
  drain-to-fixed-point behavior of the internal `while` loop, or the flush
  guarantee silently breaks.

### Neutral

- The two buffers register metrics under distinct names (`"consensus"` and
  `"eventCreator"`), so they remain individually observable despite sharing an
  implementation.
- This decision establishes a soft convention: holding/buffering logic that
  participates in a progress loop should be contained within the component
  that drives the loop, not factored upstream of it, so the intake flush stays
  a single ordered pass.

## Alternatives Considered

### 1. Single standalone future-event buffer component gating both consumers

Introduce one `FutureEventBuffer` as its own wiring component, placed upstream
of both the hashgraph and the event creator, releasing buffered events to both
as the event window advances.

**Rejected because:**

- It creates a feedback loop in the event pipeline: consensus progress
  advances the event window, which makes the standalone buffer release more
  events, which can drive more consensus progress.
- `flushIntakePipeline()` would have to become significantly more complex —
  iterating the buffer/hashgraph pair to a fixed point — to keep its guarantee
  that no event is left stranded mid-pipeline after PCES replay. That
  complexity is exactly what threatens the no-branching guarantee in
  [RUL-002](../rules/RUL-002-intake-flush-ordering.md).
- A single component would also have to reconcile two different release
  thresholds (pending consensus round vs new-event birth round) for its two
  consumers.

### 2. Status quo — no future-event buffering at all

Drop or never add buffering and simply discard events that arrive before their
consumer is ready.

**Rejected because:**

- Validated near-future events would be thrown away and would have to be
  re-gossiped later, wasting work and delaying consensus. Buffering them until
  the event window catches up is the behavior the system requires.

### 3. Embed a future-event buffer in each consuming component (selected)

See **Decision** above.

## References

- `consensus-utility/.../FutureEventBuffer.java` — the reusable holding data
  structure, parameterized by `FutureEventBufferingOption`.
- `consensus-utility/.../FutureEventBufferingOption.java` — the two thresholds
  (`PENDING_CONSENSUS_ROUND`, `EVENT_BIRTH_ROUND`) and their release-round
  arithmetic.
- `consensus-hashgraph-impl/.../DefaultConsensusEngine.java` — owns the
  `PENDING_CONSENSUS_ROUND` buffer; `addEvent(...)` closes the
  consensus-progress loop inside a single task.
- `consensus-event-creator-impl/.../DefaultEventCreationManager.java` — owns
  the `EVENT_BIRTH_ROUND` buffer; releases events to the creator on
  `setEventWindow(...)`.
- `swirlds-platform-core/.../PlatformCoordinator.java` —
  `flushIntakePipeline()`, the ordered flush this decision keeps simple.
- [RUL-002](../rules/RUL-002-intake-flush-ordering.md) — the flush-ordering
  rule whose single-pass guarantee depends on this decision.
