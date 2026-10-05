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
- Supporting modules: `consensus-model`, `consensus-utility`
- Functional-api modules: `consensus-hashgraph`, `consensus-pces`
- Structural-transitional modules: `consensus-roster`, `consensus-state`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`consensus-wiring-framework`, `swirlds-state-api`, `swirlds-state-impl`

Must not depend on:
- Any `consensus-*-impl` module — it depends on the functional APIs, not their implementations
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`, `swirlds-virtualmap`

No known violations.

`swirlds-state-impl` is pulled in because `consensus-state`'s signed-state types
([`SignedState.java`](../consensus-state/src/main/java/org/hiero/consensus/state/signed/SignedState.java),
[`ReservedSignedState.java`](../consensus-state/src/main/java/org/hiero/consensus/state/signed/ReservedSignedState.java))
expose `swirlds-state-impl` types such as `VirtualMapState` in their signatures.
