# CLPR SDLT Test Definitions

SDLT (Single Day Longevity Tests) runs sustained parallel load for 16 hours to verify stability over
time. CLPR SDLT tests add cross-ledger message traffic alongside the standard Hiero workload (crypto
transfers, HCS, HTS, smart contracts) and verify that CLPR does not degrade or leak resources over
extended operation.

SDLT requires the multi-network SOLO infrastructure on a performance cluster.
See [clpr-test-solo-multi-network.md](clpr-test-solo-multi-network.md).

> These test definitions are subject to change as the CLPR specification evolves during implementation.

---

# 1. Common Test Infrastructure

## 1.1 Test Harness Requirements

- **Two Hiero networks** deployed on performance-grade hardware.
- **Load generators** for both standard Hiero traffic (NftTransfer, HCS, CryptoTransfer, smart
  contracts) and CLPR cross-ledger messages running in parallel.
- **Periodic sampling** of resource consumption (memory, CPU, thread count) for the endpoint sync
  orchestrator.
- **State growth monitoring** for CLPR-specific state (Connection records, message store, Connector
  balances).

---

# 2. Sustained Cross-Ledger Traffic (16 Hours)

**Reference:** clpr-test-spec.md §7.4.1

**Purpose:** Verify that CLPR operates correctly under sustained load alongside normal Hiero traffic
for an extended period.

**Properties to Test:**

- P1: No degradation in CLPR throughput or latency over 16 hours.
- P2: No degradation in standard Hiero workload throughput due to CLPR traffic.
- P3: Message delivery success rate remains at or near 100%.
- P4: Queue depth remains bounded and does not grow unboundedly over time.

**Setup:**

Deploy two networks. Configure standard load generators (per existing SDLT profile: NFT at 3000 TPS,
HCS at 2000 TPS, CryptoTransfer at 5000 TPS, smart contracts at 50 TPS). Add CLPR cross-ledger
message traffic at a moderate sustained rate (TBD — calibrate based on SDCT results).

**Steps:**

1. Start all load generators in parallel: standard Hiero traffic + CLPR messages.

2. Run for 16 hours.

3. Sample every 15 minutes: CLPR message delivery rate, p99 latency, queue depth, Connection status.

4. Sample every 15 minutes: standard Hiero transaction throughput (crypto, HCS, HTS).

5. At the end: run the state validator.

→ Report: time-series of throughput, latency, and queue depth for CLPR. Time-series of standard Hiero
throughput. Any anomalies (latency spikes, queue depth growth, throughput drops). State validator pass/fail.

**Cleanup:** None.

---

# 3. CLPR State Growth

**Reference:** clpr-test-spec.md §7.4.2

**Purpose:** Verify that CLPR state does not grow unboundedly during extended operation.

**Properties to Test:**

- P1: Acknowledged messages are deleted from the message store.
- P2: Connection metadata size remains constant (queue counters and hashes, not growing lists).
- P3: Connector balances change predictably (charges and top-ups, no phantom growth).

**Setup:**

Same as Test 2 — observe state growth as a side-effect of the longevity run.

**Steps:**

1. At the start and end of the 16-hour run, measure: total CLPR state size (Connection records,
   message store entries, Connector records).

2. During the run, periodically count message store entries.
   → Assert: the count fluctuates within a bounded range (enqueue and delete are roughly balanced).
   It does not grow monotonically.

→ Report: CLPR state size over time. Maximum message store size during the run.

**Cleanup:** None.

---

# 4. Endpoint Resource Consumption

**Reference:** clpr-test-spec.md §7.4.3

**Purpose:** Verify that the endpoint sync orchestrator does not leak memory, threads, or file
descriptors over extended operation.

**Properties to Test:**

- P1: Memory consumption of the endpoint module is stable (no monotonic growth).
- P2: Thread count for the sync orchestrator thread pool is stable.
- P3: Open file descriptor count (gRPC connections) is stable.

**Setup:**

Same as Test 2 — observe resource consumption as a side-effect of the longevity run.

**Steps:**

1. Every 15 minutes, sample: JVM heap usage for the consensus node, thread count for the CLPR sync
   thread pool, and open gRPC connection count.

→ Report: time-series of resource metrics. Flag any monotonic growth trends.

**Cleanup:** None.
