# CLPR Protocol Specification

This document is the cross-platform technical specification for the CLPR (Cross Ledger Protocol). It defines the wire
formats, verification interfaces, state models, and algorithms required to implement CLPR on any ledger. Platform-
specific APIs (HAPI transactions, Solidity contract interfaces, Solana program instructions) are out of scope — this
document provides pseudo-API descriptions for those operations, from which platform-specific specifications will be
derived.

For the architectural rationale, design decisions, and conceptual overview, see the companion
[CLPR Design Document](clpr-service.md).

---

## Notation

- **MUST**, **SHOULD**, **MAY** follow [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) semantics.
- Protobuf definitions use `proto3` syntax.
- `bytes` fields are opaque unless otherwise specified. Byte lengths are noted where protocol-relevant.
- Pseudo-API sections use language-neutral function signatures. Platform-specific specs map these to native constructs
  (HAPI transactions, Solidity functions, Solana instructions, etc.).

---

# 1. Protobuf Definitions

All protobuf types in this section define the canonical wire format for cross-platform interoperability. Implementations
MUST serialize these types using standard protobuf encoding. Implementations MUST reject messages containing
unrecognized fields.

## 1.1 Ledger Identity and Configuration

```protobuf
syntax = "proto3";

// The static configuration for a ledger participating in CLPR.
message ClprLedgerConfiguration {
  // Protocol version. Implementations MUST reject configurations with
  // an unrecognized protocol_version.
  uint32 protocol_version = 1;

  // CAIP-2 chain identifier (e.g., "hedera:mainnet", "eip155:1").
  string chain_id = 2;

  // On-ledger address of this ledger's CLPR Service.
  // On EVM chains: the contract address. On Hiero: a well-known constant.
  // Included so that verifyConfig() can provide the service address to
  // the caller during connection registration.
  bytes service_address = 3;

  // Consensus timestamp of the transaction that last modified this configuration.
  // Monotonically increasing. Used to determine configuration freshness.
  Timestamp timestamp = 4;

  // Capacity limits advertised by this ledger.
  ClprThrottles throttles = 5;

  // Seed endpoints for initial peer discovery. Up to 10 entries.
  // These provide bootstrap connectivity for new endpoints that have no
  // prior knowledge of the peer network. Endpoint discovery beyond seed
  // nodes uses the off-chain gossip protocol (discoverEndpoints RPC).
  repeated ClprEndpoint seed_endpoints = 6;
}

// Consensus timestamp. Defined here rather than importing google.protobuf.Timestamp
// to avoid a dependency in constrained environments (e.g., on-chain verifiers).
// seconds MUST be non-negative. nanos MUST be in range [0, 999_999_999].
message Timestamp {
  int64 seconds = 1;
  int32 nanos = 2;
}

// Capacity limits published in the ledger's configuration.
// These are ledger-wide values advertised to all peers. Each Connection
// independently enforces them. Sending ledgers MUST respect these limits
// when constructing messages and bundles.
message ClprThrottles {
  // Hard cap: maximum messages in a single bundle.
  uint32 max_messages_per_bundle = 1;

  // Advisory: suggested maximum sync frequency (syncs per second).
  // Not enforced by the protocol; persistent violation is misbehavior
  // (see design doc §3.1.6).
  uint32 max_syncs_per_sec = 2;

  // Maximum payload size in bytes for a single message.
  // Enforced by both source (at enqueue) and destination (at bundle processing).
  uint32 max_message_payload_bytes = 3;

  // Maximum gas (or ops budget) allocated to processing a single message.
  uint64 max_gas_per_message = 4;

  // Maximum unacknowledged messages in the outbound queue per Connection.
  // When the queue is full, new messages are rejected until the peer catches up.
  uint32 max_queue_depth = 5;

  // Maximum total size of a serialized ClprSyncPayload (the gRPC message).
  // Endpoints MUST reject messages exceeding this limit.
  uint64 max_sync_bytes = 6;
}
```

## 1.2 Endpoint Identity

```protobuf
// An endpoint participating in CLPR syncs for a specific Connection.
// Used in seed endpoint lists (in ClprLedgerConfiguration) and in the
// off-chain gossip-based endpoint discovery protocol.
message ClprEndpoint {
  // Network address and port. Optional; omit for private networks that
  // only initiate outbound syncs.
  ServiceEndpoint service_endpoint = 1;

  // DER-encoded RSA public certificate used for mTLS between endpoints.
  // This key is used ONLY for transport-layer authentication (mTLS) during
  // gRPC sync calls. It is NOT used for endpoint_signature or any on-chain
  // verification.
  //
  // Minimum RSA key size: 2048 bits. RSA-3072 or higher is RECOMMENDED.
  bytes tls_certificate = 2;

  // ECDSA secp256k1 public key (33 bytes compressed, or 64 bytes
  // uncompressed x||y without 0x04 prefix) used for endpoint_signature
  // in ClprSyncPayload. This key is used for payload signing and
  // local misbehavior detection. ECDSA secp256k1 was chosen because
  // it can be verified cheaply on all target platforms (EVM ecrecover at
  // ~3K gas, Hiero native ECDSA support).
  //
  // On EVM platforms, this is typically the same key that signs
  // transactions (the endpoint operator's EOA key). On Hiero, nodes
  // MAY use their ECDSA secp256k1 key if available, or generate a
  // dedicated CLPR signing key.
  //
  // Future: ECDSA secp256k1 is not quantum-safe. The protocol anticipates
  // migrating endpoint_signature to a post-quantum scheme (e.g., Falcon,
  // CRYSTALS-Dilithium) once mainline ledgers add native support. This
  // migration will use the existing ConfigUpdate mechanism to rotate
  // endpoint keys across all Connections.
  bytes ecdsa_signing_key = 3;

  // On-ledger account associated with this endpoint node.
  // Length is platform-dependent (e.g., 20 bytes for EVM/Hiero).
  // MUST be unique within an endpoint set.
  bytes account_id = 4;
}

// Network address for an endpoint.
message ServiceEndpoint {
  // IPv4 or IPv6 numeric address (no DNS hostnames).
  string ip_address = 1;
  uint32 port = 2;
}
```

## 1.3 Control Messages

```protobuf
// Control message payload variants. These are protocol-level messages that
// manage Connection state rather than carrying application data.
// Control Messages do not involve Connectors, are not dispatched to
// applications, and do not generate responses.
//
// Forward compatibility: if a receiver encounters a ClprControlMessage with
// no oneof variant set (indicating an unknown control message type from a
// newer protocol version), it MUST reject the entire bundle. Silently
// skipping unknown control messages could cause state divergence.
message ClprControlMessage {
  oneof payload {
    ClprConfigUpdate config_update = 1;
  }
}

// Carries updated configuration parameters.
//
// Config propagation uses lazy enqueue: when the admin updates the local
// configuration, the CLPR Service stores it with its consensus_timestamp
// (O(1) operation). When a Connection next processes a bundle or enqueues a
// message, the service checks whether the Connection's last_config_timestamp
// is behind the current configuration's consensus_timestamp. If so, a
// ConfigUpdate Control Message is enqueued on that Connection at that point.
// This ensures:
//   - Config updates are O(1) for the admin regardless of Connection count.
//   - Dead or bogus Connections (with no traffic) never incur cost.
//   - Total ordering is preserved: the ConfigUpdate appears at a specific,
//     consensus-determined point in the message stream.
//
// The receiving side MUST verify that the enclosed configuration's timestamp
// is strictly greater than the stored peer_config_timestamp. Since control
// messages are ordered in the queue, this check is a consistency safeguard
// rather than a reordering defense.
message ClprConfigUpdate {
  ClprLedgerConfiguration configuration = 1;
}
```

## 1.4 Message Queue

```protobuf
// Composite key for a queued message.
message ClprMessageKey {
  // Connection ID identifying the Connection this message belongs to.
  // MUST be exactly 32 bytes.
  bytes connection_id = 1;

  // Monotonically increasing sequence number within the Connection.
  uint64 message_id = 2;
}

// Stored value for a queued message.
message ClprMessageValue {
  // The message payload (Data, Response, or Control).
  ClprMessagePayload payload = 1;

  // Cumulative SHA-256 hash after processing this message.
  // SHA-256(previous_running_hash || serialized_payload).
  // Retained even when the payload is redacted, enabling hash chain
  // verification to skip over redacted slots.
  bytes running_hash_after_processing = 2;
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

// Data Message: application-level content sent from one ledger to another.
message ClprMessage {
  // Source-chain address of the Connector that authorized this message.
  // On the destination ledger, this is resolved to the local Connector via
  // the cross-chain mapping (connection_id, connector_id) → local_connector.
  // See §2.2 for the mapping definition.
  bytes connector_id = 1;

  // Address of the destination application to dispatch to.
  bytes target_application = 2;

  // Source-chain address of the caller, stamped by the CLPR Service at enqueue time.
  bytes sender = 3;

  // Opaque application payload.
  bytes message_data = 4;
}

// Structured outcome of processing a Data Message on the destination ledger.
enum ClprMessageReplyStatus {
  // Unspecified — implementations MUST reject messages with this value.
  REPLY_STATUS_UNSPECIFIED = 0;

  // Application processed the message successfully.
  SUCCESS = 1;

  // Application reverted — not the Connector's fault, no slash.
  APPLICATION_ERROR = 2;

  // Connector missing on destination — slash source Connector.
  CONNECTOR_NOT_FOUND = 3;

  // Connector couldn't pay — slash source Connector.
  CONNECTOR_UNDERFUNDED = 4;

  // Message was redacted before delivery.
  REDACTED = 5;

  // Connection is closing — message was not processed. No slashing.
  CONNECTION_CLOSED = 6;
}

// Response Message: the outcome of a previously received Data Message.
// Every Data Message produces exactly one Response Message, in order.
message ClprMessageReply {
  // ID of the originating Data Message this responds to, from the
  // source ledger's outbound queue (the queue that sent the Data Message).
  uint64 message_id = 1;

  // Structured outcome — determines slash decision on the source ledger.
  ClprMessageReplyStatus status = 2;

  // Opaque application response (empty on protocol errors).
  bytes message_reply_data = 3;
}
```

## 1.5 Sync Protocol

The sync protocol defines the wire format for endpoint-to-endpoint communication. During a sync, two endpoints
exchange `ClprSyncPayload` messages — one in each direction. Each payload contains opaque proof bytes that the
receiving ledger's verifier contract will interpret.

```protobuf
// The complete package exchanged in one direction of a sync.
// The proof_bytes field is opaque to the protocol — it is passed directly to
// the Connection's verifier contract, which returns verified queue metadata
// and messages.
message ClprSyncPayload {
  // Connection ID identifying the Connection this sync belongs to.
  // MUST be exactly 32 bytes.
  bytes connection_id = 1;

  // Opaque proof bytes for the receiving ledger's verifier contract.
  // Contains whatever the verifier needs to extract and verify queue
  // metadata and messages (state roots, Merkle paths, ZK proofs,
  // TSS signatures, BLS aggregate signatures, etc.).
  bytes proof_bytes = 2;

  // ECDSA secp256k1 signature of the sending endpoint over
  // keccak256(connection_id || proof_bytes). This signature is NOT verified
  // during bundle processing — the verifier's proof_bytes provide the
  // cryptographic assurance. The endpoint_signature exists for attribution
  // and local misbehavior detection: it proves which endpoint produced a given
  // payload, enabling excess-frequency tracking.
  //
  // ECDSA secp256k1 is used because it can be verified cheaply on-chain
  // on all target platforms (EVM ecrecover ~3K gas, Hiero native support).
  // The signed data is the keccak256 hash of (connection_id || proof_bytes)
  // to produce a fixed 32-byte digest suitable for ECDSA signing.
  //
  // The verifier recovers the signer's public key from the signature
  // (via ecrecover or equivalent) and matches it against the registered
  // ecdsa_signing_key in the Connection's endpoint roster. The public key
  // is NOT included in the payload — it MUST be looked up from on-chain
  // state to prevent self-attestation attacks.
  bytes endpoint_signature = 3;
}

// Queue metadata extracted and verified by a verifier contract from proof_bytes.
// The verifier contract returns this as part of verifyBundle().
//
// The metadata describes the sender's queue state at the point covered by the
// proof. sent_running_hash and next_message_id correspond to the last message
// included in the bundle (not necessarily all messages the sender has ever
// enqueued — the bundle may be a prefix limited by max_messages_per_bundle).
//
// Field usage in bundle verification (§4.2):
//   - sent_running_hash:    compared against the recomputed hash chain (step 4)
//   - received_message_id:  used to update our acked_message_id (step 5)
//   - next_message_id:      informational; may be used for consistency checks
//   - received_running_hash: available for optional cross-validation against
//                            our own sent_running_hash at the acked position
message ClprQueueMetadata {
  // One past the ID of the last message included in the bundle.
  // The last message in the bundle has ID (next_message_id - 1).
  uint64 next_message_id = 1;

  // Cumulative hash through the last message in the bundle (message ID
  // next_message_id - 1). Used in running hash verification (§4.2 step 4).
  bytes sent_running_hash = 2;

  // Highest message ID the sender has received from us.
  // Used to update our acked_message_id (§4.2 step 5).
  uint64 received_message_id = 3;

  // Sender's cumulative hash of all messages received from us.
  // Available for optional cross-validation.
  bytes received_running_hash = 4;

  // The sender's current Connection status, propagated to the peer during sync.
  // Uses the same ClprConnectionStatus enum as the Connection itself — no
  // separate queue state type. During normal operation this is ACTIVE. When the
  // admin closes the Connection it becomes CLOSING, then DRAINED once the peer
  // has acknowledged all outbound messages. When both sides are DRAINED, the
  // Connection transitions to CLOSED (see §4.2).
  ClprConnectionStatus state = 5;
}
```

### gRPC Endpoint Service

Every CLPR endpoint exposes this gRPC service. It is the endpoint-to-endpoint protocol — separate from the on-ledger
CLPR Service API. Implementations MUST configure gRPC max message sizes to accommodate `max_sync_bytes`.

```protobuf
service ClprEndpointService {
  // Bidirectional sync: exchange pre-computed payloads with a peer endpoint.
  // The initiator pre-computes its payload before opening the connection.
  // The responder computes its payload upon receiving the incoming connection.
  // Each side then submits the received payload to its own ledger.
  rpc sync(ClprSyncPayload) returns (ClprSyncPayload);

  // Endpoint discovery: returns known peer endpoints for a Connection.
  // Used for gossip-based endpoint discovery. Endpoints SHOULD throttle
  // responses to protect against discovery abuse.
  rpc discoverEndpoints(DiscoverEndpointsRequest) returns (DiscoverEndpointsResponse);
}

message DiscoverEndpointsRequest {
  bytes connection_id = 1;
}

message DiscoverEndpointsResponse {
  repeated ClprEndpoint endpoints = 1;
}
```

## 1.6 Misbehavior Detection (Local Only)

Misbehavior detection and enforcement are **strictly local** — each ledger detects and responds to misbehavior
it observes on its own chain. There is no cross-ledger misbehavior reporting protocol.

Cross-ledger misbehavior reports were considered and rejected: a payload signature proves **authorship** but not
**delivery count**. Colluding endpoints on the receiving side can redistribute a single signed payload to fabricate
the appearance of excess frequency, making such reports unsafe to act on.

Each ledger locally detects:
- **Excess sync frequency** from a remote peer endpoint (shun the offending endpoint).
- **Duplicate submission** by a local endpoint (slash and remove the offending endpoint).

---

# 2. On-Ledger State Model

This section defines the logical state that every CLPR Service implementation MUST maintain, regardless of platform.
How this state is physically stored (Merkle tree, contract storage, Solana accounts) is platform-specific.

> **Note:** Connector and endpoint bond data never crosses the wire in cross-platform protobuf messages. The state
> model below describes on-ledger storage only. Balance and stake field widths are platform-specific (e.g., `uint64`
> on Hiero, `uint256` on EVM chains).

## 2.1 Connection

Each Connection is keyed by its **Connection ID** — exactly 32 bytes, computed as `keccak256(uncompressed_public_key)`
from the ECDSA_secp256k1 keypair generated at registration time. The `uncompressed_public_key` is the 64-byte
concatenation of the x and y coordinates (without the `0x04` prefix byte). The same Connection ID is used on both
ledgers.

```
Connection {
  // --- Identity ---
  connection_id          : bytes(32)   // primary key; keccak256(uncompressed_public_key)
  chain_id               : string      // CAIP-2 identifier of the peer chain
  service_address         : bytes       // on-ledger address of the peer's CLPR Service

  // --- Peer Configuration ---
  peer_config_timestamp   : Timestamp   // timestamp of the last known peer configuration

  // --- Verifier ---
  verifier_contract       : bytes       // address of the locally deployed verifier (immutable after registration)
  verifier_fingerprint    : bytes       // code hash of verifier_contract at registration time (informational)

  // --- Status ---
  status                 : enum { ACTIVE, PAUSED, CLOSING, DRAINED, CLOSED }
  // See §2.1.1 for status transition rules.

  // --- Config Propagation ---
  last_config_timestamp  : timestamp   // consensus_timestamp of the last config propagated on this Connection
  // When last_config_timestamp < current_config.consensus_timestamp, a
  // ConfigUpdate is lazily enqueued on the next interaction (see §1.3).

  // --- Outbound Queue Metadata ---
  next_message_id        : uint64      // next sequence number for outgoing messages
  acked_message_id       : uint64      // highest ID confirmed received by peer
  sent_running_hash      : bytes(32)   // cumulative SHA-256 of all enqueued outgoing messages

  // --- Inbound Queue Metadata ---
  received_message_id    : uint64      // highest ID received from peer
  received_running_hash  : bytes(32)   // cumulative SHA-256 of all received messages
}
```

**Initial values.** When a Connection is created, `next_message_id` = 1, `acked_message_id` = 0,
`received_message_id` = 0, and both running hashes are 32 bytes of zeros. No outbound Data Messages
exist, so no responses are expected. The first Data Message ever enqueued will have ID 1 (from
`next_message_id`), and the first Response Message received from the peer MUST reference that ID.

**Peer endpoint discovery** is handled off-chain via gossip between endpoints (see §5.4). Peer endpoint data is NOT
stored in on-ledger state. Seed endpoints for initial discovery are included in the peer's `ClprLedgerConfiguration`
and propagated via ConfigUpdate control messages.

**Message queue** entries are stored separately, keyed by `(connection_id, message_id)`.

### 2.1.1 Connection Status Transitions

| From      | To         | Trigger                                          | Notes                                                       |
|-----------|------------|--------------------------------------------------|-------------------------------------------------------------|
| (new)     | `ACTIVE`   | `registerConnection` succeeds                    | Initial state after registration.                           |
| `ACTIVE`  | `PAUSED`   | Response ordering violation detected (§4.5)      | Auto-triggered. No new outbound messages. Syncs continue.   |
| `ACTIVE`  | `CLOSING`  | Admin calls `closeConnection`, or peer's `ClprQueueMetadata.state` is `CLOSING`/`DRAINED` (§4.2) | Graceful drain. No new messages from applications.          |
| `PAUSED`  | `ACTIVE`   | Next bundle has correctly ordered responses       | Auto-resumes if admin hasn't closed.                        |
| `PAUSED`  | `CLOSING`  | Admin calls `closeConnection`                    | Connection drains once peer fixes ordering.                 |
| `CLOSING` | `DRAINED`  | Peer has acknowledged all outbound messages       | Queues fully drained on this side. Awaiting peer drain.     |
| `DRAINED` | `CLOSED`   | Both sides are `DRAINED`                         | Terminal state. All processing stops.                       |

**Status behavior for incoming bundles:**
- **`ACTIVE`**: Bundles accepted and processed normally.
- **`PAUSED`**: Inbound bundles containing out-of-order responses are rejected — nothing is dispatched,
  no acknowledgements updated, no hash chain advanced. New `sendMessage` calls are rejected. Existing queued
  messages remain and continue syncing. Auto-resumes to ACTIVE when a bundle arrives with correctly ordered
  responses (if admin hasn't closed). Admin may close a PAUSED Connection; the Connection transitions to
  CLOSING but cannot drain until the peer fixes the ordering.
- **`CLOSING`**: Inbound bundles are still accepted and processed. New Data Messages arriving (sent before the
  peer knew about the close) receive `CONNECTION_CLOSED` responses without dispatch. No slashing. Acks flow and
  queues drain. When the remote peer has acknowledged all outbound messages, the Connection transitions to `DRAINED`.
- **`DRAINED`**: This side's outbound queue is fully acknowledged. Inbound bundles are still accepted (the peer
  may still be draining). The `ClprQueueMetadata.state` carries `DRAINED` so the peer observes it during sync.
  Transitions to `CLOSED` automatically when both sides are `DRAINED`.
- **`CLOSED`**: All bundle submissions are rejected. No further processing occurs.

## 2.2 Connector

Each Connector is registered on a specific Connection and has a counterpart on the peer ledger.

```
Connector {
  connection_id            : bytes(32)   // Connection this Connector operates on
  source_connector_address : bytes       // address of the counterpart on the source ledger
  connector_contract       : bytes       // address of the Connector's authorization contract
  admin                    : bytes       // admin authority (can top up, adjust, shut down)
  balance                  : uint        // available funds for message execution (native tokens)
  locked_stake             : uint        // stake locked against misbehavior (slashable)
}
```

The CLPR Service maintains a cross-chain mapping index: `(connection_id, source_connector_address) → local_connector`
to resolve incoming messages to the local Connector that will pay for execution.

**Naming note:** When a Data Message arrives on the destination ledger, its `ClprMessage.connector_id` field contains
the source-chain address of the Connector that authorized it. This value is used as the `source_connector_address`
lookup key in the cross-chain mapping to find the local Connector.

**Bilateral requirement.** For a Connector to function end-to-end, it MUST be registered on **both** ledgers. The
source-side registration defines the authorization contract (which applications call to send messages). The
destination-side registration provides the `source_connector_address` mapping and the balance/stake for message
execution. If a Connector exists only on the source side, messages will be authorized and enqueued but will fail
with `CONNECTOR_NOT_FOUND` on the destination.

## 2.3 Endpoint Bond

On ledgers where endpoint registration is permissionless (e.g., Ethereum), each endpoint posts a bond against
misbehavior. The bond state is platform-specific — the CLPR Service is the custodian and the sole authority for
releasing or slashing it. Platform-specific specifications MUST define the bond structure, minimum amounts, and
slashing conditions.

---

# 3. Verification Interfaces

CLPR is proof-system-agnostic. All cryptographic verification is delegated to verifier contracts. The CLPR Service
never interprets proof bytes directly.

## 3.1 Verifier Contract Interface

Every verifier contract deployed for a Connection MUST implement three methods. The method signatures below are
language-neutral; platform-specific specs define the concrete ABI.

Verifier contracts MAY maintain internal mutable state (e.g., validator set tracking, sync committee rotation)
updated via separate administrative calls outside the CLPR interface. The three interface methods below SHOULD be
read-only with respect to CLPR Service state, but MAY read from the verifier's own mutable state.

```
interface IClprVerifier {

  // Verify a configuration proof. Returns the verified ledger configuration
  // (including chain_id, service_address, timestamp, throttles).
  //
  // Used during:
  //   - registerConnection to verify and extract the peer's configuration
  //
  // The proof_bytes contain whatever the source ledger's proof system produces
  // to attest to its configuration state (state roots, Merkle paths, etc.).
  //
  // MUST revert if verification fails.
  function verifyConfig(bytes proof_bytes) returns (ClprLedgerConfiguration)

  // Verify a bundle proof. Returns verified queue metadata and an ordered
  // array of message payloads.
  //
  // Used during:
  //   - submitBundle (on-chain bundle processing)
  //
  // The proof_bytes contain whatever the source ledger's proof system produces
  // to attest to the queue state and message contents.
  //
  // The returned ClprQueueMetadata.sent_running_hash MUST represent the
  // cumulative hash through the last message in the returned array (not
  // necessarily through all messages the sender has ever enqueued).
  //
  // MUST revert if verification fails.
  // SHOULD fail fast on obviously malformed inputs (wrong proof length, etc.)
  //   before performing expensive cryptographic operations.
  function verifyBundle(bytes proof_bytes) returns (ClprQueueMetadata, ClprMessagePayload[])
}
```

## 3.2 Connector Authorization Interface

When the CLPR Service processes a message send, it calls the Connector's authorization contract.

```
interface IClprConnectorAuth {

  // Called by the CLPR Service when an application submits a message via
  // this Connector. Returns true if the Connector authorizes the message.
  //
  // The Connector may inspect any field and apply arbitrary authorization
  // logic (allow-lists, rate limits, payment requirements, etc.).
  //
  // By returning true, the Connector is making a commitment: it asserts that
  // its counterpart on the destination ledger has sufficient funds to pay for
  // execution, and that it itself can cover the cost of handling the response.
  // This commitment is the contractual basis for slashing (§4.6).
  //
  // MUST NOT have side effects that modify CLPR Service state.
  //
  // The message_size parameter is provided as a gas optimization — Connectors
  // that only need to check size thresholds can avoid reading the full payload.
  function authorizeMessage(
    bytes sender,              // source-chain address of the caller
    bytes target_application,  // destination app address
    uint64 message_size,       // payload size in bytes (convenience; equals message_data.length)
    bytes message_data         // opaque application payload
  ) returns (bool authorized)
}
```

---

# 4. State Transition Algorithms

## 4.1 Running Hash Computation

The running hash chain uses **SHA-256**. Each message's running hash is computed as:

```
running_hash = SHA-256(previous_running_hash || serialized_payload)
```

where `||` denotes byte concatenation and `serialized_payload` is the canonical protobuf serialization of the
`ClprMessagePayload`.

**Initial value.** When a Connection is first created, both `sent_running_hash` and `received_running_hash` are
initialized to 32 bytes of zeros (`0x00 * 32`). Both sides of the Connection MUST agree on this initial value.

**Hash algorithm rationale.** SHA-256 is chosen for universal platform availability: EVM `sha256` precompile, Hiero
native, Solana `sol_sha256` syscall. Under Grover's algorithm, SHA-256 retains 128-bit preimage resistance, adequate
for the running hash chain's security requirements. The hash algorithm can be upgraded via a protocol version bump
(see `ClprLedgerConfiguration.protocol_version`) and connection renegotiation — the `running_hash` fields are opaque
`bytes`, so no wire format change is needed.

## 4.2 Bundle Verification Algorithm

When a bundle is submitted to the CLPR Service (post-consensus):

**Step 1 — Verifier call.** Pass `proof_bytes` to the Connection's verifier contract via `verifyBundle()`. If the
verifier reverts or returns an error, reject the entire bundle. The submitting endpoint pays the transaction cost.

**Step 2 — Bundle size check.** If the number of messages returned by the verifier exceeds `max_messages_per_bundle`,
reject the entire bundle. If any individual message payload exceeds `max_message_payload_bytes`, reject the entire
bundle. (Per the design document §3.2.5, an oversized payload is evidence of a dishonest source — the entire bundle
is tainted.)

**Step 3 — Replay defense.** For every message returned by the verifier:
- The first message's ID MUST equal `received_message_id + 1`.
- Subsequent message IDs MUST be contiguous and ascending (each ID = previous ID + 1).
- The last message's ID MUST equal `ClprQueueMetadata.next_message_id - 1` (consistency check against the
  verifier-returned metadata).
- If any constraint is violated, reject the entire bundle.

**Step 4 — Running hash verification.** Starting from the Connection's current `received_running_hash`, recompute
the hash chain by applying `SHA-256(prev_hash || serialized_payload)` for each message in the bundle sequentially.
The final computed hash MUST equal the `sent_running_hash` from the verifier-returned `ClprQueueMetadata`. (The
verifier returns `sent_running_hash` as the cumulative hash through the last message in the bundle, not necessarily
through all messages the sender has ever enqueued.) If they do not match, reject the entire bundle.

**Step 5 — Acknowledgement update.** Update `acked_message_id` from the verifier-returned
`ClprQueueMetadata.received_message_id`. Delete acknowledged Response Messages and Control Messages from the outbound
queue (neither generates a further response). Retain acknowledged Data Messages until their corresponding response
arrives (see §4.5).

**Step 5a — Queue state check.** If `ClprQueueMetadata.state` is `CLOSING` or `DRAINED` and the Connection is
`ACTIVE` or `PAUSED`, transition to `CLOSING`. Set the local `ClprQueueMetadata.state` to `CLOSING` so the
peer observes it on the next sync.

**Step 5b — Drain check.** If the Connection is `CLOSING` and the peer has acknowledged all outbound messages
(`acked_message_id >= next_message_id - 1`), transition the Connection to `DRAINED` (the `ClprQueueMetadata.state`
reflects the Connection's status). If both sides are `DRAINED` (the peer's state from the just-verified
metadata, and the local state just updated), transition the Connection to `CLOSED`.

**Step 5c — Lazy config propagation.** If the Connection's `last_config_timestamp` is less than the current
configuration's `consensus_timestamp`, enqueue a `ConfigUpdate` Control Message (see §1.3) on this Connection's
outbound queue and update `last_config_timestamp` to match. This ensures the ConfigUpdate appears at a deterministic,
consensus-determined point in the message stream — specifically, after the acknowledgement update and before
any new messages generated by this bundle's dispatch.

**Step 6 — Message dispatch.** For each message in order, dispatch by type:

| Message Type     | Processing                                                                                                                                                                                                                                                            |
|------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Control Message  | Apply directly (update peer endpoint roster or store new config values). Advance `received_message_id` and `received_running_hash`. No response generated.                                                                                                            |
| Data Message     | If Connection is `CLOSING` or `DRAINED`: generate `CONNECTION_CLOSED` response without dispatch (no Connector charge, no slashing). Otherwise: resolve `connector_id` to local Connector via cross-chain mapping. Charge Connector (execution cost plus margin; margin reimburses the submitting endpoint). Dispatch to target application. Generate Response Message. Advance queue metadata. |
| Response Message | Deliver to originating application. Verify ordering per §4.5. Advance `received_message_id` and `received_running_hash`.                                                                                                                                             |

A failure on one message does NOT stop processing of remaining messages in the bundle. Each message is processed
independently — if one Connector is underfunded, a `CONNECTOR_UNDERFUNDED` response is generated for that message
while other messages (including from the same Connector) continue processing normally.

## 4.3 Message Enqueue Algorithm

When a message send is processed:

1. Look up the Connection by `connection_id`. Reject if status is not `ACTIVE`.
1a. **Lazy config propagation.** If the Connection's `last_config_timestamp` is less than the current
   configuration's `consensus_timestamp`, enqueue a `ConfigUpdate` Control Message (see §1.3) on this
   Connection's outbound queue before the application's message and update `last_config_timestamp` to match.
   This ensures config changes are
   propagated before new Data Messages.
2. Look up the Connector by `connector_id` on the Connection. Reject if not found.
3. Call `IClprConnectorAuth.authorizeMessage()` on the Connector's authorization contract. Reject if not authorized.
4. Validate payload size against the destination's `max_message_payload_bytes`. Reject if exceeded.
5. Validate queue depth: `next_message_id - acked_message_id < max_queue_depth`. Reject if full.
6. Construct `ClprMessage` with `connector_id`, `target_application`, `sender` (stamped from transaction caller),
   and `message_data`.
7. Compute `running_hash = SHA-256(sent_running_hash || serialized_payload)`.
8. Store the message in the queue keyed by `(connection_id, next_message_id)`.
9. Update Connection: `sent_running_hash = running_hash`, `next_message_id += 1`.

## 4.4 Message Lifecycle and Redaction

**Response Messages** in the outbound queue are deleted when the peer acknowledges them (ack covers their ID).

**Control Messages** in the outbound queue are also deleted on ack — they do not generate responses and require no
further action once acknowledged.

**Data Messages** are retained after acknowledgement because they serve as the ordering reference for response
verification (§4.5). They are deleted only when their corresponding response has been received and matched.

**Redaction.** A message still in the queue and not yet delivered may be redacted by the CLPR Service admin (e.g.,
to remove illegal or inappropriate content). See `redactMessage` in §6.4. When redacted:
- The payload is removed, but the message slot and its `running_hash_after_processing` field (from `ClprMessageValue`)
  are retained.
- Running hash verification skips redacted slots by using the stored `running_hash_after_processing` directly rather
  than recomputing from the (now absent) payload.
- The destination receives the message slot with an empty `ClprMessagePayload` (all fields unset). The receiving
  ledger recognizes this as a redacted message and generates a deterministic `REDACTED` response without attempting
  dispatch.

## 4.5 Response Ordering Verification

When a Response Message arrives in a bundle on the source ledger:

1. Walk the outbound queue of retained Data Messages (skipping Response Messages and Control Messages).
2. The incoming response's `message_id` MUST match the oldest unresponded Data Message's ID.
3. **Match:** deliver the response to the originating application, delete the matched Data Message.
4. **Mismatch:** the peer has violated the ordering guarantee. Set Connection status to `PAUSED`. Reject new
   `sendMessage` calls on this Connection.

**Initial state.** When a Connection is first created, no outbound Data Messages exist, so no responses
are expected and the walk in step 2 finds nothing. The first Response Message received MUST match the
first Data Message ever sent on this Connection (ID 1, since `next_message_id` is initialized to 1).
Response ordering is tracked implicitly by walking the retained outbound Data Messages — there is no
separate counter.

**PAUSED recovery.** A PAUSED Connection auto-resumes when the next inbound bundle contains correctly ordered
responses (if the admin hasn't closed it). The ordering violation indicates a peer-side bug in response
generation — the peer must fix it (which may require a contract upgrade on platforms like Ethereum). While
PAUSED, existing queued messages remain and continue syncing, but inbound bundles with out-of-order responses
are rejected outright — nothing is dispatched, acknowledged, or advanced. New `sendMessage` calls are also
blocked. The admin may close a PAUSED Connection (`closeConnection` transitions it to CLOSING). While
CLOSING, bundles with out-of-order responses are still rejected; once the peer fixes the ordering, bundles
are processed with `CONNECTION_CLOSED` responses and queues drain normally through DRAINED → CLOSED.

**Distinction from bad inbound bundles.** If a peer sends bundles that fail verification (bad hash chain, replay,
oversized payloads), the CLPR Service simply rejects them — no pause, no state change. The Connection remains
ACTIVE and will accept valid bundles as soon as the peer fixes the issue. PAUSED is reserved exclusively for
response ordering violations, which indicate corruption in the peer's outbound queue state.

## 4.6 Slashing Decision

Slashing is two-sided — both the destination and source ledger enforce penalties independently, so that the endpoint
that did the work on each side is compensated on its own ledger.

When a Data Message is processed on the **destination** ledger:

| Outcome                 | Destination-Side Action                                                                               |
|-------------------------|-------------------------------------------------------------------------------------------------------|
| Connector found, funded | Charge Connector (execution cost + margin). Margin reimburses submitting endpoint.                    |
| `CONNECTOR_NOT_FOUND`   | No Connector to slash. Submitting endpoint absorbs execution cost. Failure response enqueued.         |
| `CONNECTOR_UNDERFUNDED` | Slash destination Connector's `locked_stake`. Reimburse submitting endpoint. Failure response enqueued. |

When the failure Response Message arrives back on the **source** ledger:

| `ClprMessageReplyStatus`   | Source-Side Action                                              |
|----------------------------|-----------------------------------------------------------------|
| `SUCCESS`                  | No penalty. Deliver response to application.                    |
| `APPLICATION_ERROR`        | No penalty. Deliver error to application.                       |
| `CONNECTOR_NOT_FOUND`      | Slash source Connector's `locked_stake`. Reimburse source-side submitting endpoint.  |
| `CONNECTOR_UNDERFUNDED`    | Slash source Connector's `locked_stake`. Reimburse source-side submitting endpoint.  |
| `REDACTED`                 | No penalty. Deliver redaction notice to application.            |
| `CONNECTION_CLOSED`        | No penalty. Connection was closing; message was not processed.  |

Penalties escalate. A single failure results in a fine. Repeated failures MAY result in the Connector being banned
from the Connection and its remaining stake forfeited. Platform-specific specifications MUST define the slashing
schedule (fine amounts, escalation thresholds, ban conditions).

> **Stake-to-exposure invariant.** Each side's Connector bond MUST be sufficient to cover the worst-case endpoint
> losses on that ledger. If a bond is too small, a malicious actor can create a Connector with minimal stake,
> authorize a burst of messages, and drain endpoints of more execution cost than the slash can reimburse. Platform
> specs MUST define minimum Connector bond requirements.

---

# 5. Connection Lifecycle

## 5.1 Connection Registration

Connection creation is **permissionless**. The registrant:

1. Generates an **ECDSA_secp256k1 keypair** and computes the Connection ID as `keccak256(uncompressed_public_key)`
   (64-byte x||y coordinates, without the `0x04` prefix).
2. Deploys a **verifier contract** on the local ledger.
3. Obtains a **configuration proof** from the peer ledger through off-chain means.
4. Calls `registerConnection` on the local CLPR Service with the verifier address and the peer's config proof.

The CLPR Service:

1. Verifies the **ECDSA signature** over the registration data (proves caller controls the Connection ID).
   The exact signed payload format is defined in platform-specific specifications.
2. Calls `verifyConfig(config_proof_bytes)` on the specified verifier contract to obtain the peer's verified
   `ClprLedgerConfiguration` (chain_id, service_address, throttles, timestamp).
3. Creates the Connection: stores the Connection ID, verified peer configuration, verifier contract address
   and code fingerprint. The verifier is immutable — it cannot be changed after registration.

This process is repeated independently on the peer ledger for bidirectional communication. No pairing ceremony is
needed — the deterministic Connection ID from the shared keypair links the two registrations.

**Verifier immutability.** The verifier contract is fixed at registration time and cannot be changed. If the source
ledger upgrades its proof format, a new Connection must be registered with a new verifier. This simplifies the trust
model: applications can evaluate a Connection's verifier once and know it will not change.

## 5.4 Endpoint Discovery

Peer endpoint discovery is handled entirely off-chain via gossip between endpoints. Each ledger's configuration
includes up to 10 **seed endpoints** that provide initial connectivity for new endpoints. Once connected to any
peer, an endpoint uses the `discoverEndpoints` RPC (§1.5) to learn about additional peers.

Peer endpoint data is NOT stored in on-ledger state. Each endpoint maintains its own local, ephemeral view of
known peers. This design avoids the O(N × M) gas cost of propagating endpoint changes across N endpoints and
M connections via on-chain state updates.

**Endpoint registration** is local to each ledger. On permissioned ledgers (Hiero), endpoints are derived
automatically from the consensus roster. On permissionless ledgers (Ethereum), endpoints register on-chain with
a bond for Sybil resistance. In both cases, registration is on the endpoint's own ledger — not on the peer's.

**Per-endpoint throttle.** The CLPR Service enforces a per-endpoint submission rate limit on `submitBundle`. Each
registered endpoint gets `1 / num_registered_endpoints` of the total bundle submission capacity. Excess submissions
are rejected and the submitter is charged. This incentivizes endpoints to avoid duplicate submissions (e.g., by
introducing a small random delay before submitting, as described in the design doc).

**Reciprocity-based peer selection.** Endpoints SHOULD prefer to sync with peers that reciprocate by providing
messages in return. An endpoint that only requests messages without providing any will be deprioritized by its
peers. This naturally converges on efficient pairings where both sides do useful work.

## 5.5 Administrative Operations

The CLPR Service admin can:

- **Close** a Connection — initiate graceful shutdown. Valid from `ACTIVE` or `PAUSED` status. The Connection
  transitions to `CLOSING` (the `ClprQueueMetadata.state` reflects this and is propagated to the peer on next
  sync). In-flight messages drain; new Data Messages arriving during drain receive `CONNECTION_CLOSED` responses
  without dispatch. When the peer has acknowledged all outbound messages, the Connection transitions to
  `DRAINED`. The Connection transitions to `CLOSED` automatically when both sides are `DRAINED`. If closed
  from PAUSED, the Connection cannot drain until the peer fixes the response ordering.
- **Update the local configuration** — change throttles. Changes are propagated to peers via ConfigUpdate
  Control Messages, lazily enqueued on each Connection at its next interaction (see §1.3).
- **Redact** a message — mark a queued outbound message as redacted (see §6.4).

---

# 6. Pseudo-API Reference

The following pseudo-APIs describe the operations that every CLPR Service implementation MUST support. Platform-
specific specifications map these to native constructs. Parameters marked `[auth]` require the caller to authenticate
(platform-specific mechanism — transaction signature, `msg.sender`, etc.).

## 6.1 Configuration Management

```
// Set or update this ledger's local CLPR configuration.
// Authority: CLPR Service admin only.
// Stores the new configuration. The configuration's consensus_timestamp
// naturally serves as the version marker. ConfigUpdate Control Messages
// are lazily enqueued on each Connection the next time it processes a
// bundle or enqueues a message (see ClprConfigUpdate in §1.3).
setLedgerConfiguration(
  [auth] admin,
  configuration: ClprLedgerConfiguration
) → success | error

// Query this ledger's current CLPR configuration.
// Authority: any caller.
getLedgerConfiguration() → ClprLedgerConfiguration
```

## 6.2 Connection Management

```
// Register a new Connection to a peer ledger.
// Authority: any caller (permissionless).
// Preconditions: verifier_contract is deployed on the local ledger.
// The verifier is immutable — it cannot be changed after registration.
registerConnection(
  [auth] caller,
  connection_id: bytes(32),       // keccak256(uncompressed_public_key)
  ecdsa_public_key: bytes,        // Uncompressed ECDSA_secp256k1 public key (64 bytes, x||y
                                  // without 0x04 prefix). Used to verify ecdsa_signature and to
                                  // derive the connection_id (keccak256(ecdsa_public_key)).
                                  // On EVM platforms, the public key can be recovered from the
                                  // signature via ecrecover; non-EVM platforms MUST accept the
                                  // explicit key and verify against it.
  ecdsa_signature: bytes,         // ECDSA_secp256k1 signature proving caller controls the ID;
                                  // SHOULD sign over at least (connection_id, verifier_contract)
                                  // to prevent cross-Connection replay. Exact payload format is
                                  // defined in platform-specific specifications.
  verifier_contract: bytes,       // address of locally deployed verifier (immutable)
  config_proof_bytes: bytes,      // opaque proof from peer ledger; passed to verifyConfig()
                                  // to obtain verified peer configuration (chain_id, service_address,
                                  // throttles, timestamp) at registration time
) → success | error

// Close a Connection (graceful drain, then terminal).
// Authority: CLPR Service admin only.
// Transitions ACTIVE or PAUSED → CLOSING. The `ClprQueueMetadata.state` reflects
// the Connection status and is propagated to the peer on next sync. In-flight
// Data Messages receive CONNECTION_CLOSED responses without dispatch. When the
// peer has acked all outbound messages, the Connection transitions to DRAINED.
// Transitions to CLOSED automatically when both sides are DRAINED.
// If closed from PAUSED, the Connection cannot drain until the peer fixes the
// response ordering; once fixed, bundles process with CONNECTION_CLOSED responses
// and queues drain normally.
// MUST reject if Connection is CLOSING, DRAINED, or CLOSED.
closeConnection(
  [auth] admin,
  connection_id: bytes(32)
) → success | error

// Query a Connection's current outbound queue depth.
// Authority: any caller.
getQueueDepth(
  connection_id: bytes(32)
) → { depth: uint64, max: uint32 } | error
```

## 6.3 Connector Management

```
// Register a Connector on a Connection.
// Authority: any caller (permissionless, but requires initial funds).
// Platform specs MUST define minimum stake requirements.
// The caller becomes the Connector admin, authorized for topUpConnector,
// withdrawConnectorBalance, and deregisterConnector operations.
registerConnector(
  [auth] caller,
  connection_id: bytes(32),
  source_connector_address: bytes,  // address of counterpart on source ledger
  connector_contract: bytes,        // address of the Connector's authorization contract
  initial_balance: uint,            // funds for message execution (native tokens)
  stake: uint                       // stake locked against misbehavior
) → success | error

// Add funds to a Connector's balance.
// Authority: Connector admin only.
topUpConnector(
  [auth] admin,
  connection_id: bytes(32),
  source_connector_address: bytes,
  amount: uint
) → success | error

// Withdraw surplus funds from a Connector's balance (not locked stake).
// Authority: Connector admin only.
withdrawConnectorBalance(
  [auth] admin,
  connection_id: bytes(32),
  source_connector_address: bytes,
  amount: uint
) → success | error

// Deregister a Connector and return remaining funds and stake.
// Authority: Connector admin only.
// MUST NOT deregister if the Connector has unresolved in-flight messages.
deregisterConnector(
  [auth] admin,
  connection_id: bytes(32),
  source_connector_address: bytes
) → success | error

// Query a Connector's current state.
// Authority: any caller.
getConnector(
  connection_id: bytes(32),
  source_connector_address: bytes
) → Connector | error
```

## 6.4 Messaging

```
// Send a cross-ledger message via a Connector.
// Authority: any caller.
// Returns the assigned message_id on success, which the caller can use
// to correlate with the eventual Response Message.
sendMessage(
  [auth] caller,
  connection_id: bytes(32),
  connector_id: bytes,            // address of the local Connector's authorization contract on this (source) ledger
  target_application: bytes,      // destination app address
  message_data: bytes             // opaque application payload
) → { message_id: uint64 } | error

// Submit a bundle received from a peer endpoint for on-chain processing.
// Authority: any caller (typically an endpoint node).
// The connection_id identifies which Connection to process against.
// The proof_bytes and remote_endpoint_signature correspond to the fields
// of a ClprSyncPayload received during sync. The signer's identity is
// recovered from the ECDSA signature (via ecrecover or equivalent) and
// matched against registered endpoint keys in the Connection's roster.
submitBundle(
  [auth] caller,
  connection_id: bytes(32),
  proof_bytes: bytes,                // ClprSyncPayload.proof_bytes
  remote_endpoint_signature: bytes   // ClprSyncPayload.endpoint_signature
) → success | error
```

```
// Redact a message from the outbound queue before it has been delivered.
// Authority: CLPR Service admin only.
// The message payload is removed but the queue slot and running hash are preserved.
// MUST fail if the message has already been acknowledged by the peer.
redactMessage(
  [auth] admin,
  connection_id: bytes(32),
  message_id: uint64
) → success | error
```

## 6.5 Endpoint Management

On ledgers where endpoint registration is permissionless (e.g., Ethereum, Solana), the CLPR Service MUST expose
registration and deregistration operations. On ledgers where endpoints are managed by the platform (e.g., Hiero,
where consensus nodes are the endpoints), these operations are not needed.

```
// Register as an endpoint for a Connection.
// Authority: any caller (permissionless; bond required).
// Platform specs MUST define minimum bond amounts and slashing conditions.
registerEndpoint(
  [auth] caller,
  connection_id: bytes(32),
  endpoint: ClprEndpoint,       // the endpoint to register (account_id MUST match caller)
  bond: uint                    // bond posted against misbehavior.
                                // Delivery mechanism is platform-specific: on EVM chains,
                                // this maps to msg.value (the function is payable); on Hiero,
                                // this is transferred via the transaction's crypto transfer list.
) → success | error

// Deregister an endpoint from a Connection and return the bond.
// Authority: the endpoint's account only.
// MUST NOT deregister if the endpoint has in-flight sync submissions.
deregisterEndpoint(
  [auth] caller,
  connection_id: bytes(32)
) → success | error
```

### Application Delivery

When the CLPR Service dispatches a Data Message or delivers a Response Message, it invokes the target application.
The mechanism for this invocation is **platform-specific**: on EVM chains, it is a contract call; on Hiero, it is
a system-level dispatch; on Solana, a CPI. Platform-specific specifications MUST define:

1. The **callback interface** that applications implement to receive messages and responses.
2. The **gas/compute budget** allocated to the application callback.
3. The **return value convention** for indicating success vs. application-level failure.
4. Whether the application callback is **synchronous** (completes within the bundle transaction) or
   **asynchronous** (queued for later execution).
5. How **responses are delivered** to originating senders that are externally owned accounts (not contracts).
   EOA senders cannot receive callbacks; responses SHOULD be recorded in the transaction receipt/record
   but no callback is made.

## 6.6 Misbehavior Detection

Misbehavior detection and enforcement are strictly local (see section 1.6). Each ledger independently
tracks inbound sync frequency per remote endpoint and detects duplicate submissions by local endpoints.
No cross-ledger misbehavior reports are exchanged.

---

# 7. Configuration Parameters

| **Parameter**               | **Default** | **Scope**                  | **Description**                                                                                          |
|-----------------------------|-------------|----------------------------|----------------------------------------------------------------------------------------------------------|
| `clprEnabled`               | `false`     | Global                     | Master enable switch. When disabled, all pseudo-API calls MUST return an error. Checked dynamically at call time (not at startup), enabling runtime kill-switch. |
| `maxBundlesPerSec`          | TBD         | Global                     | Total bundle submission capacity per second. Divided equally among registered endpoints. Each endpoint gets `maxBundlesPerSec / numEndpoints` capacity. Excess submissions rejected and charged. |
| `maxQueueDepth`             | TBD         | Per-Ledger (per-Connection) | Maximum unacknowledged messages in the outbound queue before new messages are rejected.                   |
| `maxMessagePayloadBytes`    | TBD         | Per-Ledger (per-Connection) | Maximum payload size for a single message. Enforced by source (enqueue) and destination (bundle).        |
| `maxMessagesPerBundle`      | TBD         | Per-Ledger (per-Connection) | Maximum messages a single bundle may contain. Platform specs MUST set this to ensure bundles fit within the platform's transaction size and execution budget limits. |
| `maxGasPerMessage`          | TBD         | Per-Ledger (per-Connection) | Maximum gas (or ops budget) allocated to processing a single message.                                    |
| `maxSyncsPerSec`            | TBD         | Per-Ledger (per-Connection) | Advisory maximum sync frequency. Endpoints derive their outbound sync interval from this value and the number of local endpoints: `sync_interval_ms = 1000 * num_local_endpoints / max_syncs_per_sec`. Persistent violation leads to shunning by the receiving ledger. |
| `maxSyncBytes`              | TBD         | Per-Ledger (per-Connection) | Maximum total size of a serialized `ClprSyncPayload`. Endpoints MUST reject messages exceeding this limit. |

**Scope note.** Parameters marked "Per-Ledger (per-Connection)" are published in the ledger-wide configuration
(`ClprThrottles`) and advertised to all peers. Each Connection independently enforces them against incoming data.

---

# 8. Security Considerations

## 8.1 Protocol Strictness

All limits are published in the configuration so that both sides know the rules. A peer that exceeds any published
limit is committing a measurable, attributable violation. The receiving side MUST reject the offending submission and
MAY count repeated violations toward local misbehavior thresholds (shunning, disconnection).

Implementations MUST NOT silently ignore unrecognized fields, unknown message types, or malformed metadata.
Unrecognized data MUST be rejected outright.

## 8.2 Trust Model

Using a CLPR Connection means trusting:
1. The **verifier contract** on the Connection — that it correctly validates proofs from the peer ledger.
2. The **peer ledger's consensus mechanism** and proof system.
3. The **local and remote CLPR Service** implementations — that they faithfully execute the protocol.

The trust chain is: the connection registrant chose the verifier, the verifier checks peer proofs, and
the peer's consensus/proof system is assumed trustworthy. Since connection creation is permissionless and there is
no verifier whitelist, applications must independently evaluate the verifier contract before using a Connection.
A malicious verifier could return fabricated data, compromising the Connection.

## 8.3 Reorg Risk

For ledgers without instant finality (e.g., Ethereum), the commitment level at which the verifier accepts proofs
determines the reorg risk. A verifier at `latest` commitment is vulnerable to chain reorganizations. For high-value
operations, only verifiers enforcing `finalized` commitment (or equivalent) should be used. Ledgers with ABFT
finality do not have this concern.

## 8.4 Endpoint Sybil Resistance

On ledgers where endpoint registration is open, the endpoint bond must be large enough to make Sybil attacks
economically infeasible. Peer endpoint selection during sync should incorporate randomization. Endpoint reputation
scoring helps honest endpoints preferentially select reliable peers.

## 8.5 Reentrancy

When dispatching messages to target applications, the CLPR Service hands execution control to arbitrary external
code. Implementations MUST use reentrancy guards on all state-modifying functions and MUST follow the
checks-effects-interactions pattern: update all Connection state before dispatching to the application.

## 8.6 Untrusted Payloads

Both messages and responses carry opaque application-layer payloads. A malicious actor could craft payloads
designed to exploit the receiving application. Applications MUST treat all cross-ledger payloads as untrusted
input. CLPR guarantees authenticity and integrity, not semantic correctness.

## 8.7 No Confidentiality

CLPR provides integrity and authenticity but NOT confidentiality. Payloads are stored on-chain in plaintext.
Applications requiring confidentiality MUST encrypt payloads at the application layer.

## 8.8 Upgradeable Verifier Contracts

Although the verifier address is immutable after connection creation, the verifier contract itself MAY be deployed
behind an upgradeable proxy (e.g., EIP-1967). If the verifier is a proxy, the underlying implementation can change
without CLPR's knowledge. The protocol is agnostic to this — upgradeable verifiers are perfectly valid.
Applications evaluating a Connection should verify whether the verifier is a proxy and, if so, who controls the
upgrade authority. This is an application-level trust decision, not a protocol constraint.

## 8.9 Upgradeable CLPR Service Contracts

On platforms where the CLPR Service is an upgradeable contract, endpoint proof construction depends on the
contract's storage layout. Contract upgrades that change the storage layout MUST be coordinated with endpoint
operators and verifier contract updates. Uncoordinated upgrades will break proof construction and halt all
syncs until endpoints are updated.

## 8.10 Endpoint Pre-Funding

Endpoint operators MUST pre-fund their transaction signing accounts with sufficient native tokens to cover
`submitBundle` transaction fees. Connector margin reimbursement occurs post-consensus and cannot cover the initial
transaction cost. Endpoints that run out of funds cannot submit bundles and effectively go offline.

## 8.11 Queue Monopolization

A single Connector could authorize a large volume of messages to fill the queue to `max_queue_depth`, blocking all
other Connectors on the Connection. This is a denial-of-service vector. Platform-specific specifications SHOULD
define mitigations such as per-Connector queue quotas, escrow requirements at send time, or priority pricing as the
queue fills.

---

# 9. Recovery Scenarios

| #   | Scenario                                            | Sync Channel         | Recovery Path                                                                                                          | Status                        |
|-----|-----------------------------------------------------|----------------------|------------------------------------------------------------------------------------------------------------------------|-------------------------------|
| R1  | Endpoints rotated during partition                  | Broken               | Endpoints discover new peers via gossip (`discoverEndpoints` RPC) once any connectivity is restored. Seed endpoints in config provide fallback bootstrap. | Works                         |
| R2  | Source ledger upgrades proof format                 | Breaks when source switches | Register new Connection with new verifier. Applications migrate. Admin closes old Connection.                    | Works (requires new Connection) |
| R3  | Endpoints rotated (proof format unchanged)          | Broken               | Endpoint discovery (R1), then ConfigUpdate flows normally.                                                              | Works                         |
| R4  | Endpoints rotated AND proof format changed           | Broken               | Register new Connection with new verifier. Endpoint discovery (R1) on new Connection. Admin closes old Connection.     | Works (requires new Connection) |
| R5  | Verifier compromised or broken                      | Suspect              | Admin closes Connection (transitions through CLOSING → DRAINED → CLOSED). Since verifier is immutable, register new Connection with correct verifier. | Works (data loss on close)    |
| R6  | Queue state permanently corrupted on peer           | Working              | Connection pauses (§4.5). Auto-resumes if peer fixes ordering. Admin may close a PAUSED Connection (`closeConnection` transitions to CLOSING); the Connection drains once the peer fixes the ordering. If the peer never fixes it, the Connection stays PAUSED (or CLOSING, if the admin closed it) indefinitely. | Works (PAUSED until peer recovers; admin can close to prepare for drain) |
| R7  | Network partition (endpoints unchanged)             | Temporarily broken   | Syncs resume automatically. Monotonic IDs and running hash verify integrity.                                           | Works                         |
| R8  | Peer ledger down entirely                           | Broken               | Messages queue up to `max_queue_depth`, then backpressure. Syncs resume when peer returns.                             | Works                         |
| R9  | Both sides' endpoints change simultaneously         | Broken on both sides | Endpoints on both sides discover new peers via gossip once any connectivity is restored.                               | Works                         |

---

# 10. Open Questions

1. **Indefinitely PAUSED Connection (R6).** If a peer ledger's queue state is permanently corrupted and it can
   never produce correctly ordered responses, the Connection stays PAUSED indefinitely (§4.5). The admin may
   close it (`closeConnection` transitions to CLOSING), but the Connection cannot drain until the peer fixes
   the ordering. If the peer never fixes it, the Connection stays in CLOSING indefinitely — no
   `CONNECTION_CLOSED` responses are generated while bundles are being rejected for out-of-order responses.
   Once the peer fixes the ordering, bundles process with `CONNECTION_CLOSED` responses and queues drain
   normally. Applications on a permanently broken peer may still need out-of-band reconciliation.
