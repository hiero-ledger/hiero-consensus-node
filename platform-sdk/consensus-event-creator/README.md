# consensus-event-creator

Public API for the event creator: interfaces and configuration for deciding when to create a
self-event, choosing other-parents, and filling the event with transactions from the execution
layer.

## Architecture

The API half of the event-creator module pair. For how event creation works within the
consensus layer, see the
[event-creator topic](../docs/consensus-layer/architecture/topics/event-creator.md) and the
[consensus/execution boundary](../docs/consensus-layer/architecture/interfaces/consensus-execution-boundary.md).

## Dependency Rules

May depend on:
- `consensus-model`; any supporting module as needed
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`swirlds-component-framework`

Must not depend on:
- Other functional-api modules
- Any `*-impl` module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

No known violations.
