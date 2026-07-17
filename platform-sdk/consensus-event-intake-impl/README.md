# consensus-event-intake-impl

Default implementation of event intake.

## Architecture

Implements the [`consensus-event-intake`](../consensus-event-intake) API. Production code
should depend on the API, not this module directly. For the intake pipeline, see the
[event-intake topic](../docs/consensus-layer/architecture/topics/event-intake.md).

## Dependency Rules

May depend on:
- `consensus-event-intake` (its API), any supporting module
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`swirlds-component-framework`

Must not depend on:
- Other `consensus-*-impl` modules
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

No known violations.
