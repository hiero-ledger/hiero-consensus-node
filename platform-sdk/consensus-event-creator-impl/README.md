# consensus-event-creator-impl

Default implementation of the event creator. Uses a Tipset-based strategy for other-parent
selection, governs event-creation cadence, and fills events with transactions from the
execution layer.

## Architecture

Implements the [`consensus-event-creator`](../consensus-event-creator) API. Production code
should depend on the API, not this module directly. For how the event creator works, see the
[event-creator topic](../docs/consensus-layer/architecture/topics/event-creator.md).

## Dependency Rules

May depend on:
- `consensus-event-creator` (its API), any supporting module
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`swirlds-component-framework`

Must not depend on:
- Other `consensus-*-impl` modules
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

No known violations.
