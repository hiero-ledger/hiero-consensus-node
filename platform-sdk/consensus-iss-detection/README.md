# consensus-iss-detection

Detects Inconsistent State Signatures (ISS): compares each round's locally-computed state hash
against the consensus of peer signatures, classifies any disagreement, and applies the
configured response.

## Architecture

A structural-transitional module — treated like an impl module: nothing should depend on it
except the platform wiring and test code. It bundles its public API and implementation in one
module rather than a split api/impl pair, and will move to the execution layer with the state
machinery it validates. For how detection and ISS handling work, see the
[ISS detection topic](../docs/consensus-layer/architecture/topics/iss-detection.md).

## Dependency Rules

May depend on:
- Supporting modules: `consensus-model`, `consensus-concurrent`, `consensus-roster`,
`consensus-utility`
- Functional-api modules: `consensus-hashgraph`, `consensus-pces`
- Structural-transitional module: `consensus-state`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`swirlds-component-framework`

Must not depend on:
- Any `consensus-*-impl` module — it depends on the functional APIs, not their implementations
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`, `swirlds-state-api`,
`swirlds-virtualmap`

Accepted exceptions:
- `requires com.swirlds.state.impl` — `swirlds-state-impl` is otherwise permitted only in
`consensus-platformstate`, `consensus-roster`, and `consensus-state`. It is pulled in here
because `consensus-state`'s signed-state types (`SignedState`, `ReservedSignedState`) expose
`swirlds-state-impl` types in their signatures. This is an accepted exception, not a violation;
it resolves when the module moves to the execution layer.
