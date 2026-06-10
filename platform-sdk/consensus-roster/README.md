# consensus-roster

Roster representation and lookup for the consensus layer. Will eventually move to the execution
layer (application).

## Architecture

A supporting module that holds the current and future roster structures. Rosters are carried as
round metadata so every module agrees on which roster applies to which round. For context, see
the [hashgraph topic](../docs/consensus-layer/architecture/topics/hashgraph.md) (round metadata)
and the [consensus/execution boundary](../docs/consensus-layer/architecture/interfaces/consensus-execution-boundary.md).

## Dependency Rules

May depend on:
- `consensus-model`
- `swirlds-base`, `swirlds-config-api`, `swirlds-state-api`, `swirlds-state-impl`

Must not depend on:
- Other supporting modules (`consensus-concurrent`, `consensus-metrics`, `consensus-utility`)
- Any functional-api or impl module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-component-framework`, `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-virtualmap`

No known violations.
