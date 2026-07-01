# consensus-transaction-handling

Applies consensus rounds to the working state and prehandles incoming events against the latest
immutable state, invoking the execution layer through `TransactionCallbacks` (prehandle / handle /
seal). Produces the per-round immutable `SignedState` that feeds the signed-state pipeline, holds
the latest-immutable-state nexus, and owns the wiring (`TransactionHandlingModule`). Will eventually
move to the execution layer.

## Architecture

A structural-transitional module — treated like an impl module: nothing should depend on it except
the platform wiring, `consensus-reconnect-impl`, tooling, and test code. It bundles the transaction
handler, prehandler, and their wiring in one module rather than a split api/impl pair, and drives
the consensus/execution boundary, so it will move to the execution layer. For the boundary handshake
it exercises, see
[consensus / execution boundary](../docs/consensus-layer/architecture/interfaces/consensus-execution-boundary.md);
for the state pipeline it feeds, see
[signed state management](../docs/consensus-layer/architecture/topics/signed-state-management.md).

## Dependency Rules

May depend on:
- Supporting modules: `consensus-model`, `consensus-metrics`, `consensus-platformstate`,
`consensus-utility`
- Functional-api module: `consensus-hashgraph`
- Structural-transitional module: `consensus-state`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`swirlds-component-framework`

Must not depend on:
- Any `consensus-*-impl` module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`

Accepted exceptions:
- `requires transitive com.swirlds.state.api`, `com.swirlds.state.impl`, `com.swirlds.virtualmap` —
otherwise permitted only in `consensus-state` and `consensus-state-management`. The handler applies
transactions directly to the state's merkle tree and virtual map, so these types appear in this
module's signatures. Accepted during modularization; resolves when the module moves to the execution
layer.
- `requires org.hiero.consensus.event.stream` — `consensus-event-stream` is itself treated like an
impl module (nothing should depend on it). `DefaultTransactionHandler` reads `EventStreamWiringConfig`
to drive the legacy running event hash for the soon-to-be-retired consensus event stream
([`DefaultTransactionHandler.java`](src/main/java/org/hiero/consensus/transaction/handling/internal/DefaultTransactionHandler.java));
the coupling disappears when the event stream is deleted.
