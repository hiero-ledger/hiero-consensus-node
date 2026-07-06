# consensus-state

Holds a node's signed-state types (`SignedState`, `ReservedSignedState`) and manages their
lifecycle: hashing each round's state, signing it, collecting peer signatures to quorum, and
persisting the on-disk snapshot. Will eventually move to the execution layer.

## Architecture

A structural-transitional module — treated like an impl module: nothing should depend on it except
the platform wiring, `consensus-reconnect-impl`, the `pcli` tooling, and test code. It bundles its
API and implementation in one module rather than a split api/impl pair, and will move to the
execution layer alongside the state machinery it manages. For how signed-state management works, see
[signed state management](../docs/consensus-layer/architecture/topics/signed-state-management.md).

## Dependency Rules

May depend on:
- Supporting modules: `consensus-model`, `consensus-concurrent`, `consensus-metrics`,
`consensus-platformstate`, `consensus-roster`, `consensus-utility`
- Functional-api module: `consensus-pces`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`swirlds-component-framework`, `swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

Must not depend on:
- Any `consensus-*-impl` module — except the accepted `consensus-pces-impl` exception below
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`

Accepted exceptions:
- `requires org.hiero.consensus.pces.impl` — `SignedStateFileWriter` copies the PCES files needed to
replay a round into the snapshot directory via `DefaultPcesModule.copyPcesFilesRetryOnFailure`
([`SignedStateFileWriter.java`](src/main/java/org/hiero/consensus/state/SignedStateFileWriter.java));
no abstraction exists yet. Do not add further impl dependencies without equivalent justification.
