# consensus-concurrent

Concurrency primitives for the consensus layer: thread pools, queues, throttles, and the
concurrency abstractions consumed by the functional modules.

## Architecture

Sits one level above `consensus-model` in the supporting module DAG. Provides the concurrency
abstractions that higher-level consensus modules build on. For how these primitives interact
with the wiring framework and backpressure, see the
[wiring-framework topic](../docs/consensus-layer/architecture/topics/wiring-framework.md) and
[health monitor and backpressure](../docs/consensus-layer/architecture/topics/health-monitor-and-backpressure.md).

## Dependency Rules

May depend on:
- `consensus-model`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`

Must not depend on:
- Other supporting modules (`consensus-metrics`, `consensus-utility`, `consensus-roster`) — must not pull in higher-level helpers
- Any functional-api or impl module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-component-framework`, `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

No known violations.
