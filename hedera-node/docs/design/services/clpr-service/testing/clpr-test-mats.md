# CLPR MATS Test Definitions

MATS (Minimal Acceptable Test Suite) tests run on every push to `main` and on every PR. They must
complete within 30 minutes. CLPR MATS tests verify fundamental handler behavior for each CLPR
transaction type on a single embedded Hiero network. They are HAPI-level tests: a test client submits
transactions and verifies state mutations. No multi-network infrastructure, no mirror node, no block
node.

These tests catch breaking changes early — if a handler rejects valid input, accepts invalid input,
or corrupts state, MATS detects it before the change merges. The tests are intentionally simple and
focused on fundamental correctness rather than edge cases or performance.

> These test definitions are subject to change as the CLPR specification evolves during implementation.
> The current spec version is the source of truth for behavioral requirements. If a test definition
> conflicts with the spec, the spec takes precedence.

---

# 1. Common Test Infrastructure

## 1.1 Test Harness Requirements

CLPR MATS tests run within the existing HAPI test framework using an embedded Hiero network. The
following additions are needed:

- **CLPR feature flag enabled.** The embedded network must have CLPR enabled in its configuration.
- **CLPR admin key available.** Tests that exercise admin operations need access to the CLPR admin key
  (the equivalent of the council key on mainnet).
- **A deployable verifier contract.** Several tests require a deployed verifier contract. A minimal
  test verifier that implements `IClprVerifier` and returns canned responses (valid configuration,
  valid bundles) is needed. This test verifier does not perform real cryptographic verification — it
  accepts pre-constructed proof bytes and returns predetermined results.
- **A deployable Connector authorization contract.** Tests for `sendMessage` need a Connector whose
  `authorizeMessage()` can be configured to accept or reject.

## 1.2 Common Setup Procedures

**Configure CLPR.** Before any Connection or messaging tests, call `setLedgerConfiguration` with valid
throttle parameters. Store the resulting configuration for reference.

**Register a Connection.** Many tests require an active Connection. The common procedure:
1. Generate an ECDSA secp256k1 keypair.
2. Deploy the test verifier contract.
3. Construct valid `config_proof_bytes` that the test verifier will accept.
4. Call `registerConnection` with the Connection ID, signature, verifier address, and proof bytes.
5. Verify the Connection is ACTIVE.

**Register a Connector.** Tests involving message sending need a funded Connector:
1. Deploy a Connector authorization contract (configured to accept all messages by default).
2. Call `registerConnector` with the Connection ID, source connector address, authorization contract
   address, initial balance, and stake.

**Construct a Valid Bundle.** Tests for `submitBundle` need a bundle that passes the test verifier.
The common procedure constructs proof bytes containing the messages, queue metadata, and running hashes
that the test verifier is configured to return.

## 1.3 Common Verification Procedures

**Query Connection state.** After each operation, query the Connection to verify status, queue metadata
(next_message_id, acked_message_id, running hashes), and verifier binding.

**Query Connector state.** After economic operations, query the Connector to verify balance and locked
stake.

**Verify transaction outcome.** Each test checks the transaction receipt for the expected status code
(SUCCESS or a specific failure status).

---

# 2. Configuration: `setLedgerConfiguration`

**Reference:** clpr-test-spec.md §3.1.1

**Purpose:** Verify that the CLPR Service correctly stores, validates, and auto-populates configuration
fields, and that unauthorized callers are rejected.

**Properties to Test:**

- P1: The admin key is required. A transaction without the admin key is rejected.
- P2: `chain_id` is immutable after first set. A subsequent call with a different `chain_id` is rejected.
- P3: `protocol_version` is auto-set by the CLPR Service code. A caller-supplied value is ignored; the
  stored value reflects the code version.
- P4: `timestamp` is auto-set to the consensus timestamp. A caller-supplied value is ignored.
- P5: Throttle fields are validated. A configuration with `max_messages_per_bundle = 0` is rejected.
- P6: A configuration where `max_sync_bytes` is less than `max_message_payload_bytes` is rejected.
- P7: When CLPR is disabled (feature flag off), the transaction returns an error.
- P8: Two sequential calls produce configurations with different timestamps. A query returns the latest.

**Setup:**

No special setup beyond enabling CLPR and having access to the admin key.

**Steps:**

1. Call `setLedgerConfiguration` without the admin key.
   → Assert: rejected (unauthorized).

2. Call `setLedgerConfiguration` with the admin key and valid parameters including a `chain_id`.
   → Assert: SUCCESS. Query the configuration and verify `chain_id`, `timestamp` (matches consensus
   time), and `protocol_version` (matches code version).

3. Call `setLedgerConfiguration` again with a *different* `chain_id`.
   → Assert: rejected (chain_id immutable).

4. Call `setLedgerConfiguration` with `max_messages_per_bundle = 0`.
   → Assert: rejected (invalid throttle).

5. Call `setLedgerConfiguration` with `max_sync_bytes` less than `max_message_payload_bytes`.
   → Assert: rejected (invalid throttle — deadlock configuration).

6. Call `setLedgerConfiguration` with a caller-supplied `protocol_version` and `timestamp`.
   → Assert: SUCCESS. Query the configuration and verify both fields were overridden by the service
   (protocol_version matches code, timestamp matches consensus time of this transaction).

7. Call `setLedgerConfiguration` twice in quick succession with valid but different throttle values.
   → Assert: both succeed. Query returns the second configuration. The two timestamps differ.

**Cleanup:** None required — configuration is overwritten by subsequent tests.

---

# 3. Configuration Query: `getLedgerConfiguration`

**Reference:** clpr-test-spec.md §3.1.2

**Purpose:** Verify that the configuration query returns the correct data and handles edge cases.

**Properties to Test:**

- P1: Returns the current configuration with all auto-set fields populated.
- P2: Returns an error when no configuration has been set.
- P3: Returns an error when CLPR is disabled.

**Setup:**

For P2 and P3, ensure the configuration has not been set (or CLPR is disabled).

**Steps:**

1. With CLPR disabled, call `getLedgerConfiguration`.
   → Assert: error (CLPR not enabled).

2. With CLPR enabled but no configuration set, call `getLedgerConfiguration`.
   → Assert: error (not configured).

3. Set a valid configuration, then call `getLedgerConfiguration`.
   → Assert: returns the configuration with correct `chain_id`, `protocol_version`, `timestamp`,
   and throttle values.

**Cleanup:** None.

---

# 4. Connection Registration: `registerConnection`

**Reference:** clpr-test-spec.md §3.2.1

**Purpose:** Verify that Connection registration validates inputs, initializes state correctly, and
rejects invalid registrations.

**Properties to Test:**

- P1: A valid registration with correct ECDSA signature, deployed verifier, and valid config proof
  succeeds. The Connection is ACTIVE with correct initial queue metadata.
- P2: Connection ID must match the public key. A mismatched Connection ID is rejected.
- P3: An invalid ECDSA signature is rejected.
- P4: A verifier contract that does not exist (not deployed) causes registration to fail.
- P5: A verifier that rejects the config proof (reverts on `verifyConfig`) causes registration to fail.
- P6: A duplicate Connection ID is rejected.
- P7: Registration is permissionless — no special key required.
- P8: The verifier address and code fingerprint are stored on the Connection.

**Setup:**

Follow the common configuration setup (§1.2). Generate a keypair and deploy the test verifier.

**Steps:**

1. Register a Connection with valid parameters.
   → Assert: SUCCESS. Query the Connection: status is ACTIVE, `next_message_id = 1`,
   `acked_message_id = 0`, both running hashes are 32 bytes of zeros. Verifier address and fingerprint
   are stored.

2. Attempt registration with the same Connection ID.
   → Assert: rejected (duplicate).

3. Attempt registration with a Connection ID that does not match the provided public key.
   → Assert: rejected.

4. Attempt registration with an invalid ECDSA signature.
   → Assert: rejected.

5. Attempt registration with a verifier address that has no deployed contract.
   → Assert: rejected.

6. Configure the test verifier to reject the next `verifyConfig` call. Attempt registration.
   → Assert: rejected.

7. Attempt registration from a non-privileged account (no admin key).
   → Assert: SUCCESS (registration is permissionless).

**Cleanup:** None — each registration uses a unique Connection ID.

---

# 5. Connection Governance: `closeConnection`

**Reference:** clpr-test-spec.md §3.2.2, §3.2.4, §3.2.5, §3.2.6

**Purpose:** Verify that the admin can close Connections, that status transitions are enforced, and
that invalid transitions are rejected.

**Properties to Test:**

- P1: `closeConnection` on an ACTIVE Connection transitions it to CLOSING.
- P2: `closeConnection` requires the admin key.
- P3: `closeConnection` on a CLOSING, DRAINED, or CLOSED Connection is rejected.
- P4: After closing, `sendMessage` is rejected on the Connection.
- P5: After closing, `submitBundle` still processes inbound bundles (for draining).

**Setup:**

Register an active Connection per the common procedure (§1.2).

**Steps:**

1. Call `closeConnection` without the admin key.
   → Assert: rejected (unauthorized).

2. Call `closeConnection` with the admin key on the active Connection.
   → Assert: SUCCESS. Query the Connection: status is CLOSING.

3. Call `sendMessage` on the CLOSING Connection.
   → Assert: rejected.

4. Call `closeConnection` again on the same Connection (now CLOSING).
   → Assert: rejected (already closing).

**Cleanup:** None — the Connection is in a terminal path.

---

# 6. Send Message: `sendMessage`

**Reference:** clpr-test-spec.md §3.4.1

**Purpose:** Verify that message enqueue validates all preconditions, correctly initializes message
state, and rejects invalid inputs.

**Properties to Test:**

- P1: A valid message is enqueued. `next_message_id` increments. Running hash is updated.
- P2: The `sender` field is stamped from the transaction caller, not a caller-supplied value.
- P3: The Connector's `authorizeMessage()` is called. If it rejects, the message is not enqueued.
- P4: A payload exceeding `max_message_payload_bytes` is rejected.
- P5: When the queue reaches `max_queue_depth`, new messages are rejected.
- P6: `sendMessage` on a non-ACTIVE Connection is rejected.
- P7: `sendMessage` with a non-existent Connector is rejected.

**Setup:**

Follow common setup: configure CLPR, register a Connection, register a Connector. Set
`max_queue_depth` to a small value (e.g., 3) for queue depth testing.

**Steps:**

1. Send a valid message.
   → Assert: SUCCESS. Query the Connection: `next_message_id` incremented to 2. `sent_running_hash`
   is `SHA-256(0x00...00 || serialized_payload)`.

2. Send a second message.
   → Assert: SUCCESS. `next_message_id` is now 3. Running hash chains correctly from the previous
   message.

3. Configure the Connector to reject the next authorization. Send a message.
   → Assert: rejected. `next_message_id` is still 3 (no state change).

4. Send a message with a payload exceeding `max_message_payload_bytes`.
   → Assert: rejected.

5. Fill the queue to `max_queue_depth` by sending messages. Send one more.
   → Assert: rejected (queue full).

6. Close the Connection. Attempt `sendMessage`.
   → Assert: rejected (Connection not ACTIVE).

7. Attempt `sendMessage` specifying a Connector that has not been registered.
   → Assert: rejected.

**Cleanup:** None.

---

# 7. Submit Bundle: `submitBundle`

**Reference:** clpr-test-spec.md §3.5.1 through §3.5.5

**Purpose:** Verify that bundle verification enforces replay defense, running hash integrity, payload
size limits, and correctly updates queue metadata on success.

**Properties to Test:**

- P1: A valid bundle passes verification. `received_message_id` and `received_running_hash` are
  updated.
- P2: A bundle with message ID ≤ `received_message_id` is rejected (replay defense).
- P3: A bundle with non-contiguous message IDs is rejected.
- P4: A bundle whose running hash does not match the expected chain is rejected.
- P5: A bundle containing a message exceeding `max_message_payload_bytes` is rejected.
- P6: A bundle where the verifier reverts is rejected. The submitting endpoint pays the cost.
- P7: Peer acknowledgement in the bundle advances local `acked_message_id`.

**Setup:**

Configure CLPR, register a Connection. For this test, the Connection needs messages in its outbound
queue (sent via `sendMessage` in an earlier step) and the ability to submit inbound bundles (constructed
with the test verifier's expected format).

**Steps:**

1. Construct a valid bundle with messages starting at ID 1. Submit it.
   → Assert: SUCCESS. `received_message_id` updated to the last message ID in the bundle.
   `received_running_hash` updated.

2. Re-submit the same bundle (same message IDs).
   → Assert: rejected (replay — message IDs ≤ `received_message_id`).

3. Construct a bundle with message IDs [3, 4, 6] (gap at 5). Submit it.
   → Assert: rejected (non-contiguous IDs).

4. Construct a bundle with valid message IDs but a tampered payload (hash chain will not match).
   Submit it.
   → Assert: rejected (running hash mismatch).

5. Construct a bundle containing a message whose payload exceeds `max_message_payload_bytes`. Submit it.
   → Assert: rejected.

6. Configure the test verifier to revert on the next call. Submit a bundle.
   → Assert: rejected (verifier reverted).

7. Submit a valid bundle whose queue metadata includes a `received_message_id` that advances the local
   `acked_message_id`.
   → Assert: `acked_message_id` updated. Previously unacknowledged messages can now be cleaned up.

**Cleanup:** None.

---

# 8. Connector Lifecycle

**Reference:** clpr-test-spec.md §3.3.1 through §3.3.3

**Purpose:** Verify Connector registration, fund management, and deregistration rules.

**Properties to Test:**

- P1: A valid `registerConnector` succeeds. Balance and stake are recorded. Cross-chain mapping is
  established.
- P2: `registerConnector` on a non-ACTIVE Connection is rejected.
- P3: `registerConnector` with balance or stake below the minimum threshold is rejected.
- P4: `topUpConnector` increases the balance.
- P5: `withdrawConnectorBalance` returns funds. Locked stake cannot be withdrawn.
- P6: Only the Connector admin can top up, withdraw, and deregister.
- P7: `deregisterConnector` returns remaining balance and stake.
- P8: `deregisterConnector` is rejected if the Connector has unresolved in-flight messages.

**Setup:**

Configure CLPR and register a Connection.

**Steps:**

1. Register a Connector with valid balance and stake on the active Connection.
   → Assert: SUCCESS. Query the Connector: balance and stake match the provided values.

2. Close the Connection. Attempt to register a second Connector.
   → Assert: rejected (Connection not ACTIVE).

3. On a fresh active Connection, register a Connector with stake below the minimum.
   → Assert: rejected.

4. Top up the Connector with additional funds.
   → Assert: SUCCESS. Balance increased by the top-up amount.

5. Withdraw an amount within the available balance.
   → Assert: SUCCESS. Balance decreased. Locked stake unchanged.

6. Attempt to withdraw more than the available balance (or attempt to withdraw locked stake).
   → Assert: rejected.

7. Attempt top-up from a non-admin account.
   → Assert: rejected.

8. Deregister the Connector (no in-flight messages).
   → Assert: SUCCESS. Balance and stake returned.

9. Send a message through a Connector, then attempt to deregister before the response arrives.
   → Assert: rejected (in-flight messages).

**Cleanup:** None.

---

# 9. Message Redaction: `redactMessage`

**Reference:** clpr-test-spec.md §3.7.1

**Purpose:** Verify that message redaction removes the payload while preserving the queue slot and
running hash, and that only the admin can redact.

**Properties to Test:**

- P1: Admin can redact an undelivered message. The payload is removed but the message slot and
  `running_hash_after_processing` are retained.
- P2: Only the admin can redact. A non-admin caller is rejected.
- P3: Redacting an already-delivered message is rejected.

**Setup:**

Configure CLPR, register a Connection, register a Connector. Send a message (creating an undelivered
message in the queue).

**Steps:**

1. Send a message. Record its message ID and the running hash after processing.

2. Call `redactMessage` without the admin key.
   → Assert: rejected (unauthorized).

3. Call `redactMessage` with the admin key for the message sent in step 1.
   → Assert: SUCCESS. The message slot still exists. The `running_hash_after_processing` is unchanged
   (it was computed over the original payload). The payload is empty.

4. Submit a bundle that delivers the message to the queue and advances `acked_message_id` past it.
   Attempt to redact the same message again.
   → Assert: rejected (already delivered).

**Cleanup:** None.

---

# 10. Response Ordering and PAUSED State

**Reference:** clpr-test-spec.md §3.6.1, §3.6.2, §3.2.3

**Purpose:** Verify that out-of-order responses trigger PAUSED, and that correctly ordered responses
allow normal processing.

**Properties to Test:**

- P1: When responses arrive in the correct order, processing succeeds and the Connection remains
  ACTIVE.
- P2: When a response does not match the next expected Data Message, the Connection transitions to
  PAUSED.
- P3: While PAUSED, `sendMessage` is rejected.
- P4: While PAUSED, a bundle with correctly ordered responses auto-resumes the Connection to ACTIVE.

**Setup:**

Configure CLPR, register a Connection, register a Connector. Send multiple messages to create
unresponded Data Messages in the outbound queue. Submit bundles carrying these messages to the peer
side (simulated via the test verifier) so that the peer generates responses.

**Steps:**

1. Send messages M1, M2, M3 on the Connection.

2. Submit a bundle containing correctly ordered responses R1, R2 (matching M1, M2 in order).
   → Assert: SUCCESS. Connection remains ACTIVE. M1 and M2 can be cleaned up.

3. Submit a bundle containing response R3 — but the test constructs it to reference the wrong
   originating message (an out-of-order response).
   → Assert: The bundle is processed but the ordering violation is detected. Connection transitions
   to PAUSED.

4. Call `sendMessage` on the PAUSED Connection.
   → Assert: rejected.

5. Submit a bundle with correctly ordered responses.
   → Assert: Connection auto-resumes to ACTIVE.

6. Call `sendMessage` again.
   → Assert: SUCCESS (Connection is ACTIVE again).

**Cleanup:** None.

---

# 11. Lazy Configuration Propagation

**Reference:** clpr-test-spec.md §3.5.6

**Purpose:** Verify that configuration changes are lazily enqueued as ConfigUpdate Control Messages
at the correct point in the message stream.

**Properties to Test:**

- P1: After a configuration change, the next `submitBundle` or `sendMessage` interaction on a
  Connection enqueues a ConfigUpdate Control Message.
- P2: The ConfigUpdate is enqueued at a deterministic position — after acknowledgement processing
  and before new messages generated by this bundle's dispatch.
- P3: If no configuration change has occurred, no ConfigUpdate is enqueued.

**Setup:**

Configure CLPR, register a Connection, send some messages to establish a baseline.

**Steps:**

1. Record the Connection's `last_config_timestamp`.

2. Call `setLedgerConfiguration` with updated throttle values.

3. Trigger an interaction on the Connection (e.g., submit a bundle or send a message).
   → Assert: a ConfigUpdate Control Message is enqueued in the Connection's outbound queue. The
   Connection's `last_config_timestamp` is updated to match the configuration's timestamp.

4. Trigger another interaction without changing the configuration.
   → Assert: no additional ConfigUpdate is enqueued.

**Cleanup:** None.

---

# 12. Economic Invariants: Connector Charging and Slashing

**Reference:** clpr-test-spec.md §3.8.1, §3.8.2

**Purpose:** Verify that Connector charging and slashing follow the rules for each response status.

**Properties to Test:**

- P1: On a `SUCCESS` response, no slashing occurs. The Connector is charged execution cost plus
  margin.
- P2: On a `CONNECTOR_NOT_FOUND` response, the source Connector's locked stake is slashed.
- P3: On a `CONNECTOR_UNDERFUNDED` response, the source Connector's locked stake is slashed.
- P4: On an `APPLICATION_ERROR` response, no slashing occurs.
- P5: On a `REDACTED` response, no slashing occurs.

**Setup:**

Configure CLPR, register a Connection, register a Connector with known balance and stake. Send
messages and submit bundles that produce each response status. For `CONNECTOR_NOT_FOUND` and
`CONNECTOR_UNDERFUNDED`, construct bundles carrying failure response messages.

**Steps:**

1. Submit a bundle carrying a `SUCCESS` response for a previously sent message.
   → Assert: Connector balance and stake unchanged (no slashing). Endpoint reimbursed.

2. Submit a bundle carrying a `CONNECTOR_NOT_FOUND` response.
   → Assert: source Connector's `locked_stake` decreased by the slash amount.

3. Submit a bundle carrying a `CONNECTOR_UNDERFUNDED` response.
   → Assert: source Connector's `locked_stake` decreased by the slash amount.

4. Submit a bundle carrying an `APPLICATION_ERROR` response.
   → Assert: Connector balance and stake unchanged.

5. Redact a message, then submit a bundle carrying the `REDACTED` response.
   → Assert: Connector balance and stake unchanged.

**Cleanup:** None.
