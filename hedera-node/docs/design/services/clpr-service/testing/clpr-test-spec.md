# CLPR Test Specification

This document defines the testing strategy for the CLPR protocol. It enumerates test types, specific test
scenarios, and testable assertions derived from the [CLPR Design Document](../clpr-service.md) and the
[Cross-Platform Specification](../clpr-service-spec.md). It is organized by test level — from single-ledger
handler tests through multi-ledger integration, adversarial scenarios, application workflows, performance,
and recovery. A cross-reference table at the end maps each CLPR specification section to the test IDs that
cover it, enabling gap analysis and coverage tracking.

---

# 1. Overview

## 1.1 Purpose and Scope

CLPR introduces a fundamentally new class of behavior for Hiero: cross-ledger communication. Every existing
test in the Hiero ecosystem — from unit tests to CITR longevity suites — operates within a single ledger
boundary. CLPR testing must extend beyond this boundary to verify that two (or more) independent ledgers can
exchange messages reliably, securely, and economically.

This document specifies *what* must be tested and *what properties must hold*, not *how* to build the test
infrastructure. Infrastructure details (SOLO extensions, Kubernetes topology, test driver architecture) are
implementation concerns for the teams that build the harnesses.

## 1.2 Relationship to Existing Test Paradigms

CLPR testing builds on three existing Hiero test paradigms and introduces a fourth:

**HAPI Tests.** Black-box tests that submit transactions to an embedded Hiero network and verify state
mutations. CLPR extends this with new transaction types (`registerConnection`, `sendMessage`, `submitBundle`,
etc.) and with multi-network support — the HAPI test framework has already been extended on a separate branch
to stand up two Hiero networks simultaneously (the ShipOfTheseusTest demonstrates complete roster rotation
on both sides while maintaining CLPR communication). HAPI tests cannot run block nodes, mirror nodes, or
non-Hiero networks.

**SDK TCK.** Transaction-level verification from SDK through consensus node to mirror node. Tests are
specified as JSON-RPC interactions with dual verification (consensus node query + mirror node REST API). CLPR
adds a new service module to the TCK with test specifications for each CLPR transaction type and CLPR-specific
mirror node data (connection state, queue metadata, message history, Connector balances).

**CITR Suites.** Full-stack integration and longevity testing (MATS, XTS, SDCT, SDLT, SDPT, MDLT, MQPT) using
SOLO to deploy Hiero networks with all components (block node, mirror node, explorer, JSON-RPC relay). CLPR
tests at this level require multi-network deployments and potentially cross-platform deployments (Hiero +
Ethereum).

**Cross-Ledger Integration (new paradigm).** Tests that span two or more independent ledger networks,
verifying end-to-end message flow, asynchronous response delivery, and cross-ledger state consistency. This
paradigm does not exist in the Hiero test ecosystem today and requires new infrastructure: multi-network
deployment orchestration, a cross-network test driver, and an adversarial component library.

## 1.3 What Is New About CLPR Testing

Four properties distinguish CLPR testing from all existing Hiero testing:

- **Multi-ledger as the fundamental unit.** The system under test is not one ledger — it is two or more
  ledgers communicating asynchronously. A test that verifies only one side is incomplete.
- **Asynchronous outcome verification.** A message sent on Ledger A does not produce a verifiable result
  until Ledger B processes it, generates a response, an endpoint syncs the response back, and Ledger A
  delivers it to the application. The test must wait for this asynchronous pipeline with appropriate
  timeouts.
- **Adversarial component injection.** Verifiers, endpoints, and Connectors can misbehave. Testing the
  protocol's resilience requires deploying intentionally malicious or broken components and verifying the
  protocol's response.
- **Cross-platform testing.** CLPR is designed to connect heterogeneous ledgers. Testing Hiero-to-Ethereum
  (and eventually Hiero-to-Solana, Hiero-to-Polygon, etc.) requires standing up non-Hiero networks alongside
  Hiero networks in the same test environment.

## 1.4 Test Categorization

| Level | Infrastructure | What It Tests | Speed |
|---|---|---|---|
| HAPI handler tests | Embedded Hiero network(s) | Individual transaction correctness, state machine transitions, protocol invariants | Fast (seconds) |
| TCK transaction tests | Single Hiero + mirror node | SDK-to-mirror-node verification per transaction type | Moderate (minutes) |
| Cross-ledger integration | Two or more networks (Hiero, Ethereum, etc.) via SOLO/K8s | End-to-end message flow, cross-ledger state consistency, connection lifecycle under traffic | Slow (minutes to hours) |
| Performance and longevity | Full-stack multi-network | Throughput, latency, queue behavior, state growth, resource consumption | Hours to days |

---

# 2. Test Infrastructure

This section describes the infrastructure capabilities needed to support the test levels defined in this
document. It does not prescribe implementation details — teams building the harnesses should use this as a
requirements specification.

## 2.1 Multi-Network Deployment Orchestration

CLPR testing requires standing up multiple independent ledger networks within a single test environment,
with network connectivity between their endpoint nodes.

### 2.1.1 Hiero-to-Hiero Deployments

Two or more independent Hiero networks deployed in the same Kubernetes cluster (or across clusters), each
with its own namespace, consensus nodes, block node, mirror node, and CLPR Service configuration. gRPC
connectivity must be established between endpoint nodes across network namespaces. Each network has its
own admin key, chain ID, and independent state.

### 2.1.2 Ethereum Network Deployments

A local Ethereum network (BESU, Hardhat, or Anvil) deployed alongside the Hiero network(s) in Kubernetes.
The CLPR Service Solidity contract is deployed and initialized on the Ethereum network. Verifier contracts
and Connector contracts are deployed. Endpoint registration is performed. The test driver must be able to
submit Ethereum contract calls alongside Hiero HAPI transactions.

### 2.1.3 Extensibility to Other Ledger Types

The deployment orchestration should be designed with a pluggable provisioner interface so that new ledger
types can be added without restructuring the test framework. Each ledger type provides: node deployment,
CLPR Service initialization, account and key setup, and a transaction submission adapter. Future candidates
include Solana, Stellar, Polygon, and Avalanche.

### 2.1.4 Topology Configuration

Different test scenarios require different network topologies:

- **Pairwise** (A↔B) — the common case for most integration tests.
- **Hub-and-spoke** (A↔B, A↔C, A↔D) — tests Connection isolation and multi-peer behavior.
- **Ring** (A↔B, B↔C, C↔A) — tests multi-ledger atomic swap workflows.
- **Mesh** — stress tests with many simultaneous cross-connections.

## 2.2 Cross-Network Test Driver

A test driver that holds simultaneous connections to all networks under test and can orchestrate transactions
and assertions across them.

### 2.2.1 Modular Ledger Adapters

Each ledger type provides a module implementing a common interface: submit transaction, query CLPR Service
state (connection status, queue depth, message state), query application state (balances, escrow, contract
storage), and query mirror/indexer state (transaction history, event logs). The driver is extended by adding
new adapter modules — one per ledger type.

### 2.2.2 Asynchronous Flow Orchestration

The driver must support waiting for cross-ledger outcomes: send a message on Ledger A, poll Ledger B for
delivery, poll Ledger A for the response. Timeouts must be configurable per ledger type (sub-second for
Hiero-to-Hiero, 30+ seconds when Ethereum is involved). Flow completion is detected by tracking message IDs
from enqueue through response delivery.

### 2.2.3 Topology-Aware Assertions

Assertions that span multiple ledgers: "balance on A decreased by X AND balance on B increased by X,"
"message sent on A with ID N produced response on A with status SUCCESS," "connection status on A is ACTIVE
AND connection status on B is ACTIVE." The assertion API must accept ledger identifiers and query the
appropriate adapter.

## 2.3 Adversarial Component Library

A collection of intentionally malicious or broken components that can be deployed into the test environment
to verify protocol resilience.

### 2.3.1 Malicious Verifier Contracts

- Verifier that accepts all proofs and returns fabricated data.
- Verifier that rejects all proofs (denial of service).
- Verifier that returns correct data but with wrong running hashes.
- Verifier that silently changes behavior after N invocations.

### 2.3.2 Misbehaving Endpoint Simulators

- Endpoint that submits duplicate bundles.
- Endpoint that exceeds sync frequency limits.
- Endpoint that submits bundles with tampered payloads.
- Endpoint that disconnects mid-sync.

### 2.3.3 Adversarial Connectors

- Connector that deregisters while messages are in-flight.
- Connector registered on source but not destination.
- Connector with insufficient balance on destination.

### 2.3.4 Network-Level Fault Injection

- Partition between networks (drop gRPC traffic between namespaces).
- Latency injection on cross-network links.
- Selective endpoint unavailability.

---

# 3. Single-Ledger Tests

These tests verify CLPR behavior within a single ledger. They exercise individual transaction handlers,
protocol invariants, and economic rules. They fit existing test paradigms (HAPI tests, TCK) and do not
require multi-network infrastructure.

## 3.1 Configuration Management

### 3.1.1 `setLedgerConfiguration`

- Admin key is required; unauthorized callers are rejected.
- `chain_id` is immutable after first activation; attempts to change it are rejected.
- `protocol_version` is set automatically by the CLPR Service code, not by the caller; caller-supplied
  values are ignored.
- `timestamp` is set to the consensus timestamp of the transaction; caller-supplied values are ignored.
- Throttle fields (`ClprThrottles`) are validated per spec boundaries: `max_messages_per_bundle` cannot be 0,
  `max_sync_bytes` must be greater than `max_message_payload_bytes` plus protocol overhead,
  `max_queue_depth` must be positive.
- Caller-supplied `protocol_version` is ignored; verify the stored value matches the code version.
- When CLPR is disabled, the transaction returns an error.
- Two sequential config sets produce different timestamps; query returns the latest.
- Configuration update triggers lazy propagation: each Connection's `last_config_timestamp` is compared
  against the new configuration's timestamp at the next interaction.

### 3.1.2 `getLedgerConfiguration`

- Returns the current configuration including auto-set fields (`protocol_version`, `chain_id`, `timestamp`).
- Returns an error when no configuration has been set.
- Returns an error when CLPR is disabled.

## 3.2 Connection Lifecycle

### 3.2.1 `registerConnection`

- Connection ID is `keccak256(uncompressed_public_key)` (64-byte x||y without 0x04 prefix). Handler
  verifies the supplied Connection ID matches the supplied public key.
- ECDSA secp256k1 signature is verified over the registration data. Invalid signatures are rejected.
- Verifier contract must be a deployed contract. If the contract does not exist, registration fails.
- Handler calls `verifyConfig(config_proof_bytes)` on the verifier to obtain verified peer configuration.
  If verification fails, registration fails.
- Duplicate Connection ID is rejected.
- Initial Connection status is ACTIVE. Queue metadata is initialized: `next_message_id = 1`,
  `acked_message_id = 0`, both running hashes are 32 bytes of zeros.
- Registration is permissionless — no special key required.
- The verifier is immutable after registration; its address and code fingerprint are stored on the Connection.

### 3.2.2 Connection Status Transitions

- ACTIVE → PAUSED automatically when a response ordering violation is detected during `submitBundle`
  processing. No admin action triggers this transition.
- ACTIVE → CLOSING via `closeConnection` (admin key required).
- PAUSED → ACTIVE automatically when the next bundle contains correctly ordered responses (if admin
  hasn't closed). No admin action triggers this transition.
- PAUSED → CLOSING via `closeConnection` (admin key required). The Connection drains once the peer
  fixes the response ordering.
- CLOSING → DRAINED automatically when the peer acknowledges all outbound messages.
- DRAINED → CLOSED automatically when both sides are DRAINED.
- CLOSED is terminal; no further transitions are accepted.

### 3.2.3 PAUSED Behavior

- No new outbound messages are accepted (`sendMessage` is rejected).
- Inbound bundles containing out-of-order responses are rejected — nothing is processed, nothing
  dispatched, no acks updated.
- Outbound syncs continue (queued messages still flow to the peer).
- `nextResponseExpectedId` does not advance.
- Auto-resumes when the next bundle has correctly ordered responses — no admin intervention required.
- Admin MAY close a PAUSED Connection (`closeConnection` transitions to CLOSING). The Connection cannot
  drain until the peer fixes the ordering; once fixed, bundles process with `CONNECTION_CLOSED` responses
  and queues drain normally.

### 3.2.4 CLOSING Behavior

- No new outbound messages are accepted (`sendMessage` is rejected).
- In-flight Data Messages receive `CONNECTION_CLOSED` responses without dispatch. No slashing occurs.
- Acknowledgements continue flowing. Queues drain.
- The `ClprQueueMetadata.state` reflects the Connection status and is propagated to the peer via the next sync.
  The peer transitions its side to CLOSING as well.
- When the peer has acknowledged all outbound messages, the Connection transitions to `DRAINED`.
- Transitions to CLOSED automatically when both sides are `DRAINED`.
- Closing is irreversible — there is no resume from CLOSING or DRAINED.

### 3.2.5 CLOSED Behavior

- All bundle submissions are rejected. No further processing occurs.
- `sendMessage` calls are rejected.
- The Connection cannot be reopened.

### 3.2.6 Invalid Transition Rejection

- `closeConnection` on a CLOSING, DRAINED, or CLOSED connection is rejected.
- `closeConnection` on an ACTIVE or PAUSED connection succeeds (transitions to CLOSING).
- No admin halt or resume transactions exist.

## 3.3 Connector Lifecycle

### 3.3.1 `registerConnector`

- Requires initial balance and stake meeting minimum thresholds.
- Connection must be ACTIVE.
- The caller becomes the Connector admin.
- Cross-chain mapping `(connection_id, source_connector_address) → local_connector` is established.

### 3.3.2 `topUpConnector` / `withdrawConnectorBalance`

- Top-up increases available balance.
- Withdrawal cannot exceed available balance; locked stake cannot be withdrawn while registered.
- Only the Connector admin can perform these operations.

### 3.3.3 `deregisterConnector`

- Returns remaining balance and locked stake.
- Rejected if the Connector has unresolved in-flight messages.
- Only the Connector admin can deregister.

## 3.4 Message Enqueue and Queue Invariants

### 3.4.1 `sendMessage`

- Connection must be ACTIVE.
- Connector must be registered and authorized. The CLPR Service calls `authorizeMessage()` on the
  Connector's authorization contract. If the Connector rejects, the message is not enqueued.
- Payload size must not exceed the destination's `max_message_payload_bytes`.
- Queue depth must not exceed `max_queue_depth`.
- Message is assigned `next_message_id`; the counter increments.
- Running hash is updated: `SHA-256(sent_running_hash || serialized_payload)`.
- The `sender` field is stamped from the transaction caller (not caller-supplied).

### 3.4.2 Running Hash Chain Integrity

- Across any sequence of sendMessage and redactMessage operations, the running hash chain remains
  consistent: each message's `running_hash_after_processing` equals
  `SHA-256(previous_running_hash || serialized_payload)`.
- Redacted messages retain their original running hash (computed over the original payload before
  redaction).
- The hash chain is verifiable from any starting point given the prior running hash and the sequence of
  payloads.

### 3.4.3 Message ID Monotonicity and Contiguity

- Message IDs are strictly increasing: each new message gets `next_message_id`, which increments by 1.
- No gaps in the ID sequence (IDs are contiguous).
- Message IDs are per-Connection (independent counters across Connections).

### 3.4.4 Queue Depth Enforcement

- When the queue reaches `max_queue_depth`, new sendMessage calls are rejected.
- When the peer acknowledges messages (advancing `acked_message_id`), queue space opens and sendMessage
  succeeds again.

## 3.5 Bundle Verification (`submitBundle`)

### 3.5.1 Verifier Invocation

- The CLPR Service calls the Connection's verifier contract with the proof bytes from the submitted payload.
- If the verifier rejects the proof (reverts), the entire bundle is rejected. The submitting endpoint pays
  the transaction cost and is not reimbursed.
- If the verifier returns data, the CLPR Service proceeds with protocol-level verification.

### 3.5.2 Replay Defense

- Every message ID in the bundle must be strictly greater than `received_message_id`. A bundle containing
  any message ID ≤ `received_message_id` is rejected outright, regardless of the verifier's output.
- Message IDs within the bundle must be contiguous and ascending.

### 3.5.3 Running Hash Verification

- The CLPR Service recomputes the cumulative hash starting from `received_running_hash`, applying
  `SHA-256(prev_hash || serialized_payload)` for each message in the bundle sequentially.
- The computed final hash must match the state-proven `sent_running_hash` from the verifier output.
- Hash mismatch causes the entire bundle to be rejected.

### 3.5.4 Payload Size Enforcement

- Each message payload in the bundle is checked against `max_message_payload_bytes`. If any payload
  exceeds the limit, the entire bundle is rejected.

### 3.5.5 Acknowledgement Processing

- After a valid bundle is processed, the Connection's `received_message_id` and `received_running_hash`
  are updated to reflect the last message in the bundle.
- The peer's `received_message_id` (from queue metadata in the bundle) is used to update the local
  Connection's `acked_message_id`.
- If the peer's `received_message_id` is less than or equal to the current `acked_message_id`, the
  acknowledgement is a no-op (no backward movement).
- A bundle containing zero messages but valid queue metadata still updates acknowledgements — the metadata
  exchange may advance `acked_message_id` even with no new messages to deliver.

### 3.5.6 Lazy Config Propagation

- If the Connection's `last_config_timestamp` is less than the current configuration's
  `consensus_timestamp`, a ConfigUpdate Control Message is enqueued on the Connection's outbound queue
  before processing new messages from this bundle.
- The ConfigUpdate is enqueued at a deterministic point in the message stream — after acknowledgement
  processing and before new messages generated by this bundle's dispatch.

## 3.6 Response Ordering

### 3.6.1 Sequential Response Generation

- Responses are generated in the same order as the originating Data Messages arrived. Each
  `ClprMessageReply` carries the `message_id` of the originating message.
- Control Messages and Response Messages in the queue are skipped during the response ordering walk.

### 3.6.2 Ordering Verification on Source

- The source ledger walks its queue of unresponded Data Messages in order and matches each incoming
  response to the next expected Data Message.
- If a response does not match the next expected Data Message, the Connection automatically transitions to
  PAUSED. The Connection auto-resumes when the next bundle contains correctly ordered responses.

### 3.6.3 Data Message Retention

- Data Messages in the outbound queue are retained after acknowledgement until their corresponding response
  arrives. Response Messages and Control Messages are deleted on ack.

## 3.7 Message Redaction

### 3.7.1 `redactMessage`

- Only the CLPR Service Admin can redact messages.
- The message payload is removed but the message slot and `running_hash_after_processing` are retained.
- The destination receives the message slot with an empty payload and a `REDACTED` indicator.
- The originating application receives a `REDACTED` response.
- Redaction of an already-delivered message is rejected.

## 3.8 Economic Invariants

### 3.8.1 Connector Charging

- When a Data Message is successfully delivered and the Connector is found and funded, the Connector is
  charged execution cost plus margin. The margin is paid to the submitting endpoint.
- When the Connector is not found or underfunded, the message is still dispatched to the target application
  and a response is generated normally. Connector status does not affect application delivery — Connector
  misbehavior must not lead to application failure. The submitting endpoint fronts the execution cost.
  Additionally, a `CONNECTOR_NOT_FOUND` or `CONNECTOR_UNDERFUNDED` status is produced and returned to the
  source ledger to trigger slashing. 

### 3.8.2 Source-Side Slashing

- When a `CONNECTOR_NOT_FOUND` or `CONNECTOR_UNDERFUNDED` response arrives on the source ledger, the
  source Connector's locked stake is slashed.
- `SUCCESS`, `APPLICATION_ERROR`, and `REDACTED` responses do not trigger slashing.

### 3.8.3 Slashing Escalation

- Repeated Connector-attributable failures result in escalating penalties.
- If the Connector's locked stake is exhausted, the Connector is banned from the Connection.

### 3.8.4 Fund Conservation

- After any sequence of operations, total funds in the system are conserved: no funds are created or
  destroyed. Connector charges equal endpoint reimbursements plus any surplus retained by the CLPR Service
  (if applicable). Slashed funds equal the sum of endpoint compensations.

### 3.8.5 Per-Endpoint Submission Throttle

- Each registered endpoint gets `1 / num_registered_endpoints` of the total bundle submission capacity.
- Submissions exceeding this quota are rejected and the submitter is charged.

## 3.9 Misbehavior Detection

### 3.9.1 Excess Sync Frequency

- When a remote endpoint exceeds its fair share of `MaxSyncsPerSec` (with ~10% tolerance), the local
  endpoint shuns it — refusing further syncs from that endpoint.
- Frequency is measured in sync rounds or blocks, not wall-clock time.
- A tolerance band is applied near round boundaries.

### 3.9.2 Duplicate Bundle Submission

- If a local endpoint submits the same bundle more than once, the duplicate is detected. The first
  submission succeeds; subsequent submissions are rejected and the submitter is charged.
- The offending local endpoint is slashed and removed from the local roster.
- When two *different* endpoints submit the same bundle, only the first succeeds; the second is rejected
  but this is normal deduplication, not misbehavior — no slashing occurs.

## 3.10 Throttle Enforcement

### 3.10.1 `MaxMessagesPerBundle`

- A bundle containing more messages than `max_messages_per_bundle` is rejected.

### 3.10.2 `MaxSyncBytes`

- A `ClprSyncPayload` whose serialized size exceeds `max_sync_bytes` is rejected before deserialization.
- If `max_sync_bytes` is configured too low (less than `max_message_payload_bytes` plus protocol overhead),
  no valid bundle can be constructed — the Connection is effectively deadlocked. This configuration should
  be prevented by throttle validation in `setLedgerConfiguration`.

### 3.10.3 `MaxGasPerMessage`

- A message whose execution exceeds `max_gas_per_message` is handled according to the platform's gas
  metering. On EVM platforms, the application callback receives a fixed gas stipend.

## 3.11 Metadata Exchange

### 3.11.1 `verifyMetadata`

- The verifier's `verifyMetadata(bytes) → ClprQueueMetadata` method is invoked during the optional metadata
  exchange phase. If verification succeeds, the endpoint uses the peer's `received_message_id` to optimize
  the subsequent bundle (avoiding duplicate messages).
- When metadata exchange is skipped, the endpoint falls back to the on-chain `acked_message_id`, which may
  lag. Bundles may contain duplicate messages that are harmlessly filtered by the receiver's replay defense.
- When metadata exchange is performed, subsequent bundles start from the peer's verified
  `received_message_id`, producing smaller bundles with no duplicates.

## 3.12 Mixed Message Type Processing

### 3.12.1 Bundles with Interleaved Message Types

- A bundle containing a mix of Data Messages, Response Messages, and Control Messages is processed
  correctly: each message type is routed to its handler (Data Messages to the Payment & Routing layer,
  Response Messages to the originating application, Control Messages to the CLPR Service).
- The running hash chain is computed over all message types in the interleaved order.
- All messages in the bundle share the same running hash chain regardless of type.

### 3.12.2 Control Messages Do Not Generate Responses

- When a Control Message (e.g., ConfigUpdate) is received in a bundle, it is processed by the CLPR Service
  directly. No response is enqueued in the outbound queue. No Connector is involved.

### 3.12.3 Partial Bundle Failure Independence

- A failure on one message in a bundle (e.g., Connector not found for one Data Message) does not stop
  processing of the remaining messages in the bundle. Each message is processed independently.

## 3.13 Redaction and Verification Interaction

### 3.13.1 Redacted Message in Bundle Verification

- When a bundle arrives containing a redacted message slot (empty payload with retained running hash),
  the receiver uses the stored `running_hash_after_processing` directly rather than recomputing from the
  absent payload. Verification of subsequent messages in the bundle continues from this stored hash.

### 3.13.2 Redaction and Response Ordering

- When a redacted message is received on the destination, a `REDACTED` response is generated and enqueued.
  On the source, the ordering walk correctly matches the `REDACTED` response to the original message slot.

## 3.14 Seed Endpoints

### 3.14.1 Seed Endpoints in Configuration

- The ledger configuration includes seed endpoints (up to 10) used for initial peer discovery.
- Seed endpoints are propagated to peers via ConfigUpdate Control Messages alongside other configuration
  changes.

## 3.15 Endpoint Registration (Permissionless Ledgers)

### 3.15.1 Registration with Bond

- On permissionless ledgers (Ethereum and others), an endpoint registers by calling `registerEndpoint`
  and posting a bond. The bond must meet the minimum threshold.
- Deregistration returns the bond, subject to the constraint that no in-flight submissions are pending.

### 3.15.2 Unregistered Endpoint Submission

- An account that is not a registered endpoint calling `submitBundle` is rejected.

## 3.16 Multiple Connections Sharing a Verifier

### 3.16.1 Same Verifier on Multiple Connections

- Two Connections may reference the same verifier contract address. Each Connection maintains independent
  state (queue metadata, Connector registrations) despite sharing the verifier.

---

# 4. Multi-Ledger Integration Tests

These tests verify end-to-end CLPR behavior across two or more independent ledger networks. They require the
multi-network infrastructure described in Section 2.

## 4.1 Hiero-to-Hiero

### 4.1.1 End-to-End Message Flow

Send a Data Message on Ledger A. Verify: the message is enqueued, an endpoint syncs it to Ledger B, the CLPR
Service on B processes the bundle, the application on B receives the message and generates a response, the
response is enqueued on B's outbound queue, an endpoint syncs the response back to A, and the application on
A receives the response with `SUCCESS` status. Verify queue metadata on both sides is consistent.

### 4.1.2 Bidirectional Simultaneous Messaging

Both ledgers send Data Messages to each other concurrently. Verify that messages flow in both directions
without interference, responses are correctly correlated to their originating messages, and queue metadata
on both sides advances correctly.

### 4.1.3 Connection Lifecycle Under Traffic

While messages are actively flowing:
- Trigger a PAUSED state (inject a response ordering violation). Verify new `sendMessage` calls are
  rejected, inbound bundles with out-of-order responses are rejected, outbound syncs continue, and existing
  queued messages continue syncing.
- Auto-resume: submit a bundle with correctly ordered responses. Verify the Connection transitions back
  to ACTIVE and normal operation resumes.
- Close the Connection via `closeConnection`. Verify the Connection transitions to CLOSING, the
  `ClprQueueMetadata.state` carries the Connection status and propagates to the peer, in-flight messages
  receive `CONNECTION_CLOSED` responses without dispatch, the Connection transitions to DRAINED when the
  peer acks all outbound messages, and the Connection reaches CLOSED when both sides are DRAINED.

### 4.1.4 Config Propagation Across Ledgers

Change a throttle value on Ledger A. Verify that a ConfigUpdate Control Message is lazily enqueued at the
next interaction, delivered to Ledger B via the normal sync mechanism, and that Ledger B enforces the new
throttle for subsequent messages. Verify that messages enqueued before the ConfigUpdate are accepted under
the old configuration.

With multiple active Connections from Ledger A to different peers, verify that the ConfigUpdate is enqueued
on every active Connection (not just the one that happened to interact next).

Change the configuration multiple times before any sync occurs. Verify that multiple ConfigUpdate Control
Messages are enqueued in order and that the peer processes each one, applying the final configuration.

### 4.1.5 Roster Rotation (Ship of Theseus)

Rotate individual consensus nodes on both networks one at a time until all original nodes are replaced.
Verify that CLPR communication continues throughout the rotation, that TSS-based state proof verification
is unaffected by the roster change, and that no messages are lost.

### 4.1.6 Multiple Connections Between the Same Pair

Register two Connections between A and B with different verifiers (e.g., different commitment levels).
Verify that each Connection maintains independent queue state, independent message ID sequences, and
independent Connector registrations.

### 4.1.7 Multiple Connectors on the Same Connection

Register two Connectors on the same Connection. Send messages through each. Verify that slashing one
Connector does not affect the other, that applications can send through either, and that each Connector's
balance and stake are tracked independently.

## 4.2 Hiero-to-Ethereum

### 4.2.1 End-to-End Message Flow

Send a Data Message from Hiero to Ethereum. Verify: the Hiero endpoint constructs a TSS proof, the
Ethereum CLPR Service's verifier contract validates it, the application on Ethereum receives the message
and generates a response, and the response flows back to Hiero through the Ethereum endpoint and the Hiero
bundle submission pipeline.

### 4.2.2 Verifier Cross-Validation

Deploy a Hiero TSS verifier on Ethereum and an Ethereum BLS verifier on Hiero. Verify that both verifiers
correctly validate proofs from their respective source ledgers. Submit known-good and known-bad proofs to
each verifier and verify accept/reject behavior.

### 4.2.3 Gas Cost Profiling

Measure and record Ethereum gas costs for: `registerConnection`, `registerConnector`, `sendMessage`,
`submitBundle`, `closeConnection`. Verify that costs are within acceptable bounds for production deployment.
Verify that `max_sync_bytes` is calibrated to keep bundle submission within Ethereum block gas limits.

### 4.2.4 Block Confirmation Latency

Measure end-to-end message delivery latency with Ethereum's ~12-second block times compared to Hiero's
sub-second finality. Verify that the asynchronous pipeline handles the latency asymmetry correctly —
messages queue on the faster side without backpressure issues.

### 4.2.5 Commitment Level Enforcement

Deploy verifiers with different commitment levels (`latest`, `safe`, `finalized`). Verify that each
verifier accepts proofs only at its configured commitment level. If testable in the local environment,
simulate a chain reorganization and verify that a `latest`-level verifier accepts a proof that is later
invalidated, while a `finalized`-level verifier would have rejected it.

## 4.3 Ethereum-to-Ethereum

### 4.3.1 End-to-End Message Flow

Send a Data Message between two EVM networks. Verify the full cycle using the Solidity CLPR Service
contract on both sides. This validates the Ethereum implementation independently of Hiero.

### 4.3.2 Gas Optimization Validation

Measure gas costs for storage-intensive operations (message enqueue, bundle processing, Connector charging).
Verify that storage patterns (aggressive payload deletion after acknowledgement) are effective at reducing
long-term gas costs.

### 4.3.3 Proxy Upgrade Testing

Deploy the CLPR Service behind an upgradeable proxy. Perform a proxy upgrade while the Connection is active
with in-flight messages. Verify that the upgrade does not corrupt Connection state, queue metadata, or
Connector balances.

## 4.4 Multi-Ledger Topologies (N >= 3)

### 4.4.1 Hub-and-Spoke

Deploy three networks: Hiero as hub, Ethereum and a second Hiero as spokes. Register Connections
Hub↔Spoke1 and Hub↔Spoke2. Send messages on both Connections simultaneously. Verify that traffic on one
Connection does not affect the other — independent queues, independent Connectors, independent throughput.

### 4.4.2 Triangle Topology

Deploy three networks with Connections A↔B, B↔C, and C↔A. Send messages around the ring. Verify each
Connection operates independently. This topology is the foundation for multi-ledger atomic swap testing
(Section 6.4).

### 4.4.3 Connection Isolation

In any multi-connection topology, verify that pausing, closing, or overloading one Connection has no effect
on other Connections — even when they share endpoints or Connectors.

---

# 5. Adversarial Tests

These tests verify that the protocol handles misbehavior correctly. Each test deploys a specific adversarial
component and verifies the protocol's response.

## 5.1 Malicious Verifier

### 5.1.1 Fabricated Queue Metadata

Deploy a verifier that returns correct message payloads but fabricated queue metadata (wrong
`received_message_id`). Verify that the CLPR Service detects the inconsistency through its own running hash
and message ID checks — the verifier's queue metadata does not match the hash chain.

### 5.1.2 Fabricated Messages

Deploy a verifier that returns entirely fabricated messages (messages that were never sent by the peer).
Verify that the fabricated messages pass the verifier check (since the verifier is the trust root) but are
processed by the CLPR Service — demonstrating that verifier compromise leads to data compromise. This test
validates the trust model documentation: the verifier is the single point of trust.

### 5.1.3 Replayed Proofs

Deploy a verifier that accepts replayed (old but previously valid) proofs. Verify that the CLPR Service's
monotonic message ID check (`message_id > received_message_id`) rejects the bundle regardless of the
verifier's output.

### 5.1.4 Denial of Service Verifier

Deploy a verifier that rejects all proofs (always reverts). Verify that the submitting endpoint pays the
transaction cost, the Connection remains ACTIVE (no state change from a rejected bundle), and the peer can
retry with a different endpoint.

## 5.2 Misbehaving Endpoints

### 5.2.1 Duplicate Bundle Submission

Have the same endpoint submit the same bundle twice. Verify the first submission succeeds and the second
is rejected. The duplicate submitter is charged for the failed transaction.

### 5.2.2 Excess Sync Frequency

Have a remote endpoint send syncs faster than its fair share of `MaxSyncsPerSec`. Verify the local
endpoint detects the violation and shuns the offender — refusing further syncs from that specific endpoint
while continuing to sync with others.

### 5.2.3 Invalid Proof Submission

Have an endpoint submit a bundle with proof bytes that the verifier rejects. Verify the bundle is rejected,
the endpoint pays the transaction cost, and the Connection remains ACTIVE.

### 5.2.4 Oversized Payload

Submit a bundle containing a message whose payload exceeds `max_message_payload_bytes`. Verify the entire
bundle is rejected.

### 5.2.5 Broken Running Hash

Submit a bundle where the messages are valid but the running hash chain is incorrect (a message payload was
tampered with after the proof was generated). Verify the CLPR Service detects the hash mismatch and rejects
the entire bundle.

### 5.2.6 Non-Contiguous Message IDs

Submit a bundle where message IDs skip a value (e.g., IDs 5, 6, 8 — missing 7). Verify the bundle is
rejected due to non-contiguous IDs.

## 5.3 Connector Failure Scenarios

### 5.3.1 Connector Not Found on Destination

Send a message through a Connector that exists on the source but has not been registered on the destination.
Verify: the message is still dispatched to the target application and a response is generated normally —
Connector misbehavior does not prevent application delivery. The submitting endpoint fronts the execution
cost. A `CONNECTOR_NOT_FOUND` status is returned to the source ledger, where the source Connector's locked
stake is slashed.

### 5.3.2 Connector Underfunded on Destination

Send a message through a Connector whose destination-side balance is insufficient. Verify: the message is
still dispatched to the target application and a response is generated normally. The destination Connector's
locked stake is slashed and the submitting endpoint is compensated. A `CONNECTOR_UNDERFUNDED` status is
returned to the source ledger, where the source Connector is slashed.

### 5.3.3 Deregistration Guard

Attempt to deregister a Connector that has unresolved in-flight messages. Verify the operation is rejected.
Wait for all messages to resolve, then verify deregistration succeeds and funds are returned.

### 5.3.4 Authorization Rejection

Deploy a Connector whose authorization contract rejects all messages. Call sendMessage. Verify the message
is not enqueued, no queue state changes, and the caller pays only the failed transaction fee.

### 5.3.5 Destination Connector Deregisters While Messages In Transit

Register a Connector on both source and destination. Send a message. Before the message is synced to the
destination, deregister the Connector on the destination side. When the bundle arrives, the destination
resolves the `connector_id` and finds no matching Connector. Verify `CONNECTOR_NOT_FOUND` behavior (failure
response, source-side slashing). This tests the cross-ledger timing scenario where the Connector's presence
on one side changes after the source-side authorization.

## 5.4 Response Ordering Violations

### 5.4.1 Out-of-Order Responses

Construct a scenario where the peer ledger sends responses out of order (e.g., response to message 3
arrives before response to message 2). Verify the source ledger's CLPR Service detects the violation and
automatically transitions the Connection to PAUSED.

### 5.4.2 PAUSED Connection Behavior

After a response ordering violation triggers PAUSED, verify: new `sendMessage` calls are rejected, inbound
bundles containing out-of-order responses are rejected (nothing processed, nothing dispatched, no acks
updated), outbound syncs continue (queued messages still flow), and `nextResponseExpectedId` does not
advance.

### 5.4.3 Auto-Resume After Fix

After the peer fixes the ordering bug, submit a bundle with correctly ordered responses. Verify the
Connection automatically transitions back to ACTIVE and normal operation continues — messages can be
enqueued, bundles can be submitted, and responses are correctly ordered. No admin intervention is required.

### 5.4.4 Admin Closes a PAUSED Connection

While a Connection is PAUSED, call `closeConnection`. Verify the Connection transitions to CLOSING. Submit
a bundle with out-of-order responses and verify it is still rejected (the ordering violation persists even in
CLOSING). Then submit a bundle with correctly ordered responses: verify the bundle is processed with
`CONNECTION_CLOSED` responses (since the Connection is CLOSING), and the Connection drains through
DRAINED → CLOSED normally.

### 5.4.5 CLOSING Propagation via Metadata

Call `closeConnection` on an ACTIVE Connection. Verify: the Connection transitions to CLOSING, the
`ClprQueueMetadata.state` carries the Connection status, the peer sees the state during the next sync and
transitions its side to CLOSING as well. When the peer acknowledges all outbound messages, the Connection
transitions to DRAINED. When both sides are DRAINED, the Connection transitions to CLOSED.

### 5.4.6 In-Flight Message Resolution During CLOSING

While a Connection is CLOSING, Data Messages that arrive from the peer (sent before the peer knew about the
close) receive `CONNECTION_CLOSED` responses without dispatch. Verify: no Connector is charged, no slashing
occurs, and the response is returned to the source.

### 5.4.7 Connector Deregistration After Close

After a Connection transitions through CLOSING and DRAINED to CLOSED, verify that Connectors with no remaining in-flight
messages can successfully deregister and recover their balance and stake.

## 5.5 Network-Level Attacks

### 5.5.1 Network Partition

Drop all gRPC traffic between the two networks. Verify messages queue up on the source to
`max_queue_depth`, backpressure engages (new sendMessage calls rejected), and when the partition heals,
syncs resume and all queued messages are delivered without loss.

### 5.5.2 Selective Endpoint Partitioning

Make one specific endpoint on the destination unreachable while others remain available. Verify that the
source endpoints discover alternative peers and continue syncing through the available endpoints.

### 5.5.3 Replay of Old Valid Bundles

Re-submit a bundle that was valid and accepted in a previous sync. Verify the monotonic message ID check
rejects it — the message IDs are now ≤ `received_message_id`.

## 5.6 EVM-Specific Adversarial Tests

### 5.6.1 Reentrancy During Application Dispatch

On an Ethereum CLPR Service, deploy a malicious application contract that attempts to re-enter the CLPR
Service during its message callback (e.g., calling `sendMessage` from within the dispatch handler). Verify
the reentrancy guard prevents the re-entrance. Verify the CLPR Service follows checks-effects-interactions
ordering: state is updated before the application callback is invoked.

### 5.6.2 Gas Exhaustion During Callback

Deploy an application contract whose callback consumes excessive gas. Verify the fixed gas stipend limits
execution cost and that the CLPR Service handles the revert gracefully (generates an `APPLICATION_ERROR`
response without corrupting Connection state).

---

# 6. Application Workflow Tests

These tests verify real-world application patterns built on CLPR. Each test deploys application contracts on
both ledgers and exercises the full stack from user action through CLPR message flow to cross-ledger
settlement and response.

## 6.1 Fungible Token Transfer

### 6.1.1 Lock-and-Mint

Lock tokens in an escrow contract on the source ledger. Send a CLPR message to the destination requesting
minting of equivalent tokens. Verify: escrow holds the locked amount on the source, the destination
application mints the equivalent, the response returns to the source confirming success, and balances on
both ledgers are consistent.

### 6.1.2 Burn-and-Mint

Burn tokens on the source ledger. Send a CLPR message to the destination requesting minting. Verify the
burn is irreversible on the source, the mint occurs on the destination, and the response confirms the
operation.

### 6.1.3 Failure Handling

Attempt a transfer where the destination application reverts (e.g., minting cap exceeded). Verify: the
source receives an `APPLICATION_ERROR` response, the escrowed/burned assets on the source are handled
according to the application's error recovery logic, and no assets are created on the destination.

### 6.1.4 Roundtrip

Transfer tokens A→B, then transfer tokens B→A. Verify net balances on both ledgers return to their
original state (minus any fees).

## 6.2 NFT Transfer

### 6.2.1 Lock-and-Mint with Metadata Preservation

Lock an NFT on the source ledger. Send a CLPR message containing the NFT's metadata (token ID, URI,
properties). Verify the destination mints an equivalent NFT with the same metadata, and the source NFT
remains locked.

### 6.2.2 Return Path

Burn the minted NFT on the destination. Send a CLPR message to the source requesting unlock. Verify the
original NFT is unlocked on the source with its metadata intact.

### 6.2.3 Ownership Verification

At each step of the transfer lifecycle, verify ownership on both ledgers: the source holder loses
ownership when the NFT is locked, the destination holder gains ownership when the NFT is minted, and
vice versa on return.

## 6.3 Escrow Patterns

### 6.3.1 Conditional Release

Escrow assets on the source ledger. Send a CLPR message to the destination with a release condition.
The destination evaluates the condition and responds with success or failure. On success, the escrow is
released. On failure, the escrow is returned to the sender.

### 6.3.2 Timeout and Expiry

Escrow assets with a deadline. If the response does not arrive within the deadline, the escrow
automatically releases back to the sender. Verify timeout-based release works correctly even when the
response eventually arrives (the response should be handled gracefully — the escrow is already released).

### 6.3.3 Connection Closure During Escrow

Close the Connection while assets are escrowed and the response has not arrived. Verify the application
detects the Connection closure, handles the ambiguous state, and provides a reconciliation path (e.g.,
querying the remote ledger out-of-band to determine whether the release condition was met).

## 6.4 Multi-Ledger Atomic Swap (N >= 3)

### 6.4.1 Three-Party Swap

Three ledgers (A, B, C) with Connections A↔B, B↔C, C↔A. Party on A sends token X to B, party on B
sends token Y to C, party on C sends token Z to A. Verify all three legs complete atomically — all
succeed or none succeed.

### 6.4.2 Partial Failure

One leg of the three-party swap fails (e.g., the Connector on B↔C is underfunded). Verify all legs
roll back — no party is left holding the wrong asset.

### 6.4.3 Timeout Coordination

All legs of the swap must respect the same deadline despite potentially different block times on each
ledger. Verify that if the deadline expires on any leg, all legs abort consistently.

## 6.5 Lightweight DEX

### 6.5.1 Cross-Ledger Order Matching and Settlement

Deploy an order book on the source ledger. A user places a buy order for a token on the destination
ledger. The DEX matches the order, sends a CLPR message to execute the settlement, and the response
confirms the trade. Verify assets are exchanged correctly on both sides.

### 6.5.2 Concurrent Order Execution

Multiple users execute trades in parallel across the same Connection. Verify that all trades settle
correctly, no double-spends occur, and the CLPR message ordering guarantees are reflected in the trade
execution order.

## 6.6 Application Library Validation

### 6.6.1 Common Pattern Coverage

Verify that the patterns above (lock/mint, burn/mint, escrow, atomic swap) are implementable using a
shared CLPR application library. The library provides: send message, receive callback, response handling,
error recovery, and balance reconciliation.

### 6.6.2 Library API Completeness

Exercise every public API of the application library in the context of at least one of the workflow tests
above. Verify that the library handles all `ClprMessageReplyStatus` values correctly.

---

# 7. Performance and Longevity Tests

These tests measure CLPR behavior under sustained load and at scale. They map to the existing CITR
performance tiers (SDCT, SDLT, SDPT) but with cross-ledger traffic.

## 7.1 Throughput

### 7.1.1 Single Connection Saturation

Send messages at increasing rates through a single Connection with a single Connector until the queue
reaches `max_queue_depth` or the system can no longer keep up. Record the maximum sustained message rate.

### 7.1.2 Multi-Connection Throughput

Send messages across multiple Connections simultaneously. Verify that per-Connection throughput is
independent and that total system throughput scales with the number of active Connections.

### 7.1.3 Throughput vs. Payload Size

Measure throughput at payload sizes of 1 KB, 10 KB, 100 KB, and `max_message_payload_bytes`. Record the
relationship between payload size and maximum message rate.

### 7.1.4 Throughput vs. Bundle Size

Measure throughput with bundles of 1, 10, 100, and `max_messages_per_bundle` messages. Determine the
optimal bundle size for maximizing throughput.

## 7.2 Latency

### 7.2.1 End-to-End Latency

Measure the time from `sendMessage` on the source to response delivery on the source, at p50, p95, and p99
under idle and loaded conditions.

### 7.2.2 Latency Breakdown

Instrument the pipeline to measure each phase: enqueue time, time waiting in queue, sync interval, proof
construction time, bundle submission time, application execution time, response enqueue, response sync,
response delivery.

### 7.2.3 Cross-Platform Latency Comparison

Compare Hiero-to-Hiero, Hiero-to-Ethereum, and Ethereum-to-Ethereum latency profiles. Document the
impact of Ethereum's ~12-second block time on end-to-end latency.

## 7.3 Queue Behavior Under Load

### 7.3.1 Fill and Drain Rates

At various message throughput levels, measure the rate at which the queue fills (messages enqueued per
second) vs. the rate at which it drains (messages acknowledged per second). Identify the throughput level
at which the queue becomes a bottleneck.

### 7.3.2 Backpressure Behavior

Saturate the queue to `max_queue_depth`. Verify that sendMessage calls are rejected cleanly with an
appropriate error. Verify that when the queue drains (peer catches up), sendMessage resumes.

### 7.3.3 Recovery Time After Backpressure

After the queue drains below `max_queue_depth`, measure how long it takes for the system to return to
steady-state throughput.

## 7.4 Longevity

### 7.4.1 Sustained Cross-Ledger Traffic

Run CLPR traffic alongside normal Hiero workload (crypto transfers, HCS, HTS) for 24 hours. Verify no
degradation in throughput, latency, or error rates over time.

### 7.4.2 State Growth

Measure the growth of CLPR state (Connection records, message store, Connector balances) over extended
operation. Verify that acknowledged messages are deleted and state does not grow unboundedly.

### 7.4.3 Resource Consumption

Monitor memory, CPU, and thread pool utilization of the endpoint sync orchestrator over 24+ hours. Verify
no memory leaks, thread exhaustion, or resource accumulation.

## 7.5 Endpoint Scaling

### 7.5.1 Throughput vs. Endpoint Count

Measure total system throughput with 4, 10, 25, and 100 endpoints. Determine whether throughput scales
linearly with endpoint count and identify the point of diminishing returns.

### 7.5.2 Per-Endpoint Submission Throttle

With varying endpoint counts, verify that the per-endpoint submission limit
(total bundle submission capacity divided by number of registered endpoints) is correctly enforced and that
aggregate submission rate stays within the global limit. Verify the quota adjusts when endpoints are added
or removed.

### 7.5.3 Peer Discovery Convergence

With a large number of endpoints, measure how long it takes for a new endpoint to discover enough peers
via gossip to achieve full throughput. Verify that reciprocity-based peer selection converges to efficient
pairings.

---

# 8. Recovery and Fault Tolerance Tests

These tests verify the recovery scenarios enumerated in the CLPR specification (§4, R1–R9) and additional
fault scenarios relevant to production operation.

## 8.1 Endpoint Rotation (R1, R3, R9)

### 8.1.1 Single Endpoint Replaced

Remove one endpoint from the roster and add a new one. Verify the new endpoint discovers peers via gossip,
syncs resume through the new endpoint, and no messages are lost.

### 8.1.2 Complete Roster Rotation (One Side)

Replace all endpoints on one side while the other side remains stable. Verify that seed endpoints in the
peer's configuration provide fallback bootstrap and syncs resume with the new roster.

### 8.1.3 Simultaneous Rotation (Both Sides)

Replace all endpoints on both sides simultaneously. Verify both sides discover new peers via gossip once
any connectivity is restored.

## 8.2 Proof Format Upgrade (R2, R4)

### 8.2.1 New Connection with New Verifier

While the old Connection is active, register a new Connection with a verifier that supports the new proof
format. Verify the new Connection is independently operational.

### 8.2.2 Application Migration

Migrate application traffic from the old Connection to the new one. Verify that messages sent on the new
Connection are delivered and responses return correctly.

### 8.2.3 Old Connection Closure

Close the old Connection after traffic has been migrated. Verify the closure is clean and does not affect
the new Connection.

### 8.2.4 Combined Rotation and Format Change

Simultaneously rotate endpoints AND change the proof format. Verify that a new Connection with a new
verifier and new endpoints is operational.

## 8.3 Verifier Compromise (R5)

### 8.3.1 Close Affected Connection

Admin closes a Connection whose verifier is suspected of being compromised via `closeConnection`. Verify
the Connection transitions to CLOSING, in-flight messages receive `CONNECTION_CLOSED` responses, queues
drain through DRAINED, and the Connection reaches CLOSED.

### 8.3.2 Migrate to Patched Verifier

Register a new Connection with a patched verifier. Verify applications can migrate and the new Connection
is operational.

### 8.3.3 Verify Compromised Connection Reaches Terminal State

After the compromised Connection drains through CLOSING and DRAINED, verify it reaches CLOSED and no further
interactions are possible.

## 8.4 Queue Corruption / Response Ordering Violation (R6)

### 8.4.1 Automatic PAUSE on Ordering Violation

Inject an out-of-order response from the peer. Verify the Connection automatically transitions to PAUSED.

### 8.4.2 Auto-Resume After Fix

After the peer fixes the ordering bug, submit a bundle with correctly ordered responses. Verify the
Connection automatically transitions back to ACTIVE and subsequent responses are correctly ordered. No
admin intervention is required.

### 8.4.3 Persistent PAUSE When Peer Never Fixes

If the peer never fixes the response ordering, the Connection remains PAUSED indefinitely. Verify: syncs
continue both directions, and no new messages are accepted. The admin may close the PAUSED Connection
(transitions to CLOSING), but the Connection cannot drain until the peer fixes the ordering. While CLOSING
with unresolved ordering, bundles with out-of-order responses are still rejected.

## 8.5 Network Partition (R7, R8)

### 8.5.1 Temporary Partition

Partition the networks for a short period (e.g., 30 seconds). Verify messages queue during the partition,
syncs resume automatically when connectivity returns, and all queued messages are delivered with no loss.

### 8.5.2 Extended Partition with Backpressure

Partition the networks long enough for the queue to fill to `max_queue_depth`. Verify backpressure engages,
new messages are rejected, and when the partition heals, the queue drains and normal operation resumes.

### 8.5.3 Peer Ledger Down Entirely

Shut down all nodes on the peer ledger. Verify messages queue up, backpressure eventually engages, and
when the peer returns, syncs resume from where they left off.

## 8.6 Node Restart and Upgrade

### 8.6.1 Single Node Restart

Restart a single consensus node while CLPR traffic is active. Verify other endpoints continue serving,
the restarted node rejoins and resumes sync participation, and no messages are lost.

### 8.6.2 Rolling Upgrade

Perform a rolling upgrade of all nodes (one at a time) while CLPR traffic is active. Verify traffic
continues without interruption throughout the upgrade.

### 8.6.3 Freeze and Restart

Perform a freeze/restart cycle (the standard Hiero upgrade procedure). Verify CLPR state survives the
restart, Connections are intact, queue metadata is preserved, and syncs resume after the restart.

---

# 9. Cross-Reference: Spec Section to Test Coverage

This table maps each CLPR specification section to the test IDs that cover it. Empty cells indicate gaps
in coverage that should be evaluated.

| Spec Section | Feature | Single-Ledger (§3) | Multi-Ledger (§4) | Adversarial (§5) | App Workflow (§6) | Performance (§7) | Recovery (§8) |
|---|---|---|---|---|---|---|---|
| §3.1.1 Configuration | ChainID, throttles, timestamps, seed endpoints | 3.1.1, 3.1.2, 3.10.1, 3.10.2, 3.10.3, 3.14.1 | 4.1.4 | — | — | — | — |
| §3.1.2 Endpoint Roster | Per-endpoint throttle, peer selection, registration | 3.8.5, 3.9.1, 3.15.1, 3.15.2 | 4.1.5 | 5.2.2, 5.5.2 | — | 7.5.1, 7.5.2, 7.5.3 | 8.1.1, 8.1.2, 8.1.3 |
| §3.1.3 Connections | Registration, lifecycle, status machine | 3.2.1, 3.2.2, 3.2.3, 3.2.4, 3.2.5, 3.2.6, 3.16.1 | 4.1.3, 4.1.6 | 5.4.4, 5.4.5, 5.4.6, 5.4.7 | 6.3.3 | — | 8.2.1, 8.3.1, 8.3.3 |
| §3.1.4 Endpoint Protocol | Sync cycle, metadata exchange | 3.11.1 | 4.1.1, 4.2.1, 4.3.1 | 5.2.1, 5.2.2 | — | 7.2.2 | 8.5.1 |
| §3.1.5 Verifier Contracts | verifyConfig, verifyBundle, verifyMetadata, trust | 3.5.1, 3.11.1 | 4.2.2 | 5.1.1, 5.1.2, 5.1.3, 5.1.4 | — | — | 8.3.1, 8.3.2 |
| §3.1.6 Misbehavior Detection | Frequency, duplicate submission | 3.9.1, 3.9.2 | — | 5.2.1, 5.2.2 | — | — | — |
| §3.2.1 Queue Metadata | next_message_id, acked, running hash | 3.4.3, 3.4.4 | 4.1.1 | — | — | 7.3.1, 7.3.2 | — |
| §3.2.2 Message Storage | Payload types, running hash chain, mixed types | 3.4.2, 3.7.1, 3.12.1, 3.12.2 | — | 5.2.5 | — | — | — |
| §3.2.3 Bundle Transport | Bundle construction, state proofs | 3.10.1 | 4.1.1, 4.2.1 | 5.2.4, 5.2.6, 5.5.3 | — | 7.1.4 | — |
| §3.2.4 Bundle Verification | Replay defense, hash verification | 3.5.2, 3.5.3, 3.5.4, 3.5.5 | — | 5.1.1, 5.1.3, 5.2.5, 5.2.6 | — | — | — |
| §3.2.5 Throughput Limits | Payload size, bundle size, sync size, gas | 3.5.4, 3.4.1, 3.10.1, 3.10.2, 3.10.3 | — | 5.2.4 | — | 7.1.3, 7.1.4 | — |
| §3.2.6 Redaction | Payload removal, hash preservation, ordering | 3.7.1, 3.13.1, 3.13.2 | — | — | — | — | — |
| §3.2.7 Response Ordering | Sequential responses, PAUSED trigger, auto-resume, walk logic | 3.6.1, 3.6.2, 3.6.3, 3.12.1 | 4.1.3 | 5.4.1, 5.4.2, 5.4.3, 5.4.4 | — | — | 8.4.1, 8.4.2, 8.4.3 |
| §3.3.1 Connectors | Registration, cross-chain mapping | 3.3.1, 3.3.2, 3.3.3 | 4.1.7 | 5.3.3, 5.3.4, 5.3.5 | — | — | — |
| §3.3.2 Sending | Authorization, enqueue, sender stamp | 3.4.1 | 4.1.2 | 5.3.4 | 6.1.1, 6.2.1 | 7.1.1 | — |
| §3.3.3 Receiving | Dispatch, charging, margin, partial failure | 3.8.1, 3.12.3 | 4.1.1, 4.2.1 | 5.3.1, 5.3.2 | 6.1.3 | — | — |
| §3.3.4 Slashing | Penalties, escalation, reimbursement | 3.8.1, 3.8.2, 3.8.3, 3.8.4 | — | 5.3.1, 5.3.2, 5.3.5 | — | — | — |
| §3.4 Application Layer | Commitment levels, app patterns | — | 4.2.5 | — | 6.1–6.6 | — | — |
| §4 Recovery Scenarios | R1–R9 | — | 4.1.5 | 5.5.1, 5.5.2 | 6.3.3 | — | 8.1–8.6 |
| Ethereum-specific | Gas, reentrancy, proxy, EVM semantics | — | 4.2.3, 4.3.2, 4.3.3 | 5.6.1, 5.6.2 | — | — | — |
| Config propagation | Lazy enqueue, timestamp, multi-Connection | 3.5.6 | 4.1.4 | — | — | — | — |
| Economic conservation | Fund arithmetic, no creation/destruction | 3.8.4 | — | — | — | — | — |
