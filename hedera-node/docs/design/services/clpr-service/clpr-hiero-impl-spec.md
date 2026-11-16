# CLPR Hiero Implementation Specification

This document maps the canonical CLPR protocol specification (`clpr-service-spec.md` in the `clpr-spec` repository)
to Hiero's native constructs. It is the reference for engineers implementing CLPR on Hiero consensus nodes. Each
section corresponds to a concrete implementation area.

For architectural rationale and cross-platform protocol details, see the canonical `clpr-service.md` and
`clpr-service-spec.md` in the `clpr-spec` repository. For file maps, handler walkthroughs, configuration and
slashing detail, see the other documents in this directory (start with [README.md](README.md)).

> **Status.** This document was brought in line with the code on `main` and release 0.80
> (`v0.80.0-alpha.5`). Sections whose behavior was not re-verified against the code are listed at the end of
> section 12. Where this document and the code disagree, the code wins.

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
12. [Historical Findings](#12-historical-findings)

---

# 1. HAPI Transaction Definitions

Each pseudo-API from the cross-platform spec section 6 maps to a HAPI transaction (state-modifying operation that
goes through consensus), a HAPI query (read-only operation), or a system contract method. All transactions are
submitted as `TransactionBody` oneofs through the standard HAPI gRPC pipeline.

## 1.1 TransactionBody Oneofs

The following `oneof` variants of `TransactionBody` belong to CLPR:

| Pseudo-API (spec section 6) |      HAPI Transaction Type      |  Field  |                                Authority                                |
|-----------------------------|---------------------------------|---------|-------------------------------------------------------------------------|
| `updateLedgerConfiguration` | `ClprUpdateLedgerConfiguration` | 82      | CLPR admin (treasury or system admin payer)                             |
| `registerChannel`           | `ClprRegisterChannel`           | 83      | Any (permissionless; commit phase, stores the ownership commitment)     |
| `completeChannel`           | `ClprCompleteChannel`           | 84      | Any (permissionless; reveal phase, creates the ACTIVE Channel)          |
| `closeChannel`              | `ClprCloseChannel`              | 85      | CLPR admin (treasury or system admin payer)                             |
| `submitBundle`              | `ClprSubmitBundle`              | 86      | Any in the protocol; submitted by consensus nodes in practice           |
| `registerConnector`         | `ClprRegisterConnector`         | 88      | Any (permissionless; commit phase, stores the connector commitment)     |
| `deregisterConnector`       | `ClprDeregisterConnector`       | 89      | Connector admin key and the stake recipient's key                       |
| (connector reveal)          | `ClprCompleteConnector`         | 90      | Any (permissionless; reveal phase, creates the Connector, locks stake)  |
| (node-internal)             | `ClprEndpointPublication`       | 91      | Consensus node publishing its own endpoint; not user-submittable        |
| (system-initiated)          | `ClprMaintenance`               | 92      | System; externalizes CLPR singleton state, never submitted by users     |
| (admin recovery)            | `ClprEvictPeerEndpoint`         | 93      | CLPR admin (treasury or system admin payer)                             |

Field 87 was `ClprRedactMessage`. Message redaction was removed from the protocol and from the node (#27549), and
there is no `REDACTED` reply status.

**Note:** There is no `reportMisbehavior` transaction. Misbehavior detection and enforcement are local (see
cross-platform spec §1.6).

**Note:** `sendMessage` is a **system contract method** on `0x16e`, not a HAPI transaction. Application smart
contracts (or externally owned accounts) call it from an EVM execution context. See
[section 3.2.2](#322-sendmessage-system-contract-method) and [section 6](#6-connector-authorization).

**Note:** There are no `topUpConnector` or `withdrawConnectorBalance` transactions. A Connector's locked stake is
set at `ClprCompleteConnector`; its spendable balance is the balance of its connector contract's own account (see
[section 6.3](#63-connector-fund-custody)).

**Note:** `registerEndpoint` and `deregisterEndpoint` from spec section 6.5 are **not implemented** on Hiero.
Each consensus node publishes its own endpoint (`ClprEndpointPublication`) into the endpoint manifest (see
[section 4](#4-endpoint-implementation)). `updateEndpointRoster` from spec section 6.2 is also **not implemented**:
peer endpoints are learned from the peer ledger's endpoint manifest, which is cached on the Channel
(`ClprChannel.endpoint_manifest`).

## 1.2 Protobuf Message Definitions

The authoritative definitions are the `.proto` files under `hapi/hedera-protobuf-java-api/src/main/proto/services/`
(`clpr_*.proto`, and `state/clpr/*.proto` for state). The following summarizes each body.

### 1.2.1 Configuration Management

```protobuf
// Updates this ledger's local CLPR configuration (throttles). Requires a CLPR admin payer.
// chain_id and protocol_version are immutable after genesis; service_address is fixed to the CLPR
// system contract address (a request may restate or omit it, any other value is rejected). The
// timestamp is set by the handler to the consensus timestamp.
message ClprUpdateLedgerConfigurationTransactionBody {
  com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration configuration = 1;
}
```

### 1.2.2 Channel Management

```protobuf
// Phase 1 (Commit): reserves an ownership commitment keccak256(channel_id || public_key).
// Permissionless. No Channel record is created; the commitment is stored in PENDING_COMMITMENTS.
message ClprRegisterChannelTransactionBody {
  bytes ownership_commitment = 1;
}

// Phase 2 (Reveal): reveals the preimage, proves key ownership by signature, verifies the peer
// configuration and endpoint manifest proofs through the verifier contract, and creates the Channel
// in ACTIVE state.
message ClprCompleteChannelTransactionBody {
  bytes channel_id = 1;                     // 32 bytes, same on all chains for a given channel
  bytes public_key = 2;                     // encoding depends on signature_scheme
  bytes signature = 3;                      // over keccak256(channel_id)
  ClprSignatureScheme signature_scheme = 4; // ECDSA_SECP256K1 = 0, ED25519 = 1
  ContractID verifier_contract = 5;         // local verifier contract; immutable after creation
  bytes config_proof_bytes = 6;             // proof of the peer's ledger configuration
  bytes endpoint_manifest_proof_bytes = 7;  // proof of the peer's endpoint manifest
}

// Admin close. Three cases, depending on what exists for channel_id:
//  - ACTIVE or PAUSED Channel: moves to DRAINED if everything sent has been acknowledged (or
//    nothing was sent), otherwise to CLOSING.
//  - DRAINED Channel: admin recovery path, moves to CLOSED.
//  - No Channel record, only a pending commitment: if ownership_commitment is supplied, the abandoned
//    commitment is deleted.
message ClprCloseChannelTransactionBody {
  bytes channel_id = 1;
  bytes ownership_commitment = 2;
}

// Removes every entry matching service_endpoint from the Channel's cached peer manifest.
// CLPR admin only. The manifest version is unchanged.
message ClprEvictPeerEndpointTransactionBody {
  bytes channel_id = 1;
  ClprServiceEndpoint service_endpoint = 2;
}
```

### 1.2.3 Connector Management

Connector registration uses the same commit-reveal shape as Channel registration.

```protobuf
// Phase 1 (Commit): requires an existing Channel.
message ClprRegisterConnectorTransactionBody {
  bytes commitment = 1;
  bytes channel_id = 2;
}

// Phase 2 (Reveal): creates the Connector and locks its stake. The stake is transferred from the payer to
// the configured staking account in the same transaction's crypto-transfer list.
message ClprCompleteConnectorTransactionBody {
  bytes connector_id = 1;
  bytes public_key = 2;
  bytes signature = 3;                      // over keccak256(connector_id || CLPR system contract address)
  ClprSignatureScheme signature_scheme = 4;
  bytes salt = 5;
  bytes channel_id = 6;
  ContractID connector_contract = 7;        // the connector's authorization contract
  Key admin_key = 8;
  uint64 locked_stake = 9;                  // tinybars
}

// Removes the Connector and returns its locked stake to stake_recipient.
// Fails with CLPR_CONNECTOR_HAS_IN_FLIGHT_MESSAGES while the Connector has in-flight messages.
message ClprDeregisterConnectorTransactionBody {
  bytes channel_id = 1;
  bytes connector_id = 2;
  AccountID stake_recipient = 3;
}
```

### 1.2.4 Messaging

```protobuf
// Submits a bundle received from a peer endpoint for on-chain processing. The bundle_payload carries the
// proof, message data and Merkle paths or signatures for the verifier. Consensus nodes submit these
// transactions themselves with an empty signature map (the platform event signature authenticates the node).
message ClprSubmitBundleTransactionBody {
  bytes channel_id = 1;
  bytes bundle_payload = 2;
  uint64 endpoint_node_id = 3 [deprecated = true];
  bytes endpoint_signature = 4 [deprecated = true];
}
```

There is no `ClprSendMessage` body. Sending a message is a call to `sendMessage(bytes32 channelId, bytes32
connectorId, bytes targetApplication, bytes messageData)` on the system contract (section 3.2.2).

### 1.2.5 Transaction Records

CLPR transactions produce a standard `TransactionReceipt` and `TransactionRecord`. The receipt and record protos
carry no CLPR-specific fields. A message ID is returned to the caller of `sendMessage` as the call's return
value (`uint64 messageId`).

## 1.3 Query Definitions

| Pseudo-API (spec section 6) |          HAPI Query Type          |            Response Type             |
|-----------------------------|-----------------------------------|--------------------------------------|
| `getLedgerConfiguration`    | `ClprGetLedgerConfigurationQuery` | `ClprGetLedgerConfigurationResponse` |
| `getEndpointManifest`       | `ClprGetEndpointManifestQuery`    | `ClprGetEndpointManifestResponse`    |

These are fields 59 and 60 of `Query`. There are no `getQueueDepth` or `getConnector` queries. Channel, Connector
and queue state is read through the mirror node, and a subset is available to contracts through the system
contract (`getChannelQueueState`, `getEndpointManifest`; section 3.2.2).

### 1.3.1 Sync (Endpoint-to-Endpoint)

Endpoint-to-endpoint sync is a separate gRPC service (`ClprEndpointService`), not a HAPI query. See
[section 4](#4-endpoint-implementation) and the [CLPR Hiero Endpoint Specification](clpr-hiero-endpoint-spec.md).

## 1.4 Signing Requirements

|           Transaction           |                                 Required Signers                                  |
|---------------------------------|-----------------------------------------------------------------------------------|
| `ClprUpdateLedgerConfiguration` | Payer must be the treasury or the system admin account                            |
| `ClprRegisterChannel`           | Transaction payer only (permissionless; commit phase)                             |
| `ClprCompleteChannel`           | Transaction payer only (permissionless; reveal phase)                             |
| `ClprCloseChannel`              | Payer must be the treasury or the system admin account                            |
| `ClprEvictPeerEndpoint`         | Payer must be the treasury or the system admin account                            |
| `ClprRegisterConnector`         | Transaction payer only (permissionless; commit phase)                             |
| `ClprCompleteConnector`         | Transaction payer (also supplies the stake transfer)                              |
| `ClprDeregisterConnector`       | Connector admin key and the stake recipient's key                                 |
| `ClprSubmitBundle`              | Transaction payer only (a consensus node account, with an empty signature map)    |

The **CLPR admin** is not a separate key stored in state or configuration. Admin gating is done by the platform's
`PrivilegesVerifier.checkClprAdmin`: the payer must be the treasury (`accounts.treasury`) or the system admin
(`accounts.systemAdmin`) account. Handlers do not perform their own admin signature checks.

---

# 2. State Schema

All CLPR state is stored in Hiero's Merkle state tree alongside other service state (accounts, tokens, etc.),
making it directly provable via Hiero state proofs and TSS signatures.

## 2.1 Service Name

The CLPR service registers state under the service name `"ClprService"`.

## 2.2 Schema Version

Schema `V0780ClprSchema` (version `0.78.0`). It defines all state below. There is no prototype migration: the
prototype state model (ledger-ID-keyed queue metadata and configurations) is gone.

## 2.3 State Definitions

### 2.3.1 Singletons

|           State Key             |             Type               |                                           Description                                            |
|---------------------------------|--------------------------------|--------------------------------------------------------------------------------------------------|
| `LEDGER_CONFIGURATION`          | `ClprLedgerConfiguration`      | This ledger's CLPR configuration. See below for how it is initialized.                           |
| `ENDPOINT_MANIFEST`             | `ClprEndpointManifest`         | This ledger's published endpoint manifest, which peers verify.                                   |
| `ENDPOINT_MANIFEST_CONSTRUCTION`| `ClprEndpointManifestConstruction` | Work-in-progress state while consensus nodes publish their endpoints into a new manifest.    |

The singletons are initialized even when `clpr.enabled` is false. `protocol_version` comes from `clpr.protocolVersion`;
`chain_id` is derived from `contracts.chainId`; the throttles are loaded from the file named by
`clpr.ledgerConfigurationFile`, `clprLedgerConfiguration.json`. `chain_id` and `protocol_version` are immutable.

### 2.3.2 Key-Value Stores

|           State Key            |         Key Type          |       Value Type       |                                          Description                                           |
|--------------------------------|---------------------------|------------------------|------------------------------------------------------------------------------------------------|
| `CHANNELS`                     | `bytes(32)` (channel_id)  | `ClprChannel`          | All Channels, kept in every status including `CLOSED`.                                         |
| `PENDING_COMMITMENTS`          | commitment bytes          | commitment bytes       | Channel ownership commitments. An entry stays for as long as the Channel exists, so a Channel can never be created twice. |
| `CONNECTORS`                   | `ClprConnectorKey`        | `ClprConnector`        | Connectors, keyed by `(channel_id, connector_id)`.                                             |
| `PENDING_CONNECTOR_COMMITMENTS`| commitment bytes          | commitment bytes       | Connector commitments. Entries are kept after deregistration or a ban, so a connector ID cannot be registered again. |
| `MESSAGE_QUEUE`                | `ClprMessageKey`          | `ClprMessageValue`     | Outbound queue entries, keyed by `(channel_id, message_id)`. Entries are deleted after acknowledgement or reply. |

Per-Channel queue metadata (next message ID, acknowledged message ID, running hashes) lives on the `ClprChannel`
record itself. There is no separate queue-metadata store and no `CONNECTOR_INDEX`.

### 2.3.3 State Protobuf Messages

```protobuf
// Full Channel state (state/clpr/clpr_channel.proto).
message ClprChannel {
  bytes channel_id = 1;                    // 32-byte primary key
  string chain_id = 2;                     // CAIP-2 identifier of the peer
  bytes service_address = 3 [deprecated = true];
  Timestamp peer_config_timestamp = 4;
  ContractID verifier_contract = 5;        // immutable after registration
  bytes verifier_fingerprint = 6;          // informational
  ClprChannelStatus status = 7;
  uint64 next_message_id = 8;              // outbound queue
  uint64 acked_message_id = 9;
  bytes sent_running_hash = 10;            // 32 bytes
  uint64 received_message_id = 11;         // inbound queue
  bytes received_running_hash = 12;        // 32 bytes
  Timestamp last_config_timestamp = 13;    // last configuration propagated on this Channel
  bytes ownership_commitment = 14;
  ClprThrottles peer_throttles = 15;       // the peer's limits, learned from its ConfigUpdate
  bytes trust_anchor = 16;                 // the verifier's current reference point on the peer
  bytes trust_anchor_id = 17;
  bytes channel_context = 18;              // binds proofs to this Channel
  ClprEndpointManifest endpoint_manifest = 19;  // the peer's endpoints
  uint64 endpoint_manifest_version = 20;
}

enum ClprChannelStatus {
  PENDING = 0;  // Commit phase: claimed but not yet revealed
  ACTIVE = 1;
  PAUSED = 2;
  CLOSING = 3;
  DRAINED = 4;
  CLOSED = 5;
}
```

At channel registration, `verifier_fingerprint` is the Keccak-256 hash of the deployed
verifier's bytecode, or 32 zero bytes if a custom verifier has no available bytecode.
Built-in verifiers instead use fixed identity hashes computed as
`keccak256(UTF8("hiero.clpr.builtin-verifier/" + name))`:

| Verifier address |      Permanent name       |                         Fingerprint (hex)                          |
|------------------|---------------------------|--------------------------------------------------------------------|
| `0x16e`          | `hiero-tss`               | `9a0239cbc7be7e16347b8bfe6ab37f7a55d4eb688a0c8b6b141cc5035acac51e` |
| `0x16f`          | `besu-qbft`               | `9bb5a67f672529e6928db26e11d0a93f56f2f3581fda619f737e3e1615c3e909` |
| `0x170`          | `sei-cometbft`            | `ee0783a29d06bd654e54a8fd53a8553af7a43b0e601b1a0a1b59034b10e2ff45` |
| `0x171`          | `ethereum-sync-committee` | `962abc985d9e3cd97cc8598b2399f85e948187d8e56cbb988b4dad04e60c398c` |

Numeric contract IDs and their corresponding 20-byte EVM addresses resolve to the same
fingerprint. These permanent names identify verifier types and remain unchanged across native
implementation updates. Registration debug logs additionally include readable names and addresses,
such as `Besu QBFT (0x16f)`; display labels do not affect the hash. Custom verifiers are logged as
`Deployed contract` alongside their contract ID and bytecode fingerprint. Existing channel
fingerprints are not rewritten.

**Channel Status Transitions** (see also cross-platform spec section 2.1.1):

|   From    |    To     |                                        Trigger                                        |
|-----------|-----------|---------------------------------------------------------------------------------------|
| (new)     | `PENDING` | `ClprRegisterChannel` succeeds (commit phase)                                         |
| `PENDING` | `ACTIVE`  | `ClprCompleteChannel` succeeds (reveal phase)                                         |
| `PENDING` | (deleted) | Admin: `ClprCloseChannel` on an abandoned PENDING registration                        |
| `ACTIVE`  | `PAUSED`  | Auto: response ordering violation during `ClprSubmitBundle`                           |
| `ACTIVE`  | `CLOSING` | Admin: `ClprCloseChannel` with unacknowledged messages, or the peer reports `CLOSING`/`DRAINED`/`CLOSED` |
| `ACTIVE`  | `DRAINED` | Admin: `ClprCloseChannel` when everything sent is acknowledged or nothing was sent    |
| `PAUSED`  | `ACTIVE`  | Auto: a bundle with correctly ordered responses arrives                               |
| `PAUSED`  | `CLOSING` | Admin: `ClprCloseChannel` with unacknowledged messages, or the peer reports a close   |
| `CLOSING` | `DRAINED` | Auto: peer has acknowledged all outbound messages                                     |
| `DRAINED` | `CLOSED`  | Admin: `ClprCloseChannel` (recovery path), or auto when both sides are drained        |

`CLOSED` is a terminal state with no outgoing transitions. The Channel record and its commitment are kept.

```protobuf
// Connector state (state/clpr/clpr_connector.proto).
message ClprConnector {
  bytes connector_id = 1;
  bytes channel_id = 2;
  ContractID connector_contract = 3;     // authorization contract
  Key admin_key = 4;
  uint64 locked_stake = 5;               // slashable stake (tinybars)
  uint32 slash_count = 6;                // cumulative slashes
  uint64 in_flight_message_count = 7;    // outbound messages not yet resolved
}

message ClprConnectorKey {
  bytes channel_id = 1;
  bytes connector_id = 2;
}
```

The queue messages are `ClprMessageKey { channel_id, message_id }` and `ClprMessageValue { payload,
running_hash_after_processing }`, where the payload is one of `ClprMessage { connector_id, target_application,
sender, message_data }`, `ClprMessageReply { message_id, status, message_reply_data }` or `ClprControlMessage`
(currently only `ClprConfigUpdate`). `ClprMessageReplyStatus` has four values: `SUCCESS`, `APPLICATION_ERROR`,
`CONNECTOR_NOT_FOUND`, `CONNECTOR_UNDERFUNDED`.

## 2.4 State Size Estimates

|      State      |     Estimated Entry Size     |          Expected Scale           |               Notes                |
|-----------------|------------------------------|-----------------------------------|------------------------------------|
| `CHANNELS`      | ~300 bytes                   | 10s-100s                          | One per Channel                    |
| `CONNECTORS`    | ~200 bytes                   | 100s-1000s per Channel            |                                    |
| `MESSAGE_QUEUE` | variable (payload-dependent) | Up to `maxQueueDepth` per Channel | Entries deleted after ack/response |

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

The implementation class `ClprServiceImpl` (package `com.hedera.node.app.service.clpr.impl`) registers
`V0780ClprSchema` and the CLPR fee calculators (`serviceFeeCalculators()`).

## 3.2 Transaction Handlers

Each HAPI transaction type maps to a `TransactionHandler` that extends `AbstractClprHandler`. Handlers follow the
standard `pureChecks` / `preHandle` / `handle` pattern. When `clpr.enabled` is false, ingest and the consensus
handler reject CLPR transactions with `CLPR_NOT_ENABLED`.

|             Handler Class              |        Transaction Type         |                                                          Notes                                                          |
|----------------------------------------|---------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `ClprUpdateLedgerConfigurationHandler` | `ClprUpdateLedgerConfiguration` | Admin. Preserves immutable fields (`chain_id`, `protocol_version`, `service_address`); timestamp set to consensus time. |
| `ClprRegisterChannelHandler`           | `ClprRegisterChannel`           | Commit phase. Rejects a commitment already in `PENDING_COMMITMENTS` (`CLPR_CHANNEL_ALREADY_EXISTS`). No Channel record is created. |
| `ClprCompleteChannelHandler`           | `ClprCompleteChannel`           | Reveal phase. Verifies the commitment preimage and signature, calls the verifier's `verifyConfig`, creates the ACTIVE Channel. |
| `ClprCloseChannelHandler`              | `ClprCloseChannel`              | Admin. See section 1.2.2 for the three cases.                                                                           |
| `ClprEvictPeerEndpointHandler`         | `ClprEvictPeerEndpoint`         | Admin. Removes a peer endpoint from the Channel's cached manifest.                                                      |
| `ClprRegisterConnectorHandler`         | `ClprRegisterConnector`         | Commit phase. Requires an existing Channel (`CLPR_CHANNEL_NOT_FOUND`).                                                  |
| `ClprCompleteConnectorHandler`         | `ClprCompleteConnector`         | Reveal phase. Verifies the signature, requires the minimum locked stake, creates the Connector.                         |
| `ClprDeregisterConnectorHandler`       | `ClprDeregisterConnector`       | Requires zero in-flight messages; returns the locked stake to the stake recipient.                                      |
| `ClprSubmitBundleHandler`              | `ClprSubmitBundle`              | **High.** Verifier call, validation, acknowledgement, per-message dispatch, replies, slashing. See 3.2.1.               |
| `ClprEndpointPublicationHandler`       | `ClprEndpointPublication`       | Node-internal. A node publishes its own endpoint into the manifest construction.                                        |

`ClprMaintenance` (field 92) is system-initiated and has no user-facing handler path.

### 3.2.1 Handler Details: ClprSubmitBundleHandler

This is the most complex handler. `handle()` implements the bundle verification algorithm from cross-platform spec
section 4.2:

1. **Look up the Channel.** Reject if the Channel is `CLOSED` or `PENDING`. `ACTIVE`, `PAUSED`, `CLOSING` and
   `DRAINED` Channels handle bundles, so both sides can reach `CLOSED`.
2. **Call the Channel's verifier** (`verifyBundle`, see [section 5](#5-verifier-integration)) and validate the
   bundle with checks that do not depend on the latest Channel state. All validation finishes before any state is
   changed or any application is called. A verification failure charges the submitting node.
3. **Apply a newer endpoint manifest.** If the bundle carries a peer endpoint manifest whose version strictly
   advances the cached one, replace the cached manifest (bounded by this ledger's `max_peer_endpoints`). A stale or
   absent manifest is skipped silently. This is allowed on `CLOSING` and `DRAINED` Channels.
4. **Install a successor trust anchor** carried by the bundle, atomically with the manifest update. A bundle with
   no queue metadata or messages (a trust-anchor rotation or manifest recovery) stops here.
5. **Pre-scan replies.** Without changing state, check that the replies in the new tail of the bundle match this
   ledger's outbound Data messages in order. A mismatch is a protocol violation by the submitter: an `ACTIVE`
   Channel moves to `PAUSED` and the submitting node is penalized. Nothing in the bundle is processed.
6. **Resume.** If the Channel was `PAUSED` and the replies are correctly ordered, it returns to `ACTIVE`.
7. **Acknowledge and clean up.** Update `acked_message_id`; delete acknowledged Control and Response messages from
   the outbound queue. Outbound Data messages are deleted when their reply is processed (step 9).
8. **Propagate configuration lazily.** If `channel.last_config_timestamp` is older than the current ledger
   configuration, enqueue a `ConfigUpdate` control message and update `last_config_timestamp`.
9. **Dispatch the new tail of messages, in order** (replayed slots are never dispatched twice). If the peer reports
   `CLOSING`, `DRAINED` or `CLOSED` and this side is `ACTIVE` or `PAUSED`, the Channel first moves to `CLOSING`; new
   local sends are refused, but inbound Data messages in the bundle are still dispatched normally.
   - **Control message:** apply the `ConfigUpdate` (store the peer's throttles).
   - **Data message:** resolve the Connector. If it is not found, enqueue a `CONNECTOR_NOT_FOUND` reply. If the
     bundle's shared application gas budget is spent, enqueue `APPLICATION_ERROR` with no charge. Otherwise compute
     this callback's gas limit (`min(max_gas_per_message, remaining shared gas ÷ callbacks still pending)`) and the
     worst-case charge at that limit (see [section 6.3](#63-connector-fund-custody)); if the Connector's balance
     can't cover it, slash the Connector, enqueue `CONNECTOR_UNDERFUNDED` and do not dispatch. Otherwise debit the
     worst-case charge, dispatch `onClprMessage` to the target application (see
     [section 7](#7-application-dispatch)), enqueue a reply (`SUCCESS` or `APPLICATION_ERROR`), and refund the
     Connector the difference between the worst case and the gas actually used.
   - **Response message:** resolve the outbound Data message it answers, release the Connector's in-flight slot,
     and — best-effort, gas-metered and charged to that Connector — deliver `onClprResponse` to the sender if the
     Connector's balance covers it. If the reply is `CONNECTOR_NOT_FOUND` or `CONNECTOR_UNDERFUNDED`, slash that
     Connector (the one this ledger originally sent through) and reimburse the submitting endpoint from the
     penalty; `APPLICATION_ERROR` never slashes either side.

**Failure isolation:** a failure on one message does not stop processing of the remaining messages. Each message
is handled independently.

### 3.2.2 sendMessage (System Contract Method)

`sendMessage(bytes32 channelId, bytes32 connectorId, bytes targetApplication, bytes messageData)` is a method of the
CLPR system contract at `0x16e`, not a HAPI transaction. It returns `uint64 messageId`. The same contract also
exposes `getChannelQueueState(bytes32)`, `getEndpointManifest()`, `verifyBundle(bytes,bytes,bytes)` and
`verifyConfig(bytes,bytes32,bytes)`. The enqueue itself (`ClprServiceApiImpl.sendMessage`) implements spec
section 4.3:

1. Reject if CLPR is not enabled (`CLPR_NOT_ENABLED`).
2. Look up the Channel by `channel_id` (`CLPR_CHANNEL_NOT_FOUND`). Reject if its status is not `ACTIVE`
   (`CLPR_INVALID_CHANNEL_STATUS`).
3. **Lazy config propagation.** If `channel.last_config_timestamp` is older than the current ledger configuration,
   enqueue a `ConfigUpdate` control message and update `last_config_timestamp`.
4. Look up the Connector by `connector_id` on the Channel (`CLPR_CONNECTOR_NOT_FOUND`).
5. **Connector authorization.** The system contract first calls the Connector's `authorizeOutboundMessage` in a
   child call frame (see [section 6](#6-connector-authorization)). A revert, halt or `false` result fails the send
   with `CLPR_AUTHORIZATION_FAILED`.
6. Validate the payload size against the peer's `max_message_payload_bytes`, or the local value if the peer's is not
   known yet (`CLPR_PAYLOAD_TOO_LARGE`).
7. Validate queue depth against the local `max_queue_depth` (`CLPR_QUEUE_FULL`), and the Connector's in-flight
   count against its quota of `max_queue_depth * clpr.connectorQueueQuotaPct / 100` (`CLPR_QUEUE_QUOTA_EXCEEDED`).
8. Construct the `ClprMessage` with `connector_id`, `target_application`, `sender` (stamped from the calling
   contract address) and `message_data`.
9. Compute `running_hash = SHA-256(sent_running_hash || serialized_payload)` and store the message keyed by
   `(channel_id, next_message_id)`.
10. Update the Channel (`sent_running_hash`, `next_message_id += 1`) and increment the Connector's
    `in_flight_message_count`.
11. Return the assigned `messageId` to the caller.

### 3.2.3 preHandle Patterns

Most CLPR handlers have minimal `preHandle` work, consistent with Hiero's design principle that `preHandle` only
verifies the payer can pay:

- **Admin transactions** (`ClprUpdateLedgerConfiguration`, `ClprCloseChannel`, `ClprEvictPeerEndpoint`): admin
  gating is performed by the platform's `PrivilegesVerifier.checkClprAdmin` (payer must be the treasury or system
  admin account), not by the handler.
- **Deregistration**: `preHandle` looks up the Connector and requires its admin key and the stake recipient's key.
- **Permissionless transactions** (`ClprRegisterChannel`, `ClprCompleteChannel`, `ClprRegisterConnector`,
  `ClprCompleteConnector`): the payer key is the only requirement.
- **`ClprSubmitBundle`**: submitted by consensus nodes with an empty signature map, and therefore listed in
  `networkAdmin.nodeTransactionsAllowList`.

**Note:** `sendMessage` is a system contract method and does not go through `preHandle`.

## 3.3 Query Handlers

|            Handler Class            |            Query Type             |
|-------------------------------------|-----------------------------------|
| `ClprGetLedgerConfigurationHandler` | `ClprGetLedgerConfigurationQuery` |
| `ClprGetEndpointManifestHandler`    | `ClprGetEndpointManifestQuery`    |

**Note:** Channel and Connector state is not exposed as HAPI queries. It is available through the mirror node REST
API, which serves it without consensus node load.

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

|          Module           |                                         Purpose                                           |
|---------------------------|-------------------------------------------------------------------------------------------|
| `:hedera-clpr-service`    | API module: `ClprService`, `ClprServiceApi`, store interfaces, lifecycle interfaces        |
| `:hedera-clpr-service-impl` | Implementation: `ClprServiceImpl`, all handlers, schema, store implementations          |

---

# 4. Endpoint Implementation

For endpoint module architecture, sync orchestration, proof construction, peer selection, gRPC service
definition, endpoint publication, and misbehavior handling, see the
[CLPR Hiero Endpoint Specification](clpr-hiero-endpoint-spec.md).

This section covers only how the on-ledger CLPR Service interacts with the endpoint module.

## 4.1 Service-to-Endpoint Interaction

The endpoint module (`ClprBundleSubmitter`) submits `ClprSubmitBundle` HAPI transactions to the local node's
transaction pipeline after receiving a peer's bundle. From the CLPR Service's perspective, these are ordinary
consensus transactions. They carry an empty signature map; the platform event signature authenticates the node, and
the endpoint identity fields on the body are deprecated.

**Config propagation is lazy and in the service.** When `ClprUpdateLedgerConfiguration` stores a new configuration,
nothing is enqueued per Channel. The next `sendMessage` or `ClprSubmitBundle` on a Channel notices that
`last_config_timestamp` is older and enqueues the `ConfigUpdate` control message (sections 3.2.1 and 3.2.2).

**Endpoint manifest.** Each consensus node publishes its own endpoint with `ClprEndpointPublication` into the active
manifest construction (`ENDPOINT_MANIFEST_CONSTRUCTION`); the assembled manifest is written to
`ENDPOINT_MANIFEST` when the construction closes. Peers learn the manifest through proofs carried in bundles
(section 3.2.1 steps 3 and 4).

---

# 5. Verifier Integration

Per cross-platform spec §3.1 for the verifier interface definition and verification semantics. On Hiero, verifiers
are contracts called from the native service layer. Four built-in verifiers are native system contracts
(section 2.3.3 table); custom verifiers are EVM contracts with Hiero contract IDs.

## 5.1 Verifier Contracts

**On Hiero-to-Hiero channels:** the verifier for Hiero proofs validates TSS signatures and Merkle paths
(`hiero-tss`, `0x16e`).

**On Ethereum-to-Hiero channels:** the verifier for Ethereum proofs validates BLS aggregate signatures from
Ethereum's sync committee (`ethereum-sync-committee`, `0x171`).

Besu QBFT (`0x16f`) and Sei CometBFT (`0x170`) have built-in verifiers too. See [verifier.md](verifier.md).

## 5.2 Implementation Fingerprint (Code Hash)

On Hiero, the verifier's implementation fingerprint is the `keccak256` hash of the deployed contract's EVM
bytecode, matching the EVM `EXTCODEHASH` semantic. Built-in verifiers use the fixed identity hashes in the table
in section 2.3.3. The CLPR service computes the fingerprint during channel registration and stores it on the
Channel as an informational field. The verifier is immutable after registration.

## 5.3 Verifier Invocation from Native Service

The CLPR native service invokes verifier contracts by dispatching a contract call (`EvmClprVerifier`), with gas
bounded by `clpr.verifierGasLimit` (default 2,000,000). The `verifyBundle` and `verifyConfig` calls are **read-only
with respect to CLPR state**.

However, verifier contracts themselves MAY maintain internal mutable state (e.g., validator set tracking, sync
committee rotation) updated via **separate administrative calls** outside the CLPR interface. The `view` modifier
only applies to the CLPR-facing verification methods, not to the verifier contract as a whole.

The built-in verifiers are implemented natively in Java behind system contract addresses, so the hot path does not
need to run EVM bytecode for those proof systems.

---

# 6. Connector Authorization

Per cross-platform spec §3.2 for the connector authorization interface and semantics.

## 6.1 Authorization Contracts on Hiero

On Hiero, Connector authorization contracts are **smart contracts** (EVM contracts with Hiero contract IDs)
implementing the connector interface (spec §3.2). The CLPR `sendMessage` system contract invokes
`authorizeOutboundMessage(bytes32,bytes,bytes,bytes)` as a child EVM `CALL` frame on the sending transaction's own
frame stack, with the CLPR system contract as `msg.sender` and a fixed 50,000 gas budget that is not charged to the
caller. The connector thus shares the sending transaction's context: its `tx.origin`, transient storage, and warm
addresses; and, when the sender is an EVM hook, the hook's restrictions (no `DELEGATECALL` or `CALLCODE`). A revert,
halt, or `false` result fails the send with `CLPR_AUTHORIZATION_FAILED`, without failing the rest of the transaction.
The connector may update its own contract state and emit logs during authorization. These changes revert if
authorization, the send, or an enclosing call fails.

## 6.2 Simple Connector Implementations

A reference "pass-through" Connector authorization contract that approves all messages can be used for testing.
More sophisticated implementations (allow-lists, rate limits, payment requirements) can be deployed by Connector
operators.

## 6.3 Connector Fund Custody

A Connector's money is held in two places:

1. **Locked stake.** At `ClprCompleteConnector`, `locked_stake` (at least `clpr.minLockedStake`) is transferred from
   the payer to `clpr.stakingAccount` through the transaction's crypto-transfer list, and recorded on the
   `ClprConnector` state entry. A slash reduces `locked_stake`. `ClprDeregisterConnector` transfers the remaining
   stake from the staking account to the stake recipient.
2. **Spendable balance.** This is the balance of the Connector's own contract account (`connector_contract`).
   Before dispatching an inbound Data message, the CLPR service computes the worst-case charge — a flat
   `clpr.messageExecutionCost` plus `clpr.messageExecutionCost × clpr.endpointMarginPercent / 100`, plus the
   worst-case EVM gas cost at the callback's granted gas limit (section 7.3) — and debits that from the balance
   before the callback runs, so a re-entering call cannot spend the same balance twice. Afterward, the difference
   between the worst-case gas cost and the gas actually used is credited back. A Response callback charges the
   Connector for gas only (no flat execution cost), and is skipped rather than failing the bundle if the balance
   can't cover it. There is no top-up or withdraw transaction: the Connector operator moves funds to and from the
   contract account directly.

---

# 7. Application Dispatch

Per cross-platform spec §3.3 for the receiver interface and callback semantics. On Hiero, the target application is
a smart contract.

## 7.1 Dispatch Mechanism

1. The CLPR service builds a contract call to the `target_application` address with the `onClprMessage(bytes32,bytes,bytes)`
   function selector and ABI-encoded parameters.
2. The call is dispatched as a child transaction of the bundle transaction.
3. **Gas limit:** the callback is granted `min(max_gas_per_message, remaining shared gas ÷ callbacks still pending
   in this bundle)` — see [section 7.3](#73-gasops-limits). Once the bundle's shared gas is exhausted, the
   remaining Data messages get `APPLICATION_ERROR` replies with no charge and without being dispatched.
4. **Return handling:**
   - If the call succeeds, a `SUCCESS` reply is generated with the returned `bytes` (ABI-unwrapped).
   - If the call reverts or runs out of gas, an `APPLICATION_ERROR` reply is generated with empty response data.
     The Connector is still charged for the gas actually used (section 6.3), but is not slashed for an application
     failure.
5. **State isolation:** the application call runs in a child transaction context. If the application reverts, all
   its state changes are rolled back, but the CLPR service's state changes (Connector charge, reply generation) are
   retained.

## 7.2 Response Delivery

When a reply arrives on the source ledger:

1. The CLPR service looks up the original outbound Data message and its `sender`, which `sendMessage` stamped from
   the calling contract's address.
2. If the sender is a smart contract implementing the response callback, the service dispatches
   `onClprResponse(bytes32,uint64,uint8,bytes)` to it, gas-metered and charged to the Connector the original
   message went through (section 6.3). This is best-effort: if that Connector's balance can't cover the gas, the
   callback is skipped rather than failing the bundle.
3. If the sender is an EOA (externally owned account), no callback is made. The sender can read the outcome from the
   mirror node.
4. If the reply is `CONNECTOR_NOT_FOUND` or `CONNECTOR_UNDERFUNDED`, the Connector the original message went
   through is slashed and the submitting endpoint is reimbursed from the penalty (section 11.7). `APPLICATION_ERROR`
   never triggers a slash.

## 7.3 Gas/Ops Limits

On Hiero, the "gas" budget for application callbacks maps to the EVM gas limit for the child contract call, and is
shared, not flat per message. The node submitting the bundle (or completing a Channel, for a verifier call)
prepays a gas allowance up front — `contracts.maxGasPerTransaction` for a bundle, or `clpr.verifierGasLimit` for
`ClprCompleteChannel` — and is refunded whatever goes unused at the end. Within that shared allowance, each
callback (`onClprMessage` or `onClprResponse`) is granted `min(max_gas_per_message, remaining shared gas ÷
callbacks still pending)`, so later callbacks in a large bundle can receive less than `max_gas_per_message` if
earlier ones used heavily. `max_gas_per_message` (from the ledger configuration) is still the per-callback cap.

**Throttle interaction:** CLPR bundle processing must respect Hiero's existing operations-per-second throttles.
`max_messages_per_bundle` must be calibrated so that a full bundle's worth of application dispatches does not exceed
the per-transaction ops limit. This is a configuration tuning concern, not a code change.

---

# 8. Security Model

For cross-platform security properties (reentrancy patterns, queue monopolization principles, threat model),
see cross-platform spec §8. This section covers Hiero-specific security mechanisms.

## 8.1 Permissioned Endpoints

On Hiero, all CLPR endpoints are consensus nodes governed by the Hedera council (on mainnet) or the network
operator (on testnets and HashSpheres). This provides strong Sybil resistance without requiring endpoint bonds.

**Implication:** the `registerEndpoint` and `deregisterEndpoint` pseudo-APIs from spec §6.5 are not implemented
on Hiero. Endpoint publication is driven by the consensus nodes themselves.

**Future consideration:** if Hiero moves to permissionless node operation, CLPR endpoint bonds would need to be
added. This is a future enhancement, not a launch requirement.

## 8.2 CLPR Admin

CLPR administration is the payer check in `PrivilegesVerifier.checkClprAdmin`: the **treasury** or **system admin**
account. There is no CLPR admin key in state or configuration. It controls:

- Updating the ledger configuration (throttles)
- Closing Channels, including the DRAINED to CLOSED recovery path and cleaning up abandoned pending registrations
- Evicting a peer endpoint from a Channel's cached manifest

## 8.3 ABFT Guarantees

Hiero state proofs leverage TSS (Threshold Signature Scheme) signatures from the active validator set. When
both participating ledgers are Hiero networks with ABFT finality, CLPR inherits ABFT properties: no reorg risk,
and a single honest endpoint per ledger is sufficient for correct operation.

## 8.4 Reentrancy

Per cross-platform spec §8.5 for the reentrancy threat model. Application callbacks run in a child transaction
context. The Connector's balance is debited before the callback runs, so a re-entering call cannot observe an
unspent balance. An application can submit a new CLPR transaction via the HAPI gRPC interface, but that goes through
consensus as a separate transaction.

## 8.5 Queue Monopolization Mitigation

Per cross-platform spec §8.9 for the queue monopolization threat model. Hiero-specific parameter:

- **Per-Connector queue quota:** 50% of `max_queue_depth` (`clpr.connectorQueueQuotaPct`), enforced in
  `sendMessage` (`CLPR_QUEUE_QUOTA_EXCEEDED`).

There is no queue-depth fee escalation: the fee-escalation threshold described in earlier drafts was never
implemented.

---

# 9. Configuration

## 9.1 Configuration Properties (`clpr.*`)

The properties below are the fields of `ClprConfig` (`@ConfigData("clpr")`). Cross-platform configuration parameters
(throttles, queue limits, message sizes) are defined in cross-platform spec §7; on Hiero they are on-ledger values in
`ClprLedgerConfiguration`, loaded at genesis from the file named by `clpr.ledgerConfigurationFile`.

|                Property                  |  Default                              |                                  Description                                   |
|------------------------------------------|---------------------------------------|--------------------------------------------------------------------------------|
| `clpr.enabled`                           | `true`                                | Master enable switch. When false, CLPR transactions return `CLPR_NOT_ENABLED`. |
| `clpr.protocolVersion`                   | `1`                                   | CLPR protocol version. Set at genesis; immutable thereafter.                   |
| `clpr.ledgerConfigurationFile`           | `data/config/clprLedgerConfiguration.json` | File that supplies the initial throttles.                                 |
| `clpr.upgradeLedgerConfigurationFile`    | `clprLedgerConfiguration.json`        | Name of the file read from the upgrade directory.                              |
| `clpr.minLockedStake`                    | `100000000` (tinybars)                | Minimum stake to complete a Connector registration.                            |
| `clpr.stakingAccount`                    | `803`                                 | Account that holds Connectors' locked stake.                                   |
| `clpr.slashBasePenalty`                  | `10000000` (tinybars)                 | Penalty for a Connector's first slash.                                         |
| `clpr.slashMultiplier`                   | `2`                                   | Multiplier applied for each subsequent slash.                                  |
| `clpr.slashBanThreshold`                 | `5`                                   | Slash count at which a Connector is banned and its remaining stake forfeited.  |
| `clpr.connectorQueueQuotaPct`            | `50`                                  | Maximum percentage of queue depth one Connector can occupy.                    |
| `clpr.verifierGasLimit`                  | `2000000`                             | Gas limit for a verifier call.                                                 |
| `clpr.messageExecutionCost`              | `1000000`                             | Execution cost charged to a Connector per dispatched inbound message.          |
| `clpr.endpointMarginPercent`             | `10`                                  | Margin on top of `messageExecutionCost`, added to a Data message's worst-case Connector charge (section 6.3). Not used in slash reimbursement. |
| `clpr.endpointPayoutCapTinybars`         | `5000000`                             | Defined, but not read anywhere in `hedera-clpr-service-impl` as of this writing.                                               |
| `clpr.endpointMisbehaviorPenaltyTinybars`| `5000000`                             | Penalty for a misbehaving submitting node.                                     |
| `clpr.nodeSubmitBundleMaxFee`            | `1000000000`                          | Maximum fee a node pays to submit a bundle.                                    |
| `clpr.verifyProofsAtSender`              | `true`                                | Verify proofs before submitting them (node-local).                             |

Sync, retry and manifest tuning (node-local unless noted): `clpr.maxConcurrentSyncs` (`4`), `clpr.syncTimeoutSeconds`
(`30`), `clpr.reputationDecaySeconds` (`300`), `clpr.retryInitialDelayMs` (`1000`), `clpr.retryMaxDelayMs`
(`30000`), `clpr.retryMaxAttempts` (`5`), `clpr.circuitBreakerCooldownSeconds` (`120`),
`clpr.syncPeerExclusionEnabled` (`false`), `clpr.peerEndpointsFile` (`data/clpr/peer-endpoints.json`),
`clpr.manifestGracePeriod` (`300s`), `clpr.manifestGraceExtension` (`300s`), `clpr.manifestMaxGraceExtensions`
(`2`), `clpr.manifestSubmissionRetries` (`50`), `clpr.manifestSubmissionRetryDelay` (`5s`) and
`clpr.manifestSubmissionDistinctTxnIds` (`10`).

**Shipped throttle defaults** (`clprLedgerConfiguration.json`, on-ledger values in `ClprThrottles`):

|      Throttle              | Shipped Value | Spec Parameter              |
|----------------------------|---------------|-----------------------------|
| `maxQueueDepth`            | `10000`       | `max_queue_depth`           |
| `maxMessagePayloadBytes`   | `65536`       | `max_message_payload_bytes` |
| `maxMessagesPerBundle`     | `1000`        | `max_messages_per_bundle`   |
| `maxGasPerMessage`         | `15000000`    | `max_gas_per_message`       |
| `maxSyncBytes`             | `4194304`     | `max_sync_bytes`            |

`max_local_endpoints` and `max_peer_endpoints` are also throttle fields. The shipped file does not set them, and a
`max_peer_endpoints` of zero means no limit when a peer manifest is stored (section 3.2.1 step 3).

## 9.2 Relationship Between Config and State

- **Config properties** (`clpr.*`) control node behavior and economics: the feature flag, stake and slash amounts,
  verifier gas, sync tuning.
- **State configuration** (`ClprLedgerConfiguration`) controls protocol behavior: throttles and chain identity. These
  are the parameters that peers see and enforce.

The on-ledger values are authoritative for cross-ledger purposes. The `chain_id` (derived from `contracts.chainId`)
and `protocol_version` are set at genesis and are immutable thereafter. The throttles can be updated via
`ClprUpdateLedgerConfiguration`. `service_address` is fixed to the CLPR system contract address (the value this
ledger's endpoint manifest carries, which peers verify it against); an update may restate it or omit it, and any
other value is rejected.

---

# 10. Fee Schedule

## 10.1 Fee Model

CLPR HAPI transactions follow Hiero's standard fee model. Each handler registers a `ClprFeeCalculator`
(`ServiceFeeCalculator`) that sets the service base fee from the `Clpr` entry of `simpleFeesSchedules.json`. Fees are
paid in HBAR by the transaction payer.

## 10.2 Fee Calculators

In the mainnet, testnet and previewnet fee schedules, every user-submittable CLPR transaction has the same shape:
a fixed `baseFee` of `10000000` and no extras.

|           Transaction           |               Fee Basis                |
|---------------------------------|----------------------------------------|
| `ClprUpdateLedgerConfiguration` | Fixed base fee, no extras              |
| `ClprRegisterChannel`           | Fixed base fee, no extras              |
| `ClprCompleteChannel`           | Fixed base fee, no extras              |
| `ClprCloseChannel`              | Fixed base fee, no extras              |
| `ClprEvictPeerEndpoint`         | Fixed base fee, no extras              |
| `ClprRegisterConnector`         | Fixed base fee, no extras              |
| `ClprCompleteConnector`         | Fixed base fee, no extras              |
| `ClprDeregisterConnector`       | Fixed base fee, no extras              |
| `ClprSubmitBundle`              | Fixed base fee, no extras              |
| `ClprEndpointPublication`       | Base fee `0`, exempt from node and network fees |

There is no per-byte, per-message or queue-depth component in the HAPI fee for any of these. `sendMessage` is not a
HAPI transaction and has no entry in this schedule: it is a contract call, so the caller pays ordinary EVM gas. Fee
changes for the system contract methods are tracked separately from this document.

## 10.3 Connector Charge and Endpoint Reimbursement

These are the only CLPR-specific money movements beyond the standard Hedera transaction fee split (section 10.1).
There is no routine per-bundle compensation paid to the submitting endpoint: it earns the ordinary node/network
fee on `ClprSubmitBundle` like any other transaction, nothing more, unless a slash occurs (below).

- **Connector charge (success path, section 6.3).** An inbound Data message debits its Connector the worst case —
  `clpr.messageExecutionCost + clpr.messageExecutionCost × clpr.endpointMarginPercent / 100`, plus the worst-case
  gas cost — before the callback runs, and credits back the difference between the worst case and the gas
  actually used afterward. A Response callback charges gas only, with no flat component.
- **Slash reimbursement (section 11.7).** When a Connector is slashed, the submitting endpoint (the payer of the
  `ClprSubmitBundle` transaction where the slash was decided) is reimbursed `min(penalty, the CLPR staking
  account's actual balance)`, transferred from the staking account. `clpr.endpointMarginPercent` and
  `clpr.endpointPayoutCapTinybars` play no part in this.

---

# 11. Platform-Specific Gaps and Decisions

This section documents areas where the cross-platform spec leaves decisions to the platform, and the
Hiero-specific choices made.

## 11.1 Endpoint Management

**Spec says:** Section 6.5 states that endpoint registration is needed on permissionless chains but not on
Hiero where "consensus nodes are the endpoints."

**Hiero decision:** No `registerEndpoint` or `deregisterEndpoint` transactions. Each consensus node publishes its
own endpoint (`ClprEndpointPublication`) into this ledger's manifest. Peer endpoints are learned from the peer
ledger's endpoint manifest, cached on the Channel (`ClprChannel.endpoint_manifest`). An admin can remove a bad peer
endpoint with `ClprEvictPeerEndpoint`.

## 11.2 Endpoint Bond

**Spec says:** Section 2.3 defines an endpoint bond for permissionless chains. Section 3.1.2 of the design doc
notes that "no separate CLPR-specific bond is required because Hedera consensus nodes are permissioned."

**Hiero decision:** No endpoint bond in the initial implementation. Misbehavior is penalized locally
(`clpr.endpointMisbehaviorPenaltyTinybars`) and through governance. This may change if Hiero moves to permissionless
node operation.

## 11.3 Service Address

**Spec says:** The `service_address` in `ClprLedgerConfiguration` is "a well-known constant on Hiero where the
CLPR Service is native."

**Hiero decision:** The service address is the CLPR system contract address `0x16e` (`0.0.366`), the same address
that exposes `sendMessage`. It is fixed: `ClprUpdateLedgerConfiguration` may restate it but not change it.

## 11.4 Verifier Implementation Fingerprint

**Spec says:** The fingerprint is "whatever mechanism that platform provides to verify deployed program code
against a known hash."

**Hiero decision:** `keccak256` of the deployed EVM bytecode, matching `EXTCODEHASH` semantics, and fixed identity
hashes for the built-in verifiers (section 2.3.3).

## 11.5 Signature Verification

**Spec says:** Channel completion requires revealing the channel_id and public_key (verifying the
ownership commitment), plus a signature proving control of the keypair. The signature scheme is
platform-specific.

**Hiero decision:** `ClprCompleteChannel` and `ClprCompleteConnector` accept `ECDSA_SECP256K1` and `ED25519`
(`ClprSignatureScheme`), so peers with either key type are supported. The cross-platform protocol allows any
signature scheme that satisfies the ownership commitment verification
(`keccak256(channel_id || public_key) == stored_commitment`). The channel_id is an arbitrary 32-byte value chosen by
the registrant, not derived from any key.

- **Channel completion (reveal phase):** the caller provides the channel_id and the public key. The handler
  verifies `keccak256(channel_id || public_key)` matches the stored ownership commitment and then verifies the
  signature against the supplied public key. The signed payload is `keccak256(channel_id)`. The channel ID alone is
  sufficient: there is no replay risk since the channel ID is unique, and the simple payload lets the same signature
  work on both ledgers. Signature verification happens only during `completeChannel`.
- **Connector completion (reveal phase):** the signature is over `keccak256(connector_id || 0x…016e)`, binding it
  to the CLPR system contract address.
- **Bundle submitter:** the endpoint signature fields on `ClprSubmitBundle` are deprecated. A consensus node
  submits the bundle with an empty signature map, authenticated by the platform event signature.

## 11.6 Minimum Connector Bond

**Spec says:** Section 4.6 requires platforms to define minimum Connector bond requirements to maintain the
stake-to-exposure invariant.

**Hiero decision:** The minimum stake is `clpr.minLockedStake` (default 100,000,000 tinybars). The value should be
reviewed against:
- Maximum per-message execution cost (gas * gas_price)
- Maximum queue depth (worst-case in-flight messages)
- Required coverage ratio (stake must cover worst-case endpoint losses)

This is an **open economic design parameter** that must be quantified before production deployment.

## 11.7 Slashing Schedule

**Spec says:** Section 4.6 states "Platform-specific specifications MUST define the slashing schedule."

**Hiero decision:** Escalating slashes (`ClprSlashingUtils`):
1. The first slash deducts `clpr.slashBasePenalty` from `locked_stake`.
2. Each later slash multiplies the previous penalty by `clpr.slashMultiplier`, capped at the remaining stake.
3. The slash that brings the count to `clpr.slashBanThreshold` (default 5) bans the Connector: its record is removed
   and its remaining stake forfeited. Its commitment stays registered, so the same connector ID cannot be used again.

A Connector is slashed in two situations, never for an application failure:
- **Destination-side**, when a Data message dispatch finds the receiving Connector's balance can't cover the
  worst-case charge (`CONNECTOR_UNDERFUNDED`).
- **Source-side**, when a reply comes back `CONNECTOR_NOT_FOUND` or `CONNECTOR_UNDERFUNDED`: the Connector this
  ledger originally sent through is slashed, on the theory that it misreported itself or the destination.

Either way, the proceeds go to the endpoint submitting the bundle in which the slash was decided (section 10.3),
capped by the CLPR staking account's actual balance.

## 11.8 Application Callback Model

**Spec says:** Section 6.5 (Application Delivery) requires platforms to define the callback interface, gas budget,
return value convention, and sync/async model.

**Hiero decision:**
- **Callback interface:** `onClprMessage(bytes32,bytes,bytes)` for inbound messages and
  `onClprResponse(bytes32,uint64,uint8,bytes)` for replies (see section 7).
- **Gas budget:** `max_gas_per_message` from the ledger configuration, within the bundle transaction's gas budget.
- **Return value:** Solidity `returns (bytes memory)` for response data; revert for application error.
- **Execution model:** synchronous within the bundle transaction. The application callback completes before the next
  message in the bundle is processed.

---

# 12. Historical Findings

Earlier drafts of this document recorded design findings F-1 to F-14 from the first review of the cross-platform
spec against the prototype. They are no longer reproduced here. Their outcomes are reflected in the sections above:

- Findings about the prototype state protos (control messages, connector fields, reply status, queue keying,
  queue-metadata naming) are resolved by the schema in section 2.
- The Channel ID and ownership commitment finding is resolved by the commit-reveal scheme in sections 1.2.2 and 11.5.
- Response ordering recovery is resolved: a violation moves the Channel to `PAUSED`, and a correctly ordered
  bundle returns it to `ACTIVE` (section 3.2.1).
- Response delivery to externally owned accounts is addressed in section 7.2.
- The verifier-as-system-contract question is resolved by the native built-in verifiers (section 5).
- Throttle interaction, verifier gas cost and the bundle size and transaction size limits are open tuning concerns,
  see sections 7.3 and 9.1.
- State proof availability and roster-driven endpoint updates are covered by the endpoint specification and by
  `ClprEndpointPublication` (section 4).

**Sections not re-verified against the code** when this document was updated: section 8.3 (ABFT guarantees),
section 8.4 apart from the balance-before-callback ordering, and the status transition table rows for automatic
`CLOSING` to `DRAINED` and `DRAINED` to `CLOSED`.

A second pass corrected the application-callback gas and Connector-charge mechanics (sections 3.2.1, 6.3, 7, 9.1,
10.3) and the slash trigger list (11.7), which the first pass had understated. `config-and-slashing.md` was
useful context for this pass but is itself out of date in places (its `ClprConfig` property table lists fields and
defaults that don't match `ClprConfig.java`, including `clpr.enabled`'s default) — it was not relied on for any
claim that wasn't independently confirmed in the handler and config source.
