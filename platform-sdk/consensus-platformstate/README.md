# consensus-platformstate

The `PlatformState` Merkle node: platform state written by the execution layer and read by
the consensus layer for freeze timestamps and similar fields. Will eventually move to the
execution layer.

## Architecture

A passive data module — holds and exposes a well-defined slice of the Merkle tree state without
orchestrating anything, analogous to `consensus-model` but scoped to platform state. For the
fields it holds, see
[freeze and upgrade](../docs/consensus-layer/architecture/topics/freeze-and-upgrade.md),
[signed state management](../docs/consensus-layer/architecture/topics/signed-state-management.md),
and the [consensus/execution boundary](../docs/consensus-layer/architecture/interfaces/consensus-execution-boundary.md).

## Dependency Rules

May depend on:
- `consensus-model`
- `swirlds-base`, `swirlds-config-api`, `swirlds-state-api`, `swirlds-state-impl`

Must not depend on:
- Any `consensus-*-impl` module or structural-transitional module
- Any functional-api module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-component-framework`, `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-virtualmap`

No known violations.
