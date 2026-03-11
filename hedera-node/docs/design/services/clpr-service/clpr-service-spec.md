# CLPR Service Specification

This document is the technical specification for the CLPR (Cross Ledger Protocol) Service. It defines the precise
interfaces, message formats, state transitions, and verification algorithms required to implement CLPR. For the
architectural rationale, design decisions, and conceptual overview, see the companion
[CLPR Service Design Document](clpr-service.md).

---

# 1. Protobuf Definitions

All protobuf definitions for CLPR are defined in `hapi/hedera-protobuf-java-api/src/main/proto/interledger/*`.

## 1.1 Ledger Identity and Configuration

```protobuf
// The static configuration for a ledger. Does not include the endpoint roster,
// which is managed separately via control messages (see design doc §3.1.2).
message ClprLedgerConfiguration {
  string chain_id = 1;                                // CAIP-2 identifier, e.g. "hedera:mainnet"
  map<string, bytes> approved_verifiers = 2;           // VerifierType → implementation fingerprint (code hash)
  proto.Timestamp timestamp = 3;
  ClprThrottles throttles = 4;
}

message ClprThrottles {
  uint32 max_messages_per_bundle = 1;   // hard cap: max messages in a single bundle
  uint32 max_syncs_per_sec = 2;         // advisory: suggested max sync frequency
  uint32 max_message_payload_bytes = 3; // max payload size in bytes for a single message
  uint64 max_gas_per_message = 4;       // max gas (or ops budget) per message execution
}

// Endpoint roster entry. Maintained in state separately from the configuration,
// propagated via EndpointJoin / EndpointLeave control messages.
message ClprEndpoint {
  proto.ServiceEndpoint service_endpoint = 1;  // optional; omit for private networks
  bytes signing_certificate = 2;               // DER-encoded RSA public certificate
  bytes account_id = 3;                        // on-ledger account (20 or 32 bytes)
}
```

## 1.2 Control Messages

```protobuf
// Control message payload variants. These are protocol-level messages that manage
// Connection state rather than carrying application data.
message ClprControlMessage {
  oneof payload {
    ClprEndpointJoin endpoint_join = 1;
    ClprEndpointLeave endpoint_leave = 2;
    ClprConfigUpdate config_update = 3;
  }
}

message ClprEndpointJoin {
  ClprEndpoint endpoint = 1;
}

message ClprEndpointLeave {
  bytes account_id = 1;  // identifies the departing endpoint
}

message ClprConfigUpdate {
  ClprLedgerConfiguration configuration = 1;
}
```

## 1.3 Connection

```protobuf
message ClprConnection {
  // --- Peer Identity (compound key: chain_id + service_address) ---

  string chain_id = 1;                     // CAIP-2 identifier for the peer chain
  bytes service_address = 2;               // on-ledger address of the peer's CLPR Service

  // --- Peer Configuration ---

  proto.Timestamp peer_config_timestamp = 3;

  // --- Verifier Contract ---

  bytes verifier_contract = 4;             // address of the locally deployed verifier for this Connection
  bytes verifier_fingerprint = 5;          // implementation fingerprint (code hash) endorsed by peer

  // --- Connection State ---

  uint64 deposit = 6;                      // locked deposit amount (returned on graceful close, slashable)
  ClprConnectionStatus status = 7;         // current operational state

  // --- Message Queue Metadata ---

  uint64 received_message_id = 10;
  uint64 acked_message_id = 11;
  bytes received_running_hash = 12;
  bytes sent_running_hash = 13;
  uint64 next_message_id = 14;
}

// Note: the peer endpoint roster is stored separately in state, keyed by
// (chain_id, service_address, account_id). It is NOT embedded in the Connection object.
// Endpoints are seeded at connection registration and updated via
// ClprEndpointJoin / ClprEndpointLeave control messages.

enum ClprConnectionStatus {
  ACTIVE = 0;    // normal operation
  PAUSED = 1;    // temporarily halted by admin
  SEVERED = 2;   // permanently closed by admin
  HALTED = 3;    // halted due to response ordering violation
}
```

## 1.4 Connector

```protobuf
// A Connector registered on a specific Connection.
// Note: balance and stake field widths are platform-specific. On Hiero, uint64 (tinybar)
// is sufficient. On EVM chains, the Solidity implementation uses uint256 (wei).
message ClprConnector {
  string chain_id = 1;                // \
  bytes service_address = 2;          // / Connection this Connector operates on
  bytes source_connector_address = 3; // address of the counterpart Connector on the source ledger
  bytes connector_contract = 4;       // address of the Connector's authorization contract
  bytes admin = 5;                    // admin authority (can top up, adjust, shut down)
  uint64 balance = 6;                 // available funds for message execution (native tokens)
  uint64 locked_stake = 7;            // stake locked against misbehavior (slashable)
}
```

## 1.5 Message Queue

```protobuf
message ClprMessageKey {
  string chain_id = 1;       // \
  bytes service_address = 2; // / compound key identifying the Connection
  uint64 message_id = 3;
}

message ClprMessageValue {
  ClprMessagePayload payload = 1;
  bytes running_hash_after_processing = 3;
}

// All three message classes share the same queue, running hash chain,
// and bundle transport. The oneof distinguishes them at processing time.
message ClprMessagePayload {
  oneof payload {
    ClprMessage message = 1;              // Data Message — application content
    ClprMessageReply message_reply = 2;   // Response Message — outcome of a Data Message
    ClprControlMessage control = 3;       // Control Message — protocol management
  }
}

message ClprMessage {
  bytes connector_id = 1;       // Connector that authorized this message
  bytes target_application = 2; // destination app address to dispatch to
  bytes sender = 3;             // source-chain address of the caller, stamped by CLPR Service at enqueue
  bytes message_data = 4;       // opaque application payload
}

enum ClprMessageReplyStatus {
  SUCCESS = 0;               // application processed the message successfully
  APPLICATION_ERROR = 1;     // application reverted — not the Connector's fault, no slash
  CONNECTOR_NOT_FOUND = 2;   // Connector missing on destination — slash source Connector
  CONNECTOR_UNDERFUNDED = 3; // Connector couldn't pay — slash source Connector
  REDACTED = 4;              // message was redacted before delivery
}

message ClprMessageReply {
  uint64 message_id = 1;                  // ID of the originating message this responds to
  ClprMessageReplyStatus status = 2;      // structured outcome — determines slash decision on source
  bytes message_reply_data = 3;           // opaque application response (empty on protocol errors)
}
```

## 1.6 Sync Protocol

```protobuf
// SyncPayload is the complete package exchanged in one direction of a sync.
// The proof_bytes field is opaque to the protocol — it is passed directly to
// the Connection's verifier contract, which returns verified queue metadata
// and messages.
message ClprSyncPayload {
  string chain_id = 1;                  // \
  bytes service_address = 2;            // / compound key identifying the source CLPR Service
  bytes proof_bytes = 3;               // opaque proof bytes for the verifier contract
  bytes endpoint_signature = 4;        // signature of the sending endpoint over this payload
  bytes endpoint_public_key = 5;       // public key of the sending endpoint (for attribution)
}

message ClprQueueMetadata {
  uint64 next_message_id = 1;
  uint64 acked_message_id = 2;
  bytes sent_running_hash = 3;
  uint64 received_message_id = 4;
  bytes received_running_hash = 5;
}
```

## 1.7 Misbehavior Reporting

```protobuf
enum ClprEvidenceType {
  DUPLICATE_BROADCAST = 0;  // same payload sent to multiple local endpoints
  EXCESS_FREQUENCY = 1;     // sync frequency exceeds advertised MaxSyncsPerSec
}

message ClprSignedPayload {
  ClprSyncPayload payload = 1;
  bytes endpoint_signature = 2;        // the offending endpoint's signature
  bytes endpoint_public_key = 3;       // the offending endpoint's public key
}

message ClprMisbehaviorReport {
  bytes offending_endpoint = 1;                // public key of the misbehaving remote endpoint
  ClprEvidenceType evidence_type = 2;
  repeated ClprSignedPayload evidence = 3;     // the signed payloads constituting the evidence
  string reporter_chain_id = 4;               // CAIP-2 identifier of the reporting ledger
}
```

---

# 2. gRPC Service Definitions

## 2.1 On-Ledger CLPR Service

The on-ledger CLPR Service API is implemented as a native service on Hiero and a smart contract on Ethereum.

```protobuf
service ClprService {
  // --- Admin Operations ---

  // Set this ledger's local configuration (ChainID, ApprovedVerifiers, Throttles).
  rpc setLedgerConfiguration(proto.Transaction) returns (proto.TransactionResponse);

  // Query: download this ledger's configuration.
  rpc getLedgerConfiguration(proto.Query) returns (proto.Response);

  // Sever (permanently close) a Connection. Returns deposits.
  rpc severConnection(proto.Transaction) returns (proto.TransactionResponse);

  // Pause a Connection (temporarily halt processing).
  rpc pauseConnection(proto.Transaction) returns (proto.TransactionResponse);

  // Resume a paused Connection.
  rpc resumeConnection(proto.Transaction) returns (proto.TransactionResponse);

  // --- Connection Management (Permissionless) ---

  // Register a new Connection. Requires a locally deployed verifier contract,
  // a ZK proof that the source ledger's ApprovedVerifiers endorses the verifier's
  // implementation fingerprint, a deposit, and seed endpoints.
  rpc registerConnection(proto.Transaction) returns (proto.TransactionResponse);

  // Update the verifier contract on an existing Connection. Requires a new ZK proof.
  rpc updateConnectionVerifier(proto.Transaction) returns (proto.TransactionResponse);

  // Submit a verified sync payload (bundle) received from a peer endpoint.
  // This is how endpoints deliver bundles to the local ledger for on-chain processing.
  rpc submitBundle(proto.Transaction) returns (proto.TransactionResponse);

  // Recovery: update a Connection's peer endpoint roster out-of-band.
  // Requires a state proof. Any user may call this.
  rpc recoverEndpointRoster(proto.Transaction) returns (proto.TransactionResponse);

  // --- Connector Management ---

  // Register a Connector on a Connection. Requires initial balance and stake.
  rpc registerConnector(proto.Transaction) returns (proto.TransactionResponse);

  // --- Messaging ---

  // Send a cross-ledger message via a Connector.
  rpc sendMessage(proto.Transaction) returns (proto.TransactionResponse);

  // --- Misbehavior ---

  // Submit a misbehavior report against a remote endpoint.
  rpc reportMisbehavior(proto.Transaction) returns (proto.TransactionResponse);
}
```

## 2.2 Transaction Body Messages

```protobuf
// Included in registerConnection transactions.
message ClprRegisterConnectionRequest {
  bytes verifier_contract = 1;                  // address of the locally deployed verifier contract
  bytes zk_proof = 2;                           // ZK proof of source ledger's ApprovedVerifiers endorsement
  uint64 deposit = 3;                           // deposit in native tokens (must meet clpr.connectionDeposit minimum)
  repeated ClprEndpoint seed_endpoints = 4;     // initial peer endpoints (at least one)
}

// Included in updateConnectionVerifier transactions.
message ClprUpdateConnectionVerifierRequest {
  string chain_id = 1;                          // \
  bytes service_address = 2;                    // / compound key identifying the Connection
  bytes verifier_contract = 3;                  // new verifier contract address
  bytes zk_proof = 4;                           // ZK proof that source ledger endorses new verifier's fingerprint
}

// Included in severConnection, pauseConnection, and resumeConnection transactions.
message ClprConnectionAdminRequest {
  string chain_id = 1;                          // \
  bytes service_address = 2;                    // / compound key identifying the Connection
}

// Included in submitBundle transactions.
message ClprSubmitBundleRequest {
  string chain_id = 1;                          // \
  bytes service_address = 2;                    // / compound key identifying the source Connection
  bytes proof_bytes = 3;                        // opaque proof bytes (passed to verifier contract)
  bytes remote_endpoint_signature = 4;          // signature of the remote endpoint that sent this payload
  bytes remote_endpoint_public_key = 5;         // public key of the remote endpoint
}

// Included in recoverEndpointRoster transactions.
message ClprRecoverEndpointRosterRequest {
  string chain_id = 1;                          // \
  bytes service_address = 2;                    // / compound key identifying the Connection
  repeated ClprEndpoint endpoints = 3;          // new/updated endpoints
  bytes zk_proof = 4;                           // state proof attesting to the endpoint data
}

// Included in registerConnector transactions.
message ClprRegisterConnectorRequest {
  string chain_id = 1;                          // \
  bytes service_address = 2;                    // / Connection this Connector will serve
  bytes source_connector_address = 3;           // address of the counterpart Connector on the source ledger
  bytes connector_contract = 4;                 // address of the Connector's authorization contract
  uint64 initial_balance = 5;                   // initial funds for message execution
  uint64 stake = 6;                             // stake to lock against misbehavior
}

// Included in sendMessage transactions.
message ClprSendMessageRequest {
  string chain_id = 1;                          // \
  bytes service_address = 2;                    // / compound key identifying the destination Connection
  bytes connector_id = 3;                       // Connector to authorize and pay for this message
  bytes target_application = 4;                 // destination app address
  bytes message_data = 5;                       // opaque application payload
}

// Included in reportMisbehavior transactions.
message ClprReportMisbehaviorRequest {
  string chain_id = 1;                          // \
  bytes service_address = 2;                    // / compound key identifying the Connection
  ClprMisbehaviorReport report = 3;
}
```

## 2.3 Endpoint-to-Endpoint Service

This gRPC service is exposed by every CLPR endpoint. It is the endpoint-to-endpoint protocol — separate from the
on-ledger CLPR Service API.

```protobuf
service ClprEndpointService {
  // Bidirectional sync: exchange pre-computed payloads with a peer endpoint.
  // Each side sends its SyncPayload; each side then submits the received payload
  // to its own ledger via ClprService.submitBundle().
  rpc sync(ClprSyncPayload) returns (ClprSyncPayload);
}
```

---

# 3. Verifier Contract Interface

Verifier contracts are deployed on the receiving ledger and implement two methods. CLPR delegates all cryptographic
verification to these contracts.

```
interface IClprVerifier {
  // Verify a configuration proof. Returns the verified configuration.
  // Used during: registerConnection, updateConnectionVerifier.
  function verifyConfig(bytes proof_bytes) returns (ClprLedgerConfiguration);

  // Verify a bundle proof. Returns verified queue metadata and messages.
  // Used during: submitBundle (on-chain bundle processing).
  function verifyBundle(bytes proof_bytes) returns (ClprQueueMetadata, ClprMessagePayload[]);
}
```

---

# 4. Connector Authorization Interface

When the CLPR Service processes a `sendMessage` call, it invokes the Connector's authorization contract to determine
whether the Connector approves the message. The Connector contract must implement:

```
interface IClprConnectorAuth {
  // Called by the CLPR Service when an application submits a message via this Connector.
  // Returns true if the Connector authorizes the message; false to reject.
  // The Connector may inspect any field and apply arbitrary authorization logic
  // (allow-lists, rate limits, payment requirements, etc.).
  function authorizeMessage(
    bytes sender,              // source-chain address of the caller
    bytes target_application,  // destination app address
    uint64 message_size,       // payload size in bytes
    bytes message_data         // opaque application payload
  ) returns (bool authorized);
}
```

---

# 5. State Transition Algorithms

## 5.1 Running Hash Computation

The running hash chain uses SHA-256. Each message's running hash is computed as:

```
running_hash = SHA-256(previous_running_hash || serialized_payload)
```

where `||` denotes concatenation and `serialized_payload` is the protobuf-serialized `ClprMessagePayload`.

**Initial value.** When a Connection is first created, both `sent_running_hash` and `received_running_hash` are
initialized to 32 bytes of zeros (`0x0000...0000`). Both sides of the Connection MUST agree on this initial value.

## 5.2 Bundle Verification Algorithm

When `submitBundle` is processed on-chain:

1. **Verifier call.** Pass `proof_bytes` to the Connection's verifier contract via `verifyBundle()`. If the verifier
   reverts or returns an error, reject the bundle. The submitting endpoint pays the transaction cost.

2. **Replay defense.** For every message returned by the verifier:
   - The first message's ID MUST equal `received_message_id + 1`.
   - Subsequent message IDs MUST be contiguous and ascending (each ID = previous ID + 1).
   - If any message ID violates these constraints, reject the entire bundle.

3. **Running hash verification.** Starting from the Connection's current `received_running_hash`, recompute the hash
   chain by applying `SHA-256(prev_hash || serialized_payload)` for each message sequentially. The final computed hash
   MUST equal the `sent_running_hash` from the verifier-returned `ClprQueueMetadata`. If they do not match, reject the
   bundle.

4. **Acknowledgement update.** Update `acked_message_id` from the verifier-returned
   `ClprQueueMetadata.received_message_id`. Delete acknowledged Response Messages from the outbound queue. Retain
   acknowledged Data Messages until their corresponding response arrives (see §5.4).

5. **Message dispatch.** For each message in order, dispatch by type:
   - **Control Message**: apply directly (update peer endpoint roster or store new config values). Advance
     `received_message_id` and `received_running_hash`. No response generated.
   - **Data Message**: resolve `connector_id` to local Connector via cross-chain mapping. Charge Connector, dispatch
     to target application, generate response. Advance `received_message_id` and `received_running_hash`.
   - **Response Message**: deliver to originating application. Check ordering per §5.4. Advance `received_message_id`
     and `received_running_hash`.

## 5.3 Message Enqueue Algorithm

When `sendMessage` is processed:

1. Look up the Connection by `(chain_id, service_address)`. Reject if not `ACTIVE`.
2. Look up the Connector by `connector_id` on the Connection. Reject if not found.
3. Call `IClprConnectorAuth.authorizeMessage()` on the Connector's authorization contract. Reject if not authorized.
4. Validate payload size against the destination's `maxMessagePayloadBytes`. Reject if exceeded.
5. Validate queue depth: `next_message_id - acked_message_id < maxQueueDepth`. Reject if full.
6. Construct `ClprMessage` with `connector_id`, `target_application`, `sender` (stamped from transaction caller),
   and `message_data`.
7. Compute `running_hash = SHA-256(sent_running_hash || serialized_payload)`.
8. Store the message in the queue keyed by `(chain_id, service_address, next_message_id)`.
9. Update Connection: `sent_running_hash = running_hash`, `next_message_id += 1`.

## 5.4 Response Ordering Verification

When a Response Message arrives in a bundle on the source ledger:

1. Walk the outbound queue of retained Data Messages (skipping Response Messages and Control Messages).
2. The incoming response's `message_id` MUST match the oldest unresponded Data Message's ID.
3. If it matches: deliver the response to the originating application, delete the matched Data Message.
4. If it does not match: the peer has violated the ordering guarantee. Set Connection status to `HALTED`. Halt
   acceptance of new outbound messages on this Connection.

## 5.5 Slashing Decision

When a Response Message is delivered back to the source ledger:

| `ClprMessageReplyStatus`   | Action                                              |
|----------------------------|-----------------------------------------------------|
| `SUCCESS`                  | No penalty. Deliver response to application.        |
| `APPLICATION_ERROR`        | No penalty. Deliver error to application.           |
| `CONNECTOR_NOT_FOUND`      | Slash source Connector. Reimburse endpoint.         |
| `CONNECTOR_UNDERFUNDED`    | Slash source Connector. Reimburse endpoint.         |
| `REDACTED`                 | No penalty. Deliver redaction notice to application. |

---

# 6. Configuration Parameters

| **Config Key**                   | **Default** | **Scope**      | **Description**                                                                                 |
|----------------------------------|-------------|----------------|-------------------------------------------------------------------------------------------------|
| `clpr.clprEnabled`               | `false`     | Global         | Master enable switch for the CLPR Service.                                                      |
| `clpr.connectionFrequency`       | `5000` ms   | Global         | How frequently endpoints initiate sync calls to peers.                                          |
| `clpr.publicizeNetworkAddresses` | `true`      | Global         | Whether to include service endpoint addresses in the Configuration.                             |
| `clpr.connectionDeposit`         | TBD         | Global         | Minimum deposit (in native tokens) required to register a new Connection.                       |
| `clpr.maxQueueDepth`             | TBD         | Per-Connection | Maximum unacknowledged messages in the outbound queue before new messages are rejected.          |
| `clpr.maxMessagePayloadBytes`    | TBD         | Per-Connection | Maximum payload size for a single message. Advertised in config; enforced by source and dest.   |
| `clpr.maxMessagesPerBundle`      | TBD         | Per-Connection | Maximum messages a single bundle may contain.                                                   |
| `clpr.maxGasPerMessage`          | TBD         | Per-Connection | Maximum gas (or ops/sec budget) allocated to processing a single message.                       |