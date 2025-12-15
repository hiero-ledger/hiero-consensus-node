# Block Production Benchmarks

## Overview

Performance benchmarks for the block production pipeline using JMH (Java Microbenchmark Harness).

---

## Benchmark Classes

### 1. `BlockProductionBenchmark.java`

**End-to-end performance test**

- **Purpose:** Measures complete block production pipeline throughput
- **Tests:** Full flow from transaction → block production → serialization
- **Target:** 100K TPS (currently achieving 127K TPS)
- **Components tested:**
  - BlockStreamBuilder
  - BlockStreamManager task system
  - Merkle tree operations (all 5 trees)
  - Block hash computation
  - State operations (simulated)
  - Block serialization

**When to run:**
- Before releases (regression testing)
- After major changes (validate performance)
- To measure overall system capacity

---

### 2. `BlockProductionMicrobenchmarks.java`

**Component-level performance tests**

- **Purpose:** Measures individual components in isolation to find bottlenecks
- **Tests:** 8 separate benchmarks for different components
  - BlockItem serialization (3 types)
  - BlockItem hashing (SHA-384)
  - Merkle tree operations (ConcurrentStreamingTreeHasher)
  - Previous block hashes (IncrementalStreamingHasher)
  - BlockStreamBuilder accumulation
  - Block hash combining (10× SHA-384)
  - Block serialization
  - Running hash computation (n-3)

**When to run:**
- When end-to-end performance drops (find which component is slow)
- To validate component optimizations
- To understand scaling behavior at different loads

---

### 3. `HashingBenchmark.java`

**Algorithm-level performance test**

- **Purpose:** Measures pure SHA-384 hashing and merkle tree computation
- **Tests:**
  - ConcurrentStreamingTreeHasher with real data
  - Parallel merkle root computation
  - Algorithm-level performance (no application overhead)

**When to run:**
- To establish baseline hashing performance
- To test different parallelization strategies
- To validate merkle tree optimizations

---

## Utility Classes

### `BenchmarkBlockStreamManager.java`

**Production-realistic block stream manager**

- **Purpose:** Trimmed copy of `BlockStreamManagerImpl` for benchmarking
- **Contains:**
  - Real task system (ParallelTask, SequentialTask)
  - Real merkle tree operations (all 5 trees)
  - Real running hash computation
  - Real block hash combining
  - Simulated state operations (equivalent CPU cost)
- **Why not use real `BlockStreamManagerImpl`?**
  - Too many dependencies (state, platform, config, etc.)
  - Disk I/O and signing would add noise
  - Want to isolate pure CPU-bound block production

---

### `TransactionGeneratorUtil.java`

**Realistic transaction generator**

- **Purpose:** Creates realistic CryptoTransfer transactions for testing
- **Features:**
  - Configurable transaction size (via memo padding)
  - Object reuse pattern (caches default size transactions)
  - Bulk generation methods
  - Rate-limited transaction spamming

---

## Performance Results

**With Production Configuration (batch size 32):**

|           Benchmark            |   Target    |   Achieved    | Headroom |
|--------------------------------|-------------|---------------|----------|
| **End-to-End**                 | 100K TPS    | 127K TPS      | +27%     |
| **BlockItem Serialization**    | 200K/s      | 4-66M ops/s   | 20-330×  |
| **BlockItem Hashing**          | 200K/s      | 10M ops/s     | 50×      |
| **Merkle Trees (100K leaves)** | 1M leaves/s | 8.8M leaves/s | 8.8×     |
| **BlockStreamBuilder**         | 100K/s      | 4.5M ops/s    | 45×      |
| **Running Hash**               | 100K/s      | 10M ops/s     | 100×     |
| **Block Serialization**        | 0.5/s       | ~30/s         | 60×      |

**Key Findings:**
- System achieves 127K TPS (27% above 100K target)
- All components have sufficient headroom

---

## Configuration

- **Merkle tree batch size:** 32 (matches production `BlockStreamConfig.hashCombineBatchSize`)
- **JMH warmup:** 2 iterations (ensures proper JIT optimization)
- **Component testing:** Extended to 100K params (validates high-load scaling)

---

## Architecture

```
BlockProductionBenchmark (end-to-end)
    ↓ uses
BenchmarkBlockStreamManager (production logic)
    ↓ uses
TransactionGeneratorUtil (test data)

BlockProductionMicrobenchmarks (components)
    ↓ uses
Real production classes directly (no mocking)
```

---

## Notes

- Changes to production APIs break benchmarks.
