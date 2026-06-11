# consensus-metrics

Metrics collection and reporting for the consensus layer: counters, gauges, cycle statistics,
and the Prometheus exposition endpoint.

## Architecture

Sits above `consensus-model` and `consensus-concurrent` in the supporting module DAG. Provides
metrics infrastructure consumed across the layer.

## Dependency Rules

May depend on:
- `consensus-model`, `consensus-concurrent`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`

Must not depend on:
- Any functional-api or impl module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-component-framework`, `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

Known violation — `requires transitive com.swirlds.metrics.impl`: should be removed;
to be addressed in a future cleanup.
