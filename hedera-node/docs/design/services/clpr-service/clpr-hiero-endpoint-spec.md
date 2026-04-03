# CLPR Hiero Endpoint Implementation Specification

This document specifies how the CLPR endpoint — the component responsible for peer-to-peer sync orchestration, proof
construction, and transaction submission — is implemented within the Hiero consensus node. Every Hiero consensus node
IS a CLPR endpoint when CLPR is enabled. There is no separate process, sidecar, or external service.

For the cross-platform protocol specification, see the companion
[CLPR Protocol Specification](clpr-service-spec.md). For the architectural rationale and design overview, see the
[CLPR Design Document](clpr-service.md).

---

## Notation

This document uses RFC 2119 keywords per the [cross-platform spec](clpr-service-spec.md). Additional terms:

- "Node" refers to a Hiero consensus node.
- "Endpoint module" refers to the CLPR endpoint code running within a node.
- "CLPR Service" refers to the on-ledger service that manages CLPR state in the Merkle tree.
- "Latest immutable state" refers to the most recent state that has been hashed and signed by consensus (also called
  the latest signed state or latest complete state).

---

# 1. Architecture Overview

## 1.1 Module Placement

The CLPR endpoint is a module within the Hiero consensus node binary. It runs in the same JVM process as the Platform
layer, the application services layer, and the gRPC server infrastructure. It is NOT a separate process, sidecar, or
plugin. It is loaded and initialized as part of the node's standard startup sequence.

```
┌─────────────────────────────────────────────────────────────┐
│                    Hiero Consensus Node                     │
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │   Platform   │  │  App Services│  │   gRPC Server    │  │
│  │  (gossip,    │  │  (Token,     │  │  (HAPI services  │  │
│  │   consensus, │  │   Crypto,    │  │   + CLPR         │  │
│  │   state mgmt)│  │   File, ...) │  │   Endpoint API)  │  │
│  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘  │
│         │                 │                    │             │
│  ┌──────┴─────────────────┴────────────────────┴─────────┐  │
│  │              CLPR Endpoint Module                      │  │
│  │                                                        │  │
│  │  ┌─────────────┐ ┌──────────────┐ ┌────────────────┐  │  │
│  │  │ Sync        │ │ Proof        │ │ Transaction    │  │  │
│  │  │ Orchestrator│ │ Constructor  │ │ Submitter      │  │  │
│  │  └─────────────┘ └──────────────┘ └────────────────┘  │  │
│  │  ┌─────────────┐ ┌──────────────┐ ┌────────────────┐  │  │
│  │  │ Roster      │ │ gRPC Client  │ │ Peer           │  │  │
│  │  │ Observer    │ │ (outbound)   │ │ Selector       │  │  │
│  │  └─────────────┘ └──────────────┘ └────────────────┘  │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌──────────────────────────────────────────────────────────┐│
│  │              Merkle State Tree                           ││
│  │  (CLPR state: connections, queues, endpoints, config)    ││
│  └──────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

## 1.2 Gradle Modules

The endpoint implementation spans two Gradle modules:

| Module                                | Contents                                                                                                           |
|---------------------------------------|--------------------------------------------------------------------------------------------------------------------|
| `hiero-clpr-interledger-service`      | API interfaces: `ClprService`, `ClprClient`, store interfaces, `ClprStateProofUtils`                               |
| `hiero-clpr-interledger-service-impl` | Endpoint implementation: handlers, stores, gRPC client/server, sync orchestrator, proof constructor, Dagger wiring |

## 1.3 Dependencies on Existing Node Infrastructure

The endpoint module depends on — but does not modify — the following existing node subsystems:

| Subsystem                            | Dependency                                             | Usage                                                                                                                                        |
|--------------------------------------|--------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| **gRPC Server**                      | Netty-based gRPC infrastructure                        | Hosts the `ClprEndpointService` alongside existing HAPI services                                                                             |
| **State APIs**                       | `ReadableStates`, `WritableStates`, etc.               | All CLPR state (connections, queues, endpoints, configuration) is accessed here                                                              |
| **State Proof Infrastructure**       | `StateProofBuilder`, `MerklePathBuilder`, block proofs | Constructs proofs over CLPR state for outbound syncs                                                                                         |
| **Transaction Submission**           | `SubmissionManager`                                    | Submits node-signed `submitBundle` transactions directly for consensus (bypasses `IngestWorkflow`, which is for external client submissions) |
| **Roster Management**                | `RosterService`, active roster state                   | Reads the consensus roster to derive the CLPR endpoint set                                                                                   |
| **TSS (Threshold Signature Scheme)** | TSS signing infrastructure                             | Provides aggregate signatures over state roots for proof construction                                                                        |
| **Dagger DI**                        | `HederaInjectionComponent`, component lifecycle        | Manages endpoint module startup, shutdown, and threading                                                                                     |
| **Configuration**                    | `ConfigProvider`, `ClprConfig` record                  | Runtime configuration for all endpoint parameters                                                                                            |
| **Metrics**                          | `Metrics` infrastructure                               | Exposes endpoint-specific counters, gauges, and histograms                                                                                   |
| **Node Identity**                    | Node's TLS certificate, ECDSA secp256k1 signing key, account ID | Used for endpoint identity (TLS authentication via RSA certificate, payload signing via ECDSA secp256k1 key)                                |

## 1.4 Dagger Integration

The endpoint module is wired into the node's Dagger dependency injection graph via
`HederaStateInjectionModule`. Key injectable components:

- `ClprConnectionManager` — manages the lifecycle of peer connections and sync scheduling
- `ClprEndpointClient` — the outbound gRPC client for contacting peer endpoints
- `ClprStateProofManager` — constructs state proofs from the Merkle tree
- `AppContext` — provides access to node identity, configuration, and platform state

---

# 2. gRPC Server Integration

## 2.1 Service Registration

The `ClprEndpointService` gRPC service is registered alongside existing HAPI services on the node's Netty gRPC
server during node initialization. It follows the same registration pattern as `CryptoServiceDefinition`,
`TokenServiceDefinition`, and other HAPI service definitions.

```protobuf
service ClprEndpointService {
  rpc sync(ClprSyncPayload) returns (ClprSyncPayload);
}
```

The service definition class (`ClprEndpointServiceDefinition`) implements `RpcServiceDefinition` and declares the
`sync` RPC method. It is discovered and registered by the same `ServiceRegistration` mechanism that handles all
other HAPI services.

**Dynamic enable/disable.** The `ClprEndpointService` is always registered, regardless of the `clprEnabled` flag.
The flag is checked dynamically at call time — when CLPR is disabled, calls return `UNAVAILABLE`. This ensures the
governing council can enable or disable CLPR via a configuration update without requiring node restarts, providing
a runtime kill-switch for emergency situations.

## 2.2 Port Configuration

The `ClprEndpointService` shares the node's existing gRPC port (default 50211 for non-TLS, 50212 for TLS).
A dedicated port is NOT required for the initial implementation.

**Rationale.** Sharing the port simplifies deployment and firewall configuration. The CLPR endpoint protocol is
a single unary RPC — it does not require streaming or long-lived connections. The existing gRPC server's thread pool
and connection limits are sufficient for the expected sync frequency.

**Future consideration.** If CLPR sync traffic grows to a level where it contends with HAPI query/transaction
traffic, a dedicated port (`clpr.endpointPort`) MAY be introduced. This would require configuring a second Netty
gRPC server instance within the same node process.

## 2.3 TLS Configuration

All CLPR endpoint-to-endpoint communication MUST use TLS. The node's existing TLS configuration is reused:

- **Server TLS.** The node's TLS certificate (RSA, DER-encoded) is used as the server certificate. This is the
  same certificate advertised in the node's `ClprEndpoint.tls_certificate` field in the endpoint roster.
- **Client TLS.** Outbound gRPC connections to peer endpoints use TLS with the node's RSA certificate for client
  authentication.
- **Peer verification.** TLS provides transport encryption. Peer identity verification is NOT based on TLS
  certificate matching against an on-chain roster — peer endpoint data is not stored in state. Instead,
  authentication is proof-based: any peer that provides a valid bundle proof (verified by the Connection's
  verifier contract) is legitimate regardless of identity. Endpoints that send invalid data are simply shunned.

**TLS mode.** Standard TLS (server-side certificate) is sufficient. Mutual TLS is NOT required since peer
authentication does not rely on certificate matching against a roster.

**Triple-key architecture.** Per cross-platform spec §1.2, endpoints have separate TLS and ECDSA signing keys. On
Hiero, nodes use three distinct keys:

1. **RSA key** (from the node's TLS certificate) — mTLS transport authentication only. Reuses the node's existing
   signing certificate already published in the address book; no additional TLS key provisioning is required.
2. **ECDSA secp256k1 key** — `endpoint_signature` and local misbehavior detection (see spec §1.5). MAY be the node's
   existing ECDSA secp256k1 key if available, or a dedicated key generated for CLPR operations.
3. **Ed25519/ECDSA key** (on the node's account) — signs HAPI transactions (`submitBundle`, etc.) submitted to the
   local ingest pipeline. Never exposed in the CLPR protocol wire format.

Account key changes (e.g., rotating the node account's Ed25519 key) do NOT affect the CLPR protocol — only the
`tls_certificate` and `ecdsa_signing_key` matter for endpoint identity.

## 2.4 gRPC Message Size

The gRPC server and client MUST configure max message sizes to accommodate `max_sync_bytes` from the
ledger's configuration. The max inbound message size MUST be set to `max_sync_bytes`. This value
bounds the serialized `ClprSyncPayload` protobuf message, which IS the gRPC message — no additional padding
is required.

---

# 3. Sync Orchestration

## 3.1 Sync Loop

Each node runs a **sync orchestrator** that initiates outbound sync calls to peer endpoints **only when there are
pending outbound messages**. Inbound messages are received passively — the remote peer dials this node when it has
messages to deliver. The orchestrator runs on a dedicated thread pool managed by the Dagger DI framework.

**Outbound sync trigger.** The orchestrator monitors each active Connection's queue state. When a Connection has
pending outbound messages (i.e., `next_message_id > acked_message_id`), the orchestrator initiates a sync to a
peer endpoint. No outbound syncs are initiated when there is nothing to send.

**Sync interval computation.** The outbound sync interval for a Connection is derived from the peer ledger's
`max_syncs_per_sec` configuration and the number of local endpoints registered on that Connection:

```
sync_interval_ms = 1000 * num_local_endpoints / max_syncs_per_sec
```

For example, if the peer advertises `max_syncs_per_sec = 10` and there are 25 local endpoints, each endpoint
syncs at most once every 2500 ms. This ensures the aggregate sync rate across all local endpoints stays within
the peer's advertised limit.

If `max_syncs_per_sec` is not yet known (e.g., the Connection is newly registered and no `ConfigUpdate` has been
received), the endpoint uses a conservative default of 1 sync per second per endpoint until the peer's
configuration is received.

**Inbound sync handling.** This node receives inbound sync calls from peer endpoints at any time. No polling or
heartbeat syncing is needed to receive messages — the peer initiates the sync when it has outbound messages for
this ledger. The endpoint module tracks inbound sync frequency per `(connection_id, remote_endpoint_account_id)`
pair to detect abuse (see Section 12.1).

**Connection status filtering.** Per cross-platform spec §2.1.1, Connections have five statuses. On Hiero, the
sync orchestrator filters as follows: ACTIVE, PAUSED, CLOSING, and DRAINED Connections all participate in syncs
(both inbound and outbound). PAUSED Connections continue outbound syncs and accept inbound syncs, but reject
inbound bundles containing out-of-order responses. CLOSING and DRAINED Connections continue syncing to drain
queues. Only CLOSED Connections are skipped entirely (see Section 13.4 for PAUSED, CLOSING, and DRAINED behavior).

## 3.2 Peer Endpoint Selection

When initiating a sync for a Connection, the endpoint must select a peer endpoint from the Connection's peer roster.

**Selection algorithm:**

1. **Filter.** Remove endpoints that are marked as unreachable (circuit breaker open — see Section 13).
2. **Randomize.** From the remaining candidates, select one using weighted random selection.
3. **Weight factors:**
   - **Base weight:** Equal for all candidates (ensures randomization).
   - **Reputation bonus:** Endpoints that have completed successful syncs recently receive a higher weight.
   - **Reputation penalty:** Endpoints that have recently failed or timed out receive a lower weight.
   - **Recency penalty:** If this node synced with a specific peer endpoint on the last tick, that endpoint's
     weight is reduced (prevents persistent pairing, which is a Sybil attack vector per the design doc Section 3.1.2).

**Reputation tracking.** Each node maintains a local, ephemeral reputation table keyed by
`(connection_id, peer_endpoint_account_id)`. This table is not persisted to state — it is rebuilt from scratch
on node restart. Entries decay over time (exponential decay with a half-life of `clpr.reputationDecaySeconds`,
default: 300 seconds).

## 3.3 Concurrency Model

**Max concurrent syncs.** The endpoint module limits the number of simultaneous outbound sync calls per node via
the `clpr.maxConcurrentSyncs` parameter (default: 4). This bounds the resource consumption (threads, network
connections, CPU for proof construction) on any single node.

**Per-Connection limit.** At most one outbound sync per Connection may be in progress at any time. This prevents
redundant work — if a sync is already in progress for a Connection, the orchestrator skips it on the current tick.

**Inbound concurrency.** Inbound sync calls (from peer endpoints contacting this node) are handled by the gRPC
server's thread pool. There is no hard limit on inbound concurrency beyond the gRPC server's max concurrent
streams configuration. However, if a peer endpoint exceeds the local ledger's `max_syncs_per_sec`, the excess
calls SHOULD be rejected with a gRPC `RESOURCE_EXHAUSTED` status.

## 3.4 Sync Call Flow (Initiator Side)

The sync wire format (`ClprSyncPayload`) and signing scheme (`endpoint_signature`) are defined in the cross-platform
spec §1.5. On Hiero, the initiator-side flow is:

```
1. Read latest immutable state (via LatestCompleteStateNexus):
   - Connection metadata, unacknowledged messages, queue metadata

2. Construct proof bytes:
   - Build Merkle paths from state root to queue metadata and message entries
   - Include block proof with TSS signature over state root
   - Package into proof_bytes blob (see Section 5)

3. Sign the payload with the node's ECDSA secp256k1 key (per spec §1.5)

4. Open mTLS gRPC connection to peer endpoint

5. Send ClprSyncPayload, receive peer's ClprSyncPayload

6. Verify the received payload locally (pre-consensus check — see Section 6.1)

7. Submit the received payload as a submitBundle HAPI transaction
```

## 3.5 Sync Call Flow (Responder Side)

```
1. Authenticate the caller:
   - Verify TLS certificate matches a known peer endpoint

2. Verify the received payload locally (pre-consensus check)
   - If verification fails, return an error response

3. Construct this node's outbound payload (same Hiero-specific steps as initiator)

4. Return ClprSyncPayload to the caller

5. Submit the caller's payload as a submitBundle HAPI transaction
```

**Asymmetry note.** The initiator pre-computes its payload before opening the connection. The responder computes
its payload upon receiving the incoming call, which blocks the gRPC thread for the duration of proof construction
(see Finding F-1 in Section 14).

---

# 4. Proof Construction (Outbound)

## 4.1 What Is Proven

When this node constructs proof bytes for a peer endpoint, it proves the following CLPR state from the Merkle tree.
The `ClprQueueMetadata` fields are defined in the cross-platform spec §1.5.

| State Element | Merkle Key Path | Purpose |
|---------------|-----------------|---------|
| Queue metadata | `CLPR_QUEUE_METADATA / {connection_id}` | Proves queue metadata fields per spec §1.5 |
| Message entries | `CLPR_MESSAGES / {connection_id, message_id}` for each unacked message | Proves message payloads and per-message running hashes |
| Connection config | `CLPR_CONNECTIONS / {connection_id}` | Proves Connection status and verifier binding |

**Field name alignment note.** What this endpoint spec refers to as the peer's "acked_message_id" in the proof
corresponds to the peer's `received_message_id` field in `ClprQueueMetadata`. The CLPR Service uses the peer's
`received_message_id` to update the local Connection's `acked_message_id` (see cross-platform spec §4.2, step 5).

## 4.2 Proof Construction Steps

The `ClprStateProofManager` constructs proofs through the following steps:

1. **Acquire latest immutable state.** Read from the `LatestCompleteStateNexus` — the most recent state that has been
   hashed by the Platform and signed via TSS. This is the latest signed state, NOT the working state.

2. **Extract the state root hash.** The root hash of the Merkle tree is the anchor for all proofs.

3. **Build Merkle paths.** For each state element to be proven, use `MerklePathBuilder` to extract the path from the
   state root to the leaf node containing the data. Each path consists of intermediate hashes that allow the verifier
   to recompute the state root from the leaf value.

4. **Collect the block proof.** The block proof includes:
   - The state root hash
   - The round number
   - The consensus timestamp
   - TSS signature(s) over the state root (see Section 4.3)

5. **Serialize messages.** Read unacknowledged messages from the queue (`acked_message_id + 1` through
   `next_message_id - 1`, up to `max_messages_per_bundle`). Serialize each as a `ClprMessagePayload`.

6. **Package into proof bytes.** Combine the block proof, Merkle paths, queue metadata, and serialized messages
   into the opaque `proof_bytes` blob (see Section 5 for format).

## 4.3 TSS Signatures

Hiero uses a Threshold Signature Scheme (TSS) to produce aggregate signatures over state roots. The TSS
infrastructure is managed by the Platform layer. The proof constructor obtains the TSS signature as follows:

1. The Platform produces a TSS signature over the state root hash after each consensus round.
2. The `ClprStateProofManager` reads the TSS signature associated with the latest signed state.
3. This signature is included in the block proof portion of the proof bytes.

The TSS signature proves that a supermajority (weighted by stake) of consensus nodes agreed on this state root.
A verifier contract on a peer ledger can verify this signature if it knows the current Hiero network's public key
(the aggregate TSS public key derived from the roster).

**Trust anchor tracking.** The peer ledger's verifier contract must track the Hiero network's TSS aggregate
public key. The TSS aggregate public key is a separate cryptographic artifact derived from TSS key shares
distributed across the consensus roster — it is NOT the same as any individual node's TLS certificate or ECDSA signing key.

TSS public key changes (resulting from roster changes) are communicated through the proof itself. Each
`HieroBlockProof` includes the full `tss_public_key` field (field 5). The verifier bootstraps from a known TSS
public key and accepts transitions when a proof is validly signed by the currently trusted key but includes a
new `tss_public_key` value, establishing a chain of trust. This allows the verifier to track key rotations
without any out-of-band communication.

Peer endpoint discovery is handled off-chain via the gossip-based `discoverEndpoints` RPC (see Section 7.2),
entirely separate from the TSS trust anchor used for proof verification.

## 4.4 State Freshness

The endpoint MUST construct proofs from the **latest immutable (signed) state**, not from working state. Working
state may be modified by in-progress consensus rounds and is not yet covered by a TSS signature.

If the latest signed state is stale (e.g., the node is catching up after reconnect), the endpoint SHOULD defer
sync initiation until a reasonably fresh signed state is available. "Reasonably fresh" means the state's consensus
timestamp is within `clpr.maxStateAgeSeconds` (default: 30) of the current wall-clock time.

---

# 5. Proof Bytes Format

## 5.1 Overview

The `proof_bytes` field in `ClprSyncPayload` is opaque to the CLPR protocol — its internal structure is defined by
the verifier contract that will parse it. For Hiero-sourced proofs (i.e., proofs constructed by a Hiero node to be
verified by a Hiero verifier contract on a peer ledger), the format is defined in this section.

Verifier contracts for different target platforms (EVM, Hiero, Solana) parse the same proof bytes format. The
format is designed to be decodable on any platform that supports protobuf parsing and SHA-256.

## 5.2 Structure

The Hiero proof bytes are serialized as a protobuf message:

```protobuf
// Internal structure of proof_bytes for Hiero-sourced proofs.
// This is NOT a cross-platform wire type — it is the format that Hiero
// verifier contracts expect. Other ledgers' proof formats will differ.
message HieroProofBytes {
  // Block proof: proves the state root is authentic.
  HieroBlockProof block_proof = 1;

  // Merkle paths from the state root to each proven leaf.
  repeated HieroMerklePath merkle_paths = 2;

  // The queue metadata at the proven state.
  ClprQueueMetadata queue_metadata = 3;

  // The message payloads included in this bundle.
  repeated ClprMessagePayload messages = 4;
}

message HieroBlockProof {
  // The state root hash (SHA-384, 48 bytes).
  bytes state_root_hash = 1;

  // Consensus round number.
  uint64 round = 2;

  // Consensus timestamp of the state.
  Timestamp consensus_timestamp = 3;

  // TSS aggregate signature over the state root hash.
  // The signature scheme and encoding are defined by the Hiero TSS specification.
  bytes tss_signature = 4;

  // The aggregate TSS public key that produced the signature.
  // Included so verifiers can check against their tracked trust anchor.
  bytes tss_public_key = 5;
}

message HieroMerklePath {
  // Identifier for which state element this path proves.
  // The verifier uses this to know which leaf to expect.
  string state_key = 1;

  // Ordered list of sibling hashes from leaf to root.
  // Each entry is (hash, direction) where direction indicates
  // whether this sibling is left or right of the path.
  repeated MerklePathEntry path = 2;

  // The leaf value (serialized protobuf of the proven state element).
  bytes leaf_value = 3;
}

message MerklePathEntry {
  bytes hash = 1;
  bool is_left = 2;  // true if this sibling is on the left
}
```

## 5.3 Verification by Peer

A Hiero verifier contract on a peer ledger implements `verifyBundle()` (cross-platform spec §3.1) by:

1. **Verify TSS signature.** Check that `tss_signature` is a valid signature over `state_root_hash` under
   `tss_public_key`. Check that `tss_public_key` matches the verifier's tracked trust anchor for the source Hiero
   network.

2. **Verify Merkle paths.** For each `HieroMerklePath`, recompute the root hash by hashing `leaf_value` and
   walking the `path` entries upward. The computed root MUST equal `state_root_hash`.

3. **Extract verified data.** Parse `leaf_value` from each path to obtain the `ClprQueueMetadata` and
   `ClprMessagePayload[]` return values.

## 5.4 Hash Algorithm

The Hiero Merkle tree uses **SHA-384** for internal node hashing. The running hash chain uses SHA-256 (per
cross-platform spec §4.1). The verifier contract must support both:
- SHA-384 for Merkle path verification
- SHA-256 for running hash chain verification (performed by the CLPR Service, not the verifier)

---

# 6. Bundle Reception and Transaction Submission

## 6.1 Pre-Consensus Verification

Per cross-platform spec §4.2, endpoints that submit invalid payloads pay the transaction cost and are not
reimbursed. On Hiero, the endpoint performs a **cost-saving pre-consensus check**: it executes the Connection's
verifier contract in a read-only context against the latest immutable state before submitting the HAPI transaction.
If the verifier rejects the proof, the payload is discarded and the peer endpoint receives a reputation penalty
(see Section 3.2), avoiding the wasted transaction fee.

## 6.2 Transaction Construction

After local verification succeeds, the endpoint constructs a `submitBundle` HAPI transaction:

```
TransactionBody {
  transactionID: {
    accountID: this_node_account_id,
    transactionValidStart: now,
    nonce: unique_per_submission
  }
  clprProcessSyncResult: ClprProcessSyncResultTransactionBody {
    connection_id: payload.connection_id,
    proof_bytes: payload.proof_bytes,
    remote_endpoint_signature: payload.endpoint_signature
  }
}
```

The transaction is signed with the node's account key and submitted to the standard ingest pipeline. Per
cross-platform spec §1.5, the signer's public key is recovered from the ECDSA signature — no explicit
`remote_endpoint_public_key` field is needed.

**Naming note.** The `clprProcessSyncResult` / `ClprProcessSyncResultTransactionBody` HAPI transaction type maps
to the cross-platform spec's `submitBundle` pseudo-API. A rename to `clprSubmitBundle` /
`ClprSubmitBundleTransactionBody` SHOULD be considered for consistency with the cross-platform terminology.

## 6.3 Submission Path

The `submitBundle` transaction enters the consensus pipeline through the **normal gossip path**. It is gossiped to
all nodes, ordered by consensus, and processed in the `handle()` phase like any other HAPI transaction. There is no
fast path or special treatment.

**Rationale.** The normal gossip path ensures:
- All nodes process the bundle in the same order (deterministic state transitions).
- The bundle is covered by the running hash of events, making it part of the auditable consensus history.
- No special infrastructure is needed — the existing transaction pipeline handles everything.

**Deduplication.** Multiple nodes may receive the same sync payload and submit it independently. Per cross-platform
spec §4.2 (replay defense), only the first submission succeeds. Subsequent submissions are rejected, and the
submitting endpoint pays the transaction cost.

## 6.4 Submission Failure Handling

| Failure Mode | Endpoint Behavior |
|-------------|-------------------|
| Transaction rejected at ingest (e.g., throttled) | Retry with exponential backoff (see Section 13) |
| Transaction fails post-consensus (verification failure) | No retry — the proof was invalid. Record peer reputation penalty. |
| Transaction fails post-consensus (replay — messages already received) | No retry — another endpoint submitted first. This is normal. |
| Network partition prevents submission | Buffer the payload and retry when connectivity is restored, up to `clpr.maxPayloadBufferAge` (default: 60 seconds). Discard stale payloads. |

---

# 7. Endpoint Management

## 7.1 Local Endpoint Identity

On Hiero, every consensus node is automatically a CLPR endpoint. The CLPR endpoint module derives its identity
from the active consensus roster. No manual endpoint registration is needed.

**Roster reading.** The `RosterObserver` component reads the active roster from state on startup and watches for
roster change events. Each node's CLPR endpoint identity is derived from:

| CLPR Endpoint Field   | Source                                                                      |
|-----------------------|-----------------------------------------------------------------------------|
| `service_endpoint`    | Node's gRPC service endpoint (IP + CLPR port)                               |
| `tls_certificate`     | Node's RSA TLS certificate (DER-encoded)                                    |
| `ecdsa_signing_key`   | Node's ECDSA secp256k1 public key (33 bytes compressed, or 64 bytes uncompressed) |
| `account_id`          | Node's account ID (serialized as bytes, 20 bytes for Hiero)                 |

The endpoint count from the active roster is used for per-endpoint throttle calculation
(`max_bundles_per_sec / num_endpoints`).

## 7.2 Peer Endpoint Discovery

Peer endpoint discovery is handled **off-chain via gossip**. Peer endpoint data is NOT stored in on-ledger state.

**Seed endpoints.** The peer ledger's `ClprLedgerConfiguration` includes up to 10 seed endpoints. These are obtained
during connection registration (via `verifyConfig`) and updated when ConfigUpdate control messages arrive. Seed
endpoints provide initial bootstrap connectivity.

**Discovery protocol.** When an endpoint needs to discover additional peers, it calls the `discoverEndpoints` RPC
on any known peer endpoint. The peer responds with its known endpoint list for that Connection. Through iterative
discovery, endpoints converge on a full view of the peer network.

**Discovery throttling.** Endpoints SHOULD throttle responses to `discoverEndpoints` to protect against abuse.
A simple rate limit per caller (e.g., 1 request per minute per IP) is sufficient.

**No authentication required for discovery.** The identity of the peer providing discovery information doesn't
matter — endpoints will validate all actual sync data via proofs. If a malicious peer provides false endpoint
information, the worst outcome is wasted connection attempts.

## 7.3 Reciprocity-Based Peer Selection

Endpoints SHOULD prefer to sync with peers that reciprocate by providing messages in return. The endpoint module
tracks a reciprocity score per known peer:

- Peers that provide bundles with messages during sync get a reputation bonus.
- Peers that only request messages without providing any get deprioritized.
- This naturally converges on efficient pairings where both sides do useful work.
- Freeloading cannot be faked — providing a valid bundle with messages requires having actual messages to send.

## 7.4 Configuration Propagation

Changes to the local ledger configuration are propagated by the CLPR Service's lazy config version mechanism
(cross-platform spec §1.3), not by the endpoint module. The endpoint reads peer throttle values from Connection
state on each sync tick, so config updates are automatically reflected.

---

# 8. State Access Patterns

## 8.1 Read Path: Proof Construction

When constructing proofs for outbound syncs, the endpoint reads from the **latest immutable (signed) state**
obtained via `LatestCompleteStateNexus`. This state is:

- Immutable — no concurrent writes.
- Hashed — all Merkle hashes are computed, enabling path extraction.
- Signed — covered by a TSS signature, making it provable.

**Thread safety.** Reading from immutable state requires no synchronization. The `LatestCompleteStateNexus` provides
a reference-counted state reservation, ensuring the state is not garbage collected while the proof constructor holds
a reference. The endpoint MUST release the reservation promptly after proof construction completes.

**Contention.** Proof construction does NOT contend with consensus rounds. The latest signed state is a snapshot
that is never modified. The Platform produces a new signed state after each round, replacing the old one. The
endpoint may hold a reference to an older state while a new one is being produced — this is safe and expected.

## 8.2 Read Path: Sync Orchestration

The sync orchestrator reads Connection metadata (status, message IDs, peer roster) to decide which Connections
need syncing and which peer endpoint to select. These reads also come from the latest immutable state.

## 8.3 Write Path: Transaction Submission

All writes to CLPR state (processing bundles, updating ack IDs, enqueuing Control Messages) happen through
the standard `handle()` phase of the transaction pipeline. The endpoint module does NOT write directly to state.
Instead, it constructs HAPI transactions that, when processed post-consensus, modify state through the CLPR
Service handlers.

This separation ensures:
- All state modifications go through consensus (deterministic across all nodes).
- No concurrent write contention between the endpoint module and the handle pipeline.
- State integrity is maintained by the same mechanisms that protect all other Hiero state.

## 8.4 Working State vs. Signed State

The endpoint module MUST NOT read from working state for proof construction or sync decisions. Working state is
being actively modified by the handle pipeline and:
- Is not covered by a TSS signature (cannot be proven to peers).
- May be inconsistent if read during a handle round.
- May be rolled back if the round fails.

The only acceptable read source for the endpoint module is the latest immutable state.

---

# 9. Configuration

All CLPR endpoint configuration parameters are defined in the `ClprConfig` configuration record, accessible via
the standard Hiero configuration system. Parameters prefixed with `clpr.` in property files.

## 9.1 Core Parameters

| Parameter                        | Type        | Default | Description                                                         |
|----------------------------------|-------------|---------|---------------------------------------------------------------------|
| `clpr.enabled`                   | `boolean`   | `false` | Master enable switch. When false, the endpoint module is dormant.   |
| `clpr.maxBundlesPerSec`          | `int`       | `100`   | Total bundle submission capacity per second. Divided among endpoints. |
| `clpr.discoveryRateLimit`        | `int`       | `1`     | Max `discoverEndpoints` responses per minute per caller.             |

## 9.2 Sync Parameters

| Parameter                  | Type        | Default | Description                                                    |
|----------------------------|-------------|---------|----------------------------------------------------------------|
| `clpr.maxConcurrentSyncs`  | `int`       | `4`     | Maximum simultaneous outbound sync calls per node.             |
| `clpr.syncTimeoutSeconds`  | `int`       | `30`    | Timeout for a single sync gRPC call.                           |
| `clpr.maxPayloadBufferAge` | `long` (ms) | `60000` | Maximum age of a buffered inbound payload before discard.      |
| `clpr.maxStateAgeSeconds`  | `int`       | `30`    | Maximum acceptable age of signed state for proof construction. |

## 9.3 Peer Selection Parameters

| Parameter                       | Type     | Default | Description                                                 |
|---------------------------------|----------|---------|-------------------------------------------------------------|
| `clpr.reputationDecaySeconds`   | `int`    | `300`   | Half-life for peer reputation score decay.                  |
| `clpr.reputationSuccessBonus`   | `double` | `1.0`   | Reputation score added for a successful sync.               |
| `clpr.reputationFailurePenalty` | `double` | `5.0`   | Reputation score subtracted for a failed sync.              |
| `clpr.recentPeerPenaltyFactor`  | `double` | `0.5`   | Weight multiplier for a peer selected on the previous tick. |

## 9.4 Retry Parameters

| Parameter                            | Type   | Default | Description                                                  |
|--------------------------------------|--------|---------|--------------------------------------------------------------|
| `clpr.retryInitialDelayMs`           | `long` | `1000`  | Initial delay for exponential backoff on transient failures. |
| `clpr.retryMaxDelayMs`               | `long` | `30000` | Maximum delay for exponential backoff.                       |
| `clpr.retryMaxAttempts`              | `int`  | `5`     | Maximum retry attempts before circuit breaker opens.         |
| `clpr.circuitBreakerCooldownSeconds` | `int`  | `120`   | Time a circuit breaker stays open before allowing a probe.   |

## 9.5 Throttle Parameters (Published to Peers)

Published throttle values (`ClprThrottles`) are defined in the cross-platform spec §7. On Hiero, these are
exposed as `ClprConfig` properties with the `clpr.` prefix (e.g., `clpr.maxMessagesPerBundle`,
`clpr.maxQueueDepth`, `clpr.maxSyncsPerSec`, etc.). Hiero-specific default values are TBD and will be calibrated
based on benchmarking of proof construction latency and transaction throughput impact (see Findings A-1 and A-3
in Section 14).

---

# 10. Lifecycle Management

## 10.1 Startup

The endpoint module starts during the node's standard initialization sequence, after the Platform layer and
application services have been initialized:

1. **gRPC registration.** Register `ClprEndpointService` on the node's gRPC server. The service is always
   registered regardless of the `clpr.enabled` flag (the flag is checked dynamically at call time).
2. **Configuration check.** If `clpr.enabled` is `false`, the endpoint module remains dormant. No sync threads
   are started. The gRPC service returns `UNAVAILABLE` for all calls.
3. **State initialization.** Read the latest signed state to load all active Connections, their peer rosters, and
   queue metadata into the orchestrator's in-memory structures.
4. **Roster observation.** Register with `LedgerIdentityChangeListener` to receive roster change notifications.
5. **Sync orchestrator start.** Start the sync loop thread pool. After a brief startup delay (to allow state to
   settle), begin monitoring Connections for pending outbound messages.

## 10.2 Shutdown

On graceful shutdown:

1. **Stop the sync orchestrator.** Cancel pending sync ticks. Wait for in-progress syncs to complete (up to
   `syncTimeoutSeconds`).
2. **Drain outbound connections.** Close all outbound gRPC channels.
3. **Unregister gRPC service.** The gRPC server shutdown handles this.
4. **No state cleanup needed.** All CLPR state is persistent in the Merkle tree and survives restart.

## 10.3 Node Restart

On restart, the endpoint module resumes from where it left off:

- All Connection state (queue metadata, endpoint rosters) is read from the signed state.
- The sync orchestrator rebuilds its in-memory scheduling state from the Connection metadata.
- Peer reputation is reset to baseline (it is ephemeral).
- Syncs resume on the next tick.

No messages are lost. The queue persists in state, and peers will re-transmit unacknowledged messages on subsequent
syncs.

## 10.4 State Recovery (Reconnect)

When a node performs a reconnect (re-synchronizes state from peers after falling behind), the CLPR endpoint module:

1. **Remains dormant** during the reconnect process.
2. **Resumes** after reconnect completes and the node has a valid signed state.
3. **Rebuilds** all in-memory structures from the recovered state.

During reconnect, no syncs are initiated. Inbound sync calls are rejected with `UNAVAILABLE`. Peers will retry.

## 10.5 Network Partition Recovery

If the node loses connectivity to all peer endpoints:

1. Outbound syncs fail with timeouts. Circuit breakers open for unreachable peers.
2. Messages continue to queue locally (up to `max_queue_depth`, then backpressure).
3. When connectivity is restored, circuit breakers probe and reopen.
4. Syncs resume and clear the backlog.

No manual intervention is required for partition recovery.

---

# 11. Monitoring and Metrics

## 11.1 Counters

| Metric Name                          | Type    | Description                                                                         |
|--------------------------------------|---------|-------------------------------------------------------------------------------------|
| `clpr.syncs.initiated`               | Counter | Total outbound sync calls initiated.                                                |
| `clpr.syncs.completed`               | Counter | Total outbound sync calls that completed successfully.                              |
| `clpr.syncs.failed`                  | Counter | Total outbound sync calls that failed (timeout, error, verification failure).       |
| `clpr.syncs.received`                | Counter | Total inbound sync calls received from peers.                                       |
| `clpr.bundles.submitted`             | Counter | Total `submitBundle` transactions submitted to the ingest pipeline.                 |
| `clpr.bundles.accepted`              | Counter | Total bundles accepted post-consensus.                                              |
| `clpr.bundles.rejected`              | Counter | Total bundles rejected post-consensus (replay, verification failure, etc.).         |
| `clpr.bundles.rejected.replay`       | Counter | Bundles rejected specifically due to replay (already-received messages).            |
| `clpr.bundles.rejected.verification` | Counter | Bundles rejected due to proof verification failure.                                 |
| `clpr.bundles.rejected.hashMismatch` | Counter | Bundles rejected due to running hash chain mismatch.                                |
| `clpr.bundles.rejected.oversized`    | Counter | Bundles rejected due to exceeding size limits.                                      |
| `clpr.bundles.rejected.closed`       | Counter | Bundles rejected because the Connection is CLOSED.                                  |
| `clpr.messages.sent`                 | Counter | Total messages enqueued in outbound queues (all Connections).                       |
| `clpr.messages.received`             | Counter | Total messages received via bundles (all Connections).                              |
| `clpr.messages.acked`                | Counter | Total messages acknowledged by peers.                                               |
| `clpr.controlMessages.sent`          | Counter | Total Control Messages enqueued (ConfigUpdate).                                     |
| `clpr.discovery.requests`            | Counter | Total `discoverEndpoints` requests received.                                        |
| `clpr.discovery.responses`           | Counter | Total `discoverEndpoints` responses sent.                                           |
| `clpr.misbehavior.detected`          | Counter | Total locally detected misbehavior events (excess frequency, duplicate submission). |

## 11.2 Gauges

| Metric Name                            | Type  | Description                                        |
|----------------------------------------|-------|----------------------------------------------------|
| `clpr.connections.active`              | Gauge | Number of Connections in ACTIVE status.            |
| `clpr.connections.paused`              | Gauge | Number of Connections in PAUSED status.            |
| `clpr.connections.closing`             | Gauge | Number of Connections in CLOSING status.           |
| `clpr.connections.drained`             | Gauge | Number of Connections in DRAINED status.           |
| `clpr.queue.depth.{connection_id}`     | Gauge | Current outbound queue depth per Connection.       |
| `clpr.peers.reachable.{connection_id}` | Gauge | Number of reachable peer endpoints per Connection. |
| `clpr.syncs.inflight`                  | Gauge | Current number of in-progress outbound syncs.      |

## 11.3 Histograms

| Metric Name                        | Type      | Description                                                                          |
|------------------------------------|-----------|--------------------------------------------------------------------------------------|
| `clpr.sync.duration`               | Histogram | Duration of outbound sync calls (ms).                                                |
| `clpr.proof.construction.duration` | Histogram | Time to construct proof bytes (ms).                                                  |
| `clpr.bundle.submission.latency`   | Histogram | Time from receiving a sync payload to submitting the transaction (ms).               |
| `clpr.bundle.consensus.latency`    | Histogram | Time from submitting a bundle transaction to it being processed post-consensus (ms). |
| `clpr.proof.size`                  | Histogram | Size of constructed proof bytes (bytes).                                             |

## 11.4 Health Indicators

| Indicator                                     | Condition                                                                |
|-----------------------------------------------|--------------------------------------------------------------------------|
| `clpr.healthy`                                | At least one active Connection has at least one reachable peer endpoint. |
| `clpr.stateStale`                             | The latest signed state is older than `maxStateAgeSeconds`.              |
| `clpr.allCircuitBreakersOpen.{connection_id}` | All peer endpoints for a Connection have open circuit breakers.          |

---

# 12. Security Considerations

## 12.1 DDoS Protection on the gRPC Endpoint

The `ClprEndpointService` is exposed on the node's gRPC port and is reachable by any network participant. The
following protections apply:

- **Connection-level rate limiting.** The gRPC server SHOULD limit the number of concurrent connections from a
  single IP address. This reuses the node's existing gRPC server configuration.
- **Request-level rate limiting.** The endpoint module tracks inbound sync frequency per `(connection_id,
  remote_endpoint_account_id)` pair. If a peer exceeds `max_syncs_per_sec`, subsequent calls are rejected with
  `RESOURCE_EXHAUSTED` without processing.
- **Payload size enforcement.** The gRPC server enforces `max_sync_bytes` at the frame level. Payloads
  exceeding this size are rejected before deserialization.
- **TLS requirement.** All connections MUST use TLS. Plaintext connections to the CLPR endpoint are rejected.

## 12.2 Peer Authentication

Peer authentication is **proof-based**, not identity-based. There is no on-chain peer endpoint roster to verify
against. Instead:

1. **TLS layer.** Provides transport encryption. No certificate matching against a roster.
2. **Proof verification.** Any peer that provides a valid bundle proof (verified by the Connection's verifier
   contract) is legitimate. The proof is self-authenticating via the verifier.
3. **Payload signature.** The `endpoint_signature` is used for local misbehavior detection (excess frequency
   tracking per signer). It is NOT used for admission control.
4. **Shunning.** Peers that send invalid data or abuse rate limits are shunned locally.

## 12.3 Malicious Payload Protection

Per cross-platform spec §4.2, fabricated messages and replays are detected by the bundle verification algorithm.
On Hiero, the endpoint adds these additional layers:

- **Oversized payloads.** Rejected at the gRPC frame level per `max_sync_bytes` before deserialization.
- **Malformed proofs.** Rejected by the local pre-consensus verifier check (Section 6.1). The peer receives
  a reputation penalty.

## 12.4 Unresponsive Peers

Peer endpoints that are consistently unresponsive are handled by the circuit breaker pattern (Section 13).
Additionally:

- If **all** peer endpoints for a Connection have open circuit breakers, the endpoint module logs a warning and
  emits the `allCircuitBreakersOpen` health indicator.
- The sync orchestrator continues periodic probes (circuit breaker half-open state) to detect recovery.
- No human intervention is required for transient unresponsiveness.
- For **permanent** unresponsiveness (all peers gone), the recovery path is gossip-based discovery via
  seed endpoints in the peer's configuration (cross-platform spec §5.4).

## 12.5 DUPLICATE_BROADCAST (Dropped)

DUPLICATE_BROADCAST was considered but dropped -- on-chain evidence cannot cryptographically prove sync direction
(who initiated), and the economic harm (duplicate gas spend) is mitigated by natural endpoint deduplication
strategies.

## 12.6 Local Misbehavior Detection

Misbehavior detection is strictly local (cross-platform spec §1.6). On Hiero, the endpoint module's inbound
sync frequency tracker (Section 12.1) detects excess-frequency violations by remote peer endpoints. When
detected, the offending peer endpoint is shunned — no cross-ledger report is sent.

## 12.7 Node Account Security

The endpoint module submits `submitBundle` transactions signed by the node's account key. On Hiero, consensus
node accounts are privileged. The endpoint module MUST NOT expose the node's signing keys (neither the ECDSA
secp256k1 endpoint signing key nor the account key) through any external interface. All signing operations happen
within the node process.

---

# 13. Error Handling and Resilience

## 13.1 Retry Strategy

Transient failures (network timeouts, gRPC `UNAVAILABLE`, ingest throttling) are retried with **exponential
backoff with jitter**:

```
delay = min(retryInitialDelayMs * 2^attempt + random(0, retryInitialDelayMs), retryMaxDelayMs)
```

The maximum number of consecutive retries before opening the circuit breaker is `retryMaxAttempts`.

## 13.2 Circuit Breaker Pattern

Each peer endpoint has an independent circuit breaker with three states:

```
         ┌─────────┐    retryMaxAttempts       ┌──────┐
         │ CLOSED  │ ──── failures ───────────> │ OPEN │
         │ (normal)│                            │(fail)│
         └────┬────┘                            └──┬───┘
              ^                                    │
              │         ┌───────────┐              │
              │ success │ HALF-OPEN │  cooldown    │
              └─────────│ (probing) │ <────────────┘
                        └───────────┘
```

- **CLOSED:** Normal operation. Failures increment a counter.
- **OPEN:** All sync attempts to this peer are skipped. After `circuitBreakerCooldownSeconds`, transition to
  HALF-OPEN.
- **HALF-OPEN:** A single probe sync is attempted. If it succeeds, transition to CLOSED. If it fails, transition
  back to OPEN.

## 13.3 Connection-Level Resilience

When all peer endpoints for a Connection have open circuit breakers:

1. Outbound messages continue to queue (up to `max_queue_depth`).
2. The orchestrator continues probing (via half-open circuit breakers) on each tick.
3. If `max_queue_depth` is reached, new `sendMessage` calls are rejected with `MAX_QUEUE_DEPTH_EXCEEDED`.
4. When any peer endpoint recovers, syncs resume and clear the backlog.

## 13.4 Graceful Degradation

| Condition                    | Behavior                                                                                                    |
|------------------------------|-------------------------------------------------------------------------------------------------------------|
| CLPR disabled at runtime     | Endpoint module goes dormant. No syncs, no gRPC service. Existing state preserved.                          |
| No active Connections        | Endpoint module runs but does nothing. No resource consumption beyond the tick timer.                       |
| All Connections PAUSED       | Outbound syncs continue. Inbound bundles with out-of-order responses rejected. `sendMessage` rejected.      |
| Connection PAUSED            | See PAUSED handling below.                                                                                  |
| Connection CLOSING           | See CLOSING handling below.                                                                                 |
| Node catching up (reconnect) | Endpoint dormant until state is recovered.                                                                  |
| Signed state unavailable     | Syncs deferred until signed state is available (startup or reconnect edge case).                            |

**PAUSED Connection handling.** Per cross-platform spec §2.1.1, PAUSED indicates a response ordering violation
detected during `submitBundle`. Inbound bundles containing out-of-order responses are rejected — nothing is
processed, nothing dispatched, no acks updated. On Hiero, the sync orchestrator reads Connection status from
immutable state and:

- Continues initiating outbound syncs for the PAUSED Connection (queued messages and acks still flow).
- Continues accepting inbound sync calls for the PAUSED Connection.
- Rejects inbound bundles with out-of-order responses. When a bundle arrives with correctly ordered responses,
  the Connection auto-resumes to ACTIVE and the bundle is processed normally.

**CLOSING Connection handling.** CLOSING indicates an admin-initiated close that is draining queues. On Hiero:

- Continues initiating outbound syncs for the CLOSING Connection (acks and `CONNECTION_CLOSED` responses drain).
- Continues accepting inbound sync calls for the CLOSING Connection.
- When the peer has acknowledged all outbound messages, the Connection transitions to DRAINED. The
  `ClprQueueMetadata.state` reflects the Connection status and is propagated to the peer via the next sync.
- The CLPR Service generates `CONNECTION_CLOSED` responses for in-flight messages during `handle()`.

**DRAINED Connection handling.** DRAINED indicates this side's outbound queue is fully acknowledged. On Hiero:

- Continues accepting inbound sync calls (the peer may still be draining its side).
- Continues initiating outbound syncs (to propagate the DRAINED status to the peer).
- Transitions automatically to CLOSED when both sides are DRAINED.
- No new messages are generated.

## 13.5 Submission Deduplication

Multiple nodes on the same Hiero network may receive the same sync payload and independently submit
`submitBundle` transactions. Per cross-platform spec §4.2, only the first submission succeeds. To reduce wasted
duplicate submissions, nodes SHOULD incorporate the following Hiero-specific heuristics:

- **Delay before submission.** After receiving a sync payload, wait a random delay in the range
  `[0, sync_interval_ms / numLocalEndpoints]` before submitting the transaction (where `sync_interval_ms` is
  derived from `max_syncs_per_sec` per Section 3.1). This staggers submissions across nodes and increases the
  chance that only one node submits per sync round.
- **Check latest state before submission.** Before submitting, re-read the Connection's `received_message_id`
  from the latest signed state. If it already covers the messages in the received payload, skip submission.

These are best-effort optimizations. The protocol is correct regardless of duplicate submissions.

---

# 14. Inconsistencies and Findings

This section documents places where the Hiero endpoint implementation may conflict with or require clarification
from the cross-platform spec or design doc, and assumptions that should be validated.

## 14.1 Contradictions and Ambiguities

### F-1: Sync Bidirectionality and gRPC Service Definition

The cross-platform spec defines the `ClprEndpointService` as a **unary** RPC:

```protobuf
rpc sync(ClprSyncPayload) returns (ClprSyncPayload);
```

The design doc (Section 3.1.4) describes the sync as "bidirectional within a single call" where both sides
exchange payloads. The unary RPC achieves this — the initiator sends a payload and receives one in return. However,
this means the **responder must compute its payload synchronously** during the RPC handler, which blocks the gRPC
thread for the duration of proof construction. On Hiero, proof construction involves reading the Merkle tree and
building paths, which may take non-trivial time.

**Risk:** If proof construction takes longer than the gRPC deadline, the sync fails. The `syncTimeoutSeconds`
parameter must be calibrated to account for proof construction time on both sides.

**Recommendation:** Benchmark proof construction latency under production state sizes. If it exceeds 1-2 seconds,
consider switching to a bidirectional streaming RPC or a two-call pattern (request/response separated).

### F-2: Pre-Consensus Verifier Execution

The design doc (Section 3.1.4) states: "Endpoints MUST verify the proof before submitting transactions to their
own ledger." On Hiero, the verifier is a native service callback within the node. The endpoint must execute this
verifier locally before submitting the transaction. However, the verifier needs access to the Connection's state
(to know which verifier contract to use, the Connection's status, etc.).

**Assumption:** The local verifier execution reads from the latest immutable state. If the Connection's verifier
has been updated in a more recent (not yet signed) round, the endpoint's local check and the post-consensus
check may use different verifiers. This is acceptable — the post-consensus check is authoritative — but it means
the endpoint might waste a transaction fee if the verifier changed between the local check and consensus.

### F-3: Proof Bytes Portability

Section 5 of this spec defines a protobuf-based `HieroProofBytes` format. However, the cross-platform spec
notes (Section 1.5) that the encoding format is "under review" and Jasper is examining XDR as an alternative
for gas efficiency on Ethereum. If the proof bytes format changes to XDR, the Hiero-side proof construction
code must change, but the Hiero verifier contract interface (`verifyBundle` accepting opaque `bytes`) remains
stable.

**Action needed:** Finalize the proof bytes encoding format before implementing the verifier contracts for
non-Hiero platforms.

### F-4: TSS Signature Availability

This spec assumes TSS signatures are available over every signed state. The current Hiero TSS implementation
may not produce signatures at every round — it depends on the TSS participation rate and threshold. If a TSS
signature is not available for the latest signed state, the proof constructor cannot produce a valid proof.

**Mitigation:** The proof constructor SHOULD fall back to the most recent state that has both a TSS signature
and is within `maxStateAgeSeconds`. If no such state exists, defer syncing.

**Action needed:** Confirm with the Platform team the guarantees around TSS signature frequency.

### F-5: Merkle Tree Hash Algorithm

Section 5.4 states the Merkle tree uses SHA-384. This MUST be confirmed against the actual `MerkleTree`
implementation, as hash algorithm discrepancies between the proof constructor and the verifier contract would
cause all proofs to fail. The Platform's `MerkleTree` has historically used SHA-384 for internal node hashing,
but this should be verified for the specific state elements being proven.

### F-6: `submitBundle` Transaction Payer

The design doc states that the endpoint submitting a bundle pays the transaction cost and is reimbursed by the
Connector's margin. On Hiero, the node's account signs the `submitBundle` transaction. This means the node's
account must have sufficient hbar balance to pay for bundle transactions. For high-traffic Connections, this
could require significant ongoing balance.

**Open question:** Should there be a mechanism to pre-fund node accounts specifically for CLPR bundle
submissions? On Hiero, node accounts are typically funded by the network treasury, but CLPR's economic model
assumes the Connector reimburses the submitting endpoint per-message.

### F-7: Deduplication Inefficiency

On Hiero, a peer endpoint may legitimately contact all Hiero nodes because any node can submit the bundle.
DUPLICATE_BROADCAST was considered as a misbehavior type but dropped (see Section 12.5). Duplicate submissions
are handled by the deduplication heuristics in Section 13.5.

### F-8: Endpoint Bond on Hiero

Per cross-platform spec §2.3, no separate CLPR-specific bond is required on Hiero because consensus nodes are
permissioned. However, the spec notes this may change. The endpoint module should be designed to support optional
bonding even though it is not required initially.

## 14.2 Hiero Architecture Constraints

### A-1: State Read Latency

Proof construction requires reading multiple state elements (queue metadata, individual messages, Merkle paths).
The Merkle tree is stored in `VirtualMap` instances backed by disk. For large queues with many unacked messages,
reading all messages and building Merkle paths may be I/O bound.

**Mitigation:** The `maxMessagesPerBundle` limit bounds the number of messages that must be read. Proof
construction should use batch reads where possible.

### A-2: gRPC Thread Pool Contention

The CLPR endpoint shares the gRPC thread pool with all HAPI services. Proof construction on the responder side
(which happens synchronously during the RPC handler) could block threads needed for regular HAPI traffic.

**Mitigation:** Proof construction SHOULD be offloaded to a dedicated thread pool. The gRPC handler should
accept the incoming payload, dispatch proof construction asynchronously, and return the result when ready
(within the gRPC deadline).

### A-3: Transaction Throughput Impact

Each sync round from each Connection may generate a `submitBundle` transaction from every node on the network.
For a network with N nodes and C active Connections, this is up to `N * C` transactions per sync round. At the
derived sync interval (e.g., if `max_syncs_per_sec = 10` with 30 endpoints → 3-second interval) and a 30-node
network with 5 Connections, this could be up to `30 * 5 / 3 = 50` additional transactions per second when all
Connections have pending messages. The deduplication heuristics (Section 13.5) should reduce this significantly,
but the impact on the node's transaction throughput budget must be evaluated.

## 14.3 Assumptions to Validate

| #   | Assumption                                                                                                              | Validation Path                                         |
|-----|-------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------|
| V-1 | TSS signatures are available for every (or nearly every) signed state.                                                  | Consult Platform team.                                  |
| V-2 | The Merkle tree uses SHA-384 for internal node hashing.                                                                 | Verify against `MerkleTree` implementation.             |
| V-3 | `LatestCompleteStateNexus` provides reference-counted reservations that prevent GC of held state.                       | Verify against Platform SDK source.                     |
| V-4 | The gRPC server supports registering additional services after initial configuration (for conditional CLPR enablement). | Verify against `GrpcServerManager`.                     |
| V-5 | Node TLS certificates are RSA, DER-encoded, and suitable for TLS server/client authentication. Node ECDSA secp256k1 signing keys are available or can be generated. | Verify against node certificate generation code and key management. |
| V-6 | `LedgerIdentityChangeListener` SPI fires on all roster change types (join, leave, key rotation).                        | Verify SPI contract and existing listeners.             |
| V-7 | The ingest pipeline can handle `submitBundle` as a new transaction type without special handling.                       | Verify transaction dispatch and throttle configuration. |
| V-8 | Proof construction from `VirtualMap`-backed state can complete within 2 seconds for typical queue depths.               | Benchmark required.                                     |
