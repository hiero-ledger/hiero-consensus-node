# consensus-metrics

Metrics collection and reporting for the consensus layer: counters, gauges, cycle statistics,
and the Prometheus exposition endpoint.

## Architecture

Sits above `consensus-model` in the supporting module DAG. Provides metrics infrastructure
consumed across the layer.

## Dependency Rules

May depend on:
- `consensus-model`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`

Must not depend on:
- Any functional-api, functional-impl, or self-contained functional module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `consensus-wiring-framework`, `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

Known violation — `requires transitive com.swirlds.metrics.impl`: should be removed;
to be addressed in a future cleanup.
