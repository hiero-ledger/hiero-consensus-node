# Block Production Benchmarks

## Overview

Performance benchmarks for the block production pipeline using JMH (Java Microbenchmark Harness).

---

## Benchmark Classes

### 1. `BlockProductionBenchmark.java`

**End-to-end performance test**

- **Purpose:** Measures complete block production pipeline throughput using REAL `BlockStreamManagerImpl`
- **Tests:** Full flow from transaction → block production → serialization
- **Components tested:**
  - `BlockStreamManagerImpl`
  - `BlockStreamBuilder` (translates execution results to BlockItems)
  - BlockStreamManager task system (`ParallelTask`, `SequentialTask`)
  - Merkle tree operations (all 5 trees via `ConcurrentStreamingTreeHasher`)
  - Block hash computation (10× SHA-384 combines)
  - State operations (API calls with simulated VirtualMap costs)
  - Block serialization

---

### 2. `BlockProductionMicrobenchmarks.java`

**Component-level performance tests**

- **Purpose:** Measures individual components in isolation to find bottlenecks
- **Tests:** 8 separate benchmarks for different components
  - BlockItem serialization (3 types: TransactionResult, SignedTransaction, StateChanges)
  - BlockItem hashing (SHA-384)
  - Merkle tree operations (`ConcurrentStreamingTreeHasher` with varying leaf counts)
  - Previous block hashes (`IncrementalStreamingHasher`)
  - `BlockStreamBuilder` accumulation (translates execution results to BlockItems)
  - Block hash combining (10× SHA-384 combine operations)
  - Block serialization (varying block sizes)
  - Running hash computation (n-3 pattern)

**When to run:**
- When end-to-end performance drops (find which component is slow)
- To validate component optimizations
- To understand scaling behavior at different loads

---

## Utility Classes

### `BlockStreamManagerWrapper.java`

**Wrapper for BlockStreamManagerImpl**

- **Purpose:** Simplifies usage of `BlockStreamManagerImpl` in benchmarks by handling complex `Round` and `State` object creation
- **Key Features:**
  - Wraps production `BlockStreamManagerImpl` with a simpler API (`startBlock()`, `writeItem()`, `sealBlock()`)
  - Provides minimal `BenchmarkRound` and `BenchmarkState` implementations
  - Handles state hash future completion (simulates platform's `StateHashedNotification`)
  - Tracks items written for metrics (`getTotalItemsWritten()`)
  - Simulates VirtualMap operation costs:
    - State read: 15× SHA-384 hashes (simulates `VirtualMap.get()` path traversal)
    - State commit: 28× SHA-384 hashes (simulates `VirtualMap.put() + commit()` path rehashing)
- **Why use real `BlockStreamManagerImpl`?**
  - Actual production code path (most realistic)
  - No code copying/maintenance burden
  - Automatically stays in sync with production changes
  - Uses `NoOpDependencies` for non-critical components

---

### `NoOpDependencies.java`

**No-op implementations for benchmarking**

- **Purpose:** Provides minimal implementations of dependencies required by `BlockStreamManagerImpl` to enable isolated performance testing
- **Key Classes:**

  **No-Op Implementations:**
  - `NoOpBlockItemWriter` - No disk I/O (intentionally excluded)
  - `NoOpPlatform` - No Swirlds platform operations
  - `NoOpMetrics` - No metrics collection overhead
  - `NoOpLifecycle` - No node reward hooks
  - `NoOpStoreMetricsService` - No store metrics

  **Realistic Implementations:**
  - `RealisticBlockHashSigner` - Simulates block signing cost (SHA-384 hash, async)
  - `createBenchmarkBoundaryStateChangeListener()` - Real `BoundaryStateChangeListener` with `NoOpStoreMetricsService`
  - `createBenchmarkQuiescenceController()` - Real `QuiescenceController` (disabled via config)
  - `createBenchmarkQuiescedHeartbeat()` - Real `QuiescedHeartbeat` with real `QuiescenceController`

  **Configuration:**
  - `createBenchmarkConfigProvider()` - Sets up complete configuration with all required config data types
  - Configures TSS disabled, quiescence disabled, and other benchmark-appropriate settings

- **Design Philosophy:**

  - Use real implementations where they matter
  - Use NoOp where they don't affect throughput (disk I/O, metrics, platform)
  - Simulate CPU costs accurately (block signing, state operations)

---

### `TransactionGeneratorUtil.java`

- **Purpose:** Creates CryptoTransfer transactions for testing
- **Features:**
  - Configurable transaction size (via memo padding)
  - Object reuse pattern (caches default size transactions for performance)
  - Bulk generation methods (`generateTransactions(count)`)
  - Rate-limited transaction spamming (`spamTransactions()` with TPS control)
  - Default transaction size support (`DEFAULT_TX_SIZE = 200` bytes)
  - Size measurement (`getTransactionSize()`)

---

## Configuration

- **Merkle tree batch size:** 32 (matches production `BlockStreamConfig.hashCombineBatchSize`)
- **JMH warmup:** 2 iterations, 2 seconds (ensures proper JIT optimization)
- **JMH measurement:** 3 iterations, 2 seconds
- **Component testing:** Extended to 100K params (validates high-load scaling)
- **Microbenchmarks warmup:** 3 iterations, 1 second
- **Microbenchmarks measurement:** 5 iterations, 1 second

---

## Architecture

```
BlockProductionBenchmark (end-to-end)
    ↓ uses
BlockStreamManagerWrapper
    ↓ wraps
BlockStreamManagerImpl (production code)
    ↓ uses
NoOpDependencies (minimal implementations)
    ↓ uses
TransactionGeneratorUtil (test data)

BlockProductionMicrobenchmarks (components)
    ↓ uses
Real production classes directly (no mocking)
    ↓ uses
TransactionGeneratorUtil (test data)
```

---

## Design Decisions

### Why Simulate Some Components?

**State Operations (VirtualMap):**
- Real VirtualMap infrastructure is complex and not needed for CPU-bound testing
- CPU cost accurately simulated with equivalent SHA-384 work
- State read: 15× SHA-384 (simulates cached path traversal)
- State commit: 28× SHA-384 (simulates log N path rehashing)

**Block Signing:**
- Signing is async and doesn't block the pipeline
- Simulated with SHA-384 hash (matches production when TSS disabled)
- Includes realistic CPU cost without TSS infrastructure complexity

### Why Use NoOp for Some Dependencies?

**NoOp Components (Don't Affect Throughput):**
- `NoOpBlockItemWriter` - Disk I/O excluded intentionally
- `NoOpPlatform` - Platform operations not part of block production
- `NoOpMetrics` - Metrics collection doesn't affect throughput
- `NoOpLifecycle` - Lifecycle hooks don't affect block production

**Real Components (Do Affect Behavior):**
- `BoundaryStateChangeListener` - Real implementation (captures state changes)
- `QuiescenceController` - Real implementation (disabled via config)
- `QuiescedHeartbeat` - Real implementation (uses real `QuiescenceController`)
