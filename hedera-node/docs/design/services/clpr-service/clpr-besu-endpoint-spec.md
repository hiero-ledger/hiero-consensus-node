# Besu CLPR Endpoint Implementation Specification

This document specifies the design of a CLPR endpoint module for Hyperledger Besu. The module enables any Besu node
operator to participate as a CLPR endpoint on the Ethereum network, orchestrating cross-ledger synchronization and
submitting bundles to the on-chain CLPR Service contract.

The CLPR Service itself is a smart contract deployed on Ethereum. This specification covers the **off-chain endpoint
node** — the software that reads contract state, constructs proofs, communicates with peer endpoints via gRPC, and
submits transactions. For the cross-platform protocol specification, see
[clpr-service-spec.md](clpr-service-spec.md). For the architectural rationale and design overview, see
[clpr-service.md](clpr-service.md).

**Target:** An upstream-mergeable patch to Hyperledger Besu, structured so that CLPR functionality is entirely
optional and imposes zero overhead when disabled.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Besu Plugin Design](#2-besu-plugin-design)
3. [gRPC Server](#3-grpc-server)
4. [Sync Orchestration](#4-sync-orchestration)
5. [Proof Construction (Outbound)](#5-proof-construction-outbound)
6. [Proof Bytes Format](#6-proof-bytes-format)
7. [Bundle Reception and Transaction Submission](#7-bundle-reception-and-transaction-submission)
8. [Endpoint Registration and Bond Management](#8-endpoint-registration-and-bond-management)
9. [Contract State Reading](#9-contract-state-reading)
10. [MEV and Transaction Ordering Considerations](#10-mev-and-transaction-ordering-considerations)
11. [Configuration](#11-configuration)
12. [Lifecycle Management](#12-lifecycle-management)
13. [Monitoring and Metrics](#13-monitoring-and-metrics)
14. [Security Considerations](#14-security-considerations)
15. [Upstream Contribution Strategy](#15-upstream-contribution-strategy)
16. [Inconsistencies and Findings](#16-inconsistencies-and-findings)

---

# 1. Architecture Overview

## 1.1 Integration Model

The CLPR endpoint is implemented as a **Besu plugin** using the official Besu Plugin API (`BesuPlugin`). This is the
correct integration point for several reasons:

- **No core modifications.** The plugin API is Besu's supported extension mechanism. A plugin does not modify Besu's
  consensus, EVM, or P2P layers — it extends the node with additional capabilities. This maximizes the likelihood of
  upstream acceptance.
- **Clean lifecycle.** Plugins participate in Besu's startup/shutdown lifecycle and receive services via dependency
  injection through `BesuContext`.
- **Zero overhead when disabled.** If the plugin JAR is not on the classpath (or is present but disabled via
  configuration), Besu operates identically to a node without CLPR support.
- **Precedent.** Besu already ships optional plugins (e.g., the `BesuEventsPlugin`, permissioning plugins). A CLPR
  plugin follows established patterns.

## 1.2 Module Boundaries

```
┌──────────────────────────────────────────────────────────────┐
│                         Besu Node                             │
│                                                               │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────────────┐ │
│  │  JSON-RPC    │  │  Engine API   │  │  P2P (devp2p)        │ │
│  │  Server      │  │  Server       │  │                      │ │
│  └─────────────┘  └──────────────┘  └──────────────────────┘ │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │                    EVM + World State                      │  │
│  │  ┌─────────────────────────────────────────────────┐    │  │
│  │  │ CLPR Service Contract (on-chain)                 │    │  │
│  │  │  - Connection state, queues, endpoint bonds      │    │  │
│  │  │  - Verifier contracts (separate deployments)     │    │  │
│  │  └─────────────────────────────────────────────────┘    │  │
│  └─────────────────────────────────────────────────────────┘  │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │              CLPR Endpoint Plugin                        │  │
│  │                                                          │  │
│  │  ┌────────────┐  ┌────────────┐  ┌──────────────────┐   │  │
│  │  │ gRPC Server │  │ Sync Engine │  │ Proof Constructor │   │  │
│  │  │ (peer comms)│  │ (scheduler) │  │ (state proofs)    │   │  │
│  │  └────────────┘  └────────────┘  └──────────────────┘   │  │
│  │                                                          │  │
│  │  ┌────────────┐  ┌────────────┐  ┌──────────────────┐   │  │
│  │  │ Tx Builder  │  │ State Reader│  │ Bond Manager      │   │  │
│  │  │ & Submitter │  │ (contract)  │  │ (registration)    │   │  │
│  │  └────────────┘  └────────────┘  └──────────────────┘   │  │
│  │                                                          │  │
│  └─────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

The plugin interacts with Besu through three primary channels:

1. **Blockchain observation** — Subscribes to new block events via `BesuEvents` to trigger state reads and detect
   finality.
2. **State access** — Reads CLPR Service contract storage via Besu's internal `WorldStateArchive` and
   `BlockchainQueries` APIs (not JSON-RPC — direct in-process access avoids serialization overhead).
3. **Transaction submission** — Submits signed transactions to the local transaction pool via
   `TransactionPoolService`.

The plugin does NOT interact with:
- Besu's P2P layer (devp2p). Peer endpoint communication uses gRPC, which is entirely separate.
- Besu's consensus layer. The plugin only reads finalized state.
- Besu's Engine API. The plugin is not involved in block production.

## 1.3 Relationship to Existing Besu Architecture

| Besu Component | CLPR Plugin Interaction |
|---|---|
| JSON-RPC Server | No direct interaction. The plugin MAY expose its own JSON-RPC namespace (`clpr_*`) for status queries, but all contract interaction uses internal APIs. |
| Engine API | None. The plugin is a passive consumer of finalized blocks. |
| EVM | Used indirectly: the plugin calls verifier contracts locally (via `eth_call` semantics) to pre-validate proofs before submitting transactions. |
| World State Trie | Read-only access to CLPR Service contract storage slots for queue metadata, endpoint rosters, and Connection state. |
| Transaction Pool | The plugin submits signed `submitBundle()` transactions to the local mempool. |
| Metrics System | The plugin registers Prometheus metrics via Besu's `MetricsSystem`. |
| P2P Layer | None. CLPR uses its own gRPC transport. |

---

# 2. Besu Plugin Design

## 2.1 Plugin Entry Point

```java
@AutoService(BesuPlugin.class)
public class ClprEndpointPlugin implements BesuPlugin {

    private BesuContext context;
    private ClprEndpointService endpointService;

    @Override
    public void register(BesuContext context) {
        this.context = context;
        // Register CLI options and configuration parameters.
        // No services are started here — only parameter registration.
    }

    @Override
    public void start() {
        if (!clprEnabled()) return;  // No-op when disabled

        // Resolve Besu services from context
        BlockchainService blockchain = context.getService(BlockchainService.class).orElseThrow();
        TransactionPoolService txPool = context.getService(TransactionPoolService.class).orElseThrow();
        BesuEvents events = context.getService(BesuEvents.class).orElseThrow();
        MetricsSystem metrics = context.getService(MetricsSystem.class).orElseThrow();

        // Initialize and start the CLPR endpoint
        endpointService = new ClprEndpointService(blockchain, txPool, events, metrics, config);
        endpointService.start();
    }

    @Override
    public void stop() {
        if (endpointService != null) {
            endpointService.stop();
        }
    }
}
```

## 2.2 Besu Plugin APIs Used

| API | Purpose |
|---|---|
| `BesuPlugin` | Plugin lifecycle (register, start, stop). |
| `BesuContext` | Service locator for accessing Besu internals. |
| `BlockchainService` | Access to the blockchain (blocks, headers, receipts). Used to read block headers for proof construction and to determine finality. |
| `TransactionPoolService` | Submit signed transactions to the local mempool. Used for `submitBundle()` calls. |
| `BesuEvents` | Subscribe to blockchain events (new block added, block reorg). Drives the sync loop and state refresh. |
| `MetricsSystem` | Register Prometheus counters, gauges, and histograms. |
| `OperationTracer` (optional) | Trace EVM execution for local `eth_call` simulation of verifier contracts. |

**Note on WorldStateArchive:** The current Besu Plugin API does not expose direct world state trie access. The
plugin MUST use one of:

- **`BlockchainQueries`** (if exposed via `BesuContext` in the target Besu version) for `eth_call` and `eth_getProof`
  semantics with in-process efficiency.
- **Localhost JSON-RPC** as a fallback, calling `eth_getProof` and `eth_call` on the node's own JSON-RPC endpoint.
  This adds serialization overhead but requires no Besu API changes.
- **A new `WorldStateService`** exposed through `BesuContext` — this would be the ideal upstream contribution,
  providing plugins with read-only access to the world state archive. This should be proposed as a prerequisite PR.

The recommended approach is to contribute a minimal `WorldStateService` to the Besu Plugin API that exposes:
- `getStorageAt(address, slot, blockNumber)` — read a single storage slot.
- `getProof(address, storageKeys, blockNumber)` — get a Merkle-Patricia trie proof.
- `call(callParams, blockNumber)` — execute a read-only call against state at a given block.

## 2.3 Source Tree Location

```
besu/
  plugins/
    clpr/
      src/main/java/org/hyperledger/besu/plugins/clpr/
        ClprEndpointPlugin.java          # Plugin entry point
        ClprConfiguration.java           # Configuration POJO
        grpc/
          ClprGrpcServer.java            # gRPC server hosting ClprEndpointService
          ClprSyncHandler.java           # sync() RPC implementation
        sync/
          SyncOrchestrator.java          # Periodic sync loop scheduler
          ConnectionSyncTask.java        # Per-Connection sync logic
          PeerSelector.java              # Peer endpoint selection strategy
        proof/
          EthereumProofConstructor.java  # Builds proof_bytes from Ethereum state
          ProofBytesCodec.java           # Serialization/deserialization of proof format
          SyncCommitteeTracker.java      # Tracks Ethereum sync committee rotations
        tx/
          BundleTransactionBuilder.java  # Constructs submitBundle() transactions
          TransactionSubmitter.java      # Manages nonces, gas, submission
          GasPriceStrategy.java          # EIP-1559 gas pricing
        state/
          ClprContractReader.java        # Reads CLPR Service contract state
          ConnectionCache.java           # Caches Connection metadata
          EndpointRosterCache.java       # Caches peer endpoint rosters
        bond/
          EndpointBondManager.java       # Registration and bond management
        metrics/
          ClprMetrics.java               # Prometheus metric definitions
      src/main/proto/
        clpr_endpoint.proto              # gRPC service definition (from spec)
      src/test/java/...                  # Unit and integration tests
      build.gradle                       # Plugin build configuration
```

## 2.4 Configuration

The plugin is configured via Besu's standard mechanisms. The operator may use either:

- **TOML configuration file** (`besu.toml`):
  ```toml
  [clpr]
  enabled = true
  contract-address = "0x..."
  signing-key-file = "/path/to/key"
  grpc-port = 9545
  ```

- **CLI flags** (override TOML):
  ```
  --clpr-enabled=true
  --clpr-contract-address=0x...
  --clpr-signing-key-file=/path/to/key
  --clpr-grpc-port=9545
  ```

See [Section 11](#11-configuration) for the full parameter list.

---

# 3. gRPC Server

## 3.1 Server Lifecycle

The CLPR gRPC server starts during the plugin's `start()` phase, after Besu's core services are initialized. It
binds to a configurable port (default: `9545`) on a configurable interface (default: `0.0.0.0`).

The gRPC server is **independent** of Besu's JSON-RPC and Engine API servers. It runs in its own Netty event loop
group and does not share thread pools with Besu's existing servers. This isolation ensures that CLPR gRPC traffic
cannot starve Besu's RPC or consensus-layer communication.

## 3.2 Service Definition

The server hosts the `ClprEndpointService` gRPC service as defined in the cross-platform specification:

```protobuf
service ClprEndpointService {
    rpc sync(ClprSyncPayload) returns (ClprSyncPayload);
}
```

This is a unary RPC. The initiator sends a `ClprSyncPayload` and receives one in response. The exchange is
symmetric: each side provides its proof bytes for the other's ledger.

## 3.3 TLS Configuration

The gRPC server MUST use TLS. The endpoint's RSA signing certificate (DER-encoded RSA public certificate, as
specified in `ClprEndpoint.signing_certificate`) doubles as the TLS server certificate. This provides:

- **Transport encryption** — All gRPC traffic is encrypted.
- **Server authentication** — Peer endpoints can verify they are connecting to the endpoint they expect by checking
  the certificate against the on-chain endpoint roster.

Because the RSA signing certificate is reused as the TLS certificate, the `--clpr-signing-certificate` parameter
and the `clpr.tls.certificate-file` parameter refer to the same certificate. Operators SHOULD configure only
`--clpr-signing-certificate`; the TLS certificate configuration is derived from it. If both are specified and they
differ, the plugin MUST fail with a configuration error. A future version may allow separate TLS and signing
certificates if a use case arises (e.g., TLS termination at a load balancer with a separate cert), but for now
they MUST be identical.

Configuration:
```toml
[clpr.tls]
# Optional: require client certificates for mutual TLS
client-auth = "REQUIRE"  # or "OPTIONAL" or "NONE"
```

When `client-auth = "REQUIRE"`, the server only accepts connections from endpoints whose certificates appear in a
known Connection's peer endpoint roster. This provides strong peer authentication at the transport layer.

## 3.4 Coexistence with Existing Servers

| Server | Default Port | Protocol |
|---|---|---|
| JSON-RPC | 8545 | HTTP/WebSocket |
| Engine API | 8551 | HTTP (authenticated) |
| GraphQL | 8547 | HTTP |
| P2P (devp2p) | 30303 | TCP/UDP |
| **CLPR gRPC** | **9545** | **gRPC over TLS** |

The CLPR gRPC port MUST NOT conflict with any existing Besu port. The default of `9545` is chosen to be in the same
range as Besu's RPC ports but clearly distinct.

## 3.5 Message Size Limits

The gRPC server MUST configure `maxInboundMessageSize` to accommodate `max_sync_payload_bytes` from the ledger's
CLPR configuration. The default gRPC limit (4 MB) may be insufficient for bundles containing many messages. The
plugin reads `max_sync_payload_bytes` from the on-chain configuration and configures the gRPC server accordingly.

If a `max_sync_payload_bytes` value is not yet available (e.g., the CLPR contract has not been configured), a
conservative default of 16 MB is used, with a log warning that the operator should verify the on-chain configuration.

---

# 4. Sync Orchestration

## 4.1 Sync Loop Design

The sync engine is a scheduled task that runs independently for each Connection the endpoint is registered on. The
design uses a single-threaded scheduler per Connection with a configurable period:

```
For each Connection C where this node is a registered endpoint:
    Every connectionFrequency milliseconds:
        0. Read C's Connection status from contract state (or cached state).
             - If SEVERED or HALTED: skip this Connection entirely. Do not attempt sync.
               HALTED does not automatically recover — admin must intervene.
             - If PAUSED: the endpoint can still receive bundles (as responder) and send
               acks, but there are no new outbound messages to push. Skip outbound sync
               initiation but remain available as a responder.
             - If ACTIVE: proceed with normal sync below.
        1. Read C's outbound queue metadata from contract state.
        2. If there are unacked messages (next_message_id > peer's received_message_id):
             a. Construct proof_bytes from local state.
             b. Select a peer endpoint from C's peer roster.
             c. Open gRPC connection to peer, exchange payloads.
             d. Pre-validate received proof_bytes locally (call verifier via eth_call).
             e. If valid, construct and submit a submitBundle() transaction.
        3. If there are NO unacked messages:
             a. Still sync (to exchange acks and receive peer's messages).
               A submitBundle() transaction is still needed if the ack updates
               acked_message_id — this allows the contract to delete acknowledged
               messages from the outbound queue.
             b. Construct a minimal proof_bytes covering only queue metadata.
```

## 4.2 Peer Selection

The endpoint selects a peer from the Connection's endpoint roster. The selection strategy balances reliability with
Sybil resistance:

1. **Weighted random selection.** Each peer is assigned a weight based on past sync success rate. New peers start
   with a default weight. Peers that consistently deliver valid bundles have higher weights.
2. **Randomization.** Selection is randomized within the weight distribution to prevent persistent pairing, which
   would be exploitable by an adversary controlling specific endpoints.
3. **Exclusion of known-bad peers.** Peers that have failed authentication (TLS handshake failure), returned invalid
   proofs, or timed out repeatedly are temporarily excluded with exponential backoff.
4. **Round-robin fallback.** If weighted selection is not possible (e.g., no history), round-robin with random
   starting offset is used.

The endpoint roster is read from the CLPR Service contract state (see [Section 9](#9-contract-state-reading)) and
cached locally with periodic refresh.

## 4.3 Concurrency Model

- Each Connection gets its own sync task running on a `ScheduledExecutorService`.
- Sync tasks for different Connections run **concurrently** — they are independent and share no state.
- Within a single Connection, syncs are **serialized** — a new sync does not start until the previous one completes
  (or times out). This prevents duplicate submissions and simplifies nonce management.
- The gRPC call has a configurable timeout (default: 30 seconds). If the peer does not respond, the call is
  cancelled and the next sync attempt will select a (potentially different) peer.

## 4.4 Multiple Connection Handling

A single Besu node may be registered as an endpoint on multiple Connections (e.g., Connections to different peer
ledgers, or multiple Connections to the same peer with different commitment levels). Each Connection is handled
independently:

- Separate sync scheduler.
- Separate peer roster cache.
- Separate nonce sequence (all sharing the same signing key / account, so nonce management must be coordinated —
  see [Section 7.5](#75-nonce-management)).
- Separate metrics (tagged by `connection_id`).

## 4.5 Sync Frequency Governance

The sync frequency is configurable per the `connectionFrequency` parameter (default: 5000 ms). The endpoint MUST
respect the peer ledger's `max_syncs_per_sec` advisory limit. If the configured frequency would exceed the peer's
limit, the endpoint MUST throttle down to comply. Persistent violation is reportable misbehavior (spec Section 8.4).

The endpoint tracks its own sync frequency per Connection per peer and ensures it stays within bounds. When multiple
endpoints from the same ledger are syncing with the same peer, the per-endpoint frequency is what matters — the
`max_syncs_per_sec` is an aggregate limit, so individual endpoints should divide it by the estimated number of active
local endpoints on the Connection.

**Lazy config propagation awareness.** The endpoint uses the **last known** peer config values from Connection state
when enforcing throttle limits. Peer configuration updates propagate lazily — a `ConfigUpdate` is enqueued on the
next interaction, not pushed immediately. This means the peer's `max_syncs_per_sec` value visible in on-chain state
may lag the peer's actual current configuration. This lag is acceptable: the cross-platform spec defines a tolerance
band for throttle enforcement, and the endpoint reads peer throttle values from Connection state on each sync tick,
so lazy config updates are automatically reflected once the `ConfigUpdate` control message has been processed by the
contract.

---

# 5. Proof Construction (Outbound)

When this Besu node initiates or responds to a sync for a Connection where Ethereum is the source ledger, it must
construct `proof_bytes` that the peer ledger's verifier contract can interpret and validate.

## 5.1 What is Proven

> **Terminology note:** "Outbound proof" in the section title means "proof of Ethereum state sent to the peer."
> The proof is always about Ethereum's own state (CLPR Service contract storage on the EVM chain). It is produced
> by this Besu endpoint and consumed by the peer ledger's verifier contract.

The proof must establish, with cryptographic assurance rooted in Ethereum's consensus, the following facts about the
CLPR Service contract's state at a specific block:

1. **Queue metadata** — The Connection's `next_message_id`, `sent_running_hash`, `received_message_id`, and
   `received_running_hash` values.
2. **Message payloads** — The serialized `ClprMessagePayload` for each unacked message in the outbound queue (from
   `acked_message_id + 1` through `next_message_id - 1`, capped at `max_messages_per_bundle`).
3. **Running hash per message** — The `running_hash_after_processing` for each message, enabling hash chain
   verification.

## 5.2 Proof Construction Pipeline

The proof construction follows these steps:

### Step 1: Determine the Reference Block

Select the block to prove against. The choice depends on the Connection's commitment level:

| Commitment Level | Block Selection |
|---|---|
| `finalized` | Latest finalized block (from the beacon chain finalized checkpoint). |
| `safe` | Latest safe block (justified but not yet finalized). |
| `latest` | Latest canonical block. |

The commitment level is a property of the verifier contract deployed for the Connection. The endpoint MUST know which
commitment level to use (configured per Connection or read from contract metadata).

**Recommendation:** Default to `finalized` for safety. Support `safe` and `latest` as opt-in configurations per
Connection for lower-latency use cases that accept reorg risk.

### Step 2: Read Contract Storage

Using Besu's internal state access (or `eth_getProof` as fallback), read the storage slots from the CLPR Service
contract that contain:

- The Connection struct fields (queue metadata).
- The message entries in the queue mapping for all unacked messages.

The specific storage slot layout depends on the Solidity compiler's storage packing for the CLPR Service contract.
The plugin MUST be configured with the contract's storage layout (either as a hardcoded ABI-derived mapping or as a
configuration file). See [Section 6.3](#63-storage-slot-computation) for slot computation details.

### Step 3: Obtain Merkle-Patricia Trie Proofs

For each storage slot identified in Step 2, obtain the MPT proof path from the account's storage trie root to the
storage slot value. This is equivalent to `eth_getProof(contractAddress, storageKeys, blockNumber)`.

Using Besu's internal API, this can be done without JSON-RPC serialization:

```java
// Pseudocode — actual Besu API calls depend on version
WorldState state = worldStateArchive.get(blockHeader.getStateRoot());
Account account = state.get(clprContractAddress);
StorageProof proof = account.getStorageProof(storageKeys);
```

### Step 4: Obtain the Block Header and Execution Payload

The proof includes the execution layer block header (containing the `stateRoot` that anchors all MPT proofs).

### Step 5: Obtain Beacon Chain Attestation

The proof must include the beacon chain's attestation to the execution payload, which proves that Ethereum's
consensus layer has finalized (or justified) the block. This requires:

- The **beacon block header** corresponding to the execution block.
- The **sync committee BLS aggregate signature** over the beacon block header.
- The **sync committee public keys** (or a reference to the known committee for the relevant period).

This data is obtained from the beacon chain, either via:

- **Besu's Engine API integration** — If the consensus layer client (e.g., Teku, Prysm, Lighthouse) exposes the
  sync committee data to Besu, the plugin can read it in-process.
- **Beacon API** — The plugin calls the consensus layer client's REST API
  (`/eth/v1/beacon/light_client/updates`, `/eth/v1/beacon/headers`, etc.) to obtain sync committee signatures.

**Recommendation:** Use the Beacon API. It is standardized, well-supported by all consensus clients, and does not
require modifying Besu's Engine API integration. The Beacon API endpoint URL is a required configuration parameter
(`clpr.beacon-api-url`).

### Step 6: Assemble proof_bytes

Combine all components into the opaque `proof_bytes` blob using the format defined in [Section 6](#6-proof-bytes-format).

## 5.3 Responding to Incoming Syncs

When this endpoint receives an incoming sync (the responder role), it performs the same proof construction
on-demand. The responder:

1. Reads the current state at the latest block meeting the Connection's commitment level.
2. Constructs proof_bytes as described above.
3. Signs `(connection_id || proof_bytes)` with the endpoint's signing key.
4. Returns the `ClprSyncPayload` to the initiator.

The responder's proof construction SHOULD be pre-computed if possible. The plugin MAY maintain a cache of
pre-computed proofs that are refreshed every block, so that responding to a sync is a cache lookup rather than an
on-demand computation.

---

# 6. Proof Bytes Format

## 6.1 Overview

The `proof_bytes` blob is what peer ledger verifier contracts parse and validate. It is opaque to the CLPR protocol
but must be precisely defined for interoperability between Ethereum endpoint nodes and verifier contracts deployed on
peer ledgers (Hiero, Solana, other EVM chains).

The format is designed to provide a complete, self-contained proof that the claimed CLPR Service contract state
existed on Ethereum at a specific finalized (or justified, or latest) block.

## 6.2 Structure

The proof_bytes are encoded as an RLP-encoded or ABI-encoded structure (the choice depends on the target verifier
contract's preference — ABI encoding is recommended for EVM target verifiers, SSZ for beacon chain structures).

**Recommendation:** Use a hybrid encoding — SSZ for beacon chain structures (which are natively SSZ) and RLP or
ABI-encoding for execution layer structures. The verifier contract specification determines the exact encoding. For
this document, we define the logical structure:

```
EthereumProofBytes {
    // === Beacon Layer ===

    // The beacon block header that the sync committee actually signs.
    // SSZ-encoded BeaconBlockHeader (slot, proposer_index, parent_root, state_root, body_root).
    // The sync committee attests to signing_root(attested_header).
    attested_header: bytes

    // The beacon block header containing the execution payload.
    // Linked from attested_header's state root via the finality_branch.
    // SSZ-encoded BeaconBlockHeader.
    finalized_header: bytes

    // Merkle branch from attested_header.state_root to finalized_root,
    // proving the finalized checkpoint referenced by the attested header.
    // This is the generalized index path in the BeaconState for
    // state.finalized_checkpoint.root.
    finality_branch: bytes[]

    // The sync committee's BLS aggregate signature over signing_root(attested_header).
    // This is the cryptographic root of trust — it proves the beacon chain
    // attested to the attested_header, which in turn commits to the finalized checkpoint.
    sync_committee_signature: bytes(96)  // BLS12-381 compressed signature

    // Bitmap indicating which sync committee members signed (512 bits).
    sync_committee_bits: bytes(64)

    // The sync committee's aggregate public key for the current period.
    // Verifiers that track committee rotations can derive this; included
    // here for verifiers that do not maintain sync committee state.
    sync_committee_pubkeys: bytes  // Optional; may be omitted if verifier tracks committees

    // The slot of the attested header (for committee period derivation).
    attested_slot: uint64

    // === Execution Layer ===

    // The execution payload header, proving the link between the finalized beacon block
    // and the execution layer state root.
    // On post-Deneb Ethereum, this is part of the beacon block body.
    // The verifier checks: finalized_header.body_root commits to this payload,
    // and this payload contains the state_root anchoring the MPT proofs.
    execution_payload_header: bytes  // SSZ-encoded ExecutionPayloadHeader

    // The Merkle branch proving that execution_payload_header is included in
    // the finalized beacon block body (body_root in finalized_header).
    execution_payload_branch: bytes[]

    // === Account Proof ===

    // The MPT proof for the CLPR Service contract's account in the state trie.
    // Proves: stateRoot -> account (nonce, balance, storageRoot, codeHash).
    account_proof: bytes[]  // Array of RLP-encoded trie nodes

    // The CLPR Service contract address (for the verifier to verify the proof path).
    contract_address: bytes(20)

    // === Storage Proofs ===

    // MPT proofs for each storage slot read from the CLPR Service contract.
    // Each entry maps a storage key to its proof path and value.
    storage_proofs: StorageProofEntry[]

    // === Extracted Data ===

    // The queue metadata extracted from the proven storage slots.
    // Included explicitly so the verifier can return it without re-parsing.
    queue_metadata: ClprQueueMetadata  // protobuf-encoded

    // The message payloads extracted from the proven storage slots.
    // Ordered by message_id (ascending).
    messages: ClprMessagePayload[]  // protobuf-encoded, one per message
}

StorageProofEntry {
    key: bytes(32)      // Storage slot key (keccak256-derived)
    value: bytes(32)    // Storage slot value
    proof: bytes[]      // MPT proof path (array of RLP-encoded trie nodes)
}
```

## 6.3 Storage Slot Computation

The CLPR Service contract stores Connection state and message queue entries in Solidity mappings. The storage slot
for each piece of data is computed using Solidity's storage layout rules:

- **Simple mapping `mapping(bytes32 => T)`:** `slot = keccak256(key . base_slot)` where `.` is concatenation and
  `base_slot` is the declaration-order slot of the mapping.
- **Nested mapping `mapping(bytes32 => mapping(uint64 => T))`:** `slot = keccak256(inner_key . keccak256(outer_key . base_slot))`
- **Struct fields:** Consecutive slots starting from the mapping's computed base.

The exact slot layout depends on the CLPR Service contract's Solidity source. The plugin MUST be compiled against a
known contract version, and the storage layout MUST be verified against the deployed contract's storage. A
`clpr-storage-layout.json` file (generated by `solc --storage-layout`) SHOULD be distributed with the plugin.

**Example:** To read a Connection's `next_message_id` where connections are stored in
`mapping(bytes32 => Connection)` at slot 3, and `next_message_id` is the 5th field (offset 4):

```
base = keccak256(connection_id . uint256(3))
slot = base + 4
```

## 6.4 Proof Verification Flow (on peer verifier contract)

A verifier contract on a peer ledger (e.g., a Solidity contract on Hiero EVM, or a native Hiero system contract)
verifies the proof as follows:

1. **Verify sync committee signature.** Check that the BLS aggregate signature over `signing_root(attested_header)`
   is valid for the current sync committee. The verifier must track sync committee rotations (every ~27 hours /
   256 epochs).
2. **Verify finality branch.** Walk the `finality_branch` Merkle path from `attested_header.state_root` to
   `finalized_root`, confirming that the attested header commits to the finalized checkpoint containing
   `finalized_header`. This links the sync committee's attestation to the finalized block.
3. **Verify execution payload inclusion.** Check that the `execution_payload_header` is committed to by the
   `finalized_header.body_root` using the `execution_payload_branch`. The `execution_payload_branch` proves
   inclusion of the `ExecutionPayloadHeader` within the `finalized_header.body_root`. The generalized index for
   the execution payload in the BeaconBlockBody Merkle tree is **25** (binary 11001) for post-Deneb Ethereum.
   This index may change in future hard forks; verifier contracts MUST be parameterized by fork version.

   **Beacon API source:** The standard Beacon API endpoints (`/eth/v1/beacon/light_client/updates`,
   `/eth/v1/beacon/light_client/finality_update`) include an `execution_branch` field in post-Capella responses.
   If the Beacon API does not provide this branch directly (e.g., for specific block queries), the Besu endpoint
   MUST compute it locally from the full `BeaconBlockBody` SSZ tree. The Besu execution client has access to
   beacon block bodies via the Engine API or consensus client RPC.

4. **Extract state root.** The `execution_payload_header` contains the `state_root`.
5. **Verify account proof.** Walk the `account_proof` MPT path from `state_root` to the CLPR Service contract's
   account, extracting the `storageRoot`.
6. **Verify each storage proof.** Walk each `storage_proofs` entry's MPT path from `storageRoot` to the storage
   slot value.
7. **Extract and decode data.** Parse the proven storage slot values to reconstruct `ClprQueueMetadata` and
   `ClprMessagePayload[]`.
8. **Return** the verified metadata and messages.

## 6.5 Commitment Level Enforcement

The verifier contract MUST enforce the commitment level by checking the attested slot against the beacon chain's
finality state:

- **`finalized`:** The attested slot must be at or before the latest finalized checkpoint. The verifier tracks the
  finalized checkpoint via light client updates.
- **`safe`:** The attested slot must be at or before the latest justified checkpoint.
- **`latest`:** No finality check — any valid sync committee signature suffices. Vulnerable to reorgs.

## 6.6 Sync Committee Tracking

The endpoint plugin must track Ethereum's sync committee rotations to include the correct committee data in proofs.
The sync committee rotates every 256 epochs (~27.3 hours). The plugin:

1. Subscribes to light client updates from the Beacon API.
2. Maintains the current and next sync committee public keys.
3. Includes the committee period in the proof so verifiers know which committee to validate against.

The `SyncCommitteeTracker` component handles this. The Beacon API returns `LightClientUpdate` objects (via
`/eth/v1/beacon/light_client/updates`) that bundle the attested header, finalized header, sync committee signature,
and participation bits together. The tracker models this directly rather than indexing signatures by block root:

```java
public class SyncCommitteeTracker {
    // Fetches and caches sync committee data from the Beacon API.
    // Returns the committee for a given slot.
    // Convenience method for committee public key lookup. Returns the sync
    // committee whose term covers the given slot. Note: for full proof
    // construction, use getFinalityUpdateForBlock() or getLatestFinalityUpdate()
    // which return the complete LightClientUpdate / LightClientFinalityUpdate
    // required by Section 5.2 (proof construction steps).
    public SyncCommittee getCommitteeForSlot(long slot);

    // Returns the current finalized slot.
    public long getFinalizedSlot();

    // Fetches LightClientUpdate objects from the Beacon API
    // (/eth/v1/beacon/light_client/updates?start_period=...&count=...).
    // Each update contains: attested_header, finalized_header, finality_branch,
    // sync_committee_signature, sync_committee_bits, and (optionally) the
    // next_sync_committee with its branch.
    public LightClientUpdate getUpdateForPeriod(long syncCommitteePeriod);

    // Returns the latest finality update from the Beacon API
    // (/eth/v1/beacon/light_client/finality_update). This provides the most
    // recent attested_header + finalized_header + signature for proof construction.
    public LightClientFinalityUpdate getLatestFinalityUpdate();

    // Returns a finality update targeting a specific block/slot. The returned
    // LightClientFinalityUpdate contains the complete package needed for proof
    // construction: attested header, finalized header, finality branch,
    // sync committee aggregate signature (BLS12-381), and participation bits.
    // If the target slot is not yet finalized, returns empty.
    public Optional<LightClientFinalityUpdate> getFinalityUpdateForBlock(long slot);
}

// Models the Beacon API LightClientUpdate structure.
public record LightClientUpdate(
    BeaconBlockHeader attestedHeader,
    BeaconBlockHeader finalizedHeader,
    byte[][] finalityBranch,
    byte[] syncCommitteeSignature,    // BLS12-381 aggregate signature
    byte[] syncCommitteeBits,          // 512-bit participation bitmap
    SyncCommittee nextSyncCommittee,   // present during committee transitions
    byte[][] nextSyncCommitteeBranch
) {}
```

---

# 7. Bundle Reception and Transaction Submission

## 7.1 Receiving a Peer's Proof

When the endpoint receives a `ClprSyncPayload` from a peer (either as initiator receiving the response, or as
responder receiving the initial payload):

1. **Deserialize** the `ClprSyncPayload`.
2. **Extract** `connection_id`, `proof_bytes`, `endpoint_signature`, `endpoint_public_key`.
3. **Pre-validate** the proof locally by simulating a call to the verifier contract:
   ```
   verifierContract.verifyBundle(proof_bytes)  // via eth_call, no on-chain execution
   ```
   If the verifier rejects the proof, discard the payload and log a warning. Do NOT submit a transaction — the
   endpoint would pay gas for a guaranteed revert.
4. If valid, proceed to transaction construction.

## 7.2 Transaction Construction

The endpoint constructs an Ethereum transaction calling `submitBundle()` on the CLPR Service contract:

```solidity
function submitBundle(
    bytes32 connection_id,
    bytes calldata proof_bytes,
    bytes calldata remote_endpoint_signature,
    bytes calldata remote_endpoint_public_key
) external;
```

The ABI-encoded calldata is:

```
selector: keccak256("submitBundle(bytes32,bytes,bytes,bytes)")[:4]
data: abi.encode(connection_id, proof_bytes, remote_endpoint_signature, remote_endpoint_public_key)
```

The transaction is a standard EIP-1559 (Type 2) transaction:

```
{
    type: 2,
    chainId: <network chain ID>,
    nonce: <managed by NonceTracker>,
    maxFeePerGas: <see gas strategy>,
    maxPriorityFeePerGas: <see gas strategy>,
    gasLimit: <estimated + buffer>,
    to: <CLPR Service contract address>,
    value: 0,
    data: <ABI-encoded submitBundle call>,
    accessList: <precomputed from storage keys>
}
```

## 7.3 Gas Estimation

Gas estimation is performed by simulating the transaction via `eth_estimateGas` (or the equivalent Besu internal
API):

```java
long estimatedGas = txSimulator.estimateGas(submitBundleTx);
long gasLimit = (long)(estimatedGas * GAS_BUFFER_MULTIPLIER);  // e.g., 1.2x
```

The `GAS_BUFFER_MULTIPLIER` (default: 1.2) accounts for minor state changes between estimation and inclusion. If
estimation fails (e.g., the verifier contract reverts during simulation), the transaction is NOT submitted.

**Gas budget considerations.** `submitBundle()` gas cost scales with:
- Verifier contract execution (BLS signature verification is ~100K-200K gas; MPT proof verification is ~5K-20K gas
  per proof node).
- Number of messages in the bundle (each message requires hash computation, Connector lookup, and application
  dispatch).
- Application callback gas (bounded by `max_gas_per_message`).

A rough estimate for a bundle of 10 messages: 500K-2M gas for verification + 10 * `max_gas_per_message` for
dispatch. At `max_gas_per_message` = 200K, total is ~2.5M-4M gas. This is well within Ethereum's 30M block gas
limit but represents significant cost at high gas prices.

## 7.4 Gas Price Strategy

The plugin implements an EIP-1559 gas price strategy:

```java
public class GasPriceStrategy {
    // Returns (maxFeePerGas, maxPriorityFeePerGas) for a submitBundle transaction.
    //
    // Base fee: read from the latest block header.
    // Priority fee: configurable (default: 2 gwei), with optional dynamic adjustment
    //   based on recent inclusion times.
    // Max fee: baseFee * multiplier + priorityFee (multiplier default: 2.0,
    //   accommodating up to one base fee doubling in the worst case).
    //
    // The operator can set a hard cap (clpr.max-gas-price) to prevent the endpoint
    // from submitting transactions during gas price spikes.
    public GasPrice computeGasPrice(BlockHeader latestBlock);
}
```

**Profitability check.** Before submitting, the endpoint SHOULD check whether the expected reimbursement (Connector
margin) exceeds the gas cost. If not, the endpoint MAY defer submission to a later block when gas is cheaper,
provided the messages are not time-sensitive. This is a configurable behavior (`clpr.skip-unprofitable-bundles`).

The profitability check SHOULD also consider whether the Connectors on the Connection have sufficient funds to
reimburse the endpoint for gas costs. If a Connector's balance is too low to cover the expected reimbursement, the
endpoint may end up paying gas for message delivery without being compensated. The endpoint can read Connector
balances from the contract state and factor this into its submission decision.

## 7.5 Nonce Management

The plugin maintains a `NonceTracker` that serializes nonce assignment for the endpoint's account:

```java
public class NonceTracker {
    private final AtomicLong pendingNonce;

    // Returns the next nonce and increments the pending counter.
    // If a transaction is dropped or replaced, the nonce is recycled.
    public synchronized long nextNonce();

    // Called when a transaction is confirmed (block inclusion).
    // Updates the base nonce from on-chain state.
    public void onTransactionConfirmed(long confirmedNonce);

    // Called when a transaction is dropped from the mempool.
    // Decrements pending nonce to recycle.
    public void onTransactionDropped(long droppedNonce);

    // Periodically syncs with on-chain nonce to recover from inconsistencies.
    public void syncWithChain();
}
```

When the endpoint is registered on multiple Connections, all Connections share the same `NonceTracker` (since they
use the same signing account). Nonce assignment is synchronized to prevent conflicts.

## 7.6 Transaction Lifecycle

After submission:

1. **Monitor for inclusion.** The plugin watches for the transaction's inclusion in a block via `BesuEvents` (new
   block added). It checks receipts for the transaction hash.
2. **Handle revert.** If the transaction is included but reverted (status = 0), log the revert reason. Common
   reasons:
   - Replay: another endpoint already submitted a bundle covering the same messages. This is expected and benign.
   - Verification failure: the proof was invalid (should not happen if pre-validation passed, but possible if the
     block was reorged between pre-validation and inclusion).
   - Connection paused/severed: state changed between estimation and inclusion.
   - Connection HALTED: the Connection has been permanently halted due to a response ordering violation. No
     automatic recovery is possible — admin must intervene. The endpoint MUST stop syncing for this Connection.

   **Important distinction — bundle verification failure vs. HALTED:**
   - **Bundle verification failures** (bad hash chain, replay, oversized payload) result in a simple revert with
     no state change. The Connection remains ACTIVE. The endpoint should retry with fresh data on the next sync
     cycle.
   - **Response ordering violation** causes the contract to transition the Connection to HALTED. This is
     permanent until an administrator intervenes. The endpoint MUST detect the HALTED status and stop all sync
     activity for the affected Connection.

   The endpoint SHOULD perform a pre-submission contract read to check the Connection status before submitting.
   Detecting HALTED or SEVERED status via a cheap `eth_call` read is preferable to paying gas for a guaranteed
   revert.
3. **Handle timeout.** If the transaction is not included within a configurable number of blocks (default: 20), the
   plugin may:
   - **Speed up:** Resubmit with higher gas price (same nonce — this replaces the pending transaction).
   - **Cancel:** Submit a zero-value self-transfer at the same nonce to clear the slot.
   - **Wait:** Continue waiting if gas prices are expected to drop.
4. **Handle replacement.** If the endpoint submits a speed-up transaction, the original transaction may be replaced.
   The `NonceTracker` handles this by tracking which nonce has the latest gas price.

## 7.7 Access List Precomputation

To reduce gas costs, the plugin precomputes an EIP-2930 access list for the `submitBundle()` transaction. The access
list includes:

- The CLPR Service contract address and all storage slots that will be read/written (Connection metadata, queue
  entries, endpoint bond balance).
- The verifier contract address and its storage slots.
- Connector contract addresses that will be called during message dispatch.

EIP-2930 access list entries cost 1900 gas per storage key (vs. 2100 gas for a cold access without the access
list). The net savings is 200 gas per key (2100 cold - 1900 access list). For a transaction accessing 50 storage
slots, the savings are approximately 10,000 gas (50 * 200). While modest, this adds up over many submissions and
also provides the benefit of deterministic gas costs by eliminating cold/warm access variability.

---

# 8. Endpoint Registration and Bond Management

**Prerequisite: Connection registration.** Before any endpoint can register, the Connection itself must exist
on-chain. Connection registration (including its deposit requirement) is an **administrative operation** performed
separately from endpoint registration — typically by the Connection administrator or governance process. The
endpoint plugin handles only endpoint registration; it assumes the Connection already exists and is properly
configured.

## 8.1 Registration

On Ethereum, endpoints are permissionless — any account can register by posting a bond. The plugin provides both a
CLI command and an automated registration flow.

### CLI Registration

```bash
besu --clpr-register-endpoint \
     --clpr-contract-address=0x... \
     --clpr-connection-id=0x... \
     --clpr-endpoint-ip=203.0.113.5 \
     --clpr-endpoint-port=9545 \
     --clpr-signing-certificate=/path/to/cert.der \
     --clpr-bond-amount=1.0  # ETH
```

This constructs and submits a `registerEndpoint()` transaction:

```solidity
/// @notice Register as an endpoint on a Connection, posting a bond.
/// @param connectionId The Connection to register on.
/// @param endpoint The endpoint metadata (IP, port, signing certificate, etc.).
/// @dev The bond amount is conveyed via msg.value. There is no separate bond parameter —
///      having bond as both a function parameter AND msg.value would be redundant and a
///      bug source (which value does the contract trust?). msg.value is authoritative.
function registerEndpoint(
    bytes32 connectionId,
    ClprEndpoint calldata endpoint
) external payable;
```

The bond is sent as `msg.value` in the transaction. The contract validates that `msg.value` meets or exceeds the
minimum bond requirement.

### Auto-Registration

If `clpr.auto-register = true`, the plugin automatically registers on startup for all configured Connections. The
plugin:

1. Checks whether the endpoint is already registered (reads contract state).
2. If not registered, constructs and submits the `registerEndpoint()` transaction.
3. Waits for confirmation before starting the sync loop.
4. If registration fails (e.g., insufficient ETH for bond), logs an error and enters a retry loop with exponential
   backoff.

### Configuration-Driven Registration

```toml
[clpr.registration]
auto-register = true
bond-amount = "1000000000000000000"  # 1 ETH in wei
connections = [
    "0xabc123...",  # Connection IDs to register on
    "0xdef456..."
]
```

## 8.2 Bond Amount

The minimum bond is defined by the CLPR Service contract and is readable via:

```solidity
function minimumEndpointBond() external view returns (uint256);
```

The plugin reads this value before registration and validates that the configured `bond-amount` meets or exceeds the
minimum. The operator may post a larger bond to signal commitment and improve reputation with peer endpoints.

## 8.3 Bond Withdrawal

An endpoint that wishes to deregister and recover its bond calls:

```solidity
function deregisterEndpoint(bytes32 connection_id) external;
```

The plugin provides a CLI command:

```bash
besu --clpr-deregister-endpoint --clpr-connection-id=0x...
```

Per the spec, deregistration MUST NOT proceed if the endpoint has in-flight sync submissions. The contract enforces
this, but the plugin also checks locally before submitting the deregistration transaction, to avoid wasting gas on a
guaranteed revert.

Bond withdrawal may be subject to a cooldown period (defined by the contract) to prevent endpoints from registering,
misbehaving, and immediately withdrawing before the report can be processed.

## 8.4 Bond Status Monitoring

The plugin periodically reads the endpoint's bond balance from the contract:

```solidity
function endpointBond(bytes32 connection_id, address endpoint) external view returns (uint256);
```

If the bond has been partially slashed (e.g., due to a misbehavior penalty), the plugin logs a warning and alerts
via metrics. If the bond falls below the minimum, the plugin stops submitting bundles (since submissions from an
under-bonded endpoint may be rejected) and logs an error instructing the operator to top up the bond.

---

# 9. Contract State Reading

## 9.1 Read Mechanisms

The plugin reads CLPR Service contract state using two mechanisms, depending on the data:

### Direct Storage Access (for proof construction)

For building proof_bytes, the plugin needs raw storage slot values and their MPT proofs. This uses:

- **In-process:** `WorldStateArchive.get(stateRoot).getStorageProof(address, keys)` (if Besu exposes this).
- **JSON-RPC fallback:** `eth_getProof(address, storageKeys, blockNumber)` on localhost.

### eth_call (for operational reads)

For reading contract state for operational purposes (not proof construction), the plugin uses simulated calls:

```java
// Read Connection metadata
Connection conn = clprContract.getConnection(connectionId);

// Read endpoint roster
ClprEndpoint[] peers = clprContract.getPeerEndpoints(connectionId);

// Read queue depth
QueueDepth depth = clprContract.getQueueDepth(connectionId);
```

These are executed as `eth_call` (no transaction, no gas cost) against the latest block.

## 9.2 Caching Strategy

Contract state is cached in memory with the following refresh policies:

| Data | Cache TTL | Refresh Trigger |
|---|---|---|
| Connection metadata (queue metadata) | 0 (no cache) | Read fresh before every sync |
| Peer endpoint roster | 60 seconds | Periodic refresh + on new block if ConfigUpdate detected |
| Ledger configuration (throttles, limits) | 300 seconds | Periodic refresh |
| Endpoint bond balance | 60 seconds | Periodic refresh |
| Local endpoint registration status | 120 seconds | Periodic refresh |

Queue metadata is NEVER cached because it changes with every block that processes a bundle. Using stale queue
metadata would cause the endpoint to construct proofs covering already-delivered messages, resulting in replay
rejections.

## 9.3 State Refresh Efficiency

To minimize the overhead of frequent state reads, the plugin:

1. **Batches storage reads.** When reading multiple storage slots for proof construction, all keys are requested in
   a single `eth_getProof` call.
2. **Uses block subscriptions.** Rather than polling, the plugin subscribes to `BesuEvents.addBlockAddedListener()`
   and triggers state reads only when a new block arrives.
3. **Reads at a specific block number.** All reads for a single sync are performed against the same block to ensure
   consistency. The plugin pins to a specific block number and uses `blockNumber` parameters in all calls.

---

# 10. MEV and Transaction Ordering Considerations

## 10.1 Threat Model

`submitBundle()` transactions are potentially valuable for multiple reasons:

1. **Endpoint reimbursement.** The submitting endpoint receives a margin from the Connector for each message
   processed. This is direct economic value.
2. **Front-running.** An MEV searcher who observes a `submitBundle()` in the public mempool could extract it, submit
   their own copy (if they are also a registered endpoint), and claim the reimbursement.
3. **Sandwich attacks.** Less applicable to CLPR (no token swap to sandwich), but the Connector charge creates a
   price impact that could theoretically be exploited.
4. **Censorship.** A block builder could selectively exclude `submitBundle()` transactions to delay message delivery.

## 10.2 Protection Strategies

### Private Transaction Submission

The primary defense is to avoid the public mempool entirely:

- **Flashbots Protect.** Submit transactions through Flashbots Protect RPC
  (`https://rpc.flashbots.net`), which routes them directly to block builders without exposing them in the public
  mempool. This is the recommended approach for Ethereum mainnet.
- **MEV-Share / OFA (Order Flow Auctions).** Submit through MEV-Share for a share of any MEV extracted. Since
  `submitBundle()` has limited MEV, this is likely equivalent to private submission.
- **Builder API direct submission.** If the node operator runs their own block builder (or has a direct relationship
  with one), transactions can be submitted directly.

Configuration:

```toml
[clpr.mev-protection]
strategy = "flashbots-protect"  # or "direct", "mev-share", "none"
flashbots-rpc = "https://rpc.flashbots.net"
builder-endpoints = ["https://builder1.example.com"]
```

When `strategy = "none"`, transactions are submitted to the local mempool. This is appropriate for private networks
or testnets.

### Competing Bundle Submissions

When multiple registered endpoints observe the same unacked messages, they may all construct and submit
`submitBundle()` transactions. Only one will succeed (the first to be included); the others will revert (replay
defense). This is benign from a protocol perspective but wastes gas for the losing endpoints.

Mitigations:

1. **Randomized delay.** Each endpoint adds a small random delay (0-2 seconds) before submitting, reducing the
   probability of simultaneous submission.
2. **Pre-check before submission.** Immediately before submitting, the endpoint re-reads the Connection's
   `received_message_id` to verify the bundle is still needed. If another endpoint has already submitted, the
   messages will already be acknowledged.
3. **Gas price competition.** Endpoints that submit with higher gas prices are more likely to be included first.
   This creates a natural market, but also increases costs. The `clpr.max-gas-price` cap prevents unbounded
   escalation.

### Endpoint Exclusivity

The CLPR protocol does NOT provide exclusive submission rights. Any registered endpoint can submit any bundle. This
is by design — it ensures liveness (a single unresponsive endpoint does not block the Connection). However, it means
gas waste from competing submissions is an inherent cost of permissionless endpoint participation.

Future protocol iterations might introduce:
- **Round-robin submission rights** (per-block or per-slot assignment to specific endpoints).
- **Commit-reveal schemes** (endpoints commit to a bundle hash, then reveal).
- **Priority fee sharing** (the contract splits the Connector margin among all endpoints that submitted valid
  bundles in the same block, reimbursing them for gas).

These are out of scope for this specification but should be considered for the contract design.

---

# 11. Configuration

## 11.1 Full Parameter Reference

| Parameter | CLI Flag | TOML Key | Default | Description |
|---|---|---|---|---|
| Enable CLPR | `--clpr-enabled` | `clpr.enabled` | `false` | Master switch. When false, the plugin does nothing. |
| Contract Address | `--clpr-contract-address` | `clpr.contract-address` | (required) | Address of the CLPR Service contract on this chain. |
| Signing Key File | `--clpr-signing-key-file` | `clpr.signing-key-file` | (required) | Path to the ECDSA private key file for transaction signing. |
| Signing Certificate | `--clpr-signing-certificate` | `clpr.signing-certificate` | (required) | Path to the DER-encoded RSA certificate for TLS and payload signing. |
| gRPC Port | `--clpr-grpc-port` | `clpr.grpc-port` | `9545` | Port for the CLPR gRPC server. |
| gRPC Host | `--clpr-grpc-host` | `clpr.grpc-host` | `0.0.0.0` | Interface for the CLPR gRPC server. |
| TLS Certificate | `--clpr-tls-cert` | `clpr.tls.certificate-file` | (derived from signing-certificate) | TLS certificate for the gRPC server. Defaults to the RSA signing certificate. If specified, MUST match the signing certificate. |
| TLS Key | `--clpr-tls-key` | `clpr.tls.key-file` | (derived from signing certificate key) | TLS private key for the gRPC server. Defaults to the RSA signing key. |
| Client Auth | `--clpr-tls-client-auth` | `clpr.tls.client-auth` | `OPTIONAL` | TLS client authentication mode: `NONE`, `OPTIONAL`, `REQUIRE`. |
| Sync Frequency | `--clpr-sync-frequency` | `clpr.sync-frequency-ms` | `5000` | Milliseconds between sync attempts per Connection. |
| Beacon API URL | `--clpr-beacon-api-url` | `clpr.beacon-api-url` | `http://localhost:5052` | Beacon chain API endpoint for sync committee data. |
| Commitment Level | `--clpr-commitment-level` | `clpr.commitment-level` | `finalized` | Block commitment level for proof construction: `finalized`, `safe`, `latest`. |
| Max Gas Price | `--clpr-max-gas-price` | `clpr.max-gas-price-gwei` | `100` | Maximum gas price (gwei). Transactions above this are deferred. |
| Gas Buffer | `--clpr-gas-buffer` | `clpr.gas-buffer-multiplier` | `1.2` | Multiplier applied to gas estimates. |
| Priority Fee | `--clpr-priority-fee` | `clpr.priority-fee-gwei` | `2` | Default EIP-1559 priority fee (gwei). |
| Auto-Register | `--clpr-auto-register` | `clpr.auto-register` | `false` | Automatically register as endpoint on startup. |
| Bond Amount | `--clpr-bond-amount` | `clpr.bond-amount-wei` | (required if auto-register) | Bond to post during registration (wei). |
| Connection IDs | `--clpr-connections` | `clpr.connections` | `[]` | List of Connection IDs to participate in. |
| MEV Strategy | `--clpr-mev-strategy` | `clpr.mev-protection.strategy` | `none` | MEV protection: `none`, `flashbots-protect`, `mev-share`. |
| Flashbots RPC | `--clpr-flashbots-rpc` | `clpr.mev-protection.flashbots-rpc` | `https://rpc.flashbots.net` | Flashbots RPC endpoint. |
| Skip Unprofitable | `--clpr-skip-unprofitable` | `clpr.skip-unprofitable-bundles` | `false` | Skip bundles where gas cost exceeds expected reimbursement. |
| Sync Timeout | `--clpr-sync-timeout` | `clpr.sync-timeout-ms` | `30000` | gRPC sync call timeout (milliseconds). |
| Tx Inclusion Timeout | `--clpr-tx-timeout` | `clpr.tx-inclusion-timeout-blocks` | `20` | Blocks to wait for transaction inclusion before speed-up. |
| Storage Layout | `--clpr-storage-layout` | `clpr.storage-layout-file` | (bundled) | Path to CLPR contract storage layout JSON. |

## 11.2 Connection-Specific Overrides

Some parameters may be overridden per Connection:

```toml
[clpr.connection-overrides."0xabc123..."]
commitment-level = "safe"
sync-frequency-ms = 2000
max-gas-price-gwei = 50
```

This allows operators to use different commitment levels or sync frequencies for different Connections (e.g., lower
latency for a `safe`-level Connection to a high-throughput peer, higher gas tolerance for a critical Connection).

---

# 12. Lifecycle Management

## 12.1 Startup Sequence

1. **Plugin registered.** Besu loads the plugin JAR and calls `register()`. CLI options are registered.
2. **Plugin started.** Besu calls `start()` after core services are initialized.
3. **Configuration validated.** The plugin reads and validates all configuration parameters. Missing required
   parameters cause a startup failure with a descriptive error message.
4. **Contract state checked.** The plugin reads the CLPR Service contract to verify it exists and is initialized.
   If the contract is not deployed at the configured address, the plugin logs an error and enters a retry loop
   (the contract might be deployed in a pending block).
5. **Registration verified.** The plugin checks whether the endpoint is registered on each configured Connection.
   If `auto-register = true` and not registered, it submits registration transactions and waits for confirmation.
6. **Sync committee initialized.** The `SyncCommitteeTracker` fetches the current and next sync committees from the
   Beacon API.
7. **gRPC server started.** The gRPC server begins accepting incoming sync connections.
8. **Sync loops started.** A sync scheduler is created for each registered Connection.
9. **Metrics registered.** All Prometheus metrics are registered with Besu's `MetricsSystem`.

## 12.2 Shutdown Sequence

1. **Sync loops stopped.** All sync schedulers are cancelled. In-progress syncs are allowed to complete (with a
   timeout).
2. **gRPC server stopped.** The gRPC server stops accepting new connections. Existing connections are gracefully
   drained.
3. **Pending transactions.** The plugin waits (up to a configurable timeout) for pending transactions to be included
   or cancelled. If the timeout expires, pending transactions are abandoned (they will eventually expire from the
   mempool).
4. **State flushed.** Cached state and metrics are flushed.
5. **Plugin stopped.** The `stop()` method returns.

## 12.3 Besu Restart Behavior

When Besu restarts:

- The plugin re-reads all contract state from scratch (no persistent cache across restarts).
- The `NonceTracker` re-syncs with the on-chain nonce.
- If any transactions were pending before the restart, they may still be in the mempool. The plugin detects this by
  comparing the on-chain nonce with the expected nonce and adjusts accordingly.
- Sync loops resume from the current contract state. No messages are lost — the contract's queue is the source of
  truth.

## 12.4 Behavior During Besu Sync

When Besu is synchronizing (catching up to the chain head), the plugin MUST NOT start sync loops or submit
transactions. The world state is incomplete and reads would return incorrect data.

Detection:
- Check `BlockchainService.isChainHeadSynced()` or equivalent.
- Subscribe to `BesuEvents` and wait for the "sync complete" event.
- The plugin enters a polling loop during sync, checking status every 30 seconds, and logs its waiting state.

## 12.5 Behavior During Reorgs

When Besu processes a chain reorganization:

1. **Proof invalidation.** Any proof_bytes constructed against a block that was reorged out are now invalid. The
   plugin MUST detect reorgs via `BesuEvents.addBlockReorgListener()` and discard any proofs referencing reorged
   blocks.
2. **Transaction invalidation.** If a `submitBundle()` transaction was included in a reorged block, it is no longer
   confirmed. The plugin re-checks inclusion after the reorg settles.
3. **State re-read.** All cached contract state is invalidated after a reorg. The next sync cycle reads fresh state
   from the canonical chain.
4. **Commitment level protection.** Using `finalized` commitment level for proof construction eliminates reorg
   concerns — finalized blocks are never reorged (by definition). This is the recommended default.

## 12.6 Behavior on Minority Fork

If the Besu node temporarily follows a minority fork:

- Proofs constructed from minority-fork state will be invalid on the canonical chain. Peer endpoints running
  canonical-chain nodes will reject them during pre-validation.
- Transactions submitted to the local mempool will not propagate to the canonical chain's miners/builders.
- When the node re-syncs to the canonical chain, all state is corrected and normal operation resumes.

The plugin does not need special handling for this case — the protocol's proof verification naturally rejects
minority-fork data.

## 12.7 Misbehavior Detection and Reporting

The endpoint is responsible for detecting and reporting peer misbehavior as defined in the cross-platform spec
(Section 8.4). Two primary misbehavior types are relevant to the endpoint:

### DUPLICATE_BROADCAST Detection

A DUPLICATE_BROADCAST occurs when a remote endpoint sends the same payload to multiple local endpoints. To
correctly detect this, the endpoint MUST track the **direction** of each sync:

- **Locally initiated:** This endpoint called the peer's `sync()` RPC. The response received is a consequence of
  this endpoint's own action.
- **Remotely initiated:** The peer called this endpoint's `sync()` RPC. The payload received was pushed by the
  remote endpoint.

Only **remotely initiated** inbound syncs can contribute to DUPLICATE_BROADCAST evidence. If this Besu endpoint
independently initiated a sync and received the same payload that another local endpoint also received, that is
normal operation (both local endpoints asked the same peer for data and got the same answer). This is NOT
misbehavior.

DUPLICATE_BROADCAST evidence requires: two or more `ClprSyncPayload`s with identical `proof_bytes`, initiated by
the remote endpoint (i.e., the remote endpoint called `sync()` on two different local endpoints with the same
payload), submitted by different local endpoints.

### EXCESS_FREQUENCY Detection

The endpoint tracks the rate of remotely initiated inbound syncs per peer. If a peer exceeds the local ledger's
`max_syncs_per_sec` limit, the endpoint records the timestamps as evidence.

### reportMisbehavior Transaction

When the endpoint detects DUPLICATE_BROADCAST or EXCESS_FREQUENCY, it constructs a `reportMisbehavior()` transaction
and submits it to the CLPR Service contract:

```solidity
function reportMisbehavior(
    bytes32 connectionId,
    uint8 misbehaviorType,       // DUPLICATE_BROADCAST or EXCESS_FREQUENCY
    bytes calldata evidence       // Encoded evidence (see below)
) external;
```

Evidence format for DUPLICATE_BROADCAST: ABI-encoded array of two or more `ClprSyncPayload`s with identical
`proof_bytes` and distinct `endpoint_signature` values from different local endpoints, along with timestamps
proving they were remotely initiated.

Evidence format for EXCESS_FREQUENCY: ABI-encoded array of timestamps showing the rate of inbound syncs from the
reported peer exceeding the configured limit.

The transaction is submitted through the same `TransactionSubmitter` pipeline as `submitBundle()`, using the same
nonce management and gas estimation strategies.

### Cross-Endpoint Detection Coordination

On a given ledger, each Besu endpoint is an independent process. To detect DUPLICATE_BROADCAST (where a remote
endpoint pushes the same payload to multiple local endpoints), local endpoints must share information about
received payloads. Detection mechanisms (in order of preference):

1. **On-chain event monitoring.** Each endpoint monitors `BundleSubmitted` events on the CLPR Service contract.
   If two events in the same block (or within a sync round window) reference the same `connection_id` and
   overlapping `proof_bytes` from different submitters, the remote endpoint may be guilty of DUPLICATE_BROADCAST.
   This is the simplest approach — no additional infrastructure needed.

2. **Shared mempool observation.** Endpoints can observe pending `submitBundle` transactions in the mempool
   before they are mined. This provides earlier detection but is less reliable (transactions may be
   private/MEV-protected).

3. **Off-chain gossip.** Endpoints on the same ledger run a lightweight gossip protocol to share payload
   fingerprints. This adds infrastructure complexity but provides the fastest detection.

The recommended approach for initial implementation is option 1 (on-chain event monitoring), as it requires no
additional coordination infrastructure.

---

# 13. Monitoring and Metrics

## 13.1 Metric Categories

All metrics are registered under the `clpr` subsystem in Besu's Prometheus metrics. Labels include `connection_id`
(truncated to 8 hex chars for cardinality management) where applicable.

### Sync Metrics

| Metric | Type | Labels | Description |
|---|---|---|---|
| `clpr_syncs_initiated_total` | Counter | `connection_id`, `status` | Total syncs initiated. Status: `success`, `failure`, `timeout`. |
| `clpr_syncs_received_total` | Counter | `connection_id`, `status` | Total syncs received (as responder). |
| `clpr_sync_duration_seconds` | Histogram | `connection_id`, `role` | Duration of sync calls. Role: `initiator`, `responder`. |
| `clpr_sync_payload_bytes` | Histogram | `connection_id`, `direction` | Size of sync payloads. Direction: `sent`, `received`. |

### Transaction Metrics

| Metric | Type | Labels | Description |
|---|---|---|---|
| `clpr_transactions_submitted_total` | Counter | `connection_id`, `status` | Transactions submitted. Status: `confirmed`, `reverted`, `dropped`. |
| `clpr_transaction_gas_used` | Histogram | `connection_id` | Gas used by confirmed `submitBundle()` transactions. |
| `clpr_transaction_gas_price_gwei` | Gauge | `connection_id` | Effective gas price of the latest confirmed transaction. |
| `clpr_transaction_inclusion_blocks` | Histogram | `connection_id` | Blocks between submission and inclusion. |

### Proof Metrics

| Metric | Type | Labels | Description |
|---|---|---|---|
| `clpr_proof_construction_seconds` | Histogram | `connection_id` | Time to construct proof_bytes. |
| `clpr_proof_storage_slots` | Histogram | `connection_id` | Number of storage slots included in a proof. |
| `clpr_proof_validation_seconds` | Histogram | `connection_id`, `result` | Time to pre-validate received proofs. Result: `valid`, `invalid`. |

### State Metrics

| Metric | Type | Labels | Description |
|---|---|---|---|
| `clpr_queue_depth` | Gauge | `connection_id`, `direction` | Current queue depth. Direction: `outbound`, `inbound`. |
| `clpr_messages_processed_total` | Counter | `connection_id`, `type` | Messages processed by type: `data`, `response`, `control`. |
| `clpr_acked_message_id` | Gauge | `connection_id` | Current acked_message_id per Connection. |

### Bond and Economic Metrics

| Metric | Type | Labels | Description |
|---|---|---|---|
| `clpr_endpoint_bond_wei` | Gauge | `connection_id` | Current endpoint bond balance. |
| `clpr_endpoint_reimbursement_wei_total` | Counter | `connection_id` | Cumulative reimbursement received. |
| `clpr_transaction_cost_wei_total` | Counter | `connection_id` | Cumulative gas costs for bundle submissions. |
| `clpr_net_profit_wei` | Gauge | `connection_id` | Reimbursement minus gas costs (rolling window). |

### Peer Health Metrics

| Metric | Type | Labels | Description |
|---|---|---|---|
| `clpr_peer_endpoints_known` | Gauge | `connection_id` | Number of known peer endpoints. |
| `clpr_peer_sync_failures_total` | Counter | `connection_id`, `peer` | Sync failures per peer endpoint. |
| `clpr_peer_latency_seconds` | Histogram | `connection_id`, `peer` | gRPC round-trip latency per peer. |

## 13.2 Logging

The plugin uses Besu's logging framework (Log4j2). Log levels:

- **ERROR:** Registration failure, bond below minimum, contract not found, signing key issues.
- **WARN:** Sync failure, transaction revert, gas price exceeds cap, partial bond slash detected.
- **INFO:** Sync completed, bundle submitted, endpoint registered/deregistered, sync committee rotated.
- **DEBUG:** Proof construction details, storage slot reads, gas estimation, peer selection.
- **TRACE:** Raw gRPC messages, MPT proof nodes, ABI encoding details.

---

# 14. Security Considerations

## 14.1 gRPC Endpoint DDoS Protection

The gRPC server is exposed to the internet and is a potential DDoS target. Protections:

1. **Rate limiting.** Limit incoming connections per IP address (default: 10 concurrent, 30 per minute). Configurable
   via `clpr.grpc.rate-limit-per-ip`.
2. **Connection limits.** Maximum total concurrent gRPC connections (default: 100). Excess connections are rejected
   with `RESOURCE_EXHAUSTED`.
3. **Message size limits.** Enforced at the gRPC layer via `maxInboundMessageSize`. Oversized messages are rejected
   before deserialization.
4. **Deadline enforcement.** All gRPC calls have a server-side deadline (default: 60 seconds). Long-running calls are
   cancelled.
5. **IP allowlisting (optional).** The operator can restrict incoming connections to known peer endpoint IP addresses
   (read from the on-chain roster). This provides strong DDoS protection but breaks the ability to accept connections
   from newly registered peers until the roster cache is refreshed.

## 14.2 mTLS for Peer Authentication

When `clpr.tls.client-auth = "REQUIRE"`, the gRPC server enforces mutual TLS:

- The connecting peer must present a certificate.
- The server validates that the certificate matches an entry in the Connection's peer endpoint roster
  (`ClprEndpoint.signing_certificate`).
- Connections from unknown certificates are rejected at the TLS handshake level.

This provides strong authentication — only registered peer endpoints can connect. The tradeoff is that roster updates
(new peers joining) require a cache refresh before the new peer can connect.

**Recommendation:** Use `client-auth = "OPTIONAL"` for production. The server accepts connections with or without
client certificates. If a certificate is presented, it is validated against the roster. If not, the endpoint is
treated as unauthenticated, and the proof_bytes it sends will be validated on-chain regardless. The protocol's
cryptographic verification (verifier contract + running hash chain) provides security even without transport-layer
authentication.

## 14.3 Signing Key Management

The endpoint requires **two distinct signing keys** for different purposes:

- **RSA private key** — Signs `ClprSyncPayload.endpoint_signature` (per cross-platform spec Section 1.5) and
  provides the TLS server certificate. The corresponding RSA public certificate is registered on-chain as
  `ClprEndpoint.signing_certificate`.
- **ECDSA secp256k1 private key** — Signs Ethereum transactions (`submitBundle()`, `registerEndpoint()`, etc.).
  This is the standard Ethereum account key.

These keys MUST NOT be conflated. The `endpoint_signature` field in the CLPR protocol is always RSA (for
cross-platform compatibility with non-EVM ledgers), while Ethereum transactions always use secp256k1 ECDSA.

Key management options (apply to both keys — each key may use a different storage mechanism):

1. **File-based key.** The private key is stored in a file on disk, encrypted with a passphrase. This is the
   simplest option and is appropriate for development and testing.
   ```toml
   [clpr]
   signing-key-file = "/path/to/keystore.json"
   signing-key-password-file = "/path/to/password"
   ```

2. **Hardware wallet / HSM.** The private key is stored in a hardware security module. The plugin interacts with the
   HSM via PKCS#11 or a cloud KMS API. This is recommended for production.
   ```toml
   [clpr]
   signing-key-type = "hsm"
   hsm-library = "/path/to/pkcs11.so"
   hsm-slot = 0
   hsm-pin-file = "/path/to/pin"
   ```

3. **Cloud KMS.** The private key is managed by a cloud provider (AWS KMS, GCP Cloud KMS, Azure Key Vault). The
   plugin uses the cloud provider's SDK to sign transactions.
   ```toml
   [clpr]
   signing-key-type = "aws-kms"
   kms-key-id = "arn:aws:kms:..."
   ```

**Key rotation** is supported via the CLPR protocol's endpoint roster update mechanism. To rotate:
1. Register a new endpoint with the new key.
2. Wait for the roster update to propagate to peers.
3. Deregister the old endpoint.

## 14.4 Protection Against Oversized Payloads

Beyond gRPC message size limits:

- The plugin validates `proof_bytes` size against `max_sync_payload_bytes` before processing.
- The plugin validates the number of messages in a received payload against `max_messages_per_bundle`.
- Deserialization is performed in a bounded-memory context — malformed protobuf that attempts memory amplification
  (e.g., extremely deep nesting) is rejected by the protobuf library's recursion limit.

## 14.5 Node Isolation

The CLPR plugin MUST NOT become an attack vector against the Besu node itself:

- **Read-only state access.** The plugin reads world state but never writes to it directly. All state modifications
  go through signed transactions in the normal transaction pipeline.
- **Separate thread pools.** CLPR operations run in isolated thread pools. A hang or resource exhaustion in the CLPR
  plugin does not affect Besu's consensus participation or block processing.
- **Memory limits.** The plugin allocates a bounded amount of memory for caches and proof construction. A
  configuration parameter (`clpr.max-memory-mb`, default: 256) limits the plugin's heap usage.
- **No filesystem writes.** The plugin does not write to the filesystem (except logs via Besu's logging framework).
  No persistent state is maintained outside the Ethereum chain.

## 14.6 Rate Limiting on Outbound Syncs

The plugin enforces the peer's `max_syncs_per_sec` to avoid being reported for `EXCESS_FREQUENCY` misbehavior. The
rate limiter is per (Connection, peer endpoint) pair. If the configured `sync-frequency-ms` would violate the limit,
the plugin adjusts automatically and logs a warning.

---

# 15. Upstream Contribution Strategy

## 15.1 PR Structure

The contribution should be structured as a series of PRs:

### PR 1: WorldStateService Plugin API (prerequisite)

Adds a minimal `WorldStateService` to Besu's Plugin API:
- `getStorageAt(address, slot, blockNumber)`
- `getProof(address, storageKeys, blockNumber)`
- `call(callParams, blockNumber)`

This is independently useful for any plugin that needs to read contract state and should be reviewed and merged
first.

### PR 2: CLPR Endpoint Plugin (core)

The main plugin implementation:
- Plugin entry point, configuration, lifecycle.
- gRPC server with `ClprEndpointService`.
- Sync orchestration.
- Proof construction and verification.
- Transaction submission.
- Endpoint registration.
- Metrics.

### PR 3: Documentation and Examples

- User documentation (how to enable and configure).
- Operator guide (bond management, monitoring, troubleshooting).
- Example TOML configuration.
- Example Grafana dashboard for CLPR metrics.

## 15.2 Feature Flag

The plugin is behind a feature flag at multiple levels:

1. **JAR presence.** If the plugin JAR is not on the classpath, Besu has zero awareness of CLPR.
2. **Configuration flag.** Even with the JAR present, `clpr.enabled = false` (the default) means the plugin
   registers but does nothing during `start()`.
3. **Experimental flag.** During initial contribution, the plugin should be marked as experimental:
   `--Xclpr-enabled` (the `X` prefix follows Besu's convention for experimental features).

## 15.3 Test Requirements

- **Unit tests.** Each component (proof constructor, tx builder, sync orchestrator, etc.) with mocked dependencies.
  Target: >90% line coverage.
- **Integration tests.** Using Besu's existing test infrastructure (e.g., `BesuNode` test framework) with a deployed
  CLPR Service contract on an ephemeral chain.
- **Interop tests.** End-to-end tests with a Hiero test network, verifying that proofs constructed by the Besu
  plugin are accepted by the Hiero verifier contract.
- **Performance benchmarks.** Proof construction time, gRPC throughput, transaction submission latency.

## 15.4 Compatibility

- **Besu version.** Target the latest stable Besu release. The plugin should be forward-compatible with Besu's
  Plugin API stability guarantees.
- **Java version.** Match Besu's Java version requirement (Java 21+).
- **Protobuf version.** Use the same protobuf version as Besu to avoid classpath conflicts.
- **gRPC version.** Use a gRPC version compatible with Besu's existing gRPC dependencies (if any), or shade the
  dependency to avoid conflicts.
- **SSZ serialization.** The CLPR endpoint plugin requires an SSZ (Simple Serialize) library for
  encoding/decoding beacon chain structures (`BeaconBlockHeader`, `LightClientUpdate`, sync committee data).
  Recommended Java SSZ libraries: Apache Tuweni SSZ (`org.apache.tuweni:tuweni-ssz`) or the Besu-internal
  `tech.pegasys.teku:ssz` module. If using Besu's internal SSZ, the plugin must shade this dependency to avoid
  version conflicts with Besu's own SSZ usage.

## 15.5 Code Style and Conventions

- Follow Besu's existing code style (Google Java Format with Besu-specific overrides).
- Use Besu's existing logging patterns.
- Follow Besu's existing Gradle conventions for the plugin module.
- Use `@AutoService` for plugin discovery (consistent with existing Besu plugins).

---

# 16. Inconsistencies and Findings

This section documents inconsistencies, potential issues, and open questions identified during the generation of this
specification, after cross-referencing with the source documents.

## 16.1 Contradictions with Source Documents

### 16.1.1 Signing Key Type Mismatch (Resolved)

The cross-platform spec (Section 1.2) specifies that `ClprEndpoint.signing_certificate` is a "DER-encoded RSA
public certificate" used for TLS and payload signing. However, Ethereum transactions are signed with ECDSA
(secp256k1), not RSA. This means the endpoint node needs **two distinct keys**:

- An **RSA keypair** for TLS and `endpoint_signature` (as specified in the protocol).
- An **ECDSA secp256k1 keypair** for Ethereum transaction signing.

This dual-key requirement is explicitly addressed in Section 14.3 of this specification. The `signing-key-file`
configuration refers to the ECDSA key (for Ethereum transactions), while the `signing-certificate` refers to the
RSA certificate (for TLS and protocol-level `endpoint_signature`). The RSA signing certificate also serves as the
TLS server certificate (see Section 3.3).

### 16.1.2 Sidecar vs. Plugin Discrepancy

The design document (Section 3.1.4) states: "The gRPC server runs as a **sidecar process** alongside the Besu
client (or as a built-in module if CLPR support is contributed to Besu)." This specification chooses the plugin
approach over the sidecar approach. The design document acknowledges both options — this is not a contradiction, but
the choice should be documented as a deliberate architectural decision. The plugin approach is superior because:

- It avoids IPC overhead between the sidecar and Besu.
- It enables direct world state access (no JSON-RPC serialization for proof construction).
- It participates in Besu's lifecycle cleanly.

The sidecar approach remains viable as a fallback if the Besu Plugin API proves insufficient.

## 16.2 Besu Architecture Challenges

### 16.2.1 Plugin API Limitations

The current Besu Plugin API (as of Besu 24.x) does not expose:

- **World state trie access** for `eth_getProof`-equivalent operations. The plugin needs MPT proofs for proof
  construction. Without a `WorldStateService`, the plugin must fall back to localhost JSON-RPC calls, which adds
  latency and serialization overhead.
- **Beacon chain state.** Besu does not expose sync committee data or finality status through the Plugin API. The
  plugin must call the consensus layer client's Beacon API externally.
- **Transaction simulation with custom state.** The plugin needs `eth_call`-equivalent functionality for
  pre-validating proofs. The current Plugin API does not expose this directly.

**Recommendation:** Propose `WorldStateService` as a prerequisite Besu enhancement (PR 1 in Section 15.1). If this
is rejected, the fallback is localhost JSON-RPC, which is functional but suboptimal.

### 16.2.2 Plugin JAR Dependency Conflicts

The CLPR plugin introduces gRPC and protobuf dependencies. Besu itself uses Netty (for JSON-RPC) and may have
protobuf on its classpath (for other plugins or internal use). Dependency version conflicts are a real risk.

**Mitigation:** Use Gradle's `shadow` plugin to shade the CLPR plugin's gRPC and protobuf dependencies into a
unique package namespace, avoiding classpath conflicts with Besu's own dependencies. The `shadow` plugin
configuration MUST include `mergeServiceFiles()` to preserve `@AutoService` / `ServiceLoader` metadata files
(typically under `META-INF/services/`) when shading gRPC and protobuf dependencies. Without this directive,
`ServiceLoader`-based discovery (used by gRPC for transport providers and by Besu for plugin discovery) will
silently fail.

## 16.3 Gas Feasibility

### 16.3.1 Cost Analysis

A rough gas cost estimate for `submitBundle()`:

| Component | Gas Estimate |
|---|---|
| Transaction base cost | 21,000 |
| Calldata (10KB proof + 10 messages) | ~160,000 (16 gas/byte for non-zero) |
| Verifier contract execution (BLS verification) | 100,000 - 200,000 |
| MPT proof verification (20 nodes avg) | 20,000 - 60,000 |
| CLPR Service logic (replay checks, hash chain) | 50,000 - 100,000 |
| Per-message dispatch (10 messages, excluding app callback) | 200,000 - 500,000 |
| Application callbacks (10 * 200K max) | 0 - 2,000,000 |
| Storage writes (queue state updates) | 100,000 - 200,000 |
| **Total (10-message bundle)** | **~650,000 - 3,200,000** |

At 30 gwei gas price and 2M gas average:
- Cost per bundle: ~0.06 ETH (~$180 at $3000/ETH)
- Cost per message: ~$18

At 10 gwei:
- Cost per bundle: ~0.02 ETH (~$60)
- Cost per message: ~$6

**Viability assessment:** These costs are comparable to existing bridge protocols (LayerZero, Wormhole). However,
the Connector margin must cover this cost plus profit for the endpoint to be economically viable. For high-value
messages (token bridges, large settlements), $6-18 per message is acceptable. For high-frequency, low-value messages
(oracle updates, small transfers), the cost may be prohibitive.

### 16.3.2 BLS Precompile (EIP-2537)

If EIP-2537 (BLS12-381 precompiles) is activated on Ethereum mainnet, BLS signature verification gas cost drops
from ~200K (pure Solidity implementation) to ~45K (precompile). This significantly improves the economics of CLPR
endpoint operation. The verifier contract should be designed to use the BLS precompile when available and fall back
to a Solidity implementation otherwise.

### 16.3.3 Gas Price Volatility

Ethereum gas prices are highly volatile. During network congestion, gas can spike to >100 gwei, making a single
bundle submission cost >$500. The `max-gas-price` cap and the `skip-unprofitable-bundles` option are essential for
preventing the endpoint from hemorrhaging ETH during gas spikes. However, this means messages may be delayed during
high-gas periods — a latency vs. cost tradeoff that the operator must consciously accept.

## 16.4 MEV Risks

### 16.4.1 Bundle Sniping

The most significant MEV risk is **bundle sniping**: an MEV searcher observes a `submitBundle()` transaction in the
public mempool, extracts the proof_bytes, and submits their own `submitBundle()` transaction with higher gas. If the
searcher is also a registered endpoint, they claim the Connector margin. The original endpoint's transaction reverts,
and the endpoint loses gas.

The mitigation (Flashbots Protect / private transactions) is effective but not universally available (not all block
builders participate in Flashbots). Approximately 80-90% of Ethereum blocks are built by Flashbots-connected
builders, so the protection is strong but not absolute.

### 16.4.2 Endpoint Profitability

The combination of gas costs, competing endpoint submissions, and potential MEV extraction means that endpoint
operation on Ethereum is **not guaranteed to be profitable**. Operators should expect:

- **Profitable cases:** High-value Connections with few competing endpoints, where the Connector margin is calibrated
  to cover gas costs with a healthy buffer.
- **Break-even cases:** Many competing endpoints on a popular Connection, where competition drives down effective
  margins (due to gas wasted on reverted competing submissions).
- **Unprofitable cases:** Gas spikes, low Connector margins, or aggressive MEV extraction.

The protocol's economic model assumes rational endpoints that will exit unprofitable Connections, finding an
equilibrium where the number of active endpoints balances with the available margin. This equilibrium may take time
to develop and may result in Connections with only one or two active endpoints — which is acceptable for liveness
(the protocol requires only one honest endpoint) but reduces redundancy.

## 16.5 Assumptions Requiring Besu Team Validation

1. **`BesuContext` service availability.** This spec assumes `BlockchainService`, `TransactionPoolService`,
   `BesuEvents`, and `MetricsSystem` are available through `BesuContext`. This should be validated against the
   target Besu version.

2. **`BesuEvents` reorg listener.** This spec assumes the plugin can subscribe to chain reorganization events. If
   `BesuEvents` does not expose a reorg listener, the plugin must detect reorgs by monitoring the canonical chain
   head and detecting parent hash discontinuities.

3. **Transaction pool submission.** This spec assumes `TransactionPoolService` allows the plugin to submit signed
   transactions. If the API only accepts unsigned transactions (for the node's own account), the plugin may need to
   submit via the JSON-RPC `eth_sendRawTransaction` endpoint instead.

4. **Plugin JAR isolation.** This spec assumes plugins can bring their own dependencies (gRPC, protobuf) without
   conflicting with Besu's classpath. If Besu does not support dependency shading or classloader isolation for
   plugins, dependency conflicts may arise.

5. **Concurrent plugin access to world state.** This spec assumes the plugin can safely read world state concurrently
   with Besu's block processing. If world state access requires synchronization with block imports, proof
   construction may need to be deferred to block import boundaries.

## 16.6 Open Design Questions

1. **Pre-computed proof caching.** Should the plugin pre-compute proofs every block and cache them, or compute
   on-demand per sync? Pre-computation wastes work if no sync occurs in that block. On-demand computation adds
   latency to sync response. The right answer depends on sync frequency vs. block time and should be configurable.

2. **Multi-Connection nonce contention.** When the endpoint is registered on many Connections, all sharing one
   signing account, nonce contention becomes a bottleneck. Each Connection's sync may want to submit a transaction
   simultaneously, but nonces must be strictly sequential. Should the plugin batch multiple Connection bundles into
   a single multicall transaction? This would require contract support (a `submitBundles()` batch method).

3. **Beacon API reliability.** The Beacon API is the plugin's only source for sync committee data. If the consensus
   client's Beacon API is down or returns stale data, proof construction fails. Should the plugin support multiple
   Beacon API endpoints with failover?

4. **Storage layout versioning.** The CLPR Service contract may be upgraded (e.g., via a proxy). When the storage
   layout changes, the plugin's hardcoded slot computation breaks. How should the plugin detect and adapt to
   contract upgrades? Options: (a) the contract exposes a `version()` method, and the plugin bundles multiple
   layout mappings; (b) the plugin reads the layout from a registry contract; (c) the operator manually updates the
   storage layout file.

5. **Endpoint signing certificate renewal.** The RSA certificate used for TLS has an expiry date. Renewal requires
   updating the on-chain endpoint roster (via EndpointLeave + EndpointJoin control messages). The plugin should
   detect impending certificate expiry and alert the operator. Automated renewal is complex because the new
   certificate must be registered on-chain before it can be used for TLS, and peers must see the roster update
   before they will accept connections with the new certificate.
