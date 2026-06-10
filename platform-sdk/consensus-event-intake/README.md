# consensus-event-intake

Public API for event intake: interfaces and configuration for receiving, validating,
deduplicating, and topologically ordering events before emitting them downstream.

## Architecture

The API half of the event-intake module pair. For how intake works, see the
[event-intake topic](../docs/consensus-layer/architecture/topics/event-intake.md) and
concepts [event-lifecycle](../docs/consensus-layer/concepts/event-lifecycle.md),
[branching](../docs/consensus-layer/concepts/branching.md),
[stale-events](../docs/consensus-layer/concepts/stale-events.md),
[birth-round](../docs/consensus-layer/concepts/birth-round.md).

## Dependency Rules

May depend on:
- `consensus-model`, `consensus-metrics`, `consensus-roster`, `consensus-utility`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`swirlds-component-framework`

Must not depend on:
- Other functional-api modules
- Any `*-impl` module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

No known violations.
