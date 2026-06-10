# consensus-state

Consensus-side state structures shared at the boundary with the execution layer: signed state,
state snapshots, and the signing/hashing pipeline. Will eventually move to the execution layer.

## Architecture

A structural-transitional module — treated like an impl module until it moves to the execution
layer. Production code should not depend on it directly. For its role, see
[signed state management](../docs/consensus-layer/architecture/topics/signed-state-management.md)
and the [consensus/execution boundary](../docs/consensus-layer/architecture/interfaces/consensus-execution-boundary.md).

## Dependency Rules

May depend on:
- `consensus-model`, `consensus-metrics`, `consensus-platformstate`, `consensus-roster`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

Must not depend on:
- Any `consensus-*-impl` module or functional-api module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-component-framework`, `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`

No known violations.
