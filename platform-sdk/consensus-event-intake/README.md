# consensus-event-intake

Public API for event intake: interfaces and configuration for receiving, validating,
deduplicating, and topologically ordering events before emitting them downstream.

## Architecture

The API half of the event-intake module pair. For how intake works, see the
[event-intake topic](../docs/consensus-layer/architecture/topics/event-intake.md).

## Dependency Rules

May depend on:
- `consensus-model`, `consensus-metrics`, `consensus-utility`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`consensus-wiring-framework`

Must not depend on:
- Other functional-api modules
- Any `*-impl` module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

Known violation — `requires transitive org.hiero.consensus.roster`: `consensus-roster` is a
structural-transitional module that nothing outside that category should depend on (rules 3 and
7). This does not resolve on its own — the dependency has to be removed before `consensus-roster`
can move to the execution layer.
