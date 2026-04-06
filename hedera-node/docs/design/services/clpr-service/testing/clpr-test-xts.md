# CLPR XTS Test Definitions

XTS (Extended Test Suite) runs every 3 hours on `main` and is expected to complete within 3 hours.
CLPR XTS tests extend beyond MATS in two directions: (1) cross-ledger integration tests that require
two Hiero networks communicating via CLPR, and (2) adversarial tests that deploy intentionally
malicious or broken components and verify the protocol's response. XTS also covers the more complex
single-ledger scenarios deferred from MATS.

XTS requires the multi-network SOLO infrastructure described in
[clpr-test-solo-multi-network.md](clpr-test-solo-multi-network.md). Two Hiero networks are deployed
in the same Kubernetes cluster with CLPR enabled and cross-namespace gRPC connectivity.

> These test definitions are subject to change as the CLPR specification evolves during implementation.
> The current spec version is the source of truth for behavioral requirements.

---

# 1. Common Test Infrastructure

## 1.1 Test Harness Requirements

XTS CLPR tests require:

- **Two Hiero networks** deployed via SOLO in separate Kubernetes namespaces, each with CLPR enabled,
  block node, and mirror node. See [SOLO Multi-Network Guide](clpr-test-solo-multi-network.md).
- **Cross-namespace gRPC connectivity** so CLPR endpoints on Network A can reach endpoints on Network B.
- **A cross-network test driver** that holds SDK/RPC connections to both networks and can submit
  transactions and query state on either side.
- **Test verifier contracts** deployed on each network that accept proofs constructed by the test driver.
  For end-to-end tests, use real Hiero TSS verifiers. For adversarial tests, use configurable mock
  verifiers that can be told to accept, reject, or return fabricated data.
- **Test Connector authorization contracts** that can be configured to accept or reject messages.
- **Test application contracts** deployed on both networks that send messages, receive messages, and
  record responses for assertion.

## 1.2 Common Setup Procedures

**Deploy two networks.** Follow the SOLO multi-network deployment guide. Configure each network with
CLPR enabled and distinct chain IDs.

**Establish a Connection.** On each network:
1. Deploy a verifier contract.
2. Generate a shared ECDSA keypair.
3. Register the Connection on both sides using the shared keypair.
4. Verify both sides show the Connection as ACTIVE.

**Register Connectors.** On each network, register a Connector with sufficient balance and stake.
The source-side Connector specifies the destination-side Connector's address in the cross-chain mapping.

**Deploy test applications.** Deploy a simple echo application on the destination (receives a message,
returns the payload as the response) and a sending application on the source (sends messages, records
responses).

## 1.3 Common Verification Procedures

**End-to-end message verification.** After sending a message on Network A:
1. Wait for the message to appear in A's outbound queue.
2. Wait for an endpoint sync to deliver it to Network B (poll B's `received_message_id`).
3. Wait for B to process the message, generate a response, and enqueue it.
4. Wait for an endpoint sync to deliver the response back to A.
5. Verify A's application received the response with the expected status and payload.

**Timeout.** Cross-ledger flows depend on sync timing. Allow up to 60 seconds for a complete
send-deliver-respond-return cycle between two Hiero networks.

**Queue metadata consistency.** After a flow completes, verify that both sides' queue metadata is
consistent: A's `acked_message_id` matches B's `received_message_id`, and vice versa.

---

# 2. End-to-End Message Flow

**Reference:** clpr-test-spec.md §4.1.1

**Purpose:** Verify the complete lifecycle of a single message from source application to destination
application and back.

**Properties to Test:**

- P1: A message sent on Network A is delivered to the application on Network B.
- P2: The application on Network B generates a response that is delivered back to Network A.
- P3: The response carries `SUCCESS` status and the expected reply data.
- P4: Queue metadata on both sides is consistent after the flow completes.

**Setup:**

Follow common setup (§1.2): two networks, one Connection, Connectors on both sides, test applications
deployed.

**Steps:**

1. On Network A, send a message through the source application specifying the Connection, Connector,
   destination application, and a test payload.

2. Wait for the end-to-end flow to complete (per §1.3 verification procedure).

3. On Network A, query the source application's response store.
   → Assert: response received for the sent message. Status is `SUCCESS`. Reply data matches the echo
   of the original payload.

4. Query Connection queue metadata on both sides.
   → Assert: A's `next_message_id` advanced. B's `received_message_id` matches. Running hashes are
   consistent.

**Cleanup:** None — state is per-Connection.

---

# 3. Bidirectional Simultaneous Messaging

**Reference:** clpr-test-spec.md §4.1.2

**Purpose:** Verify that messages flow correctly in both directions concurrently without interference.

**Properties to Test:**

- P1: Messages sent from A to B and from B to A simultaneously are all delivered.
- P2: Responses are correctly correlated to their originating messages on both sides.
- P3: Queue metadata on both sides advances correctly.

**Setup:**

Common setup with test applications on both sides capable of both sending and receiving.

**Steps:**

1. Concurrently: send message M_AB from A to B, and send message M_BA from B to A.

2. Wait for both end-to-end flows to complete.

3. On Network A, verify: response received for M_AB, and M_BA was received and processed by A's
   application.

4. On Network B, verify: response received for M_BA, and M_AB was received and processed by B's
   application.

5. Verify queue metadata consistency on both sides.

**Cleanup:** None.

---

# 4. Connection Lifecycle Under Traffic

**Reference:** clpr-test-spec.md §4.1.3

**Purpose:** Verify the PAUSED, CLOSING, DRAINED, and CLOSED lifecycle while messages are actively
flowing.

**Properties to Test:**

- P1: A response ordering violation triggers PAUSED. `sendMessage` is rejected while PAUSED.
- P2: Correctly ordered responses auto-resume the Connection to ACTIVE.
- P3: `closeConnection` transitions to CLOSING. The Connection status propagates to the peer via
  `ClprQueueMetadata.state`.
- P4: In-flight messages during CLOSING receive `CONNECTION_CLOSED` responses without dispatch.
- P5: The Connection drains through DRAINED to CLOSED when both sides acknowledge all messages.

**Setup:**

Common setup with active message traffic.

**Steps:**

1. Send several messages from A to B. Verify normal delivery.

2. To trigger PAUSED: configure the test environment to produce an out-of-order response on B.
   Submit the bundle containing the out-of-order response on A.
   → Assert: Connection on A transitions to PAUSED.

3. Attempt `sendMessage` on A.
   → Assert: rejected (PAUSED).

4. Submit a bundle from B with correctly ordered responses.
   → Assert: Connection on A auto-resumes to ACTIVE. Message sending works again.

5. Send additional messages, then call `closeConnection` on A (admin key).
   → Assert: Connection on A transitions to CLOSING.

6. Wait for the next sync. Verify B's Connection also transitions to CLOSING (learned via
   `ClprQueueMetadata.state`).

7. Wait for queues to drain (all messages acknowledged on both sides).
   → Assert: Connection transitions through DRAINED to CLOSED on both sides.

**Cleanup:** None — Connection is closed.

---

# 5. Configuration Propagation Across Ledgers

**Reference:** clpr-test-spec.md §4.1.4

**Purpose:** Verify that configuration changes propagate via ConfigUpdate Control Messages and that
the peer enforces the new configuration.

**Properties to Test:**

- P1: A throttle change on Network A produces a ConfigUpdate delivered to Network B.
- P2: Network B enforces the new throttle for subsequent messages.
- P3: Messages enqueued before the ConfigUpdate are accepted under the old configuration.
- P4: With multiple active Connections, all receive the ConfigUpdate.

**Setup:**

Common setup with an active Connection and message traffic flowing.

**Steps:**

1. Record the current `max_message_payload_bytes` on both sides.

2. On Network A, call `setLedgerConfiguration` to reduce `max_message_payload_bytes`.

3. Send a message on A (this triggers lazy enqueue of the ConfigUpdate).

4. Wait for the ConfigUpdate to be delivered to B via the normal sync mechanism.

5. On Network B, verify the peer's stored configuration reflects the new throttle value.

6. Attempt to send a message from B to A with a payload that exceeds A's new limit but is within
   the old limit.
   → Assert: B's CLPR Service rejects the message (destination's advertised limit exceeded).

**Cleanup:** Restore the original configuration.

---

# 6. Roster Rotation (Ship of Theseus)

**Reference:** clpr-test-spec.md §4.1.5

**Purpose:** Verify CLPR communication survives complete consensus node rotation on both networks.

**Properties to Test:**

- P1: CLPR messages continue to flow after individual node rotations.
- P2: After all original nodes on both sides have been replaced, messages still flow correctly.
- P3: TSS-based proof verification is unaffected by the roster change.

**Setup:**

Common setup with active message traffic.

**Steps:**

1. Send a message and verify end-to-end delivery (baseline).

2. Rotate one node on Network A (remove and add a replacement).

3. Send another message and verify delivery.

4. Repeat node rotations until all original nodes on A are replaced.

5. Send a message and verify delivery.

6. Repeat the process on Network B.

7. Send a final message and verify delivery — both networks have completely new rosters.

**Cleanup:** None.

---

# 7. Multiple Connections Between the Same Pair

**Reference:** clpr-test-spec.md §4.1.6

**Purpose:** Verify that multiple Connections between the same ledger pair maintain independent state.

**Properties to Test:**

- P1: Two Connections with different verifiers maintain independent queue metadata.
- P2: Messages sent on one Connection do not appear on the other.
- P3: Closing one Connection does not affect the other.

**Setup:**

Register two Connections (different keypairs, potentially different verifiers) between A and B.
Register Connectors on both.

**Steps:**

1. Send a message on Connection 1. Verify delivery. Check Connection 2's queue metadata is unchanged.

2. Send a message on Connection 2. Verify delivery. Check Connection 1's queue metadata is unchanged.

3. Close Connection 1.

4. Send a message on Connection 2.
   → Assert: delivery succeeds. Connection 2 is unaffected by Connection 1's closure.

**Cleanup:** None.

---

# 8. Multiple Connectors on the Same Connection

**Reference:** clpr-test-spec.md §4.1.7

**Purpose:** Verify that multiple Connectors operate independently on the same Connection.

**Properties to Test:**

- P1: Messages can be sent through either Connector.
- P2: Slashing one Connector does not affect the other.
- P3: Each Connector's balance and stake are tracked independently.

**Setup:**

Register two Connectors on the same Connection with separate authorization contracts.

**Steps:**

1. Send a message through Connector 1. Verify delivery and that Connector 1 is charged.

2. Send a message through Connector 2. Verify delivery and that Connector 2 is charged.

3. Trigger a `CONNECTOR_UNDERFUNDED` scenario for Connector 1 (reduce its destination balance below
   the execution cost). Send a message through Connector 1.
   → Assert: Connector 1 is slashed. Connector 2's balance and stake are unchanged.

4. Send a message through Connector 2.
   → Assert: delivery succeeds normally. Connector 2 is unaffected.

**Cleanup:** None.

---

# 9. Malicious Verifier: Fabricated Queue Metadata

**Reference:** clpr-test-spec.md §5.1.1

**Purpose:** Verify that the CLPR Service detects fabricated queue metadata from a compromised verifier
through its own running hash and message ID checks.

**Properties to Test:**

- P1: A verifier that returns correct payloads but fabricated `received_message_id` is caught by
  the protocol's monotonic ID check.
- P2: The bundle is rejected. The Connection remains ACTIVE.

**Setup:**

Deploy a configurable mock verifier on Network B that returns valid message payloads but a fabricated
`received_message_id` (e.g., a value less than the current `received_message_id`).

**Steps:**

1. Send messages from A to B to establish some baseline queue state.

2. Configure the mock verifier to return a `received_message_id` lower than B's current value.

3. Submit a bundle with the fabricated metadata.
   → Assert: rejected (replay defense — ID is not strictly greater than `received_message_id`).
   Connection remains ACTIVE.

**Cleanup:** Reconfigure the verifier to normal behavior.

---

# 10. Malicious Verifier: Replayed Proofs

**Reference:** clpr-test-spec.md §5.1.3

**Purpose:** Verify that the CLPR Service's monotonic ID check catches replayed bundles even if the
verifier accepts them.

**Properties to Test:**

- P1: A previously valid bundle is rejected on re-submission because its message IDs are now
  ≤ `received_message_id`.

**Setup:**

Common setup with a completed message flow (bundle was accepted, IDs advanced).

**Steps:**

1. Submit a valid bundle. Verify it is accepted and `received_message_id` advances.

2. Re-submit the same bundle.
   → Assert: rejected. The message IDs are now ≤ `received_message_id`.

**Cleanup:** None.

---

# 11. Malicious Verifier: Denial of Service

**Reference:** clpr-test-spec.md §5.1.4

**Purpose:** Verify that a verifier that rejects all proofs does not corrupt Connection state.

**Properties to Test:**

- P1: The bundle is rejected. The submitting endpoint pays the cost.
- P2: The Connection remains ACTIVE and accepts valid bundles from other endpoints.

**Setup:**

Deploy a mock verifier configured to revert on all `verifyBundle` calls.

**Steps:**

1. Submit a bundle to the Connection with the rejecting verifier.
   → Assert: rejected (verifier reverted). Connection remains ACTIVE.

2. Reconfigure the verifier to accept. Submit a valid bundle.
   → Assert: accepted. Normal processing resumes.

**Cleanup:** Reconfigure verifier.

---

# 12. Duplicate Bundle Submission

**Reference:** clpr-test-spec.md §5.2.1

**Purpose:** Verify that duplicate bundle submissions are handled correctly.

**Properties to Test:**

- P1: The first submission succeeds.
- P2: A duplicate from the same endpoint is rejected and the submitter is charged.

**Setup:**

Common setup with a valid bundle ready to submit.

**Steps:**

1. Submit a valid bundle from an endpoint.
   → Assert: accepted.

2. Submit the same bundle again from the same endpoint.
   → Assert: rejected (duplicate). The endpoint pays the transaction cost.

**Cleanup:** None.

---

# 13. Oversized Payload in Bundle

**Reference:** clpr-test-spec.md §5.2.4

**Purpose:** Verify that a bundle containing an oversized message is rejected entirely.

**Properties to Test:**

- P1: The entire bundle is rejected, not just the oversized message.
- P2: No queue state changes occur.

**Setup:**

Construct a bundle where one message's payload exceeds `max_message_payload_bytes`.

**Steps:**

1. Submit the bundle.
   → Assert: rejected. `received_message_id` unchanged. Connection ACTIVE.

**Cleanup:** None.

---

# 14. Broken Running Hash in Bundle

**Reference:** clpr-test-spec.md §5.2.5

**Purpose:** Verify that a bundle with an incorrect running hash chain is rejected.

**Properties to Test:**

- P1: The CLPR Service detects the hash mismatch and rejects the entire bundle.

**Setup:**

Construct a bundle where a message payload has been tampered with after the proof was generated,
causing the recomputed hash to diverge from the state-proven value.

**Steps:**

1. Submit the bundle with the tampered payload.
   → Assert: rejected (running hash mismatch). Connection ACTIVE.

**Cleanup:** None.

---

# 15. Non-Contiguous Message IDs in Bundle

**Reference:** clpr-test-spec.md §5.2.6

**Purpose:** Verify that a bundle with gaps in message IDs is rejected.

**Properties to Test:**

- P1: Message IDs must be contiguous and ascending within a bundle.

**Setup:**

Construct a bundle with message IDs [5, 6, 8] — missing 7.

**Steps:**

1. Submit the bundle.
   → Assert: rejected (non-contiguous IDs).

**Cleanup:** None.

---

# 16. Connector Not Found on Destination

**Reference:** clpr-test-spec.md §5.3.1

**Purpose:** Verify that a missing destination Connector does not prevent application delivery and
triggers appropriate slashing.

**Properties to Test:**

- P1: The message is still dispatched to the target application.
- P2: A `CONNECTOR_NOT_FOUND` status is returned to the source.
- P3: The source Connector's locked stake is slashed.

**Setup:**

Register a Connector on the source but not on the destination for a given Connection.

**Steps:**

1. Send a message from A through the source-only Connector.

2. Wait for delivery on B. Verify the application on B received the message (Connector absence does
   not prevent dispatch).

3. Wait for the failure response to return to A.
   → Assert: response carries `CONNECTOR_NOT_FOUND` status. Source Connector's locked stake is reduced.

**Cleanup:** None.

---

# 17. Connector Underfunded on Destination

**Reference:** clpr-test-spec.md §5.3.2

**Purpose:** Verify that an underfunded destination Connector triggers slashing but does not prevent
application delivery.

**Properties to Test:**

- P1: The message is still dispatched to the target application.
- P2: The destination Connector's locked stake is slashed.
- P3: A `CONNECTOR_UNDERFUNDED` status is returned to the source, triggering source-side slashing.

**Setup:**

Register a Connector on the destination with a balance below the execution cost of a message.

**Steps:**

1. Send a message from A.

2. Wait for delivery on B. Verify the application on B received the message.

3. Verify the destination Connector's locked stake was slashed.

4. Wait for the failure response to return to A.
   → Assert: `CONNECTOR_UNDERFUNDED` status. Source Connector slashed.

**Cleanup:** None.

---

# 18. Response Ordering Violation: PAUSED and Auto-Resume

**Reference:** clpr-test-spec.md §5.4.1, §5.4.2, §5.4.3

**Purpose:** Verify the complete PAUSED lifecycle: out-of-order responses trigger PAUSED, correctly
ordered responses auto-resume.

**Properties to Test:**

- P1: Out-of-order responses trigger PAUSED.
- P2: While PAUSED, bundles with out-of-order responses are rejected. Outbound syncs continue.
- P3: A bundle with correctly ordered responses auto-resumes the Connection.

**Setup:**

Send messages M1, M2, M3 from A to B. Configure the test environment so B produces responses in
the wrong order.

**Steps:**

1. Submit a bundle on A containing out-of-order responses (e.g., response to M2 before M1).
   → Assert: Connection on A transitions to PAUSED.

2. Attempt `sendMessage` on A.
   → Assert: rejected (PAUSED).

3. Verify outbound syncs from A to B continue (A's queued messages are still available for sync).

4. Submit a bundle on A with correctly ordered responses.
   → Assert: Connection auto-resumes to ACTIVE. The correctly ordered responses are processed.

**Cleanup:** None.

---

# 19. Admin Closes a PAUSED Connection

**Reference:** clpr-test-spec.md §5.4.4

**Purpose:** Verify that an admin can close a PAUSED Connection and it drains correctly.

**Properties to Test:**

- P1: `closeConnection` on a PAUSED Connection transitions to CLOSING.
- P2: The Connection cannot drain until the peer fixes the ordering.
- P3: Once ordering is fixed, bundles process with `CONNECTION_CLOSED` responses and queues drain.

**Setup:**

Trigger PAUSED by submitting out-of-order responses (per Test 18 steps 1-2).

**Steps:**

1. With the Connection PAUSED, call `closeConnection`.
   → Assert: Connection transitions to CLOSING.

2. Submit a bundle with out-of-order responses.
   → Assert: still rejected (ordering violation persists even in CLOSING).

3. Submit a bundle with correctly ordered responses.
   → Assert: responses processed with `CONNECTION_CLOSED` status (since Connection is CLOSING).
   Queues begin draining.

4. Wait for full drain.
   → Assert: Connection transitions through DRAINED to CLOSED.

**Cleanup:** None — Connection is closed.

---

# 20. CLOSING Propagation via Queue Metadata

**Reference:** clpr-test-spec.md §5.4.5

**Purpose:** Verify that closing a Connection propagates the status to the peer via
`ClprQueueMetadata.state`.

**Properties to Test:**

- P1: The peer learns about the CLOSING state during the next sync.
- P2: The peer transitions its side to CLOSING as well.
- P3: Both sides drain to CLOSED.

**Setup:**

Common setup with active message traffic.

**Steps:**

1. Call `closeConnection` on Network A.
   → Assert: A's Connection transitions to CLOSING.

2. Wait for the next sync cycle.

3. Query Network B's Connection status.
   → Assert: B's Connection has transitioned to CLOSING (learned via queue metadata).

4. Wait for both sides to drain.
   → Assert: both sides reach CLOSED.

**Cleanup:** None.

---

# 21. In-Flight Message Resolution During CLOSING

**Reference:** clpr-test-spec.md §5.4.6

**Purpose:** Verify that messages arriving on a CLOSING Connection receive `CONNECTION_CLOSED`
responses without dispatch.

**Properties to Test:**

- P1: Data Messages that arrive after the Connection enters CLOSING are not dispatched to the
  application.
- P2: `CONNECTION_CLOSED` responses are generated. No Connector is charged. No slashing occurs.

**Setup:**

Send messages from A, then close A's Connection before all messages have been delivered to B.

**Steps:**

1. Send several messages from A.

2. Call `closeConnection` on A before all messages have been synced to B.

3. Wait for the remaining messages to be synced to B (B's endpoint receives the bundle).

4. On B, verify: the messages that arrived after B learned of the CLOSING state receive
   `CONNECTION_CLOSED` responses. The application on B was not invoked for those messages.

5. Verify: no Connector was charged for the closed messages. No slashing occurred.

**Cleanup:** None.

---

# 22. Network Partition and Recovery

**Reference:** clpr-test-spec.md §5.5.1

**Purpose:** Verify that messages queue during a network partition and are delivered when connectivity
returns.

**Properties to Test:**

- P1: Messages sent during the partition are queued on the source.
- P2: When the partition heals, syncs resume and all queued messages are delivered.
- P3: No messages are lost.

**Setup:**

Common setup with active message traffic.

**Steps:**

1. Send a message and verify delivery (baseline).

2. Partition the two networks (block gRPC traffic between namespaces).

3. Send additional messages on A. Verify they are enqueued (next_message_id advances) but not
   delivered (B's received_message_id does not advance).

4. Heal the partition (restore gRPC traffic).

5. Wait for syncs to resume.
   → Assert: all queued messages are delivered to B. Responses return to A. No messages lost.

**Cleanup:** Ensure partition is healed.

---

# 23. Mixed Message Type Bundles

**Reference:** clpr-test-spec.md §3.12.1

**Purpose:** Verify correct processing of bundles containing interleaved Data Messages, Response
Messages, and Control Messages.

**Properties to Test:**

- P1: Each message type is routed to its correct handler.
- P2: The running hash chain is computed over all types in the interleaved order.
- P3: Control Messages do not generate responses.

**Setup:**

Construct a bundle on Network B that contains a mix: a new Data Message from B, a response to a
previously sent message from A, and a ConfigUpdate Control Message.

**Steps:**

1. Send messages from A to B to create the conditions for B to have responses to send.

2. Change B's configuration to trigger a ConfigUpdate.

3. Send a new message from B to A.

4. Wait for B's next outbound bundle. It should contain a mix of the Data Message, responses, and
   the ConfigUpdate.

5. After A processes the bundle, verify: the Data Message was dispatched to A's application, the
   responses were matched to A's outbound messages, and the ConfigUpdate was applied to A's stored
   peer configuration. No response was generated for the ConfigUpdate.

**Cleanup:** None.

---

# 24. Partial Bundle Failure Independence

**Reference:** clpr-test-spec.md §3.12.3

**Purpose:** Verify that a failure on one message in a bundle does not block processing of the
remaining messages.

**Properties to Test:**

- P1: A bundle with one underfunded-Connector message and two valid messages processes all three.
- P2: The underfunded message gets a failure response. The valid messages get success responses.

**Setup:**

Register two Connectors on the same Connection. Underfund one. Send messages through both, then
submit the resulting bundle on the destination.

**Steps:**

1. Send three messages from A: two through the funded Connector, one through the underfunded one.

2. Wait for delivery on B.
   → Assert: all three messages are processed. The two funded messages get `SUCCESS` responses. The
   underfunded message gets `CONNECTOR_UNDERFUNDED`. The underfunded Connector is slashed but the
   funded Connector is unaffected.

**Cleanup:** None.

---

# 25. Connector Deregistration After Connection Close

**Reference:** clpr-test-spec.md §5.4.7

**Purpose:** Verify that Connectors can deregister after a Connection reaches CLOSED.

**Properties to Test:**

- P1: After the Connection is fully CLOSED, a Connector with no in-flight messages can deregister.
- P2: Balance and stake are returned.

**Setup:**

Establish a Connection with a Connector, close the Connection, and wait for it to reach CLOSED.

**Steps:**

1. Close the Connection and wait for full drain to CLOSED.

2. Call `deregisterConnector`.
   → Assert: SUCCESS. Balance and stake returned.

**Cleanup:** None.

---

# 26. Fund Conservation Invariant

**Reference:** clpr-test-spec.md §3.8.4

**Purpose:** Verify that total funds in the system are conserved across a sequence of operations.

**Properties to Test:**

- P1: After a complete message lifecycle (send, deliver, charge, respond, slash or not slash), the
  sum of all Connector balances, endpoint balances, and CLPR Service held funds equals the starting
  total.

**Setup:**

Common setup. Record all fund balances before the test.

**Steps:**

1. Record total funds: Connector balances + Connector stakes + endpoint account balances.

2. Execute a sequence of messages: some successful, some triggering slashing.

3. Record total funds again.
   → Assert: the total is unchanged. Connector charges equal endpoint reimbursements. Slashed funds
   equal endpoint compensations.

**Cleanup:** None.

---

# 27. Redacted Message in Bundle Verification

**Reference:** clpr-test-spec.md §3.13.1

**Purpose:** Verify that a bundle containing a redacted message slot is correctly verified using the
stored running hash.

**Properties to Test:**

- P1: The receiver uses the stored `running_hash_after_processing` for the redacted slot rather than
  recomputing from the absent payload.
- P2: Verification of subsequent messages in the bundle continues correctly from the stored hash.

**Setup:**

Send a message, redact it (admin), then arrange for the redacted slot to be included in a bundle
delivered to the peer.

**Steps:**

1. On A, send messages M1, M2, M3.

2. Redact M2 (admin operation).

3. Wait for the bundle containing M1, M2 (redacted), M3 to be synced to B.

4. On B, verify: M1 was received with its payload. M2 was received as a redacted slot. M3 was
   received with its payload. The running hash chain is valid across all three (using the stored
   hash for the redacted slot). B generates a `REDACTED` response for M2 and normal responses for
   M1 and M3.

**Cleanup:** None.

---

# 28. Deferred Single-Ledger Tests

The following tests were deferred from MATS because they require more complex setup or multi-step
orchestration. They run within a single ledger but exercise protocol invariants and edge cases
beyond basic handler correctness.

## 28.1 Running Hash Chain Integrity Across Operations

**Reference:** clpr-test-spec.md §3.4.2

**Purpose:** Verify the running hash chain remains consistent across a mixed sequence of sendMessage
and redactMessage operations.

**Properties to Test:**

- P1: After a sequence of sends and redactions, the running hash at any point equals the expected
  cumulative SHA-256 computation from the initial state.
- P2: Redacted messages retain their original running hash (computed over the pre-redaction payload).

**Setup:**

Common setup with an active Connection and Connector.

**Steps:**

1. Send M1. Record `sent_running_hash` (should be `SHA-256(0x00..00 || M1_payload)`).
2. Send M2. Record hash.
3. Redact M2.
4. Send M3. Record hash (should chain from M2's original hash, not a recomputed one).
5. Verify: the running hash at each step matches the expected cumulative computation.

**Cleanup:** None.

## 28.2 Message ID Monotonicity and Contiguity

**Reference:** clpr-test-spec.md §3.4.3

**Purpose:** Verify message IDs are strictly increasing, contiguous, and per-Connection.

**Properties to Test:**

- P1: Each `sendMessage` increments `next_message_id` by exactly 1.
- P2: Two different Connections maintain independent ID counters.

**Setup:**

Two active Connections on the same ledger.

**Steps:**

1. Send three messages on Connection 1. Verify IDs are 1, 2, 3.
2. Send two messages on Connection 2. Verify IDs are 1, 2 (independent counter).
3. Send another message on Connection 1. Verify ID is 4.

**Cleanup:** None.

## 28.3 Queue Depth Enforcement and Recovery

**Reference:** clpr-test-spec.md §3.4.4

**Purpose:** Verify that the queue rejects new messages at `max_queue_depth` and accepts them again
after acknowledgement frees space.

**Properties to Test:**

- P1: At `max_queue_depth`, `sendMessage` is rejected.
- P2: After acknowledgement advances `acked_message_id`, space opens and `sendMessage` succeeds.

**Setup:**

Configure `max_queue_depth` to a small value (e.g., 3). Register Connection and Connector.

**Steps:**

1. Send 3 messages (filling the queue to depth).
2. Attempt a 4th message. → Assert: rejected (queue full).
3. Submit a bundle whose acknowledgement advances `acked_message_id` by 1.
4. Send a message. → Assert: accepted (space freed).

**Cleanup:** None.

## 28.4 Data Message Retention Until Response

**Reference:** clpr-test-spec.md §3.6.3

**Purpose:** Verify that Data Messages are retained after acknowledgement until their response arrives,
while Response and Control Messages are deleted on ack.

**Properties to Test:**

- P1: An acknowledged Data Message remains queryable until its response is processed.
- P2: An acknowledged Response Message or Control Message is deleted immediately on ack.

**Setup:**

Send Data Messages and a ConfigUpdate (Control Message) on the same Connection. Submit bundles to
acknowledge them.

**Steps:**

1. Send Data Messages M1, M2 and trigger a ConfigUpdate.
2. Submit a bundle that acknowledges all three.
3. Verify: M1 and M2 are still retained (awaiting responses). The ConfigUpdate is deleted.
4. Submit responses for M1 and M2.
5. Verify: M1 and M2 are now deleted.

**Cleanup:** None.

## 28.5 Slashing Escalation

**Reference:** clpr-test-spec.md §3.8.3

**Purpose:** Verify that repeated Connector failures escalate penalties and eventually result in a ban.

**Properties to Test:**

- P1: Each successive failure slashes a larger amount (or the platform-defined escalation schedule).
- P2: When locked stake is exhausted, the Connector is banned from the Connection.

**Setup:**

Register a Connector with a known locked stake amount. Send multiple messages that will each
trigger `CONNECTOR_UNDERFUNDED` responses.

**Steps:**

1. Submit a failure response. Record the slash amount.
2. Submit a second failure response. Record the slash amount.
   → Assert: escalation (amount increased, or at least matched the schedule).
3. Continue submitting failure responses until the locked stake is exhausted.
   → Assert: Connector is banned from the Connection.

**Cleanup:** None.

## 28.6 Per-Endpoint Submission Throttle

**Reference:** clpr-test-spec.md §3.8.5

**Purpose:** Verify that each endpoint's submission rate is limited to its fair share of the global
capacity.

**Properties to Test:**

- P1: An endpoint exceeding its quota has submissions rejected.
- P2: The submitter is charged for the rejected submission.

**Setup:**

Register multiple endpoints. Set a global bundle submission capacity.

**Steps:**

1. From a single endpoint, submit bundles at a rate exceeding `capacity / num_endpoints`.
   → Assert: excess submissions rejected. Submitter charged.

**Cleanup:** None.

## 28.7 Excess Sync Frequency Detection

**Reference:** clpr-test-spec.md §3.9.1

**Purpose:** Verify that a remote endpoint exceeding its fair share of `MaxSyncsPerSec` is shunned.

**Properties to Test:**

- P1: Syncs exceeding the per-endpoint fair share (with ~10% tolerance) are detected.
- P2: The offending endpoint is shunned — further syncs from it are refused.
- P3: Frequency is measured in sync rounds or blocks, not wall-clock time.

**Setup:**

Two-network setup with a configured `MaxSyncsPerSec` and known endpoint count.

**Steps:**

1. Have a remote endpoint sync more frequently than its fair share.
2. Verify: the local endpoint detects the violation and shuns the offender.
3. Verify: syncs from other (non-offending) endpoints continue normally.

**Cleanup:** None.

## 28.8 Duplicate Bundle Submission: Same Endpoint Slashing

**Reference:** clpr-test-spec.md §3.9.2

**Purpose:** Verify that a local endpoint submitting the same bundle twice is slashed and removed,
while two different endpoints submitting the same bundle results in normal deduplication.

**Properties to Test:**

- P1: Same endpoint, same bundle: second submission rejected, endpoint slashed and removed.
- P2: Different endpoints, same bundle: second submission rejected, no slashing (normal dedup).

**Setup:**

Two-network setup with multiple local endpoints.

**Steps:**

1. Endpoint A submits a valid bundle. → Assert: accepted.
2. Endpoint A submits the same bundle again. → Assert: rejected. Endpoint A slashed and removed.
3. Reset. Endpoint A submits a valid bundle. → Assert: accepted.
4. Endpoint B submits the same bundle. → Assert: rejected but no slashing (normal deduplication).

**Cleanup:** None.

## 28.9 MaxMessagesPerBundle Enforcement

**Reference:** clpr-test-spec.md §3.10.1

**Purpose:** Verify that a bundle exceeding `max_messages_per_bundle` is rejected.

**Properties to Test:**

- P1: A bundle with messages count > `max_messages_per_bundle` is rejected entirely.

**Setup:**

Set `max_messages_per_bundle` to a small value (e.g., 5). Construct a bundle with 6 messages.

**Steps:**

1. Submit the oversized bundle. → Assert: rejected. No queue state change.

**Cleanup:** None.

## 28.10 MaxSyncBytes Enforcement

**Reference:** clpr-test-spec.md §3.10.2

**Purpose:** Verify that a sync payload exceeding `max_sync_bytes` is rejected.

**Properties to Test:**

- P1: A `ClprSyncPayload` exceeding `max_sync_bytes` is rejected before deserialization.

**Setup:**

Set `max_sync_bytes` to a value slightly larger than one message. Construct a bundle that exceeds it.

**Steps:**

1. Submit the oversized sync payload. → Assert: rejected.

**Cleanup:** None.

## 28.11 MaxGasPerMessage Enforcement

**Reference:** clpr-test-spec.md §3.10.3

**Purpose:** Verify that a message whose execution exceeds `max_gas_per_message` is handled by the
platform's gas metering.

**Properties to Test:**

- P1: An application callback that exceeds the gas budget is bounded by the platform's metering.

**Setup:**

Deploy an application contract that consumes variable gas. Set `max_gas_per_message`.

**Steps:**

1. Send a message to the gas-consuming application.
   → Assert: execution is bounded. An `APPLICATION_ERROR` response is generated if the callback
   exceeds the gas stipend.

**Cleanup:** None.

## 28.12 Metadata Exchange Optimization

**Reference:** clpr-test-spec.md §3.11.1

**Purpose:** Verify that the optional metadata exchange reduces duplicate messages in bundles.

**Properties to Test:**

- P1: With metadata exchange, bundles start from the peer's verified `received_message_id` —
  no duplicates.
- P2: Without metadata exchange, bundles may include messages already received by the peer
  (duplicates filtered by replay defense).

**Setup:**

Two-network setup with configurable metadata exchange behavior.

**Steps:**

1. Send messages. Perform a sync WITH metadata exchange. Observe the bundle contents.
   → Assert: bundle starts from the correct message (no duplicates).
2. Send more messages. Perform a sync WITHOUT metadata exchange.
   → Assert: bundle may include already-delivered messages. They are harmlessly rejected by the
   receiver's replay defense.

**Cleanup:** None.

## 28.13 Control Messages Do Not Generate Responses

**Reference:** clpr-test-spec.md §3.12.2

**Purpose:** Verify that when a Control Message (ConfigUpdate) is received, no response is enqueued.

**Properties to Test:**

- P1: After processing a bundle containing a ConfigUpdate, the outbound queue has no new Response
  Messages for the ConfigUpdate.

**Setup:**

Two-network setup. Change configuration on A to trigger a ConfigUpdate delivered to B.

**Steps:**

1. On A, change configuration. Wait for ConfigUpdate to be delivered to B in a bundle.
2. On B, inspect the outbound queue after processing.
   → Assert: no Response Message was generated for the ConfigUpdate. Only responses to Data Messages
   (if any were in the same bundle) appear.

**Cleanup:** None.

## 28.14 Redaction and Response Ordering Interaction

**Reference:** clpr-test-spec.md §3.13.2

**Purpose:** Verify that a redacted message's `REDACTED` response is correctly matched in the
response ordering walk on the source.

**Properties to Test:**

- P1: The source's ordering walk correctly matches the `REDACTED` response to the original message.
- P2: Subsequent responses continue to be matched correctly.

**Setup:**

Send M1, M2, M3. Redact M2. Wait for all to be delivered to the peer.

**Steps:**

1. Wait for the peer to process and generate responses: SUCCESS for M1, REDACTED for M2,
   SUCCESS for M3.
2. Wait for responses to return to the source.
3. Verify: the ordering walk matches R1→M1, R2(REDACTED)→M2, R3→M3 in order. No PAUSED
   transition. All three messages cleaned up.

**Cleanup:** None.

## 28.15 Seed Endpoints in Configuration

**Reference:** clpr-test-spec.md §3.14.1

**Purpose:** Verify that seed endpoints are stored in the configuration and propagated via ConfigUpdate.

**Properties to Test:**

- P1: Seed endpoints included in `setLedgerConfiguration` are stored and queryable.
- P2: Seed endpoints propagate to peers via ConfigUpdate Control Messages.

**Setup:**

Two-network setup.

**Steps:**

1. On A, set configuration including seed endpoint addresses.
2. Query A's configuration. → Assert: seed endpoints present.
3. Wait for ConfigUpdate to propagate to B.
4. On B, verify the peer configuration includes A's seed endpoints.

**Cleanup:** None.

## 28.16 Endpoint Registration and Bond (Permissionless Ledgers)

**Reference:** clpr-test-spec.md §3.15.1, §3.15.2

**Purpose:** Verify endpoint registration with bond and rejection of unregistered endpoint submissions.

**Properties to Test:**

- P1: `registerEndpoint` with sufficient bond succeeds.
- P2: `deregisterEndpoint` returns the bond.
- P3: An unregistered account calling `submitBundle` is rejected.

**Setup:**

On a permissionless ledger (Ethereum), deploy the CLPR Service and Connection.

**Steps:**

1. Call `registerEndpoint` with the required bond. → Assert: accepted.
2. From a non-registered account, call `submitBundle`. → Assert: rejected.
3. Call `deregisterEndpoint`. → Assert: bond returned.

**Cleanup:** None.

## 28.17 Multiple Connections Sharing a Verifier

**Reference:** clpr-test-spec.md §3.16.1

**Purpose:** Verify that two Connections can use the same verifier contract address with independent
state.

**Properties to Test:**

- P1: Both Connections operate independently despite sharing a verifier.
- P2: Messages on one Connection do not affect the other.

**Setup:**

Deploy one verifier contract. Register two Connections using the same verifier address but different
Connection IDs.

**Steps:**

1. Send a message on Connection 1. Verify delivery.
2. Verify Connection 2's queue metadata is unchanged.
3. Send a message on Connection 2. Verify delivery.
4. Verify Connection 1's queue metadata is unchanged.

**Cleanup:** None.

---

# 29. Additional Adversarial Tests

## 29.1 Fabricated Messages from Compromised Verifier

**Reference:** clpr-test-spec.md §5.1.2

**Purpose:** Verify that a compromised verifier returning entirely fabricated messages results in those
messages being accepted — demonstrating that verifier compromise leads to data compromise.

**Properties to Test:**

- P1: Fabricated messages pass the verifier check (the verifier is the trust root).
- P2: The CLPR Service processes the fabricated messages as if they were real.
- P3: This validates the trust model: the verifier is the single point of trust.

**Setup:**

Deploy a mock verifier configured to return fabricated messages (messages the peer never sent).

**Steps:**

1. Submit a bundle containing proof bytes that the mock verifier translates into fabricated messages.
   → Assert: the bundle is accepted. The fabricated messages are dispatched to the application. This
   demonstrates the risk — not a defense.

**Cleanup:** Reconfigure the verifier.

## 29.2 Excess Sync Frequency (Adversarial)

**Reference:** clpr-test-spec.md §5.2.2

**Purpose:** Verify that a remote endpoint deliberately exceeding sync frequency is shunned.

**Properties to Test:**

- P1: The offending endpoint is shunned after exceeding its fair share.
- P2: Other endpoints are unaffected.

**Setup:**

Two-network setup. Configure one remote endpoint to sync aggressively.

**Steps:**

1. Remote endpoint sends syncs at 2x its fair share of `MaxSyncsPerSec`.
2. Verify: local endpoints detect the violation and shun the offender.
3. Verify: syncs from other remote endpoints continue normally.

**Cleanup:** None.

## 29.3 Connector Deregistration Guard

**Reference:** clpr-test-spec.md §5.3.3

**Purpose:** Verify that `deregisterConnector` is blocked while messages are in-flight.

**Properties to Test:**

- P1: Deregistration rejected when unresolved messages exist.
- P2: After all messages resolve, deregistration succeeds.

**Setup:**

Register a Connector, send messages through it, and attempt deregistration before responses arrive.

**Steps:**

1. Send messages through the Connector. Attempt `deregisterConnector`.
   → Assert: rejected (in-flight messages).
2. Wait for all responses. Call `deregisterConnector`.
   → Assert: accepted. Funds returned.

**Cleanup:** None.

## 29.4 Connector Authorization Rejection

**Reference:** clpr-test-spec.md §5.3.4

**Purpose:** Verify that a Connector whose authorization contract rejects a message prevents enqueue.

**Properties to Test:**

- P1: The message is not enqueued. No queue state changes.

**Setup:**

Deploy a Connector configured to reject all `authorizeMessage` calls.

**Steps:**

1. Call `sendMessage` with the rejecting Connector.
   → Assert: transaction fails. `next_message_id` unchanged. Queue depth unchanged.

**Cleanup:** None.

## 29.5 Destination Connector Deregisters Mid-Transit

**Reference:** clpr-test-spec.md §5.3.5

**Purpose:** Verify that a Connector deregistering on the destination while a message is in transit
triggers `CONNECTOR_NOT_FOUND` behavior.

**Properties to Test:**

- P1: The message is still delivered to the application.
- P2: `CONNECTOR_NOT_FOUND` status returned to source. Source Connector slashed.

**Setup:**

Two-network setup. Register Connector on both sides. Send a message from A.

**Steps:**

1. Send a message from A.
2. Before the message is delivered to B, deregister the destination Connector on B.
3. When the bundle arrives on B, the Connector is not found.
   → Assert: message is dispatched to the application. `CONNECTOR_NOT_FOUND` response generated.
   Source Connector slashed when response returns.

**Cleanup:** None.

## 29.6 Selective Endpoint Partitioning

**Reference:** clpr-test-spec.md §5.5.2

**Purpose:** Verify that making one destination endpoint unreachable does not prevent message delivery
through other endpoints.

**Properties to Test:**

- P1: Source endpoints discover alternative peers and continue syncing.
- P2: Messages are delivered without loss.

**Setup:**

Two-network setup with multiple endpoints on the destination.

**Steps:**

1. Make one destination endpoint unreachable (network partition for that pod).
2. Send messages from A.
   → Assert: messages are delivered through the remaining endpoints. No messages lost.

**Cleanup:** Restore the partitioned endpoint.

## 29.7 Replay of Old Valid Bundles

**Reference:** clpr-test-spec.md §5.5.3

**Purpose:** Verify that re-submitting a previously accepted bundle is rejected.

**Properties to Test:**

- P1: The monotonic message ID check rejects the replayed bundle.

**Setup:**

Submit a valid bundle and record it. Then re-submit.

**Steps:**

1. Submit a valid bundle. → Assert: accepted. `received_message_id` advances.
2. Re-submit the same bundle. → Assert: rejected (message IDs ≤ `received_message_id`).

**Cleanup:** None.

---

# 30. Recovery and Fault Tolerance Tests

These tests verify the recovery scenarios from the CLPR specification (§4, R1-R9).

## 30.1 Single Endpoint Replaced

**Reference:** clpr-test-spec.md §8.1.1

**Purpose:** Verify that replacing a single endpoint does not disrupt CLPR communication.

**Properties to Test:**

- P1: The new endpoint discovers peers via gossip.
- P2: Syncs resume through the new endpoint. No messages lost.

**Setup:**

Two-network setup with active CLPR traffic.

**Steps:**

1. Send a message and verify delivery (baseline).
2. Remove one endpoint from Network A's roster. Add a replacement.
3. Send another message.
   → Assert: delivered successfully through the new endpoint.

**Cleanup:** None.

## 30.2 Complete Roster Rotation (One Side)

**Reference:** clpr-test-spec.md §8.1.2

**Purpose:** Verify communication survives complete replacement of all endpoints on one side.

**Properties to Test:**

- P1: Seed endpoints provide fallback bootstrap for the new roster.
- P2: All messages delivered after rotation completes.

**Setup:**

Two-network setup. Rotate all nodes on Network A one at a time.

**Steps:**

1. Rotate each node on A sequentially, sending a message after each rotation.
2. After all original nodes are replaced, send a final message.
   → Assert: delivered. Syncs resumed through the fully new roster.

**Cleanup:** None.

## 30.3 Simultaneous Roster Rotation (Both Sides)

**Reference:** clpr-test-spec.md §8.1.3

**Purpose:** Verify communication recovers when both sides' endpoints change simultaneously.

**Properties to Test:**

- P1: Both sides discover new peers via gossip once connectivity is restored.

**Setup:**

Two-network setup.

**Steps:**

1. Simultaneously rotate all endpoints on both networks.
2. Wait for gossip-based peer discovery to converge.
3. Send a message. → Assert: delivered.

**Cleanup:** None.

## 30.4 New Connection with New Verifier (Proof Format Upgrade)

**Reference:** clpr-test-spec.md §8.2.1

**Purpose:** Verify that a new Connection with a new verifier can be registered while the old
Connection is still active.

**Properties to Test:**

- P1: The new Connection is independently operational.
- P2: Traffic on the old Connection is unaffected.

**Setup:**

Two-network setup with an active Connection. Deploy a new verifier.

**Steps:**

1. Register a new Connection with the new verifier on both networks.
2. Send a message on the new Connection. → Assert: delivered.
3. Send a message on the old Connection. → Assert: still delivered (old Connection unaffected).

**Cleanup:** Close the old Connection when migration is complete.

## 30.5 Close Compromised Connection

**Reference:** clpr-test-spec.md §8.3.1, §8.3.3

**Purpose:** Verify that an admin can close a Connection suspected of verifier compromise and that
it drains to terminal state.

**Properties to Test:**

- P1: `closeConnection` transitions to CLOSING.
- P2: In-flight messages receive `CONNECTION_CLOSED` responses.
- P3: The Connection reaches CLOSED.

**Setup:**

Two-network setup with an active Connection and in-flight messages.

**Steps:**

1. Call `closeConnection` (admin).
   → Assert: transitions to CLOSING.
2. Wait for in-flight messages to receive `CONNECTION_CLOSED` responses.
3. Wait for queues to drain.
   → Assert: Connection reaches CLOSED on both sides.

**Cleanup:** None.

## 30.6 Migrate to Patched Verifier

**Reference:** clpr-test-spec.md §8.3.2

**Purpose:** Verify applications can migrate from a compromised Connection to a new one with a
patched verifier.

**Properties to Test:**

- P1: The new Connection with the patched verifier is operational.
- P2: Applications can send messages through the new Connection.

**Setup:**

After Test 30.5 (compromised Connection closed), register a new Connection with a patched verifier.

**Steps:**

1. Register a new Connection with the patched verifier on both sides.
2. Register Connectors on the new Connection.
3. Send a message. → Assert: delivered through the new Connection.

**Cleanup:** None.

## 30.7 Automatic PAUSE on Ordering Violation

**Reference:** clpr-test-spec.md §8.4.1

**Purpose:** Verify that out-of-order responses automatically trigger PAUSED.

**Properties to Test:**

- P1: Connection transitions to PAUSED.

**Setup:**

Two-network setup. Send messages and produce out-of-order responses.

**Steps:**

1. Inject an out-of-order response. → Assert: Connection transitions to PAUSED.

**Cleanup:** None.

## 30.8 Auto-Resume After Ordering Fix

**Reference:** clpr-test-spec.md §8.4.2

**Purpose:** Verify automatic resume when the peer fixes response ordering.

**Properties to Test:**

- P1: Connection returns to ACTIVE when correctly ordered responses arrive.

**Setup:**

Connection is PAUSED from Test 30.7.

**Steps:**

1. Submit a bundle with correctly ordered responses.
   → Assert: Connection auto-resumes to ACTIVE. Normal operation continues.

**Cleanup:** None.

## 30.9 Persistent PAUSE When Peer Never Fixes

**Reference:** clpr-test-spec.md §8.4.3

**Purpose:** Verify behavior when the peer never fixes the ordering and the admin closes the
Connection.

**Properties to Test:**

- P1: Connection remains PAUSED indefinitely while the ordering violation persists.
- P2: Admin can close the PAUSED Connection (transitions to CLOSING).
- P3: The Connection cannot drain until the peer fixes the ordering. Once fixed, it drains.

**Setup:**

Connection is PAUSED.

**Steps:**

1. Verify Connection stays PAUSED with continued out-of-order bundles.
2. Call `closeConnection` (admin). → Assert: transitions to CLOSING.
3. Submit bundle with out-of-order responses. → Assert: still rejected.
4. Submit bundle with correctly ordered responses. → Assert: processed with `CONNECTION_CLOSED`
   responses. Queues drain to CLOSED.

**Cleanup:** None.

## 30.10 Temporary Network Partition

**Reference:** clpr-test-spec.md §8.5.1

**Purpose:** Verify messages queue during a partition and are delivered when it heals.

**Properties to Test:**

- P1: Messages are queued on the source during the partition.
- P2: Syncs resume and messages are delivered when connectivity returns.

**Setup:**

Two-network setup with active traffic.

**Steps:**

1. Partition the networks for 30 seconds.
2. Send messages during the partition (they queue on the source).
3. Heal the partition.
   → Assert: syncs resume. All queued messages delivered. No loss.

**Cleanup:** Ensure partition is healed.

## 30.11 Extended Partition with Backpressure

**Reference:** clpr-test-spec.md §8.5.2

**Purpose:** Verify queue backpressure during an extended partition and recovery afterward.

**Properties to Test:**

- P1: Queue fills to `max_queue_depth`. New messages rejected.
- P2: After partition heals, queue drains and normal operation resumes.

**Setup:**

Set `max_queue_depth` to a testable value. Partition the networks.

**Steps:**

1. Partition networks. Send messages until queue is full.
   → Assert: `sendMessage` rejected at `max_queue_depth`.
2. Heal the partition. Wait for queue to drain.
   → Assert: `sendMessage` succeeds again after queue space opens.

**Cleanup:** Ensure partition is healed.

## 30.12 Peer Ledger Down Entirely

**Reference:** clpr-test-spec.md §8.5.3

**Purpose:** Verify behavior when the peer ledger is completely offline.

**Properties to Test:**

- P1: Messages queue on the source. Backpressure eventually engages.
- P2: When the peer returns, syncs resume from where they left off.

**Setup:**

Two-network setup.

**Steps:**

1. Shut down all nodes on Network B.
2. Send messages from A. They queue.
3. Bring Network B back online.
   → Assert: syncs resume. Queued messages delivered.

**Cleanup:** Ensure Network B is running.

## 30.13 Rolling Upgrade

**Reference:** clpr-test-spec.md §8.6.2

**Purpose:** Verify CLPR traffic continues during a rolling upgrade of all nodes.

**Properties to Test:**

- P1: Messages continue to flow throughout the upgrade.
- P2: No messages are lost.

**Setup:**

Two-network setup with active CLPR traffic.

**Steps:**

1. Under active traffic, upgrade nodes one at a time on Network A (stop, update, start).
2. After each node upgrade, verify a message is delivered.
3. After all nodes upgraded, verify queue metadata consistency.

**Cleanup:** None.

## 30.14 Freeze and Restart

**Reference:** clpr-test-spec.md §8.6.3

**Purpose:** Verify CLPR state survives a freeze/restart cycle.

**Properties to Test:**

- P1: After restart, Connection metadata, queue state, and Connector balances are intact.
- P2: Syncs resume and messages are delivered.

**Setup:**

Two-network setup with active CLPR state (Connections, queued messages, Connectors).

**Steps:**

1. Record Connection state, queue metadata, and Connector balances.
2. Perform a freeze/restart cycle on Network A (standard Hiero upgrade procedure).
3. After restart, query Connection state.
   → Assert: all metadata matches pre-freeze values.
4. Send a message. → Assert: delivered normally. Syncs resumed.

**Cleanup:** None.
