# CLPR MQPT Test Definitions

MQPT (Merge Queue Performance Tests) runs as a gate before code merges. It must complete within
3 hours 40 minutes. CLPR MQPT tests are a compressed smoke test: verify that the most fundamental
cross-ledger behavior works and that a node restart during CLPR traffic does not break anything.
MQPT tests are intentionally minimal — they catch regressions that would block the merge queue without
the full scope of XTS.

MQPT requires the multi-network SOLO infrastructure described in
[clpr-test-solo-multi-network.md](clpr-test-solo-multi-network.md).

> These test definitions are subject to change as the CLPR specification evolves during implementation.

---

# 1. Common Test Infrastructure

## 1.1 Test Harness Requirements

Same as XTS (§1.1): two Hiero networks with CLPR enabled, cross-namespace connectivity, test driver
with dual-network access. Test verifiers, Connectors, and applications deployed on both sides.

## 1.2 Common Setup

Same as XTS (§1.2): configure CLPR on both networks, establish a Connection, register Connectors,
deploy test applications. The setup should be automated and complete within 10 minutes.

---

# 2. Cross-Ledger Message Roundtrip Smoke Test

**Reference:** clpr-test-spec.md §4.1.1

**Purpose:** Verify the most basic cross-ledger operation: send a message from A, receive it on B,
receive the response on A.

**Properties to Test:**

- P1: A message sent on A is delivered to B's application.
- P2: B's response is delivered back to A with `SUCCESS` status.
- P3: Queue metadata is consistent on both sides.

**Setup:**

Common setup (§1.2).

**Steps:**

1. Send a message from A to B with a known test payload.

2. Wait for end-to-end completion (up to 60 seconds).

3. Verify: A's application received a `SUCCESS` response with the expected reply data (echo of
   the original payload). Queue metadata on both sides is consistent.

**Cleanup:** None.

---

# 3. Node Restart During CLPR Traffic

**Reference:** clpr-test-spec.md §8.6.1

**Purpose:** Verify that CLPR traffic survives a single node restart without message loss.

**Properties to Test:**

- P1: Messages sent before the restart are delivered after the node recovers.
- P2: Messages sent after the restart are delivered normally.
- P3: No messages are lost or duplicated.

**Setup:**

Common setup with active message traffic. Send several messages to establish baseline delivery.

**Steps:**

1. Send messages M1, M2 from A to B. Verify delivery.

2. Send message M3 from A.

3. Restart one consensus node on Network A (stop, then start).

4. Wait for the restarted node to rejoin consensus.

5. Wait for M3 to be delivered to B and the response to return to A.
   → Assert: M3 delivered successfully. Response received on A.

6. Send message M4 after the restart.
   → Assert: M4 delivered successfully. No messages lost.

7. Verify queue metadata consistency: message IDs are contiguous, running hashes are valid,
   no gaps in the sequence.

**Cleanup:** None.

---

# 4. Bidirectional Smoke Test

**Reference:** clpr-test-spec.md §4.1.2

**Purpose:** Verify messages flow correctly in both directions simultaneously.

**Properties to Test:**

- P1: A message from A to B and a message from B to A are both delivered.
- P2: Responses are correctly correlated on both sides.

**Setup:**

Common setup with sending applications on both sides.

**Steps:**

1. Send a message from A to B and from B to A concurrently.

2. Wait for both flows to complete.

3. Verify both sides received correct responses.

**Cleanup:** None.
