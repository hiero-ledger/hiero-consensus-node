# consensus-pces

Public API for Pre-Consensus Event Stream (PCES): interfaces and configuration for event
durability before events are handed to the hashgraph.

## Architecture

The API half of the PCES module pair. For PCES durability and replay across restarts, see
[restart and PCES](../docs/consensus-layer/architecture/topics/restart-and-pces.md).

## Dependency Rules

May depend on:
- `consensus-model`, `consensus-metrics`, `consensus-state`, `consensus-utility`
- `swirlds-base`, `swirlds-config-api`, `swirlds-metrics-api`, `swirlds-component-framework`

Must not depend on:
- Other functional-api modules
- Any `*-impl` module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

No known violations.
