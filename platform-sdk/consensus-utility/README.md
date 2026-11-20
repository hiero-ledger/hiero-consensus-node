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

No known violations.
