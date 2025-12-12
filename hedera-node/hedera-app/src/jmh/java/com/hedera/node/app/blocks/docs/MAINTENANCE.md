# Benchmark Maintenance Guide

## Items Copied from Production (Must Keep in Sync)

When `BlockStreamManagerImpl.java` changes, manually review these:

1. **`writeItem()` method** - Lines 125-133
   - Production: `BlockStreamManagerImpl.java` lines 732-739

2. **`BlockStreamManagerTask` class** - Lines 458-481
   - Production: `BlockStreamManagerImpl.java` lines 905-929

3. **`ParallelTask.onExecute()` method** - Lines 497-526
   - Production: `BlockStreamManagerImpl.java` lines 943-968

4. **`SequentialTask.onExecute()` method** - Lines 543-572
   - Production: `BlockStreamManagerImpl.java` lines 983-1010

5. **`RunningHashManager.nextResultHash()` method** - Lines 617-628
   - Production: `BlockStreamManagerImpl.java` lines 1040-1050

6. **`combineTreeRoots()` method** - Lines 258-310
   - Production: `BlockStreamManagerImpl.java` lines 1200-1249

7. **Merkle tree batch size** - Line 109-113
   - Production: `BlockStreamConfig.java` `hashCombineBatchSize` (default: 32)

8. **`IncrementalStreamingHasher` usage** - Lines 68, 87-91, 169, 199
   - Production: `BlockStreamManagerImpl.java` `previousBlockHashes` field

9. **`BlockFooter` creation** - Lines 205-218
   - Production: `BlockStreamManagerImpl.java` (in `endRound()` method)

---

## Simulated Components (Not in Production)

These are custom implementations for benchmarking only:

1. **`simulateStateCommit()`** - Lines 354-376
   - Simulates VirtualMap.put() + commit() with 28× SHA-384 hashes

2. **`simulateStateRead()`** - Lines 388-412
   - Simulates VirtualMap.get() with 15× SHA-384 hashes

3. **`createStateChangesItem()`** - Lines 421-429
   - Creates minimal STATE_CHANGES items (production uses BoundaryStateChangeListener)

4. **`computeStateHash()`** - Lines 437-447
   - Derives state hash from block hash (production uses full state merkle tree)

5. **Block storage** - Line 56, 546
   - Uses `currentBlockItems` list (production writes to disk via BlockBufferIO)
