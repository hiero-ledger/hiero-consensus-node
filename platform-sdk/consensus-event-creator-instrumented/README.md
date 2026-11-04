# consensus-event-creator-instrumented

Instrumented implementation of the event creator for Otter tests. It wraps the default event
creator and receives commands from a test through the node's `EventBus` (in `base-utility`).
An Otter instrumented node selects it with the `modules.eventCreator` configuration property.

## Architecture

Implements the [`consensus-event-creator`](../consensus-event-creator) API by delegating to
[`consensus-event-creator-impl`](../consensus-event-creator-impl). All code lives in the
`testFixtures` source set, so it cannot end up in a production module graph.

## Dependency Rules

May depend on:
- `consensus-event-creator` (its API) and `consensus-event-creator-impl` (the wrapped implementation)
- Supporting modules, `consensus-wiring-framework`, `base-utility`
- `swirlds-base`, `swirlds-config-api`, `swirlds-metrics-api`, `swirlds-logging`

Must not depend on:
- Structural-transitional modules
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`

No known violations.
