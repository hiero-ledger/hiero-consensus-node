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
// Does not include the endpoint roster, which is managed separately
// via control messages (see design doc §3.1.2).
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

  // Map from verifier type label to implementation fingerprint (code hash).
  // The label is a human-readable identifier for a platform-specific verifier
  // implementation (e.g., "HederaProofs-EVM", "HederaProofs-Solana").
  // The fingerprint is the hash of the deployed verifier code on the target
  // platform (e.g., EXTCODEHASH on EVM chains).
  map<string, bytes> approved_verifiers = 4;

  // Consensus timestamp of the transaction that last modified this configuration.
  // Monotonically increasing. Used to determine configuration freshness.
  Timestamp timestamp = 5;

  // Capacity limits advertised by this ledger.
  ClprThrottles throttles = 6;
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

  // Maximum total size of a sync payload (proof bytes + metadata + bundle).
  // Endpoints MAY terminate streams exceeding this limit.
  uint64 max_sync_payload_bytes = 6;
}
```

## 1.2 Endpoint Identity

```protobuf
// An endpoint participating in CLPR syncs for a specific Connection.
// Maintained in state separately from the configuration, propagated via
// EndpointJoin / EndpointLeave control messages.
message ClprEndpoint {
  // Network address and port. Optional; omit for private networks that
  // only initiate outbound syncs.
  ServiceEndpoint service_endpoint = 1;

  // DER-encoded RSA public certificate used for TLS and payload signing.
  // This is the endpoint's CLPR protocol key, used for endpoint_signature
  // in ClprSyncPayload and for mTLS with peer endpoints.
  //
  // Minimum RSA key size: 2048 bits. RSA-3072 or higher is RECOMMENDED.
  //
  // Note: endpoints also need a platform-native transaction signing key
  // (e.g., ECDSA secp256k1 on Ethereum, Ed25519 or ECDSA on Hiero) for
  // submitting transactions to their own ledger. The platform key is NOT
  // part of the CLPR protocol — it is managed by the endpoint operator
  // and is not included in the roster.
  bytes signing_certificate = 2;

  // On-ledger account associated with this endpoint node.
  // Length is platform-dependent (e.g., 20 bytes for EVM/Hiero).
  // MUST be unique within a Connection's endpoint roster.
  bytes account_id = 3;
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
    ClprEndpointJoin endpoint_join = 1;
    ClprEndpointLeave endpoint_leave = 2;
    ClprConfigUpdate config_update = 3;
  }
}

// Announces a new endpoint joining the peer's roster.
message ClprEndpointJoin {
  ClprEndpoint endpoint = 1;
}

// Announces an endpoint's departure from the peer's roster.
// The connection_id is implicit — the endpoint is removed from the roster
// of the Connection on which this Control Message was received.
message ClprEndpointLeave {
  // Account ID of the departing endpoint. MUST match an existing roster entry.
  bytes account_id = 1;
}

// Carries updated configuration parameters.
//
// Config propagation uses lazy enqueue: when the admin updates the local
// configuration, the CLPR Service increments a global config version counter
// (O(1) operation). When a Connection next processes a bundle or enqueues a
// message, the service checks whether the Connection's config version is
// behind the global version. If so, a ConfigUpdate Control Message is
// enqueued on that Connection at that point. This ensures:
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

  // Signature of the sending endpoint over (connection_id || proof_bytes),
  // using the endpoint's signing key (from its signing_certificate).
  // This signature is NOT verified during bundle processing — the verifier's
  // proof_bytes provide the cryptographic assurance. The endpoint_signature
  // exists for attribution and misbehavior evidence: it proves which endpoint
  // produced a given payload, enabling EXCESS_FREQUENCY reports.
  //
  // The signing scheme is RSASSA-PSS with SHA-256 (preferred) or
  // RSASSA-PKCS1-v1_5 with SHA-256. Platform-specific specifications
  // MUST declare which scheme they use.
  bytes endpoint_signature = 3;

  // Public key of the sending endpoint, matching its signing_certificate.
  // Included so that misbehavior reporters can identify the offender without
  // needing the full certificate.
  bytes endpoint_public_key = 4;
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
}
```

### gRPC Endpoint Service

Every CLPR endpoint exposes this gRPC service. It is the endpoint-to-endpoint protocol — separate from the on-ledger
CLPR Service API. Implementations MUST configure gRPC max message sizes to accommodate `max_sync_payload_bytes`.

```protobuf
service ClprEndpointService {
  // Bidirectional sync: exchange pre-computed payloads with a peer endpoint.
  // The initiator pre-computes its payload before opening the connection.
  // The responder computes its payload upon receiving the incoming connection.
  // Each side then submits the received payload to its own ledger.
  rpc sync(ClprSyncPayload) returns (ClprSyncPayload);
}
```

## 1.6 Misbehavior Reporting

```protobuf
// Types of provable misbehavior by a remote endpoint.
enum ClprEvidenceType {
  // Unspecified — implementations MUST reject reports with this value.
  EVIDENCE_TYPE_UNSPECIFIED = 0;

  // Sync frequency exceeds the receiving ledger's advertised max_syncs_per_sec.
  EXCESS_FREQUENCY = 1;
}

// A misbehavior report submitted to the offending endpoint's home ledger.
// The signed payloads are self-proving: the receiving ledger can verify the
// offending endpoint's signature without trusting the reporter.
message ClprMisbehaviorReport {
  // Connection ID on which the misbehavior occurred.
  // MUST be exactly 32 bytes.
  bytes connection_id = 1;

  // Public key of the misbehaving remote endpoint.
  bytes offending_endpoint = 2;

  // Type of misbehavior being reported.
  ClprEvidenceType evidence_type = 3;

  // The sync payloads constituting the evidence. Each payload includes
  // the offending endpoint's signature (endpoint_signature field).
  //
  // For EXCESS_FREQUENCY: multiple ClprSyncPayloads from the same endpoint
  //   with consensus timestamps demonstrating frequency violation.
  //   Frequency MUST be measured in sync rounds or blocks, not wall-clock time.
  //   A tolerance band SHOULD be applied near round boundaries.
  repeated ClprSyncPayload evidence = 4;

  // CAIP-2 identifier of the reporting ledger.
  string reporter_chain_id = 5;
}
```

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
  verifier_contract       : bytes       // address of the locally deployed verifier for this Connection
  verifier_fingerprint    : bytes       // implementation fingerprint (code hash) endorsed by peer

  // --- Status ---
  status                 : enum { ACTIVE, PAUSED, SEVERED, HALTED }
  // See §2.1.1 for status transition rules.

  // --- Registration ---
  registrant             : bytes       // account that registered this Connection (deposit recipient)
  deposit                : uint        // anti-griefing deposit held for this Connection's lifetime

  // --- Config Propagation ---
  local_config_version   : uint64      // last local config version propagated on this Connection
  // When local_config_version < global config version, a ConfigUpdate
  // is lazily enqueued on the next interaction (see §1.3).

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

**Peer endpoint roster** is stored separately, keyed by `(connection_id, account_id)`. It is NOT embedded in the
Connection object. Endpoints are seeded at connection registration and updated via EndpointJoin/EndpointLeave control
messages.

**Message queue** entries are stored separately, keyed by `(connection_id, message_id)`.

### 2.1.1 Connection Status Transitions

| From      | To        | Trigger                                          | Notes                                                       |
|-----------|-----------|--------------------------------------------------|-------------------------------------------------------------|
| (new)     | `ACTIVE`  | `registerConnection` succeeds                    | Initial state after registration.                           |
| `ACTIVE`  | `PAUSED`  | Admin calls `pauseConnection`                    | Outbound enqueue rejected. Inbound bundles still processed. |
| `ACTIVE`  | `SEVERED` | Admin calls `severConnection`                    | Terminal state. All processing stops.                       |
| `ACTIVE`  | `HALTED`  | Response ordering violation detected (§4.5)      | Protocol-triggered. Requires admin intervention.            |
| `PAUSED`  | `HALTED`  | Response ordering violation detected during inbound bundle processing (§4.5) | Inbound bundles are still processed while paused. |
| `PAUSED`  | `ACTIVE`  | Admin calls `resumeConnection`                   |                                                             |
| `PAUSED`  | `SEVERED` | Admin calls `severConnection`                    | Terminal state.                                             |
| `HALTED`  | `SEVERED` | Admin calls `severConnection`                    | Only valid transition out of HALTED.                        |

**Status behavior for incoming bundles:**
- **`ACTIVE`**: Bundles accepted and processed normally.
- **`PAUSED`**: Inbound bundles are still accepted and processed (the pause only prevents new outbound messages). This ensures acknowledgements continue flowing and the peer's queue does not stall.
- **`SEVERED`**: All bundle submissions are rejected. No further processing occurs.
- **`HALTED`**: Inbound bundles are rejected. The Connection is frozen pending admin action.

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
  // (including chain_id, service_address, approved_verifiers, timestamp, throttles).
  //
  // Used during:
  //   - registerConnection (initial bootstrap)
  //   - updateConnectionVerifier with ZK proof path (recovery)
  //
  // The proof_bytes contain whatever the source ledger's proof system produces
  // to attest to its configuration state (state roots, Merkle paths, ZK proofs, etc.).
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

  // Verify an endpoint roster proof. Returns the verified endpoint list
  // for a Connection on the source ledger.
  //
  // Used during:
  //   - recoverEndpointRoster (when sync channel is broken)
  //
  // MUST revert if verification fails.
  function verifyEndpoints(bytes proof_bytes) returns (ClprEndpoint[])
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

## 3.3 Built-in ZK Verifier

Every CLPR Service implementation includes a **built-in ZK verifier** that is hardcoded into the service itself (not
a separate contract). This verifier is used exclusively during Connection registration and the ZK recovery path of
`updateConnectionVerifier`. It verifies a zero-knowledge proof attesting to a peer ledger's configuration state.

The built-in ZK verifier is the bootstrap trust anchor — it cannot rely on a Connection-specific verifier whose
legitimacy has not yet been established. Upgrading the built-in ZK verifier requires a platform upgrade (software
update on Hiero, contract upgrade on Ethereum).

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

**Step 5a — Lazy config propagation.** If the Connection's `local_config_version` is less than the global
config version, enqueue a `ConfigUpdate` Control Message (see §1.3) on this Connection's outbound queue and
update `local_config_version` to match. This ensures the ConfigUpdate appears at a deterministic,
consensus-determined point in the message stream — specifically, after the acknowledgement update and before
any new messages generated by this bundle's dispatch.

**Step 6 — Message dispatch.** For each message in order, dispatch by type:

| Message Type     | Processing                                                                                                                                                                                                                                                            |
|------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Control Message  | Apply directly (update peer endpoint roster or store new config values). Advance `received_message_id` and `received_running_hash`. No response generated.                                                                                                            |
| Data Message     | Resolve `connector_id` to local Connector via cross-chain mapping. Charge Connector (execution cost plus margin; margin reimburses the submitting endpoint). Dispatch to target application. Generate Response Message. Advance queue metadata. |
| Response Message | Deliver to originating application. Verify ordering per §4.5. Advance `received_message_id` and `received_running_hash`.                                                                                                                                             |

A failure on one message does NOT stop processing of remaining messages in the bundle. Each message is processed
independently — if one Connector is underfunded, a `CONNECTOR_UNDERFUNDED` response is generated for that message
while other messages (including from the same Connector) continue processing normally.

## 4.3 Message Enqueue Algorithm

When a message send is processed:

1. Look up the Connection by `connection_id`. Reject if status is not `ACTIVE`.
1a. **Lazy config propagation.** If the Connection's `local_config_version` is less than the global config
   version, enqueue a `ConfigUpdate` Control Message (see §1.3) on this Connection's outbound queue before
   the application's message and update `local_config_version` to match. This ensures config changes are
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
4. **Mismatch:** the peer has violated the ordering guarantee. Set Connection status to `HALTED`. Halt acceptance
   of new outbound messages on this Connection.

**Initial state.** When a Connection is first created, no outbound Data Messages exist, so no responses
are expected and the walk in step 2 finds nothing. The first Response Message received MUST match the
first Data Message ever sent on this Connection (ID 1, since `next_message_id` is initialized to 1).
Response ordering is tracked implicitly by walking the retained outbound Data Messages — there is no
separate counter.

**HALTED recovery.** A HALTED Connection does not automatically recover. The ordering violation indicates a
fundamental peer-side bug in response generation. The admin MUST intervene — typically by coordinating with the
peer to fix the bug (which may require a contract upgrade on platforms like Ethereum), then severing the Connection
(see §5.5) and re-registering if queue state is unrecoverable.

**Distinction from bad inbound bundles.** If a peer sends bundles that fail verification (bad hash chain, replay,
oversized payloads), the CLPR Service simply rejects them — no HALT, no state change. The Connection remains
ACTIVE and will accept valid bundles as soon as the peer fixes the issue. HALT is reserved exclusively for
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

Connection creation is **permissionless but requires a deposit**. The deposit is a non-trivial amount of native
tokens held by the CLPR Service for the lifetime of the Connection. It is returned when the Connection is severed.
The deposit prevents griefing attacks where an attacker registers large numbers of bogus Connections to inflate
operational costs for the CLPR Service admin. Platform-specific specifications MUST define the minimum deposit
amount. The registrant:

1. Generates an **ECDSA_secp256k1 keypair** and computes the Connection ID as `keccak256(uncompressed_public_key)`
   (64-byte x||y coordinates, without the `0x04` prefix).
2. Deploys a **verifier contract** on the local ledger.
3. Obtains a **ZK proof** attesting to the peer ledger's current configuration.
4. Calls `registerConnection` on the local CLPR Service.

The CLPR Service:

1. Verifies that the provided `deposit` meets the platform-defined minimum deposit threshold. Transfers the
   deposit from the caller to the CLPR Service. Records the caller as `registrant` and the amount as `deposit`
   on the Connection.
2. Verifies the **ECDSA signature** over the registration data (proves caller controls the Connection ID).
   The exact signed payload format is defined in platform-specific specifications.
3. Verifies the **ZK proof** using the built-in ZK verifier, extracting the peer's configuration (including
   `chain_id`, `service_address`, `approved_verifiers`).
4. Checks the deployed verifier contract's code hash against the peer's `approved_verifiers`. Rejects if no match.
5. Creates the Connection: stores the Connection ID, peer's `chain_id` and `service_address`, peer configuration
   timestamp, verifier address and fingerprint, and seed endpoints.

This process is repeated independently on the peer ledger for bidirectional communication. No pairing ceremony is
needed — the deterministic Connection ID from the shared keypair links the two registrations.

## 5.2 Verifier Migration

For a given Connection, there is exactly one active verifier. If the source ledger upgrades its proof format, the
migration is fully automated through the existing ack mechanism:

1. **Deploy** new verifier implementations (supporting the new proof format) on all target platforms.
2. **Update `approved_verifiers`** — remove old fingerprints and add new ones. This increments the global config
   version. ConfigUpdate Control Messages are lazily enqueued on each Connection at the next interaction.
3. **Continue proving with the old format.** The bundle carrying the ConfigUpdate is verified by the old verifier
   on each destination. After the destination processes the ConfigUpdate, it knows the new `approved_verifiers` and
   can `updateConnectionVerifier` locally.
4. **Per Connection, switch to the new proof format once the ConfigUpdate is acked.** The source ledger tracks
   acks per Connection — once a Connection's ack covers the ConfigUpdate message, the source knows that Connection
   has processed the change and begins generating proofs in the new format.

No dual-format verifiers are needed. No unobservable "wait" step exists.

## 5.3 Updating the Connection Verifier

Any user can update the verifier on an existing Connection. Two verification paths:

**Local check (normal case).** The CLPR Service checks the new verifier's code hash against the `approved_verifiers`
already stored on the Connection (delivered via ConfigUpdate Control Messages). If the fingerprint matches, the
verifier is replaced. No ZK proof needed.

**ZK proof (recovery case).** If the sync channel is broken and the stored `approved_verifiers` is stale (peer
changed its proof format and old verifier cannot read new proofs), the caller supplies a ZK proof. The built-in ZK
verifier extracts the peer's current config, the CLPR Service checks the new verifier's code hash against the
freshly proven `approved_verifiers`, and the Connection's stored config is updated. Queue state is preserved.

## 5.4 Endpoint Roster Recovery

If the sync channel breaks down (e.g., a ledger has completely rotated its endpoint set), any user may submit a
`recoverEndpointRoster` call. The Connection's verifier validates proof bytes via `verifyEndpoints()` and returns
the peer's current endpoint list. The CLPR Service replaces the stale peer roster.

## 5.5 Administrative Operations

The CLPR Service admin can:

- **Sever** a Connection — permanently close it, immediately stopping all message processing.
- **Pause** a Connection — temporarily halt processing without closing it.
- **Resume** a paused Connection — return it to `ACTIVE` status.
- **Update the local configuration** — change `chain_id`, `approved_verifiers`, throttles. Changes are propagated
  to peers via ConfigUpdate Control Messages, lazily enqueued on each Connection at its next interaction (see §1.3).

---

# 6. Pseudo-API Reference

The following pseudo-APIs describe the operations that every CLPR Service implementation MUST support. Platform-
specific specifications map these to native constructs. Parameters marked `[auth]` require the caller to authenticate
(platform-specific mechanism — transaction signature, `msg.sender`, etc.).

## 6.1 Configuration Management

```
// Set or update this ledger's local CLPR configuration.
// Authority: CLPR Service admin only.
// Stores the new configuration and increments the global config version
// counter. ConfigUpdate Control Messages are lazily enqueued on each
// Connection the next time it processes a bundle or enqueues a message
// (see ClprConfigUpdate in §1.3).
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
// Authority: any caller (permissionless; deposit required).
// Preconditions: verifier_contract is deployed; zk_proof is valid.
// The deposit is held for the Connection's lifetime and returned on sever.
// Platform specs MUST define the minimum deposit amount.
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
  verifier_contract: bytes,       // address of locally deployed verifier
  zk_proof: bytes,                // ZK proof of peer's configuration
  seed_endpoints: ClprEndpoint[], // at least one peer endpoint
  deposit: uint                   // anti-griefing deposit (native tokens); returned on sever
) → success | error

// Update the verifier contract on an existing Connection.
// Authority: any caller (permissionless).
updateConnectionVerifier(
  [auth] caller,
  connection_id: bytes(32),
  verifier_contract: bytes,       // new verifier contract address
  zk_proof: bytes (optional)      // omit for local check; include for recovery path
) → success | error

// Recover a Connection's peer endpoint roster from a state proof.
// Authority: any caller (permissionless).
recoverEndpointRoster(
  [auth] caller,
  connection_id: bytes(32),
  proof_bytes: bytes              // passed to verifyEndpoints()
) → success | error

// Sever (permanently close) a Connection.
// Authority: CLPR Service admin only.
// Returns the Connection registration deposit to the original registrant.
severConnection(
  [auth] admin,
  connection_id: bytes(32)
) → success | error

// Pause a Connection (temporarily halt processing).
// Authority: CLPR Service admin only.
pauseConnection(
  [auth] admin,
  connection_id: bytes(32)
) → success | error

// Resume a paused Connection.
// Authority: CLPR Service admin only.
resumeConnection(
  [auth] admin,
  connection_id: bytes(32)
) → success | error

// Query a Connection's current state.
// Authority: any caller.
getConnection(
  connection_id: bytes(32)
) → Connection | error

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
// The proof_bytes, remote_endpoint_signature, and remote_endpoint_public_key
// correspond to the fields of a ClprSyncPayload received during sync.
submitBundle(
  [auth] caller,
  connection_id: bytes(32),
  proof_bytes: bytes,             // ClprSyncPayload.proof_bytes
  remote_endpoint_signature: bytes,  // ClprSyncPayload.endpoint_signature
  remote_endpoint_public_key: bytes  // ClprSyncPayload.endpoint_public_key
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

## 6.6 Misbehavior Reporting

```
// Submit a misbehavior report against a remote endpoint.
// Authority: any caller.
// Reporters that submit frivolous or fabricated reports MAY be penalized
// (e.g., the Connection may be severed with the reporting ledger).
reportMisbehavior(
  [auth] caller,
  connection_id: bytes(32),
  report: ClprMisbehaviorReport
) → success | error
```

---

# 7. Configuration Parameters

| **Parameter**               | **Default** | **Scope**                  | **Description**                                                                                          |
|-----------------------------|-------------|----------------------------|----------------------------------------------------------------------------------------------------------|
| `clprEnabled`               | `false`     | Global                     | Master enable switch. When disabled, all pseudo-API calls MUST return an error.                           |
| `connectionFrequency`       | `5000` ms   | Global                     | How frequently endpoints initiate sync calls to peers.                                                   |
| `publicizeNetworkAddresses` | `true`      | Global                     | Whether to include service endpoint addresses in the endpoint roster.                                    |
| `maxQueueDepth`             | TBD         | Per-Ledger (per-Connection) | Maximum unacknowledged messages in the outbound queue before new messages are rejected.                   |
| `maxMessagePayloadBytes`    | TBD         | Per-Ledger (per-Connection) | Maximum payload size for a single message. Enforced by source (enqueue) and destination (bundle).        |
| `maxMessagesPerBundle`      | TBD         | Per-Ledger (per-Connection) | Maximum messages a single bundle may contain. Platform specs MUST set this to ensure bundles fit within the platform's transaction size and execution budget limits. |
| `maxGasPerMessage`          | TBD         | Per-Ledger (per-Connection) | Maximum gas (or ops budget) allocated to processing a single message.                                    |
| `maxSyncsPerSec`            | TBD         | Per-Ledger (per-Connection) | Advisory maximum sync frequency. Not protocol-enforced; persistent violation is misbehavior.             |
| `maxSyncPayloadBytes`       | TBD         | Per-Ledger (per-Connection) | Maximum total size of a sync payload. Endpoints MAY terminate streams exceeding this limit.              |

**Scope note.** Parameters marked "Per-Ledger (per-Connection)" are published in the ledger-wide configuration
(`ClprThrottles`) and advertised to all peers. Each Connection independently enforces them against incoming data.

---

# 8. Security Considerations

## 8.1 Protocol Strictness

All limits are published in the configuration so that both sides know the rules. A peer that exceeds any published
limit is committing a measurable, attributable violation. The receiving side MUST reject the offending submission and
MAY count repeated violations toward misbehavior thresholds.

Implementations MUST NOT silently ignore unrecognized fields, unknown message types, or malformed metadata.
Unrecognized data MUST be rejected outright.

## 8.2 Trust Model

Connecting to a peer ledger via CLPR means trusting:
1. The peer ledger's consensus mechanism.
2. The admin of the peer ledger's CLPR Service (controls `approved_verifiers`).

A compromised admin can endorse a fraudulent verifier that returns fabricated data — and the ZK proof of endorsement
will faithfully prove that fraudulent endorsement. Evaluate the security of the peer's CLPR Service admin before
connecting.

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

Verifier contracts MAY be deployed behind upgradeable proxies (e.g., EIP-1967). When proxies are used, the
implementation fingerprint in `approved_verifiers` is the proxy's code hash. This is acceptable ONLY if the proxy's
upgrade authority is controlled by the source ledger's CLPR Service admin (or equivalent governance). A verifier
whose upgrade key is controlled by a third party is a critical vulnerability — that third party could silently
replace the verification logic.

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
| R1  | Endpoints rotated during partition                  | Broken               | `recoverEndpointRoster` with `verifyEndpoints` proof.                                                                  | Works                         |
| R2  | Planned verifier migration (sync working)           | Working              | 4-step ack-driven migration (§5.2).                                                                                    | Works                         |
| R3  | Endpoints rotated + verifier changed (same format)  | Broken               | Endpoint recovery (R1), then ConfigUpdate flows normally.                                                              | Works                         |
| R4  | Endpoints rotated AND proof format changed           | Broken               | `updateConnectionVerifier` with ZK proof (§5.3), then endpoint recovery (R1).                                          | Works                         |
| R5  | Verifier compromised or broken                      | Suspect              | Admin pauses/severs. New verifier via `updateConnectionVerifier` if buggy. Sever and re-register if compromised.       | Works (data loss on sever)    |
| R6  | Queue state permanently corrupted on peer           | Working              | Connection halts (§4.5). Admin severs. Applications need out-of-band reconciliation.                                   | **Open question** (see below) |
| R7  | Network partition (endpoints unchanged)             | Temporarily broken   | Syncs resume automatically. Monotonic IDs and running hash verify integrity.                                           | Works                         |
| R8  | Peer ledger down entirely                           | Broken               | Messages queue up to `max_queue_depth`, then backpressure. Syncs resume when peer returns.                             | Works                         |
| R9  | Both sides' endpoints change simultaneously         | Broken on both sides | `recoverEndpointRoster` submitted independently on both ledgers.                                                       | Works                         |
| R10 | Built-in ZK verifier becomes obsolete               | N/A                  | Requires platform upgrade. Existing Connections unaffected.                                                            | Works (requires upgrade)      |

---

# 10. Open Questions

1. **Recovery from permanent response ordering violation (R6).** If a peer ledger's queue state is permanently
   corrupted and it can never produce correctly ordered responses, the Connection is stuck (§4.5). Severing the
   Connection leaves in-flight messages in an ambiguous state — the source cannot determine which were processed
   before the corruption. CLPR cannot skip or reorder messages without breaking ABFT properties. What recovery
   mechanism, if any, can resolve this without violating ordering guarantees? Applications may need their own
   out-of-band reconciliation, but the protocol-level recovery path is undefined.
