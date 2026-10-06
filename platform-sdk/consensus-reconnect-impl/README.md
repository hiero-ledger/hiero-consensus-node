# consensus-reconnect-impl

Implementation of reconnect: orchestrating state transfer from a teacher node to a learner
node that has fallen behind.

## Architecture

A structural-transitional module — treated like an impl module: nothing outside that category
should depend on it except platform wiring, tooling, and test code. Implements the
[`consensus-reconnect`](../consensus-reconnect) API and wires directly into the gossip networking
layer — hence the known dependency on `consensus-gossip-impl`. The entire reconnect function will
move to the execution layer. For how
reconnect works, see the [reconnect topic](../docs/consensus-layer/architecture/topics/reconnect.md).

## Dependency Rules

May depend on:
- `consensus-reconnect` (its API), any supporting module
- Functional-api modules: `consensus-gossip`, `consensus-event-creator`, `consensus-event-intake`,
`consensus-hashgraph`, `consensus-pces`
- Structural-transitional modules: `consensus-iss-detection`, `consensus-platformstate`,
`consensus-roster`, `consensus-state`, `consensus-transaction-handling`
- Self-contained functional module: `consensus-status-monitor`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`consensus-wiring-framework`

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

`org.hiero.consensus.iss.detection` and `org.hiero.consensus.transaction.handling` are permitted
under rule 7, but neither is named anywhere in this module's sources: both are required because
`DefaultReconnectModule` receives `ConsensusLayerBuildingBlocks`, whose record components are typed
`IssDetectionModule` and `TransactionHandlingModule`. A consequence of the
`com.swirlds.platform.core` dependency above, and it resolves with the same move to the execution
layer.
