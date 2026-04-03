# CLPR Hiero Implementation Specification

This document maps the [CLPR Protocol Specification](clpr-service-spec.md) to Hiero's native constructs. It is the
authoritative reference for engineers implementing CLPR on Hiero consensus nodes. Each section corresponds to a
concrete implementation area and is designed to be decomposed directly into epics and issues.

For architectural rationale and cross-platform protocol details, see the companion
[CLPR Design Document](clpr-service.md) and [CLPR Protocol Specification](clpr-service-spec.md).

---

## Table of Contents

1. [HAPI Transaction Definitions](#1-hapi-transaction-definitions)
2. [State Schema](#2-state-schema)
3. [Service Implementation](#3-service-implementation)
4. [Endpoint Implementation](#4-endpoint-implementation)
5. [Verifier Integration](#5-verifier-integration)
6. [Connector Authorization](#6-connector-authorization)
7. [Application Dispatch](#7-application-dispatch)
8. [Security Model](#8-security-model)
9. [Configuration](#9-configuration)
10. [Fee Schedule](#10-fee-schedule)
11. [Platform-Specific Gaps and Decisions](#11-platform-specific-gaps-and-decisions)
12. [Inconsistencies and Findings](#12-inconsistencies-and-findings)

---

# 1. HAPI Transaction Definitions

Each pseudo-API from the cross-platform spec section 6 maps to either a HAPI transaction (state-modifying operation
that goes through consensus) or a HAPI query (read-only operation). All transactions are submitted as
`TransactionBody` oneofs through the standard HAPI gRPC pipeline. All queries follow the standard HAPI
`Query`/`Response` pattern.

## 1.1 TransactionBody Oneofs

The following new `oneof` variants are added to `TransactionBody`:

| Pseudo-API (spec section 6) | HAPI Transaction Type          | TransactionBody oneof field    | Authority                                         |
|-----------------------------|--------------------------------|--------------------------------|---------------------------------------------------|
| `setLedgerConfiguration`    | `ClprSetLedgerConfiguration`   | `clprSetLedgerConfiguration`   | CLPR admin (council key)                          |
| `registerConnection`        | `ClprRegisterConnection`       | `clprRegisterConnection`       | Any (permissionless)                              |
| `closeConnection`           | `ClprCloseConnection`          | `clprCloseConnection`          | CLPR admin (transitions through CLOSING → DRAINED → CLOSED) |
| `registerConnector`         | `ClprRegisterConnector`        | `clprRegisterConnector`        | Any (requires initial funds)                      |
| `topUpConnector`            | `ClprTopUpConnector`           | `clprTopUpConnector`           | Connector admin                                   |
| `withdrawConnectorBalance`  | `ClprWithdrawConnectorBalance` | `clprWithdrawConnectorBalance` | Connector admin                                   |
| `deregisterConnector`       | `ClprDeregisterConnector`      | `clprDeregisterConnector`      | Connector admin                                   |
| `submitBundle`              | `ClprSubmitBundle`             | `clprSubmitBundle`             | Any (typically endpoint)                          |
| `redactMessage`             | `ClprRedactMessage`            | `clprRedactMessage`            | CLPR admin                                        |

**Note:** There is no `reportMisbehavior` transaction. Misbehavior detection and enforcement are strictly
local (see cross-platform spec §1.6).

**Note:** `sendMessage` is a **system contract method**, not a HAPI transaction. Application smart contracts
call it directly from EVM execution context. See [section 6](#6-connector-authorization) for details.

**Note:** `registerEndpoint` and `deregisterEndpoint` from spec section 6.5 are **not implemented** on Hiero.
Endpoints are derived automatically from the active roster (see [section 4](#4-endpoint-implementation)).

**Note:** `updateEndpointRoster` from spec section 6.2 is **not implemented**. Peer endpoint discovery is
handled off-chain via gossip (see cross-platform spec §5.4). Peer endpoint data is not stored in on-ledger state.

## 1.2 Protobuf Message Definitions

### 1.2.1 Configuration Management

```protobuf
// Sets or updates this ledger's local CLPR configuration.
// Requires the CLPR admin key (governing council).
// ConfigUpdate control messages are lazily enqueued on each Connection at the
// next interaction (see cross-platform spec §1.3).
//
// The following fields are NOT caller-supplied and are set automatically:
//   - protocol_version: determined by the CLPR service code version, not the caller.
//   - chain_id: immutable; determined by the ledger at CLPR activation time.
//   - timestamp: set to the consensus timestamp of this transaction.
message ClprSetLedgerConfigurationTransactionBody {
  // The new configuration. The protocol_version, chain_id, and timestamp
  // fields are ignored if supplied — the handler sets them automatically.
  ClprLedgerConfiguration configuration = 1;
}
```

### 1.2.2 Connection Management

```protobuf
// Registers a new Connection to a peer ledger.
// Note: chain_id and service_address are NOT supplied by the caller. The peer's
// chain_id and service_address are derived from the peer's CLPR service configuration
// (obtained via the verifier during the first successful sync), and the local ledger's
// own identity is determined at CLPR activation time.
message ClprRegisterConnectionTransactionBody {
  // Connection ID: keccak256(uncompressed_public_key). Exactly 32 bytes.
  bytes connection_id = 1;

  // ECDSA_secp256k1 signature over the registration payload, proving
  // the caller controls the Connection ID keypair.
  // Signed payload: keccak256(connection_id || verifier_contract || caller_account_id)
  // See section 11.5 for verification details.
  bytes ecdsa_signature = 2;

  // ECDSA_secp256k1 public key.
  // MUST be 64 bytes (uncompressed x||y coordinates without the 0x04 prefix).
  // The Connection ID is derived as keccak256(ecdsa_public_key).
  bytes ecdsa_public_key = 3;

  // Account ID of the locally deployed verifier system contract.
  // The verifier is immutable — it cannot be changed after registration.
  AccountID verifier_contract = 4;

  // Opaque proof bytes from the peer ledger containing the peer's configuration.
  // Passed to verifyConfig() on the verifier contract to obtain the verified
  // ClprLedgerConfiguration (chain_id, service_address, throttles, timestamp).
  bytes config_proof_bytes = 5;
}

// Initiates graceful close of a Connection. Transitions to CLOSING (the
// ClprQueueMetadata.state reflects the Connection status and is propagated to
// the peer). When the peer acks all outbound messages, the Connection
// transitions to DRAINED. Transitions to CLOSED when both sides are DRAINED.
// Valid from ACTIVE or PAUSED status (see cross-platform spec §2.1.1).
message ClprCloseConnectionTransactionBody {
  bytes connection_id = 1;
}
```

### 1.2.3 Connector Management

```protobuf
// Registers a Connector on a Connection.
message ClprRegisterConnectorTransactionBody {
  bytes connection_id = 1;
  // Address of the counterpart Connector on the source (peer) ledger.
  bytes source_connector_address = 2;
  // Account ID of the Connector's authorization contract (system contract).
  AccountID connector_contract = 3;
  // Initial balance in tinybars for message execution on this ledger.
  uint64 initial_balance = 4;
  // Stake in tinybars locked against misbehavior.
  uint64 stake = 5;
  // Account authorized to manage this Connector (top-up, withdraw, deregister).
  // Defaults to the transaction payer if not specified.
  AccountID admin = 6;
}

// Adds funds to a Connector's balance.
message ClprTopUpConnectorTransactionBody {
  bytes connection_id = 1;
  bytes source_connector_address = 2;
  uint64 amount = 3;
}

// Withdraws surplus funds from a Connector's balance (not locked stake).
message ClprWithdrawConnectorBalanceTransactionBody {
  bytes connection_id = 1;
  bytes source_connector_address = 2;
  uint64 amount = 3;
}

// Deregisters a Connector and returns remaining funds and stake.
message ClprDeregisterConnectorTransactionBody {
  bytes connection_id = 1;
  bytes source_connector_address = 2;
}
```

### 1.2.4 Messaging

```protobuf
// Sends a cross-ledger message via a Connector.
message ClprSendMessageTransactionBody {
  bytes connection_id = 1;
  // Address of the Connector's authorization contract on the local ledger.
  // This is the key used to look up the Connector in the CONNECTORS state store
  // (i.e., the source_connector_address component of the ClprConnectorKey).
  bytes connector_id = 2;
  // Destination application address on the peer ledger.
  bytes target_application = 3;
  // Opaque application payload.
  bytes message_data = 4;
}

// Submits a bundle received from a peer endpoint for on-chain processing.
// The signer's identity is recovered from the ECDSA signature via ecrecover
// and matched against registered ecdsa_signing_key entries in the Connection's
// endpoint roster — no explicit public key field is needed.
message ClprSubmitBundleTransactionBody {
  bytes connection_id = 1;
  // Opaque proof bytes from ClprSyncPayload.proof_bytes.
  bytes proof_bytes = 2;
  // ECDSA secp256k1 endpoint signature from ClprSyncPayload.endpoint_signature.
  // Signed data: keccak256(connection_id || proof_bytes).
  bytes remote_endpoint_signature = 3;
}

// Redacts a message from the outbound queue before delivery.
message ClprRedactMessageTransactionBody {
  bytes connection_id = 1;
  uint64 message_id = 2;
}
```

### 1.2.5 Transaction Records

Each CLPR transaction produces a `TransactionRecord` with a standard `TransactionReceipt`. Specific result fields:

- `ClprSendMessage` records include the assigned `message_id` in the receipt.
- `ClprSubmitBundle` records include the count of messages processed, any generated response message IDs,
  and a list of slash events (if any).
- `ClprRegisterConnection` records include the `connection_id`.
- `ClprRegisterConnector` records include the connector key
  `(connection_id, source_connector_address)`.

## 1.3 Query Definitions

| Pseudo-API (spec section 6) | HAPI Query Type | Response Type |
|---|---|---|
| `getLedgerConfiguration` | `ClprGetLedgerConfigurationQuery` | `ClprGetLedgerConfigurationResponse` |
| `getQueueDepth` | `ClprGetQueueDepthQuery` | `ClprGetQueueDepthResponse` |
| `getConnector` | `ClprGetConnectorQuery` | `ClprGetConnectorResponse` |

```protobuf
message ClprGetLedgerConfigurationQuery {
  QueryHeader header = 1;
}

message ClprGetLedgerConfigurationResponse {
  ResponseHeader header = 1;
  ClprLedgerConfiguration configuration = 2;
}

message ClprGetQueueDepthQuery {
  QueryHeader header = 1;
  bytes connection_id = 2;
}

message ClprGetQueueDepthResponse {
  ResponseHeader header = 1;
  uint64 depth = 2;
  uint32 max = 3;
}

message ClprGetConnectorQuery {
  QueryHeader header = 1;
  bytes connection_id = 2;
  bytes source_connector_address = 3;
}

message ClprGetConnectorResponse {
  ResponseHeader header = 1;
  ClprConnector connector = 2;
}
```

### 1.3.1 Sync Query (Endpoint-to-Endpoint, Existing Prototype)

The existing prototype defines `ClprSyncQuery` and `ClprSyncResponse` for the endpoint-to-endpoint gRPC service.
These remain as they are in the prototype, served by the `ClprEndpointService` gRPC server (see
[section 4](#4-endpoint-implementation)), separate from the on-ledger HAPI queries.

## 1.4 Signing Requirements

| Transaction                    | Required Signers                                                             |
|--------------------------------|------------------------------------------------------------------------------|
| `ClprSetLedgerConfiguration`   | CLPR admin key (Hedera council key, file `0.0.150` or equivalent system key) |
| `ClprRegisterConnection`       | Transaction payer only (permissionless)                                      |
| `ClprCloseConnection`          | CLPR admin key                                                               |
| `ClprRegisterConnector`        | Transaction payer + Connector admin key                                      |
| `ClprTopUpConnector`           | Connector admin key                                                          |
| `ClprWithdrawConnectorBalance` | Connector admin key                                                          |
| `ClprDeregisterConnector`      | Connector admin key                                                          |
| `ClprSubmitBundle`             | Transaction payer only (typically consensus node account)                    |
| `ClprRedactMessage`            | CLPR admin key                                                               |
The **CLPR admin key** is a new system entity key, analogous to the address book admin key. It is stored in state
as part of the CLPR service's system entity configuration and is controlled by the Hedera governing council.

---

# 2. State Schema

All CLPR state is stored in Hiero's Merkle state tree alongside other service state (accounts, tokens, etc.),
making it directly provable via Hiero state proofs and TSS signatures.

## 2.1 Service Name

The CLPR service registers state under the service name `"ClprService"`.

## 2.2 Schema Version

Initial schema: `V0650ClprSchema` (version `0.65.0`, matching the anticipated release).

## 2.3 State Definitions

### 2.3.1 Singletons

| State Key               | Type                      | Description                                                                                                                                                                                                                                                                            |
|-------------------------|---------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `LEDGER_CONFIGURATION`  | `ClprLedgerConfiguration` | This ledger's local CLPR configuration (chain_id, protocol_version, throttles, timestamp). The `chain_id` and `protocol_version` are immutable after CLPR activation — `chain_id` is determined by the ledger at activation time, `protocol_version` by the CLPR service code version. |
| `LOCAL_LEDGER_METADATA` | `ClprLocalLedgerMetadata` | Local metadata: generated ledger ID, roster hash used for current config, next short ledger ID. Exists in prototype.                                                                                                                                                                   |


### 2.3.2 Key-Value Stores

| State Key                | Key Type                    | Value Type                 | Description                                                                                                             |
|--------------------------|-----------------------------|----------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `CONNECTIONS`            | `bytes(32)` (connection_id) | `ClprConnection`           | All registered Connections.                                                                                             |
| `CONNECTORS`             | `ClprConnectorKey`          | `ClprConnector`            | Registered Connectors, keyed by `(connection_id, source_connector_address)`.                                            |
| `CONNECTOR_INDEX`        | `ClprConnectorIndexKey`     | `ClprConnectorKey`         | Cross-chain mapping: `(connection_id, source_connector_address)` to local Connector. Used to resolve incoming messages. |
| `MESSAGE_QUEUE`          | `ClprMessageKey`            | `ClprMessageValue`         | Outbound message queue entries, keyed by `(connection_id, message_id)`. Exists in prototype.                            |
| `MESSAGE_QUEUE_METADATA` | `ClprLedgerId`              | `ClprMessageQueueMetadata` | Per-Connection queue metadata. Exists in prototype, but will be rekeyed to `connection_id`.                             |
| `LEDGER_CONFIGURATIONS`  | `ClprLedgerId`              | `ClprLedgerConfiguration`  | Peer ledger configurations (remote configs adopted via state proofs). Exists in prototype.                              |

### 2.3.3 New Protobuf State Messages

```protobuf
// Full Connection state stored on-ledger.
message ClprConnection {
  // --- Identity ---
  bytes connection_id = 1;          // 32-byte primary key
  string chain_id = 2;              // CAIP-2 identifier of the peer
  bytes service_address = 3;        // Peer's CLPR service address

  // --- Peer Configuration ---
  Timestamp peer_config_timestamp = 4;

  // --- Verifier (immutable after registration) ---
  AccountID verifier_contract = 5;  // Hiero account of the verifier system contract
  bytes verifier_fingerprint = 6;   // Code hash at registration time (informational)

  // --- Status ---
  ClprConnectionStatus status = 7;

  // --- Outbound Queue Metadata ---
  uint64 next_message_id = 8;
  uint64 acked_message_id = 9;
  bytes sent_running_hash = 10;     // 32 bytes

  // --- Inbound Queue Metadata ---
  uint64 received_message_id = 11;
  bytes received_running_hash = 12; // 32 bytes

  // --- Lazy Config Propagation ---
  // Consensus timestamp of the last configuration propagated on this Connection.
  Timestamp last_config_timestamp = 13;
}

enum ClprConnectionStatus {
  ACTIVE = 0;
  PAUSED = 1;
  CLOSING = 2;
  DRAINED = 3;
  CLOSED = 4;
}
```

**Connection Status Transitions** (see also cross-platform spec section 2.1.1):

| From      | To        | Trigger                                                                 |
|-----------|-----------|-------------------------------------------------------------------------|
| `ACTIVE`  | `PAUSED`  | Auto: response ordering violation during `ClprSubmitBundle`             |
| `ACTIVE`  | `CLOSING` | Admin: `ClprCloseConnection`, or peer's `ClprQueueMetadata.state` is `CLOSING`/`DRAINED` |
| `PAUSED`  | `ACTIVE`  | Auto: peer fixes ordering (if admin hasn't closed)                     |
| `PAUSED`  | `CLOSING` | Admin: `ClprCloseConnection` — Connection drains once peer fixes ordering |
| `CLOSING` | `DRAINED` | Auto: peer has acknowledged all outbound messages                       |
| `DRAINED` | `CLOSED`  | Auto: both sides are `DRAINED`                                         |

`CLOSED` is a terminal state with no outgoing transitions.

```protobuf
// Connector state.
message ClprConnector {
  bytes connection_id = 1;
  bytes source_connector_address = 2;
  AccountID connector_contract = 3;   // Authorization contract (system contract)
  AccountID admin = 4;                // Admin account
  uint64 balance = 5;                 // Available funds (tinybars)
  uint64 locked_stake = 6;           // Slashable stake (tinybars)
  uint64 slash_count = 7;            // Cumulative slash events for escalation
}

// Composite keys for K/V stores.
message ClprConnectorKey {
  bytes connection_id = 1;
  bytes source_connector_address = 2;
}

message ClprConnectorIndexKey {
  bytes connection_id = 1;
  bytes source_connector_address = 2;
}
```

## 2.4 Migration from Prototype

The prototype uses `ClprLedgerId` as the key for message queue metadata and ledger configurations. The full
implementation replaces ledger ID-based keying with `connection_id`-based keying (32-byte Connection ID). The
migration path:

1. Schema `V0650ClprSchema` reads all existing prototype state.
2. Existing `ClprLedgerId`-keyed entries are re-indexed under their corresponding `connection_id`.
3. New state stores (`CONNECTIONS`, `CONNECTORS`, `CONNECTOR_INDEX`) are created empty.
4. The `LEDGER_CONFIGURATION` singleton is preserved but extended with new fields
   (`throttles`, `service_address`).
**Decision:** The prototype state structure was intentionally simplified for early development. The migration
drops prototype-specific fields that do not map to the cross-platform spec (e.g., `next_ledger_short_id`
in `ClprLocalLedgerMetadata` becomes unused once Connections have proper 32-byte IDs).

## 2.5 State Size Estimates

| State | Estimated Entry Size | Expected Scale | Notes |
|---|---|---|---|
| `CONNECTIONS` | ~300 bytes | 10s-100s | One per active Connection |
| `CONNECTORS` | ~200 bytes | 100s-1000s per Connection | |
| `MESSAGE_QUEUE` | variable (payload-dependent) | Up to `maxQueueDepth` per Connection | Entries deleted after ack/response |

---

# 3. Service Implementation

## 3.1 Service Class

```java
package com.hedera.node.app.service.clpr;

/**
 * The CLPR Service interface, extending Hiero's RpcService.
 * Provides cross-ledger protocol message passing.
 */
public interface ClprService extends RpcService {
    String NAME = "ClprService";
}
```

The implementation class `ClprServiceImpl` follows the standard Hiero pattern:

```java
package com.hedera.node.app.service.clpr.impl;

public final class ClprServiceImpl implements ClprService {
    @Inject
    public ClprServiceImpl() {}

    @Override
    public void registerSchemas(@NonNull SchemaRegistry registry) {
        registry.register(new V0650ClprSchema());
    }
}
```

## 3.2 Transaction Handlers

Each HAPI transaction type maps to a `TransactionHandler` implementation. Handlers follow the standard
`preHandle(PreHandleContext)` / `handle(HandleContext)` pattern.

| Handler Class                         | Transaction Type               | Complexity                                                                                                                                                                                                                                                                                 |
|---------------------------------------|--------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ClprSetLedgerConfigurationHandler`   | `ClprSetLedgerConfiguration`   | Medium (admin key check, store config). The handler sets `protocol_version` automatically from the CLPR service code version (callers do not supply it). The `chain_id` is immutable — it is determined by the ledger at CLPR activation time and cannot be modified via this transaction. |
| `ClprRegisterConnectionHandler`       | `ClprRegisterConnection`       | Medium (ECDSA verify, verifyConfig call, store verifier contract and code fingerprint, Connection creation). The `chain_id` and `service_address` are derived from the verified peer configuration proof. The verifier is immutable after registration.                                    |
| `ClprCloseConnectionHandler`          | `ClprCloseConnection`          | Low (transition to CLOSING; `ClprQueueMetadata.state` reflects Connection status; automatic transition to DRAINED when peer acks all outbound, then to CLOSED when both sides DRAINED)                                                                                                       |
| `ClprRegisterConnectorHandler`        | `ClprRegisterConnector`        | Medium (fund transfer, mapping setup). Records the `admin` field on the Connector state (defaults to the transaction payer if not specified in the body). The signing requirement includes the admin key if an explicit admin is provided; otherwise the payer key suffices.               |
| `ClprTopUpConnectorHandler`           | `ClprTopUpConnector`           | Low (balance update)                                                                                                                                                                                                                                                                       |
| `ClprWithdrawConnectorBalanceHandler` | `ClprWithdrawConnectorBalance` | Low (balance check + transfer)                                                                                                                                                                                                                                                             |
| `ClprDeregisterConnectorHandler`      | `ClprDeregisterConnector`      | Medium (in-flight check, fund return)                                                                                                                                                                                                                                                      |
| `ClprSubmitBundleHandler`             | `ClprSubmitBundle`             | **High** (per-endpoint throttle check, verifier call, replay defense, hash chain verify, per-message dispatch, response generation, slashing)                                                                                                                                              |
| `ClprRedactMessageHandler`            | `ClprRedactMessage`            | Low (payload removal, slot retention)                                                                                                                                                                                                                                                      |

### 3.2.1 Handler Details: ClprSubmitBundleHandler

This is the most complex handler. Its `handle()` method implements the bundle verification algorithm from
cross-platform spec section 4.2:

1. **Verify Connection status.** Reject if `CLOSED`. **Note:** PAUSED, CLOSING, and DRAINED connections
   accept inbound bundles — syncs continue in both directions. PAUSED rejects bundles containing out-of-order
   responses (nothing is processed, dispatched, or acked); when a bundle arrives with correctly ordered
   responses, the Connection auto-resumes to ACTIVE and processes normally. CLOSING and DRAINED generate
   `CONNECTION_CLOSED` responses without dispatch for any new Data Messages.
2. **Call verifier contract** via `verifyBundle(proof_bytes)`. On Hiero, verifiers are system contracts invoked
   through the `HandleContext.dispatchChildTransaction()` or a dedicated verifier dispatch mechanism (see
   [section 5](#5-verifier-integration)). If verification fails, charge the submitter and reject.
3. **Bundle size check.** Verify message count does not exceed `max_messages_per_bundle` and no individual payload
   exceeds `max_message_payload_bytes`.
4. **Replay defense.** Verify first message ID equals `received_message_id + 1`, IDs are contiguous and ascending,
   and last message ID equals `ClprQueueMetadata.next_message_id - 1`.
5. **Running hash verification.** Recompute SHA-256 chain from `received_running_hash` and compare against
   `sent_running_hash` from verifier metadata.

> **Clarification (PAUSED vs. Bad Bundle):** Steps 1-5 reject invalid bundles with no Connection state change.
> The Connection remains ACTIVE and will accept valid bundles. Only step 8 (response ordering violation)
> triggers PAUSED status. While PAUSED, bundles containing out-of-order responses are rejected entirely
> (no messages processed, no acks updated). The Connection auto-resumes to ACTIVE when a bundle arrives
> with correctly ordered responses.

6. **Acknowledgement update.** Update `acked_message_id` from `ClprQueueMetadata.received_message_id`. Delete
   acknowledged Response Messages and Control Messages from outbound queue.
   **Queue state check:** After ack update, check `ClprQueueMetadata.state`. If `CLOSING` or `DRAINED` and
   Connection is `ACTIVE` or `PAUSED`, transition to `CLOSING` (the local `ClprQueueMetadata.state` reflects
   the Connection status). The peer has initiated a close; this side mirrors it.
   **Drain check:** If Connection is `CLOSING` and peer has acked all outbound messages
   (`acked_message_id >= next_message_id - 1`), transition Connection to `DRAINED`. If both sides are
   `DRAINED`, transition Connection to `CLOSED`.
7. **Lazy config propagation check.** If `connection.last_config_timestamp < current_config.consensus_timestamp`, enqueue a
   ConfigUpdate Control Message containing the current `ClprLedgerConfiguration` on this Connection's outbound
   queue and set `connection.last_config_timestamp = current_config.consensus_timestamp`.
8. **Message dispatch.** For each message in order:
   - **Control Message:** Apply directly (store peer config values from ConfigUpdate).
   - **Data Message:** Resolve Connector via cross-chain mapping. Charge Connector (execution cost + margin).
     Dispatch to target application (see [section 7](#7-application-dispatch)). Generate Response Message and
     enqueue it.
   - **Response Message:** Verify ordering per spec section 4.5. If response ordering violation detected, set
     Connection to `PAUSED` and reject the entire bundle (no messages processed, no acks updated). When a
     subsequent bundle arrives with correctly ordered responses, auto-resume to ACTIVE and process normally.
   - **Data Message (CLOSING/DRAINED):** If Connection is CLOSING or DRAINED, generate a `CONNECTION_CLOSED` response without
     dispatching to the target application. Connector is not charged, not slashed.

**Failure isolation:** A failure on one message does not stop processing of remaining messages. Each message is
handled independently.

### 3.2.2 sendMessage (System Contract Method)

`sendMessage` is a **system contract method**, not a HAPI transaction handler. Application smart contracts call
it directly from EVM execution context. It implements the message enqueue algorithm from cross-platform spec
section 4.3:

1. Look up Connection by `connection_id`. Reject if status is not `ACTIVE`.
2. **Lazy config propagation check.** If `connection.last_config_timestamp < current_config.consensus_timestamp`, enqueue a
   ConfigUpdate Control Message containing the current `ClprLedgerConfiguration` on this Connection's outbound
   queue and set `connection.last_config_timestamp = current_config.consensus_timestamp`.
3. Look up Connector by `connector_id` on the Connection. Reject if not found.
4. Call `IClprConnectorAuth.authorizeMessage()` on the Connector's authorization contract (see
   [section 6](#6-connector-authorization)). Reject if not authorized.
5. Validate payload size against the destination's `max_message_payload_bytes`. Reject if exceeded.
6. Validate queue depth: `next_message_id - acked_message_id < max_queue_depth`. Reject if full.
7. Construct `ClprMessage` with `connector_id`, `target_application`, `sender` (stamped from calling contract
   address), and `message_data`.
8. Compute `running_hash = SHA-256(sent_running_hash || serialized_payload)`.
9. Store message in queue keyed by `(connection_id, next_message_id)`.
10. Update Connection: `sent_running_hash = running_hash`, `next_message_id += 1`.
11. Return the assigned `messageId` to the calling contract.

### 3.2.3 preHandle Patterns

Most CLPR handlers have minimal `preHandle` work, consistent with Hiero's design principle that `preHandle` only
verifies the payer can pay:

- **Admin transactions** (`SetLedgerConfiguration`, `Close`, `Redact`): `preHandle` adds the
  CLPR admin key to the required keys set.
- **Connector admin transactions** (`TopUp`, `Withdraw`, `Deregister`): `preHandle` looks up the Connector and
  adds its admin key to the required keys set.
- **Permissionless transactions** (`RegisterConnection`, `SubmitBundle`): `preHandle` is a no-op (payer key
  is the only requirement).

**Note:** `sendMessage` is a system contract method and does not go through `preHandle`.

## 3.3 Query Handlers

| Handler Class | Query Type |
|---|---|
| `ClprGetLedgerConfigurationHandler` | `ClprGetLedgerConfigurationQuery` |
| `ClprGetQueueDepthHandler` | `ClprGetQueueDepthQuery` |
| `ClprGetConnectorHandler` | `ClprGetConnectorQuery` |

**Note:** `getConnection` from the cross-platform spec is not exposed as a HAPI query. Connection state is
available via the Mirror Node REST API, which can serve this data more efficiently without consensus node load.

All query handlers are straightforward state reads from the appropriate K/V store or singleton.

## 3.4 Dagger Module

```java
@Module
public interface ClprServiceModule {
    @Binds
    @IntoSet
    Service bindClprService(ClprServiceImpl impl);
}
```

The `ClprServiceImpl` is registered into the Dagger dependency graph and discovered by the `ServicesRegistryImpl`.

## 3.5 Gradle Module Structure

| Module | Purpose |
|---|---|
| `:hiero-clpr-interledger-service` | API module: `ClprService` interface, store interfaces, ReadableClprStore/WritableClprStore |
| `:hiero-clpr-interledger-service-impl` | Implementation: `ClprServiceImpl`, all handlers, schema, store implementations |

---

# 4. Endpoint Implementation

For endpoint module architecture, sync orchestration, proof construction, peer selection, gRPC service
definition, roster auto-population, and misbehavior handling, see the
[CLPR Hiero Endpoint Specification](clpr-hiero-endpoint-spec.md).

This section covers only how the on-ledger CLPR Service interacts with the endpoint module.

## 4.1 Service-to-Endpoint Interaction

The endpoint module submits `ClprSubmitBundle` HAPI transactions to the local node's transaction pipeline
after receiving a peer's `ClprSyncPayload`. From the CLPR Service's perspective, these are ordinary consensus
transactions — the service does not distinguish between bundles submitted by local endpoints and those submitted
by other nodes.

**Config timestamp tracking:** When `ClprSetLedgerConfiguration` stores a new configuration, the endpoint
module detects the timestamp mismatch on subsequent state reads and enqueues ConfigUpdate control messages.
The service does not push config changes to the endpoint module — the endpoint module pulls from state.

**Per-endpoint throttle:** The `ClprSubmitBundleHandler` enforces a per-endpoint submission rate limit. Each
registered endpoint (derived from the active consensus roster on Hiero) gets `max_bundles_per_sec / num_endpoints`
of the total bundle submission capacity. The submitter's identity is recovered from the `endpoint_signature` via
ecrecover and matched against the local endpoint registry. Excess submissions are rejected and the submitter is
charged.

---

# 5. Verifier Integration

Per cross-platform spec §3.1 for the `IClprVerifier` interface definition and verification semantics. On Hiero,
verifiers are **system contracts** deployed as EVM contracts with Hiero `AccountID`s, callable from the native
service layer.

## 5.1 Verifier System Contracts

Each verifier is deployed as a Hiero smart contract (EVM contract with a Hiero `AccountID`) implementing the
`IClprVerifier` interface (spec §3.1).

**On Hiero-to-Hiero connections:** The verifier for Hiero proofs (the "HederaProofs-Hiero" verifier) validates
TSS signatures and Merkle paths. This is expected to be the first verifier implementation.

**On Ethereum-to-Hiero connections:** The verifier for Ethereum proofs validates BLS aggregate signatures from
Ethereum's sync committee.

## 5.2 Implementation Fingerprint (Code Hash)

On Hiero, the verifier's implementation fingerprint is computed as the `keccak256` hash of the deployed contract's
EVM bytecode. This matches the EVM `EXTCODEHASH` semantic. During connection registration, the CLPR service computes
the fingerprint by reading the contract's bytecode from the smart contract service state and stores it on the
Connection as an informational field. The verifier is immutable after registration.

## 5.3 Verifier Invocation from Native Service

The CLPR native service invokes verifier contracts via `HandleContext.dispatchChildTransaction()`, constructing a
`ContractCallTransactionBody` targeting the verifier contract's address with the appropriate function selector and
encoded `proof_bytes`. The `verifyBundle`/`verifyConfig`/`verifyMetadata` calls are **read-only with respect to
CLPR state** — the `view` modifier on the Solidity interface is correct (reads storage, does not write during
the call).

However, verifier contracts themselves MAY maintain internal mutable state (e.g., validator set tracking, sync
committee rotation) updated via **separate administrative calls** outside the CLPR interface. For example, an
Ethereum sync committee verifier would have a non-view `updateValidatorSet()` function called periodically by
a governance process. The `view` modifier only applies to the CLPR-facing verification methods, not to the
verifier contract as a whole.

**Alternative approach:** If dispatching to the EVM is too heavyweight for the hot path (`submitBundle`), a
dedicated verifier interface could be defined as a Java SPI (Service Provider Interface) that verifier
implementations register. This would allow native Java verifier implementations that bypass the EVM entirely.
**Decision needed:** The EVM dispatch approach is the default; the SPI approach is an optimization to evaluate
during performance testing.

---

# 6. Connector Authorization

Per cross-platform spec §3.2 for the `IClprConnectorAuth` interface definition and authorization semantics.

## 6.1 Authorization Contracts on Hiero

On Hiero, Connector authorization contracts are **smart contracts** (EVM contracts with Hiero `AccountID`s)
implementing the `IClprConnectorAuth` interface (spec §3.2). The CLPR service invokes `authorizeMessage()`
during `ClprSendMessage` handling via `HandleContext.dispatchChildTransaction()` with a
`ContractCallTransactionBody`. The call is read-only (view function) and MUST NOT modify state.

## 6.2 Simple Connector Implementations

For the initial Hiero deployment, a reference "pass-through" Connector authorization contract is provided that
approves all messages. More sophisticated implementations (allow-lists, rate limits, payment requirements) can
be deployed by Connector operators.

## 6.3 Connector Fund Custody

Connector balances and locked stakes are held in the CLPR service's state, not in the Connector contract's
account. When a Connector is registered:

1. The `initial_balance` and `stake` amounts in tinybars are transferred from the transaction payer's account
   to the CLPR service's internal accounting.
2. The CLPR service tracks the balance and stake in the `ClprConnector` state entry.
3. When the Connector is charged for message execution, the CLPR service debits the Connector's `balance`.
4. When slashing occurs, the CLPR service debits the Connector's `locked_stake` and credits the submitting
   endpoint's account.

**Implementation note:** Hiero's native token transfer mechanism is used for all fund movements. The CLPR service
calls `HandleContext.dispatchChildTransaction()` with `CryptoTransferTransactionBody` to move tinybars between
accounts and the CLPR service's custody accounting.

---

# 7. Application Dispatch

Per cross-platform spec §3.3 for the `IClprReceiver` interface definition and callback semantics. On Hiero,
the target application is a smart contract.

## 7.1 Dispatch Mechanism

1. The CLPR service constructs a `ContractCallTransactionBody` targeting the `target_application` address with
   the `onClprMessage` function selector and ABI-encoded parameters.
2. The call is dispatched via `HandleContext.dispatchChildTransaction()`.
3. **Gas limit:** The call is executed with a gas limit of `max_gas_per_message` (from the ledger configuration).
4. **Return handling:**
   - If the call succeeds, a `SUCCESS` Response Message is generated with the returned `responseData`.
   - If the call reverts, an `APPLICATION_ERROR` Response Message is generated with empty response data.
5. **State isolation:** The application call runs in a child transaction context. If the application reverts,
   all its state changes are rolled back, but the CLPR service's state changes (Connector charge, response
   generation) are retained.

## 7.2 Response Delivery

When a Response Message arrives on the source ledger:

1. The CLPR service looks up the original `ClprSendMessage` transaction's `sender` address (the transaction payer).
2. If the sender is a smart contract implementing `IClprReceiver`, the service dispatches `onClprResponse` to it.
3. If the sender is an EOA (externally owned account), the response is recorded in the transaction record but
   no callback is made. The sender polls via mirror node or subscribes to transaction records.

## 7.3 Gas/Ops Limits

On Hiero, the "gas" budget for application callbacks maps to the EVM gas limit for the child contract call.
`max_gas_per_message` from the ledger configuration is the hard cap. The CLPR service sets this as the gas limit
on the `ContractCallTransactionBody`.

**Throttle interaction:** CLPR bundle processing must respect Hiero's existing operations-per-second throttles.
The `max_messages_per_bundle` configuration must be calibrated so that a full bundle's worth of application
dispatches does not exceed the per-transaction ops limit. This is a configuration tuning concern, not a code
change.

---

# 8. Security Model

For cross-platform security properties (reentrancy patterns, queue monopolization principles, threat model),
see cross-platform spec §8. This section covers Hiero-specific security mechanisms.

## 8.1 Permissioned Endpoints

On Hiero, all CLPR endpoints are consensus nodes governed by the Hedera council (on mainnet) or the network
operator (on testnets and HashSpheres). This provides strong Sybil resistance without requiring endpoint bonds.

**Implication:** The `registerEndpoint` and `deregisterEndpoint` pseudo-APIs from spec §6.5 are not implemented
on Hiero. Endpoint set changes are automatic, driven by roster changes.

**Future consideration:** If Hiero moves to permissionless node operation, CLPR endpoint bonds would need to be
added. This is a future enhancement, not a launch requirement.

## 8.2 CLPR Admin Key

The CLPR admin key is the trust anchor for all administrative operations on Hiero. It controls:

- Setting the ledger configuration (throttles)
- Closing Connections (from ACTIVE or PAUSED status)
- Redacting messages from the outbound queue

On Hedera mainnet, this key is controlled by the governing council. On HashSpheres, it is controlled by the
network operator.

**Implementation:** The CLPR admin key is stored as a `Key` in the CLPR service's state (singleton). It is
initialized during genesis or CLPR feature activation. It can be updated via a privileged transaction (similar
to how other system keys are managed).

## 8.3 ABFT Guarantees

Hiero state proofs leverage TSS (Threshold Signature Scheme) signatures from the active validator set. When
both participating ledgers are Hiero networks with ABFT finality, CLPR inherits ABFT properties: no reorg risk,
and a single honest endpoint per ledger is sufficient for correct operation.

## 8.4 Reentrancy

Per cross-platform spec §8.5 for the reentrancy threat model. On Hiero, the EVM-to-native boundary prevents
synchronous reentrancy: application callbacks run in a child transaction context and cannot call back into the
CLPR service's native handler code. No explicit reentrancy guard is needed. An application could submit a new
CLPR transaction via the HAPI gRPC interface, but that goes through consensus as a separate transaction.

## 8.5 Queue Monopolization Mitigation

Per cross-platform spec §8.9 for the queue monopolization threat model. Hiero-specific parameters:

1. **Per-Connector queue quota:** 50% of `max_queue_depth` (`clpr.connectorQueueQuotaPct`).
2. **Fee escalation threshold:** 75% queue capacity (`clpr.queueFeeEscalationThresholdPct`), with linear
   fee increase for `ClprSendMessage` beyond this point.

These parameters are configurable via the CLPR config properties (see [section 9](#9-configuration)).

---

# 9. Configuration

## 9.1 Configuration Properties (clpr.properties)

Cross-platform configuration parameters (throttles, queue limits, message sizes) are defined in cross-platform
spec §7. These are node-local or network-wide configuration properties managed through Hiero's standard config
system (property files / dynamic config).

**Hiero-specific properties** (no cross-platform equivalent):

| Property | Type | Default | Description |
|---|---|---|---|
| `clpr.enabled` | `boolean` | `false` | Master enable switch. When false, all CLPR transactions return `NOT_SUPPORTED`. |
| `clpr.defaultSyncsPerSec` | `int` | `1` | Conservative default sync rate per endpoint when peer's `max_syncs_per_sec` is not yet known. |
| `clpr.endpointPort` | `int` | `50212` | Port for the `ClprEndpointService` gRPC server. |
| `clpr.maxBundlesPerSec` | `int` | `100` | Total bundle submission capacity per second. Divided among registered endpoints. |
| `clpr.minConnectorStake` | `long` (tinybars) | TBD | Minimum Connector stake requirement. |
| `clpr.minConnectorBalance` | `long` (tinybars) | TBD | Minimum Connector initial balance. |
| `clpr.connectorQueueQuotaPct` | `int` | `50` | Maximum percentage of queue depth a single Connector can occupy. |
| `clpr.queueFeeEscalationThresholdPct` | `int` | `75` | Queue fill percentage at which fee escalation begins. |
| `clpr.slashBaseAmount` | `long` (tinybars) | TBD | Base slash amount for first offense. |
| `clpr.slashEscalationMultiplier` | `double` | `2.0` | Multiplier applied to slash amount for each subsequent offense. |
| `clpr.slashBanThreshold` | `int` | `3` | Number of slashes before a Connector is banned. |

**Hiero defaults for cross-platform parameters** (spec §7):

| Property | Type | Hiero Default | Spec Parameter |
|---|---|---|---|
| `clpr.maxQueueDepth` | `int` | `10000` | `max_queue_depth` |
| `clpr.maxMessagePayloadBytes` | `int` | `16384` | `max_message_payload_bytes` |
| `clpr.maxMessagesPerBundle` | `int` | `100` | `max_messages_per_bundle` |
| `clpr.maxGasPerMessage` | `long` | `1000000` | `max_gas_per_message` |
| `clpr.maxSyncsPerSec` | `int` | `2` | `max_syncs_per_sec` |
| `clpr.maxSyncBytes` | `long` | `4194304` | `max_sync_bytes` |

## 9.2 Relationship Between Config and State

- **Config properties** (`clpr.properties`) control node behavior: sync frequency, port, feature flag, economic
  parameters (slash amounts, minimums).
- **State configuration** (`ClprLedgerConfiguration`) controls protocol behavior: throttles, verifier endorsements,
  chain identity. These are the parameters that peers see and enforce.

Some parameters appear in both (e.g., `maxQueueDepth`, `maxMessagePayloadBytes`). The on-ledger values are
authoritative for cross-ledger purposes. The config property values serve as initial defaults when the CLPR admin
first sets the ledger configuration via `ClprSetLedgerConfiguration`.

---

# 10. Fee Schedule

## 10.1 Fee Model

CLPR transactions follow Hiero's standard fee model: each transaction is charged based on the
`ServiceFeeCalculator` registered for its type. Fees are paid in HBAR (tinybars) by the transaction payer.

## 10.2 Fee Calculators

Each handler registers a `ServiceFeeCalculator`:

| Transaction                    | Fee Basis                                                   | Notes                                                                                                   |
|--------------------------------|-------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| `ClprSetLedgerConfiguration`   | Fixed base                                                  | Admin operation; relatively rare. Config propagation is lazy, so no per-Connection enqueue at set time. |
| `ClprRegisterConnection`       | Fixed base + ECDSA verification cost                        | ECDSA verify + store verifier contract and code fingerprint                                             |
| `ClprUpdateEndpointRoster`     | Fixed base + verification cost                              |                                                                                                         |
| `ClprCloseConnection`          | Fixed base                                                  | Low; transition to CLOSING                                                                              |
| `ClprRegisterConnector`        | Fixed base                                                  |                                                                                                         |
| `ClprTopUpConnector`           | Fixed base                                                  |                                                                                                         |
| `ClprWithdrawConnectorBalance` | Fixed base                                                  |                                                                                                         |
| `ClprDeregisterConnector`      | Fixed base                                                  |                                                                                                         |
| `ClprSendMessage`              | Fixed base + per-byte payload cost + queue depth escalation | Primary user-facing fee                                                                                 |
| `ClprSubmitBundle`             | Fixed base + per-message processing cost                    | Typically paid by endpoint node; reimbursed by Connector margin                                         |
| `ClprRedactMessage`            | Fixed base                                                  | Admin operation                                                                                         |

## 10.3 Connector Margin and Endpoint Reimbursement

The Connector margin that reimburses the submitting endpoint on the destination ledger is calculated as:

```
margin = actual_gas_used * gas_price + base_endpoint_fee
```

Where:
- `actual_gas_used` is the gas consumed by the application dispatch.
- `gas_price` is the current Hiero gas price.
- `base_endpoint_fee` is a fixed per-message reimbursement for the endpoint's transaction submission cost.

This margin is charged to the Connector's `balance` and credited to the submitting endpoint's account.

---

# 11. Platform-Specific Gaps and Decisions

This section documents areas where the cross-platform spec leaves decisions to the platform, and the
Hiero-specific choices made.

## 11.1 Endpoint Management

**Spec says:** Section 6.5 states that endpoint registration is needed on permissionless chains but not on
Hiero where "consensus nodes are the endpoints."

**Hiero decision:** No `registerEndpoint` or `deregisterEndpoint` transactions. Endpoints are derived
automatically from the active roster. Peer endpoint discovery is handled off-chain via the gossip-based
`discoverEndpoints` RPC (see cross-platform spec §5.4). Peer endpoint data is NOT stored in on-ledger state.

## 11.2 Endpoint Bond

**Spec says:** Section 2.3 defines an endpoint bond for permissionless chains. Section 3.1.2 of the design doc
notes that "no separate CLPR-specific bond is required because Hedera consensus nodes are permissioned."

**Hiero decision:** No endpoint bond in the initial implementation. Misbehavior is enforced through governance.
This may change if Hiero moves to permissionless node operation.

## 11.3 Service Address

**Spec says:** The `service_address` in `ClprLedgerConfiguration` is "a well-known constant on Hiero where the
CLPR Service is native."

**Hiero decision:** The service address is a fixed constant `0x0000000000000000000000000000CLPR` (20 bytes,
TBD exact value). This is analogous to how other Hiero system entities have well-known entity numbers
(e.g., `0.0.1` for the address book). The exact entity number will be assigned during implementation.

## 11.4 Verifier Implementation Fingerprint

**Spec says:** The fingerprint is "whatever mechanism that platform provides to verify deployed program code
against a known hash."

**Hiero decision:** `keccak256` of the deployed EVM bytecode, matching EVM `EXTCODEHASH` semantics. This works
because Hiero verifiers are deployed as EVM smart contracts.

## 11.5 ECDSA Signature Verification

**Spec says:** Connection registration and `endpoint_signature` both require ECDSA secp256k1 verification.
Endpoint identity is recovered via ecrecover from the signature rather than being passed explicitly.

**Hiero decision:** Use Hiero's native `ECDSA_SECP256K1` key support. Signatures are verified using the
platform's built-in cryptographic utilities (same code path as `ecrecover` in the EVM).

- **Connection registration:** Signed payload is `keccak256(connection_id || verifier_contract_address || caller_account_id)`.
- **Endpoint signature:** Signed payload is `keccak256(connection_id || proof_bytes)`. The signer's public key
  is recovered via ecrecover and matched against `ecdsa_signing_key` entries in the Connection's endpoint roster.
- **Local misbehavior detection:** Uses the same ecrecover mechanism on the `endpoint_signature` for attribution during local frequency tracking.

## 11.6 Minimum Connector Bond

**Spec says:** Section 4.6 requires platforms to define minimum Connector bond requirements to maintain the
stake-to-exposure invariant.

**Hiero decision:** The minimum stake is configurable via `clpr.minConnectorStake`. The initial value will be
determined through economic modeling based on:
- Maximum per-message execution cost (gas * gas_price)
- Maximum queue depth (worst-case in-flight messages)
- Required coverage ratio (stake must cover worst-case endpoint losses)

This is an **open economic design parameter** that must be quantified before production deployment.

## 11.7 Slashing Schedule

**Spec says:** Section 4.6 states "Platform-specific specifications MUST define the slashing schedule."

**Hiero decision:** Escalating slashes:
1. First offense: `clpr.slashBaseAmount` deducted from `locked_stake`.
2. Each subsequent offense: previous slash amount * `clpr.slashEscalationMultiplier`.
3. After `clpr.slashBanThreshold` offenses: Connector is banned from the Connection and remaining stake forfeited.

Slash proceeds go to the endpoint that submitted the bundle containing the relevant message.

## 11.8 Application Callback Model

**Spec says:** Section 6.5 (Application Delivery) requires platforms to define the callback interface, gas budget,
return value convention, and sync/async model.

**Hiero decision:**
- **Callback interface:** `IClprReceiver` Solidity interface (see section 7.1).
- **Gas budget:** `max_gas_per_message` from ledger configuration.
- **Return value:** Solidity `returns (bytes memory)` for response data; revert for application error.
- **Execution model:** Synchronous within the bundle transaction. The application callback completes before
  the next message in the bundle is processed.

## 11.9 Prototype Compatibility

The existing prototype on the `20111-clpr-prototype` branch defines a simpler model with:
- `adoptPeerConfiguration` instead of `registerConnection`
- `processSyncResult` instead of `submitBundle`
- `sync` as a HAPI query instead of a separate gRPC service
- `ClprLedgerId` instead of 32-byte Connection IDs
- No Connector model
- No Control Messages

The full implementation replaces the prototype entirely. The migration schema (`V0650ClprSchema`) handles
converting any prototype state that exists on testnets.

---

# 12. Inconsistencies and Findings

After reviewing both source documents against this implementation spec, the following inconsistencies, ambiguities,
and Hiero-specific concerns were identified.

## 12.1 Cross-Platform Spec Issues

### F-1: Control Messages Missing from Prototype State Protos

The cross-platform spec defines `ConfigUpdate` as a control message type in the `ClprControlMessage` oneof
(part of the `ClprMessagePayload` oneof). The existing prototype's `clpr_message_queue.proto` does not include a
`ClprControlMessage` variant in the `ClprMessagePayload` oneof. This must be added.

**Action:** Add `ClprControlMessage control = 3` to `ClprMessagePayload` in the state proto.

### F-2: Connector Fields Missing from Prototype Data Message

The cross-platform spec's `ClprMessage` includes `connector_id`, `target_application`, and `sender`. The
prototype's `ClprMessage` only has `message_data`. The full fields must be added.

**Action:** Extend the `ClprMessage` state proto to include all fields from the cross-platform spec.

### F-3: ClprMessageReplyStatus Missing from Prototype

The prototype's `ClprMessageReply` has no status field. The cross-platform spec defines `ClprMessageReplyStatus`
with five values (`SUCCESS`, `APPLICATION_ERROR`, `CONNECTOR_NOT_FOUND`, `CONNECTOR_UNDERFUNDED`, `REDACTED`).

**Action:** Add the `ClprMessageReplyStatus` enum and the `status` field to `ClprMessageReply`.

### F-4: Connection ID Derivation Ambiguity

The cross-platform spec says the Connection ID is `keccak256(uncompressed_public_key)` where the uncompressed key
is "the 64-byte concatenation of the x and y coordinates (without the 0x04 prefix byte)." The ECDSA signature
in `registerConnection` must sign a payload that includes the `connection_id`. The spec does not define the exact
signed payload format — it says "The exact signed payload format is defined in platform-specific specifications."

**Resolution:** This spec defines the signed payload as
`keccak256(connection_id || verifier_contract_address || caller_account_id)` (see section 11.5).

### F-5: Response Message to Non-Contract Senders

The cross-platform spec assumes applications receive response callbacks. On Hiero, if the original sender is an
EOA (not a contract), there is no way to "call" it with a response. The spec does not address this case.

**Resolution:** This spec defines that EOA senders receive responses via transaction records only (see
section 7.3). The response is recorded but no callback is made.

### F-6: Verifier as System Contract vs. Native Code

The cross-platform spec describes verifiers as "contracts" generically. On Hiero, calling an EVM contract from
a native service handler is possible but has performance implications for the hot path (`submitBundle` processes
every incoming bundle through the verifier). If the verifier is a Java implementation accessed via SPI, performance
would be significantly better.

**Resolution:** Start with EVM contract verifiers for cross-platform consistency. Evaluate native Java verifier
SPI as a performance optimization (see section 5.3). This is a **key architectural decision** that affects
performance of the entire CLPR pipeline.

### F-7: Message Queue Keying Mismatch

The prototype uses `ClprLedgerId` (a simple byte array ledger identifier) as the key for message queue metadata.
The cross-platform spec uses `connection_id` (32-byte keccak256 hash). Multiple Connections can exist to the
same peer ledger, so ledger ID is insufficient as a key.

**Action:** Migrate all queue-related state from `ClprLedgerId`-keyed to `connection_id`-keyed.

## 12.2 Design Document vs. Cross-Platform Spec Inconsistencies

### F-8: Response Ordering Recovery

**Resolved.** A response ordering violation transitions the Connection to PAUSED. PAUSED auto-recovers when the
next bundle contains correctly ordered responses (if admin hasn't closed). Admin may close a PAUSED Connection
(`closeConnection` transitions to CLOSING); the Connection drains once the peer fixes the ordering. If the
peer never fixes the ordering, the Connection stays PAUSED (or CLOSING) indefinitely.

### F-9: Queue Metadata Naming

The prototype uses `sent_message_id` in `ClprMessageQueueMetadata`, but the cross-platform spec uses
`next_message_id` (one past the last sent). The prototype also lacks `acked_message_id` entirely.

**Action:** Align to the cross-platform spec naming. Add `acked_message_id`.

## 12.3 Hiero-Specific Concerns

### F-10: Transaction Size for submitBundle

A `ClprSubmitBundle` transaction carries the full `proof_bytes` from the peer. With `max_messages_per_bundle = 100`
and `max_message_payload_bytes = 16384`, a single bundle could carry up to ~1.6 MB of message payloads plus proof
overhead. This exceeds Hiero's current maximum transaction size (6 KB default, configurable up to ~1 MB).

**Action:** Either:
(a) Increase the max transaction size for CLPR bundle transactions (special-cased throttle), or
(b) Keep `max_messages_per_bundle` low enough that bundles fit within existing limits, or
(c) Implement bundle chunking (split large bundles across multiple transactions).

**Recommendation:** Option (b) for initial implementation. Set `max_messages_per_bundle` conservatively (e.g., 10)
and calibrate based on average payload sizes.

### F-11: Throttle Interaction

CLPR `submitBundle` executes multiple application dispatches within a single transaction. Each dispatch consumes
gas/ops. Hiero's existing per-transaction and per-second throttles must accommodate this multiplied workload.

**Action:** Define CLPR-specific throttle categories that account for the multiplied execution within bundle
transactions. Coordinate with the throttle team.

### F-12: State Proof Availability

The endpoint must construct state proofs over the Merkle tree, including TSS signatures. State proofs are currently
produced by block nodes, not consensus nodes directly. The endpoint code needs access to recent state proofs.

**Action:** Determine whether endpoints construct proofs directly from the consensus node's local state tree
(with TSS signing coordination) or whether they request pre-built proofs from block nodes. This is a dependency
on the TSS/block node infrastructure.

### F-13: Multiple Verifier Calls per Bundle

The `submitBundle` handler calls the verifier once for the entire bundle, not once per message. This is correct
per the spec, but the verifier call is an EVM execution that could be expensive. If the verifier processes a
large proof (e.g., multiple Merkle paths for 100 messages), the gas cost of the verifier call itself may be
substantial.

**Action:** Benchmark verifier gas costs with realistic proof sizes. Set `max_messages_per_bundle` accordingly.

### F-14: Cross-Service State Access

The CLPR service needs to read the active roster from the roster service to auto-populate endpoints. This is a
cross-service state read, which is supported via `ReadableStoreFactory` in handler code, but the roster change
notification mechanism needs to be defined.

**Action:** Define how roster changes trigger endpoint updates. Options:
(a) Check roster hash on every sync tick and reconcile, or
(b) Subscribe to roster change events from the platform, or
(c) Check roster on CLPR service initialization and on `submitBundle` handling.

**Recommendation:** Option (a) for simplicity: the endpoint sync loop checks the current roster hash against
the stored `ClprLocalLedgerMetadata.roster_hash` and regenerates the endpoint set if it has changed.
