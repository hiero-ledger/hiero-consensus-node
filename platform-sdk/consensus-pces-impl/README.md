# consensus-pces-impl

Default implementation of PCES file handling.

## Architecture

Implements the [`consensus-pces`](../consensus-pces) API. Production code should depend on
the API, not this module directly. For PCES internals, see
[restart and PCES](../docs/consensus-layer/architecture/topics/restart-and-pces.md).

## Dependency Rules

May depend on:
- `consensus-pces` (its API), `consensus-state`, any supporting module
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`swirlds-component-framework`

Must not depend on:
- Other `consensus-*-impl` modules
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

No known violations.
