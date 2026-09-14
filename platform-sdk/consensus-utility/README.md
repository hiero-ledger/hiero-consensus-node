# consensus-utility

General-purpose helpers for the consensus layer: event validation, crypto helpers, transaction
handling, orphan tracking, and monitoring utilities.

## Architecture

The top of the supporting module DAG — sits above `consensus-model` and `consensus-metrics`.

## Dependency Rules

May depend on:
- `consensus-model`, `consensus-metrics`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`

Must not depend on:
- Any functional-api, functional-impl, or self-contained functional module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `consensus-wiring-framework`, `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

Known violation — `requires org.hiero.consensus.roster`: `consensus-roster` is a
structural-transitional module that nothing outside that category should depend on (rules 3 and
7). This does not resolve on its own — the dependency has to be removed before `consensus-roster`
can move to the execution layer.
