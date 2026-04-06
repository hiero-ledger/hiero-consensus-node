# CLPR SDPT Test Definitions

SDPT (Single Day Performance Tests) runs sequential performance benchmarks — each test runs in
isolation to produce clean, uncontested measurements. CLPR SDPT tests isolate specific performance
dimensions of cross-ledger messaging: proof construction cost, bundle verification overhead,
Connector charging throughput, and endpoint scaling behavior.

SDPT requires the multi-network SOLO infrastructure on a performance cluster.
See [clpr-test-solo-multi-network.md](clpr-test-solo-multi-network.md).

> These test definitions are subject to change as the CLPR specification evolves during implementation.

---

# 1. Common Test Infrastructure

## 1.1 Test Harness Requirements

- **Two Hiero networks** deployed on performance-grade hardware.
- **Isolated load generator** that sends CLPR messages at controlled rates with no other concurrent
  traffic. Each test runs alone to eliminate interference.
- **Per-operation timing instrumentation** to measure individual handler costs (proof construction,
  verifier invocation, hash computation, Connector charging, application dispatch).

---

# 2. `submitBundle` Handler Cost

**Reference:** clpr-test-spec.md §7.2.2

**Purpose:** Measure the per-bundle cost of the `submitBundle` handler in isolation, broken down by
verification phase.

**Properties to Test:**

- P1: Total `submitBundle` handler time as a function of bundle size (1, 10, 50 messages).
- P2: Relative cost of verifier invocation vs. running hash verification vs. message dispatch.

**Setup:**

Two networks with an active Connection. No concurrent traffic.

**Steps:**

For each bundle size (1, 10, 50 messages):

1. Pre-enqueue the required number of messages on the source.

2. Trigger a sync to produce a bundle of the target size.

3. On the destination, measure `submitBundle` execution time. If per-phase instrumentation is
   available, record time in each phase: verifier call, running hash verification, message dispatch,
   response generation.

→ Report: total handler time and per-phase breakdown as a function of bundle size.

**Cleanup:** None.

---

# 3. `sendMessage` Handler Cost

**Reference:** clpr-test-spec.md §7.1.1

**Purpose:** Measure the per-message cost of the `sendMessage` handler in isolation.

**Properties to Test:**

- P1: `sendMessage` transaction execution time at various payload sizes (1 KB, 10 KB, 100 KB).
- P2: Time spent in Connector authorization vs. queue enqueue vs. running hash computation.

**Setup:**

One network with an active Connection and funded Connector. No concurrent traffic.

**Steps:**

For each payload size:

1. Send 100 messages sequentially. Measure per-transaction execution time.

→ Report: average and p99 execution time per message at each payload size.

**Cleanup:** None.

---

# 4. Endpoint Scaling

**Reference:** clpr-test-spec.md §7.5.1

**Purpose:** Determine how total system throughput scales with the number of endpoints.

**Properties to Test:**

- P1: Does doubling the number of endpoints double the throughput?
- P2: At what endpoint count do diminishing returns appear?

**Setup:**

Two networks. Start with the minimum endpoint count (1 node each), then increase by adding
consensus nodes.

**Steps:**

1. With 1 node per network: measure maximum sustained throughput (per SDCT Test 3 methodology).

2. With 4 nodes per network: measure maximum sustained throughput.

3. With 7 nodes per network: measure maximum sustained throughput.

→ Report: throughput as a function of endpoint count. Identify the scaling curve shape (linear,
sublinear, plateau).

**Cleanup:** None.

---

# 5. Per-Endpoint Submission Throttle Behavior

**Reference:** clpr-test-spec.md §7.5.2

**Purpose:** Verify that the per-endpoint submission quota is correctly enforced under load and adjusts
when endpoints are added or removed.

**Properties to Test:**

- P1: Each endpoint's submission rate stays within its quota (total capacity / num_endpoints).
- P2: When an endpoint is removed, remaining endpoints' quotas increase.
- P3: Aggregate submission rate does not exceed the global limit.

**Setup:**

Two networks with multiple endpoints.

**Steps:**

1. Under sustained CLPR load, monitor per-endpoint submission rates.
   → Assert: no endpoint exceeds its quota. Aggregate rate stays within the global limit.

2. Remove one endpoint from the destination network.

3. Continue monitoring.
   → Assert: remaining endpoints' quotas increase. Aggregate rate remains within the global limit.

**Cleanup:** Restore the removed endpoint.

---

# 6. Proof Construction Latency

**Reference:** clpr-test-spec.md §7.2.2

**Purpose:** Measure the time an endpoint spends constructing state proofs for outbound bundles.

**Properties to Test:**

- P1: Proof construction time as a function of message count in the bundle.
- P2: Proof construction time as a function of payload size.

**Setup:**

One network with an active Connection and varying numbers of enqueued messages. Instrument the
endpoint module's proof construction path.

**Steps:**

For each combination of message count (1, 10, 50) and payload size (1 KB, 10 KB):

1. Enqueue the target number of messages.

2. Trigger a sync. Measure the time from "begin proof construction" to "proof bytes ready."

→ Report: proof construction time as a function of message count and payload size.

**Cleanup:** None.

---

# 7. Peer Discovery Convergence

**Reference:** clpr-test-spec.md §7.5.3

**Purpose:** Measure how quickly a new endpoint discovers enough peers via gossip to achieve full
throughput.

**Properties to Test:**

- P1: Time from endpoint startup to first successful sync with a peer.
- P2: Time from endpoint startup to achieving steady-state submission rate.

**Setup:**

Two networks under sustained CLPR load. Add a new endpoint to one network mid-test.

**Steps:**

1. Under steady-state CLPR load, add a new consensus node to Network A.

2. Monitor the new node's sync activity: when does it first successfully sync with a peer on
   Network B? When does its submission rate reach the per-endpoint quota?

→ Report: time to first sync, time to steady-state throughput.

**Cleanup:** None.
