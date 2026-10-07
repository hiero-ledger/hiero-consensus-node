# consensus-platformstate

The `PlatformState` Merkle node: platform state written by the execution layer and read by
the consensus layer for freeze timestamps and similar fields. Will eventually move to the
execution layer.

## Architecture

A structural-transitional module, and a passive data module within that category — it holds and
exposes a well-defined slice of the Merkle tree state without orchestrating anything, analogous to
`consensus-model` but scoped to platform state. It will move to the execution layer alongside the platform
state it wraps.

## Dependency Rules

May depend on:
- `consensus-model`
- `swirlds-base`, `swirlds-config-api`, `swirlds-state-api`, `swirlds-state-impl`

Must not depend on:
- Any `consensus-*-impl` module or other structural-transitional module
- Any functional-api or self-contained functional module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `consensus-wiring-framework`, `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-virtualmap`

No known violations.
