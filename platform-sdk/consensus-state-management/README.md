# consensus-state-management

Orchestrates the signed-state pipeline: hashing each round's state, locally signing it, collecting
peer signatures to quorum, deciding when a round is persisted, and reading/writing the on-disk
snapshot. Holds the wiring (`StateManagementModule`) that connects these components and exposes the
latest complete state. Complements the runtime `SignedState` types in
[`consensus-state`](../consensus-state); will eventually move to the execution layer.

## Architecture

A structural-transitional module — treated like an impl module: nothing should depend on it except
the platform wiring, `consensus-reconnect-impl`, the `pcli` tooling, and test code. It bundles the
state-management components and their wiring in one module rather than a split api/impl pair, and
will move to the execution layer alongside the signed-state machinery it drives. For the lifecycle,
on-disk layout, and reservation discipline, see
[signed state management](../docs/consensus-layer/architecture/topics/signed-state-management.md).

## Dependency Rules

May depend on:
- Supporting modules: `consensus-model`, `consensus-metrics`, `consensus-platformstate`,
`consensus-roster`, `consensus-utility`
- Functional-api module: `consensus-pces`
- Structural-transitional module: `consensus-state`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`swirlds-component-framework`

Must not depend on:
- Other `consensus-*-impl` modules — except the accepted `consensus-pces-impl` exception below
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`

Accepted exceptions:
- `requires transitive com.swirlds.state.api`, `com.swirlds.state.impl`, `com.swirlds.virtualmap` —
otherwise permitted only in `consensus-state`. Hashing and snapshotting operate directly on the
state's merkle tree and virtual map, so these types appear in this module's signatures. Accepted
during modularization; resolves when the module moves to the execution layer.
- `requires org.hiero.consensus.pces.impl` — `SignedStateFileWriter` copies the PCES files needed to
replay a round into the snapshot directory via `DefaultPcesModule.copyPcesFilesRetryOnFailure`
([`SignedStateFileWriter.java`](src/main/java/org/hiero/consensus/state/management/SignedStateFileWriter.java));
no abstraction exists yet. Do not add further impl dependencies without equivalent justification.
