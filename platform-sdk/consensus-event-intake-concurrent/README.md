# consensus-event-intake-concurrent

Alternate concurrent implementation of event intake.

## Architecture

Implements the [`consensus-event-intake`](../consensus-event-intake) API using a concurrent
pipeline. Production code should depend on the API, not this module directly. For how intake
works, see the [event-intake topic](../docs/consensus-layer/architecture/topics/event-intake.md).

## Dependency Rules

May depend on:
- `consensus-event-intake` (its API), any supporting module
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`consensus-wiring-framework`

Must not depend on:
- Other `consensus-*-impl` modules
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

Known violation — `requires transitive org.hiero.consensus.roster`: `consensus-roster` is a
structural-transitional module that nothing outside that category should depend on (rules 3 and
7). This does not resolve on its own — the dependency has to be removed before `consensus-roster`
can move to the execution layer.
