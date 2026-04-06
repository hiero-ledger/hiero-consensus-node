# CLPR SDCT Test Definitions

SDCT (Single Day Canonical Tests) measures throughput and end-to-end latency. SDCT runs run
sequentially on a real multi-node cluster with mirror node support. CLPR SDCT tests profile
cross-ledger message performance at increasing load levels to establish canonical throughput and
latency baselines.

SDCT requires the multi-network SOLO infrastructure on a performance cluster (Latitude or equivalent),
not a kind cluster. See [clpr-test-solo-multi-network.md](clpr-test-solo-multi-network.md).

> These test definitions are subject to change as the CLPR specification evolves during implementation.

---

# 1. Common Test Infrastructure

## 1.1 Test Harness Requirements

- **Two Hiero networks** deployed on performance-grade hardware (7+ consensus nodes each).
- **Load generator** capable of sending CLPR messages at configurable rates (TPS).
- **Latency instrumentation** that timestamps each message at send time and at response receipt,
  computing end-to-end latency per message.
- **Mirror node** on both sides for post-test state validation.

## 1.2 Common Measurement Procedures

**Throughput measurement:** Send messages at a target TPS for a fixed duration. Record the actual
sustained rate (messages enqueued per second on source, messages delivered per second on destination).

**Latency measurement:** For each message, record: (1) consensus timestamp of the `sendMessage`
transaction, (2) consensus timestamp of the response delivery on the source. Compute the difference.
Report p50, p95, p99 latency.

**Queue depth monitoring:** Periodically sample the Connection's queue depth (gap between
`next_message_id` and `acked_message_id`) during the load test. Report max and average queue depth.

---

# 2. Idle Baseline

**Reference:** clpr-test-spec.md §7.2.1

**Purpose:** Establish end-to-end latency under zero load — a single message roundtrip with no
contention.

**Properties to Test:**

- P1: End-to-end latency under idle conditions (p50, p95, p99 across 100 messages sent sequentially).

**Setup:**

Two networks with an active Connection, funded Connectors, deployed applications. No concurrent load.

**Steps:**

1. Send 100 messages sequentially (wait for each response before sending the next).

2. Record per-message latency.
   → Report: p50, p95, p99 latency. This is the baseline for comparison with loaded tests.

**Cleanup:** None.

---

# 3. Throughput Ramp

**Reference:** clpr-test-spec.md §7.1.1

**Purpose:** Determine the maximum sustained message throughput for a single Connection.

**Properties to Test:**

- P1: Maximum TPS at which all messages are delivered within a bounded time.
- P2: The TPS at which the queue begins to grow (delivery rate < enqueue rate).
- P3: The TPS at which backpressure engages (`max_queue_depth` reached).

**Setup:**

Common setup with `max_queue_depth` set to a production-like value.

**Steps:**

1. Send messages at 10 TPS for 5 minutes. Record throughput and queue depth.
2. Increase to 50 TPS for 5 minutes. Record.
3. Increase to 100 TPS for 5 minutes. Record.
4. Increase to 500 TPS for 5 minutes. Record.
5. Continue increasing until the queue grows unboundedly or backpressure engages.

→ Report: for each rate, the sustained delivery rate, average queue depth, and p99 latency.
Identify the inflection point where queue growth begins.

**Cleanup:** None.

---

# 4. Throughput vs. Payload Size

**Reference:** clpr-test-spec.md §7.1.3

**Purpose:** Measure how message payload size affects maximum throughput.

**Properties to Test:**

- P1: Throughput at 1 KB, 10 KB, 100 KB, and `max_message_payload_bytes` payloads.

**Setup:**

Common setup. Run each payload size as a separate sub-test.

**Steps:**

For each payload size (1 KB, 10 KB, 100 KB, max):

1. Send messages at the maximum sustainable rate (determined in Test 3 or a conservative estimate)
   for 5 minutes.

2. Record sustained throughput, average queue depth, and p99 latency.

→ Report: throughput as a function of payload size.

**Cleanup:** None.

---

# 5. Throughput vs. Bundle Size

**Reference:** clpr-test-spec.md §7.1.4

**Purpose:** Measure how the number of messages per bundle affects throughput.

**Properties to Test:**

- P1: Throughput with bundles of 1, 10, 50, and `max_messages_per_bundle` messages.

**Setup:**

Common setup. This test may require controlling the sync timing to accumulate a specific number of
messages before each sync (e.g., by sending bursts followed by a sync trigger).

**Steps:**

For each bundle size target:

1. Send a burst of N messages, wait for a sync cycle to package them into a bundle.

2. Measure the wall-clock time from first send to last response received.

→ Report: effective throughput as a function of messages per bundle.

**Cleanup:** None.

---

# 6. Latency Under Load

**Reference:** clpr-test-spec.md §7.2.1

**Purpose:** Measure end-to-end latency at various sustained load levels.

**Properties to Test:**

- P1: Latency (p50, p95, p99) at 10%, 50%, and 100% of the maximum sustainable throughput.

**Setup:**

Common setup. Use the maximum throughput from Test 3 as the 100% reference.

**Steps:**

For each load level (10%, 50%, 100% of max throughput):

1. Send messages at the target rate for 10 minutes.

2. Record per-message latency.

→ Report: p50, p95, p99 latency at each load level. Compare to idle baseline (Test 2).

**Cleanup:** None.

---

# 7. Latency Breakdown

**Reference:** clpr-test-spec.md §7.2.2

**Purpose:** Identify which phase of the cross-ledger pipeline contributes the most to latency.

**Properties to Test:**

- P1: Time spent in each phase: enqueue, time waiting in queue, sync interval, proof construction,
  bundle submission, application execution, response enqueue, response sync, response delivery.

**Setup:**

Common setup with instrumentation points at each phase boundary (may require consensus node log
analysis or custom metrics).

**Steps:**

1. Send 100 messages at moderate load.

2. For each message, extract timestamps at each phase boundary from logs or metrics.

→ Report: average time per phase. Identify the dominant contributor to end-to-end latency.

**Cleanup:** None.

---

# 8. Queue Behavior Under Sustained Load

**Reference:** clpr-test-spec.md §7.3.1, §7.3.2, §7.3.3

**Purpose:** Characterize queue fill/drain dynamics and backpressure behavior.

**Properties to Test:**

- P1: Queue fill rate vs. drain rate at various throughput levels.
- P2: When the queue reaches `max_queue_depth`, `sendMessage` is rejected cleanly.
- P3: After backpressure clears, recovery time to return to steady-state throughput.

**Setup:**

Common setup with `max_queue_depth` set to a testable value (e.g., 1000).

**Steps:**

1. Send messages at a rate that exceeds the delivery rate. Monitor queue depth over time.
   → Record: fill rate (messages/sec) and drain rate (messages/sec).

2. Continue until `max_queue_depth` is reached.
   → Verify: `sendMessage` returns a rejection status. No queue corruption.

3. Stop sending. Monitor queue depth as it drains.
   → Record: time to drain from `max_queue_depth` to zero.

4. Resume sending at the original rate.
   → Record: time to return to steady-state queue depth.

**Cleanup:** None.
