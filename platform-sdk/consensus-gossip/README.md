# consensus-gossip

Public API for gossip: interfaces and configuration for peer-to-peer event communication,
neighbor discipline, falling-behind detection, and buffering for catch-up.

## Architecture

The API half of the gossip module pair — the only consensus module that talks directly to the
network. For how gossip works, see the
[gossip topic](../docs/consensus-layer/architecture/topics/gossip.md).

## Dependency Rules

May depend on:
- `consensus-model`, `consensus-utility`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`consensus-wiring-framework`

Must not depend on:
- Other functional-api modules
- Any `*-impl` module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

Known violations:
- `requires transitive com.swirlds.state.api`, `com.swirlds.state.impl`, `com.swirlds.virtualmap` —
transitional; acceptable during modularization but not permitted in the final architecture.
- `requires org.hiero.consensus.state` — `consensus-state` is a structural-transitional module that
nothing outside that category should depend on (rules 3 and 7). This does not resolve on its own —
the dependency has to be removed before `consensus-state` can move to the execution layer.
