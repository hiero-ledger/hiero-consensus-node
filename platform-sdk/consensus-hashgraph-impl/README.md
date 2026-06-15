# consensus-hashgraph-impl

Default implementation of the hashgraph consensus algorithm.

## Architecture

Implements the [`consensus-hashgraph`](../consensus-hashgraph) API. Production code should
depend on the API, not this module directly. For the algorithm, see the
[hashgraph topic](../docs/consensus-layer/architecture/topics/hashgraph.md).

## Dependency Rules

May depend on:
- `consensus-hashgraph` (its API), any supporting module
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`swirlds-component-framework`

Must not depend on:
- Other `consensus-*-impl` modules
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

No known violations.
