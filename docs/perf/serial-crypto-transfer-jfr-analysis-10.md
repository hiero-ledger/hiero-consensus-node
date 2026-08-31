# Serial CryptoTransfer JFR analysis (`node-profile-10.jfr`)

Follow-up to [`serial-crypto-transfer-jfr-analysis-9.md`](serial-crypto-transfer-jfr-analysis-9.md). Same NLG CryptoTransfer shape, **serial handle**, after P9 items 1 and 3: wrap originals via `getCurrent().getOriginalValue` (not merkle readable / VirtualMap), wrap `commit()` skips merkle `putLeaf` when `requiresImmediateCommit()` is false, listeners fire on wrap apply, `VirtualMap.copy()` still `flushPendingWrites()`, and `WritableStoreFactory` caches stores by `Class` for the life of the factory. NLG: **16,973 TPS** (`5,092,108` transfers in 300 s) — **−1.5%** vs P9 (17,237), **+0.3%** vs P5 (16,922). No `FAIL_INVALID` / account `1002`.

Recording: 42 MB, JFR 2.1, **2026-08-30 15:28:40 UTC, 391 s**. Temurin 25.0.2, PID 93901, `:app:run` (default 16g pin). How it was read: `jfr summary` / `jfr view`, then `jfr print --stack-depth 48` on `jdk.ExecutionSample` (46,989 events) and `--stack-depth 24` on `jdk.ObjectAllocationSample` (81,000 events, weighted). Dump reason: JVM shutdown.

## Bottom line

**Soft-commit landed on the wrap path and did not break originals. It did not leave the serial thread.** `commitFullStack` **20.4% → 7.0%**, `SavepointStack` **27.5% → 12.8%**, wrap **21.4 / 20.4 → 9.2 / 8.7**. `putLeaf` inclusive **11.7% → 6.3%** (first-app still **3.1%**). The missing `putLeaf` moved to `NodeFeeManager.onCloseBlock` → `updateNodePaymentsState` → `TokenService` `MerkleWritableStates.commit()` (**13.2%** of handle, 705 samples). That call flushes **every** dirty Token KV leaf at block close, including all CryptoTransfer account mods deferred from wrap. Same VirtualMap work, later on the same thread. TPS stayed in the P5–P9 band.

Store cache did what a per-dispatch factory can do: `WritableAccountStore` as an allocated class **2.9% → 0.41%**. `getStore` inclusive alloc is still **4.8%** because a new factory (and its `HashMap`) is built every dispatch, and `TokenServiceApiImpl` still `new WritableAccountStore`.

1. **Originals fix held.** 5.09 M transfers finished. Wrap `getOriginalValue` seeing merkle mods is enough for CT finalize / entity-id.
2. **`commitFullStack` is no longer the TPS lever.** The leftover VM flush is `onCloseBlock` committing all of TokenService.
3. **TPS 16,973 is noise around P9.** −264 vs P9, +51 vs P5. Same 300 s window.
4. **Store cache cut AccountStore construction, not `getStore`.** Next win is reuse the factory across txns (and stop `TokenServiceApiImpl` allocating its own store).
5. **G1 is slightly better, still tight.** STW **48.0 s / 391 s (12.3%)**, P99 **1.28 s**, `EvacuationFailed` **902**, `ConcurrentModeFailure` **28**. Used heap **1.8–16 / 16 GB** (p50 **14.9**).

```145:154:hedera-node/hedera-app/src/main/java/com/hedera/node/app/state/WrappedWritableStates.java
public void commit() {
    commitInStateIdOrder(writableKVStateMap, WrappedWritableKVState::commit);
    commitInStateIdOrder(writableQueueStateMap, WrappedWritableQueueState::commit);
    commitInStateIdOrder(writableSingletonStateMap, WrappedWritableSingletonState::commit);
    if (delegate instanceof CommittableWritableStates terminalStates && terminalStates.requiresImmediateCommit()) {
        terminalStates.commit();
    }
}
```

```107:110:hedera-node/hedera-app/src/main/java/com/hedera/node/app/services/NodeFeeManager.java
public void onCloseBlock(@NonNull final State state) {
    if (configProvider.getConfiguration().getConfigData(NodesConfig.class).feeCollectionAccountEnabled()) {
        updateNodePaymentsState(state);
```

```383:401:hedera-node/hedera-app/src/main/java/com/hedera/node/app/services/NodeFeeManager.java
private void updateNodePaymentsState(@NonNull final State state) {
    final var writableTokenState = state.getWritableStates(TokenService.NAME);
    // ... put NODE_PAYMENTS singleton ...
    ((CommittableWritableStates) writableTokenState).commit();
}
```

Of 817 handle samples in `MerkleWritableStates.commit`: **694** are `NodeFeeManager` + `BlockStreamManager`, **109** are `RecordCacheImpl.commitReceipts` (still required for the receipt queue), **81** also contain `WrappedWritableStates` (HandleWorkflow streaming commits, not wrap calling merkle).

## Recording shape

|              Item              | P9 (list expiry + get-then-put) | **P10 (soft-commit + store cache)** |
|--------------------------------|--------------------------------:|------------------------------------:|
| NLG TPS (300 s)                |                          17,237 |                          **16,973** |
| Heap                           |            16=16 GB, pretouch |              **16=16 GB, pretouch** |
| Heap used                      |                  13.9–16 / 16 |              **1.8–16 / 16** (p50 14.9) |
| Duration                       |                           378 s |                           **391 s** |
| Execution samples              |                          46,067 |                          **46,989** |
| Handle samples                 |                           11.3% |                           **11.4%** |
| Handle alloc                   |                 92.5 GB / 32.8% |                 **83.7 GB / 30.5%** |
| Handle alloc rate              |                       ~245 MB/s |                       **~219 MB/s** |
| GC pause total / % file        |                  50.5 s / 13.4% |                  **48.0 s / 12.3%** |
| Pause P50 / P99 / max STW      |             25 / 1230 / 1300 ms |             **28 / 1280 / 1710 ms** |
| `EvacuationFailed`             |                             854 |                             **902** |
| `ConcurrentModeFailure`        |                              29 |                              **28** |
| Old GC events                  |                              98 |                              **92** |
| JVM user avg / max             |                   31.5% / 78.5% |                   **28.9% / 77.3%** |
| Leak detector                  |                               0 |                               **0** |
| `SubmissionManager.submit` max |                          1.21 s |                          **1.30 s** |
| `hashCopy` max                 |                          1.42 s |                          **1.78 s** |

Handle share was flat while `commitFullStack` fell: the handler spent the saved wrap-commit time in block-close TokenService flush and block items.

## Where CPU went

|           Thread group           |                          P9 |                          **P10** |
|----------------------------------|----------------------------:|---------------------------------:|
| `grpc-nio-worker-*`              | 49.6% (ingest 77%, sig 60%) | **49.3%** (ingest 77%, sig 59%) |
| `platformForkJoinThread-*`       |       27.1% (prehandle 90%) |       **27.1%** (prehandle 89%) |
| `<scheduler TransactionHandler>` |                       11.3% |                       **11.4%** |
| `VirtualHasherForkJoinThread-*`  |                        4.4% |                        **4.3%** |

### Handle thread (5,343 samples)

|                  Marker                  |                                      P9 |                                                       **P10** |
|------------------------------------------|----------------------------------------:|--------------------------------------------------------------:|
| `SavepointStack`                         |                                   27.5% |                                                     **12.8%** |
| `VirtualMap`                             |                                   28.1% |                                                     **24.1%** |
| `commitFullStack`                        |                                   20.4% |                                                      **7.0%** |
| CHM / `java.util.HashMap`                |                             24.0 / 14.2 |                                               **20.2 / 17.0** |
| `FinalizeRecord`                         |                                   13.9% |                                                     **10.3%** |
| `RecordCacheImpl`                        |                                   23.0% | **23.1%** (add 4.7%, purge 12.0%, `hasDuplicate` 1.2%, receipts 17.1%) |
| `dropExpiredPayerBuckets`                |                                   11.8% |                    **12.0%** (leaf `PayerTxnIndex.isEmpty` 8.5%) |
| `VirtualNodeCache`                       |                                   14.4% |                                                      **9.1%** |
| `ImmediateStateChangeListener`           |                                   20.1% |                                                     **19.9%** |
| `BlockStreamManager`                     |                                   24.9% |                                                     **33.6%** |
| `CryptoTransferHandler`                  |                                    8.7% |                                                     **10.7%** |
| `AdjustHbarChangesStep`                  |                                    5.8% |                                                      **8.3%** |
| `preHandleAllTransactions`               |                                    4.6% |                                                      **5.0%** |
| `WrappedState` / `WrappedWritableStates` |                             21.4 / 20.4 |                                                **9.2 / 8.7** |
| `putLeaf`                                |                  11.7% incl / 3.1% first |                           **6.3%** incl / **3.1%** first-app |
| `MerkleWritableStates.commit`            |                             (in wrap commit) | **15.3%** (NodeFee block-close 13.2%, receipts 2.0%) |
| `commitInStateIdOrder`                   |                             (in wrap+merkle) | **19.7%** (merkle 15.3% + wrap apply 4.4%) |
| wrap / stack `.<init>` / `newRootStack`  |                               0 / 0 / 0 |                                                 **0 / 0 / 0** |
| WRB / streams / leak                     |                                       0 |                                                         **0** |

`commitInStateIdOrder` on wrap (237 samples) is wrap apply into merkle **buffers** + listener notify — not `putLeaf`. The merkle overload (816 samples) is `NodeFeeManager` / `RecordCacheImpl`.

First-app frames: `PayerTxnIndex.isEmpty` **8.5%**, `WritableStreamingData.writeByte` **4.0%**, `ConfigDataService.getConfigData` **3.7%**, `putLeaf` **3.1%**, `dropExpiredPayerBuckets` **2.1%**.

Hottest ingest locks (max ≈ STW): `hashCopy` 1.78 s, `submit` 1.30 s, warmup/`computeIfAbsent` 1.12 s, throttle 1.09 s.

## Allocation

Handle **83.7 GB / 391 s ≈ 219 MB/s** (P9 ~245). AccountStore construction is gone. Leftover `getStore` is the per-dispatch factory `HashMap` plus one-shot Nft/Token/Staking stores.

|                   Site                   |      P9 |      **P10** |
|------------------------------------------|--------:|-------------:|
| `WrappedState.<init>` / wrap states ctor |       0 |        **0** |
| `DirectMethodHandle.allocateInstance`    |    3.3% |     **1.9%** |
| wrap / stack `$$Lambda`                  |       0 |        **0** |
| `usageSnapshot()`                        |    5.5% |     **7.6%** |
| `WritableStoreFactory.getStore`          |    4.8% |     **4.8%** |
| `WritableAccountStore` (class)           |    2.9% | **0.41%** |
| `HashMap.resize` (handle)                |    1.7% |     **3.2%** |
| `ThrottleUsageSnapshot` (class)          |    5.2% |     **6.9%** |

Top handle classes: `ThrottleUsageSnapshot` **6.9%**, `Object[]` 4.9%, `Account` 4.1%, `byte[]` 3.8%, `HashMap$Node[]` 3.2%. `getStore` object mix is `HashMap$Node` / resize **61%**, then Nft/Token/Rel/Staking stores; AccountStore is **5.3% of that 4.8%**.

```163:176:hedera-node/hedera-app/src/main/java/com/hedera/node/app/store/WritableStoreFactory.java
public <C> C getStore(@NonNull final Class<C> storeInterface) {
    final var cached = storeCache.get(storeInterface);
    if (cached != null) {
        return storeInterface.cast(cached);
    }
    // ... create once, then storeCache.put ...
}
```

## JVM series so far

```
P1  BOTH + leak on + 1→16g      ~10k+ TPS, WRB + re-prehandle + streams
P2  BLOCKS + leak on + 1→16g     14,024
P3  BLOCKS + leak off + 1→16g    16,551
P4  BLOCKS + leak off + 12g pin  13,103
P5  BLOCKS + leak off + 16g pin  16,922  ← JVM baseline
P6  P5 + SavepointStack reuse    16,511
P7  P6 + WrappedState reuse      16,767
P8  P7 + record-cache index      16,986
P9  P8 + list expiry + get-then-put
    (soft-commit reverted)       17,237  ← series high
P10 P9 + wrap soft-commit
    + store cache                16,973  ← wrap path cheaper; Token flush at block close
```

---

## Top 5 next (code)

### 1. Stop flushing all TokenService leaves on block close

**Why:** Soft-commit did its job on wrap. `NodeFeeManager.updateNodePaymentsState` then calls `writableTokenState.commit()`, which `putLeaf`s every dirty account. `onCloseBlock` is **13.2%** of handle; `putLeaf` first-app is still **3.1%**.

**What to do:** commit only the `NODE_PAYMENTS` singleton (or a dedicated writable states view), and leave account KV buffers for `VirtualMap.copy()` / `flushPendingWrites()`. Do not call `MerkleWritableStates.commit()` on TokenService just to persist node-fee totals.

**What should move:** `putLeaf` / `MerkleWritableStates.commit` / `BlockStreamManager` down on handle; TPS if that flush left the serial path.

### 2. Throttle snapshots at round/block

**Why:** `ThrottleUsageSnapshot` is the top handle class (**6.9%**). `usageSnapshot()` **7.6%** of handle alloc. More visible now that wrap/store construction is quieter.

### 3. Reuse `WritableStoreFactory` across sequential handle txns

**Why:** Intra-dispatch cache worked (`WritableAccountStore` class **0.41%**). `getStore` is still **4.8%** because `ParentTxnFactory` builds a new factory every dispatch (HashMap nodes + one Nft/Token/Staking store each). `TokenServiceApiImpl` still `new WritableAccountStore`. Keep one factory on the reusable root stack.

### 4. Batch receipt-queue / KV block items

**Why:** `BlockStreamManager` **33.6%** (was 24.9% — block close now includes the Token flush). `commitReceipts` **17.1%**, `ImmediateStateChangeListener` **19.9%**. Receipt-queue merkle `commit()` is the remaining per-txn `putLeaf` (~2%) and should stay until receipts can buffer.

### 5. Trim once-per-second record-cache purge

**Why:** `dropExpiredPayerBuckets` **12.0%**, first-app `PayerTxnIndex.isEmpty` **8.5%**. Unchanged vs P9. Do not put `removeIf` back. Optional: skip the list walk when `livePayerIndexes.size() == 1`.

|              Metric               |            P9 |           **P10** |              After (1) |
|-----------------------------------|--------------:|------------------:|-----------------------:|
| NLG TPS                           |        17,237 |        **16,973** |   up if Token flush left handle |
| `commitFullStack` / wrap          |   20% / 21–20 |     **7% / 9–9** |                    stay |
| `putLeaf` incl / first-app        |   11.7 / 3.1 |    **6.3 / 3.1** |              down / down |
| `MerkleWritableStates.commit`     |    (in wrap) |         **15.3%** |          ~2% (receipts) |
| `BlockStreamManager`              |         24.9% |         **33.6%** |                    down |
| `getStore` / AccountStore class   |   4.8 / 2.9 |   **4.8 / 0.41** |              stay / stay |
| Handle alloc rate                 |     ~245 MB/s |       **~219 MB/s** |           ~P10 or below |
| Leak detector / WRB / wrap ctors  |   0 / 0 / 0 |     **0 / 0 / 0** |                  stay 0 |
