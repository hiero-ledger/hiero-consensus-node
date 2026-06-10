# consensus-reconnect-impl

Implementation of reconnect: orchestrating state transfer from a teacher node to a learner
node that has fallen behind.

## Architecture

Implements the [`consensus-reconnect`](../consensus-reconnect) API. Also wires directly into
the gossip networking layer — hence the known dependency on `consensus-gossip-impl`. For how
reconnect works, see the [reconnect topic](../docs/consensus-layer/architecture/topics/reconnect.md).

## Dependency Rules

May depend on:
- `consensus-reconnect` (its API), any supporting module
- Functional-api modules: `consensus-gossip`, `consensus-event-creator`, `consensus-event-intake`,
`consensus-hashgraph`, `consensus-pces`
- Structural-transitional module: `consensus-state`; supporting module: `consensus-platformstate`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`swirlds-component-framework`

Must not depend on:
- Other `consensus-*-impl` modules
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

Known violations:
- `requires transitive org.hiero.consensus.gossip.impl` — reconnect must wire directly into
gossip's internal networking layer; no abstraction exists yet. Do not add further impl
dependencies without equivalent justification.
- `requires transitive com.swirlds.platform.core` — the entire reconnect function will move
to the execution layer; this dependency is expected during the transition.
- `requires transitive com.swirlds.state.api`, `com.swirlds.state.impl`,
`com.swirlds.virtualmap` — transitional; acceptable during modularization but not permitted
in the final architecture.
