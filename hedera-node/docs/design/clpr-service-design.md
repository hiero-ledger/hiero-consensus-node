# CLPR Service Design

## 1. Introduction

This document provides a detailed design for the Cross-Ledger PRotocol (CLPR) service. The CLPR service is a system-level RPC service responsible for exchanging state proofs with other ledgers to facilitate cross-ledger interactions.

### Prototype status (November 2025)

The current code base contains a working prototype that allows a single node to
bootstrap its own `ClprLedgerConfiguration`, refresh the configuration with an
advancing timestamp, and retrieve it via the gRPC query path. Configuration
transactions are gossiped and applied by consensus, so the prototype does not
manually push updates to every peer. Deployment is limited to developer builds; a
number of temporary bypasses are in place:

- **Signature bypass in ingest** – when `clpr.devModeEnabled` is `true`, the
  ingest workflow skips payer signature expansion for `CLPR_SET_LEDGER_CONFIG`
  transactions. This removes the requirement to configure signing material while
  the prototype is being iterated.
- **Development signer in the client** – `ClprClientImpl` looks for a local key in
  `data/onboard/devGenesisKeypair.pem` or `StartUpAccount.txt`. If no key is
  found the client submits an empty `SignatureMap`. This is sufficient for the
  prototype but must be replaced by the production signing story.
- **State proof shortcuts** – `ClprStateProofManager` fabricates state proofs from
  the in-memory merkle state and returns empty ledger IDs when snapshots are not
  yet available. These behaviours are guarded by `devModeEnabled` and exist only
  to keep the prototype running while the history service integration is being
  built.
- **Relaxed throttles** – the `genesis/throttles-dev.json` configuration allows
  `ClprGetLedgerConfig` requests by default so the locally running node can query
  without additional setup.

All of these shortcuts are intentionally scoped to the demo experience and must
be revisited before the CLPR service is enabled in production networks.

### Prototype progress (current)

The prototype has progressed beyond a "hello world" service stub and now
exercises real node plumbing end-to-end. Highlights:

- **Ledger configuration lifecycle is functional in dev mode**
  - The `ClprEndpoint` can bootstrap the local ledger configuration and
    periodically refresh it by submitting `CLPR_SET_LEDGER_CONFIG`
    transactions with a monotonically advancing timestamp.
  - Query and transaction handlers are wired through the normal gRPC workflow
    and update/read `CLPRSERVICE_I_CONFIGURATIONS`.
- **State proof tooling exists and is tested**
  - `BlockProvenStateAccessor` provides access to the latest sealed immutable
    state snapshot at a block boundary.
  - `StateProofBuilder`, `StateProofVerifier`, and `MerklePathBuilder` provide
    utilities for constructing and validating state proofs (currently used by
    the CLPR prototype alongside dev-mode shortcuts).
- **CLPR state changes are visible in block stream output**
  - CLPR map updates are represented in the block stream state-changes surface
    and can be decoded/rendered by tooling.
- **Test coverage has been added for the prototype paths**
  - Unit tests cover the endpoint/client/handler flows.
  - HAPI suites exercise the prototype end-to-end for local/demo runs.

For a pragmatic "where did we touch the node" inventory, see
`hedera-node/docs/design/clpr-prototype-integration-notes.md`.

## 2. Core Concepts

- **Ledger Configuration**: Each ledger participating in the CLPR network has a `ClprLedgerConfiguration`, which includes its unique `ClprLedgerId` and a list of network endpoints.
- **State Proofs**: The service relies on cryptographic state proofs to validate the authenticity of ledger configurations.

## 3. Service Architecture

The CLPR service is implemented as a modular component with a public API and a private implementation.

- **`hiero-clpr-interledger-service`**: This module defines the public API for the CLPR service, including the `ClprService` interface.
- **`hiero-clpr-interledger-service-impl`**: This module provides the concrete implementation of the service, including the `ClprEndpoint` for communication and the handlers for processing transactions and queries.

## 4. Client Architecture

The CLPR service includes a client component for interacting with remote ledgers.

- **`ClprClient` Interface**: This interface defines the contract for a client that can communicate with a remote CLPR endpoint. It includes methods for getting and setting ledger configurations.
- **`ClprClientImpl`**: This is the concrete implementation of the `ClprClient`. It uses a gRPC client to send queries and transactions to the remote service. In the prototype it signs transactions with a development key when available and otherwise submits an empty `SignatureMap`.

## 5. API and Operations

The CLPR service exposes the following operations through its gRPC endpoint:

- **`getLedgerConfiguration` (Query)**: Retrieves the `ClprLedgerConfiguration` for a given `ClprLedgerId`.
- **`setLedgerConfiguration` (Transaction)**: Sets or updates the `ClprLedgerConfiguration` for a remote ledger. This operation requires a valid state proof to be included in the transaction.

## 6. State Management

The CLPR service manages a single state in the merkle tree:

- **`CLPRSERVICE_I_CONFIGURATIONS`**: A map that stores `ClprLedgerConfiguration` objects, keyed by their `ClprLedgerId`.

## 7. Local Ledger Configuration Generation

The CLPR service is responsible for generating and maintaining its own ledger configuration. This process is triggered by specific events within other services.

### Trigger Conditions

The generation of the local `ClprLedgerConfiguration` is triggered under the following circumstances:
1.  **Ledger ID Update**: Any time the `HistoryService` sets or updates the ledger ID in the state, it will trigger this process. This is particularly important at genesis when the ledger ID is first established.
2.  **Roster Update**: When the `RosterService` adopts a new roster during a software upgrade or restart, it will trigger this process.

### Mechanism

When a trigger condition occurs, the responsible service (e.g., `HistoryService` via the `HandleWorkflow`, or `RosterService` via `Hedera.java`) will call a new method on the `ClprService`. This method will be provided with the active `Roster`, the network's `LedgerId`, and the current `consensusTime`.

The `ClprService` will then:
1.  Construct a complete `ClprSetLedgerConfigurationTransactionBody` containing the full local ledger configuration.
2.  Dispatch this transaction for handling.

The `ClprSetLedgerConfigurationHandler` will identify this transaction as a "local" transaction because its creator will be the node itself. For such transactions, the handler will bypass the state proof validation that is required for configurations received from remote ledgers.

### Prototype behaviour

In developer builds the `ClprEndpoint` runs every `clpr.connectionFrequency`
milliseconds. Each cycle it either bootstraps a configuration (when none exists)
or resubmits the current configuration with a freshly generated timestamp based
on the system clock. The resulting transaction is ingested without signature
checks (per the bypass above) and is gossiped through the regular consensus
pipeline, so every node observes the same state transition without the endpoint
manually iterating through peers. Remote fetches still return dev-mode state
proofs constructed by `ClprStateProofManager`.

## 8. Handlers and State

The following table details the handlers for the CLPR service and the state they modify:

| gRPC Endpoint            | Handler                             | Input                                       | Output                               | State Modified                 |
|:-------------------------|:------------------------------------|:--------------------------------------------|:-------------------------------------|:-------------------------------|
| `getLedgerConfiguration` | `ClprGetLedgerConfigurationHandler` | `ClprGetLedgerConfigurationQuery`           | `ClprGetLedgerConfigurationResponse` | None                           |
| `setLedgerConfiguration` | `ClprSetLedgerConfigurationHandler` | `ClprSetLedgerConfigurationTransactionBody` | `TransactionReceipt`                 | `CLPRSERVICE_I_CONFIGURATIONS` |

## 9. Future Work

The following items are planned for the CLPR service prototype, based on the work outlined in GitHub issue #20111 and its sub-issues.

### 9.1. State Proof Integration

- Integrate with the platform's state proof generation mechanism to provide cryptographic guarantees of ledger configurations.
  - Add and integrate the `StateProof` protobuf into the `BlockProof`.
  - Create a `StateProof` accessor to provide the latest block proof and state.
  - Add utility classes for constructing and verifying state proofs.
  - Add a State API to convert a `MerklePath` into a `MerkleProof`.
- Implement state proof verification in the `ClprSetLedgerConfigurationHandler`.
- Conduct "Ship of Theseus" testing to ensure that ledger configurations remain up-to-date as the network's node composition changes.

### 9.2. Message Queue API

- Implement a message queue system for passing application-level messages between ledgers.
- Define protobuf formats for application messages and replies.
- Create incoming and outgoing message queues.
- Implement back-pressure mechanisms and throttles to prevent network flooding.

### 9.3. Payment and Signing

- Implement a system for billing linked payment accounts for cross-ledger transactions.
- Integrate node signing keys for signing state proofs.
- Implement signature verification for all state proofs.

### 9.4. Malicious Node Testing

- Develop tests for various malicious node scenarios, including:
  - Nodes that fail to connect or disobey throttles.
  - Nodes that flood the network with duplicate messages.
  - Nodes that provide false state proofs.

### 9.5. Testing and Metrics

- Develop a comprehensive end-to-end testing framework for running multiple ledgers locally.
  - Modify the test launcher to spin up multiple networks with multiple nodes in each.
  - Enhance `HapiClients` to communicate with multiple networks.
  - Create end-to-end tests that perform DAB transactions to replace the node composition of multiple networks.
- Implement key performance metrics, such as messages/second, bytes/second, and duplicate state proof counts.
- Create Grafana dashboards for visualizing CLPR metrics.

### 9.6. Tooling

- Add support for `GetLedgerConfiguration` and `SetLedgerConfiguration` to `yahcli`.
- Create HAPI test framework operation classes for `HapiGetLedgerConfig` and
  `HapiSetClprLedgerConfig`.
