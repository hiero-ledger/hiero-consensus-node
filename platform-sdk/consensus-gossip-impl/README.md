# consensus-gossip-impl

Default implementation of gossip.

## Architecture

Implements the [`consensus-gossip`](../consensus-gossip) API. Production code should depend on
the API, not this module directly. For how gossip is implemented, see the
[gossip topic](../docs/consensus-layer/architecture/topics/gossip.md).

## Dependency Rules

May depend on:
- `consensus-gossip` (its API), `consensus-state`, any supporting module
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`swirlds-component-framework`

Must not depend on:
- Other `consensus-*-impl` modules
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

Known violations — `requires transitive com.swirlds.state.api`, `com.swirlds.state.impl`,
`com.swirlds.virtualmap`: transitional; acceptable during modularization but not permitted
in the final architecture.
