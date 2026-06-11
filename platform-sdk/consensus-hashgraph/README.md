# consensus-hashgraph

Public API for the hashgraph consensus algorithm: interfaces and configuration for running the
algorithm and emitting consensus rounds with timestamps and round metadata.

## Architecture

The API half of the hashgraph module pair. For the algorithm, see the
[hashgraph topic](../docs/consensus-layer/architecture/topics/hashgraph.md).

## Dependency Rules

May depend on:
- `consensus-model`, `consensus-metrics`
- `swirlds-base`, `swirlds-config-api`, `swirlds-metrics-api`, `swirlds-component-framework`

Must not depend on:
- Other functional-api modules
- Any `*-impl` module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

No known violations.
