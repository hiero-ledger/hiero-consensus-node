# Serial CryptoTransfer JFR analysis (`node-profile-8.jfr`)

Follow-up to [`serial-crypto-transfer-jfr-analysis-7.md`](serial-crypto-transfer-jfr-analysis-7.md). Same NLG CryptoTransfer shape, **serial handle**, after the record-cache cut (second-based purge skip, per-second payer buckets, slim `HistorySource`). NLG: **16,986 TPS** (`5,096,028` transfers in 300 s) — **+1.3%** vs P7 (16,767), **+0.4%** vs P5 (16,922).

Recording: 42 MB, JFR 2.1, **2026-08-30 13:22:57 UTC, 379 s**. Temurin 25.0.2, `:app:run` (default 16g pin). How it was read: `jfr summary` / `jfr view`, then `jfr print --stack-depth 64` on `jdk.ExecutionSample` (46,689 events) and `--stack-depth 32` on `jdk.ObjectAllocationSample` (79,041 events, weighted). Dump reason: JVM shutdown.

## Bottom line

**TPS is the series high. The record-cache change did not buy it — and `dropExpiredPayerBuckets` is a handle regression.** `addRecordSource` fell **6.5% → 4.0%** and `hasDuplicate` **1.7% → 1.3%**. Inclusive `RecordCacheImpl` rose **16.2% → 24.0%** because the new expiry path spends **14.2% of handle samples** in `ConcurrentHashMap.Traverser.advance` / `removeEntryIf`.

```656:660:hedera-node/hedera-app/src/main/java/com/hedera/node/app/state/recordcache/RecordCacheImpl.java
private void dropExpiredPayerBuckets(final long earliestValidStartSecond) {
    payerTxnIds.entrySet().removeIf(entry -> {
        entry.getValue().dropExpired(earliestValidStartSecond);
        return entry.getValue().isEmpty();
    });
```

Every time the earliest valid-start **second** advances, this walks the whole `payerTxnIds` CHM table. Every drop sample (824 / 5,614) is on that traverser, called from `commitReceipts` at round end. `PayerTxnIndex.dropExpired` itself is only **3.2%** of those stacks — the cost is scanning the map, not dropping lists.

1. **`addRecordSource` / slim `HistorySource` look real.** First-app `addRecordSource` is gone from the top 15 (was 5.2% in P7). Handle alloc in `HistorySource` / `PayerTxnIndex` is &lt; 1%.
2. **TPS 16,986 is P5-like.** +64 vs P5, +219 vs P7. Same 300 s window. Do not treat it as a record-cache win.
3. **`dropExpiredPayerBuckets` is the new hottest first-app frame (14.2%).** Fix this before another JFR or P9 will still be this scan.
4. **Handle alloc ~302 MB/s** (P7 ~283). Lambda tax and throttle snapshots are unchanged. Wrap ctors stay 0.
5. **G1 is slightly better, still tight.** STW **46.9 s / 379 s (12.4%)**, P99 **1.22 s**, `EvacuationFailed` **899**, `ConcurrentModeFailure` **28**. Used heap still **13.7–16 / 16 GB**.

Do **not** start `commitFullStack` / VirtualMap until this scan is gone. Otherwise the next profile mixes two stories.

## Recording shape

|              Item              |     P7 (wrap reuse) |   **P8 (record cache)** |
|--------------------------------|--------------------:|------------------------:|
| NLG TPS (300 s)                |              16,767 |              **16,986** |
| Heap                           |  16=16 GB, pretouch |  **16=16 GB, pretouch** |
| Heap used (end)                |        14.5–16 / 16 |        **13.7–16 / 16** |
| Duration                       |               400 s |               **379 s** |
| Execution samples              |              45,322 |              **46,689** |
| Handle samples                 |                8.8% |               **12.0%** |
| Handle alloc                   |      113 GB / 34.5% |      **114 GB / 34.7%** |
| Handle alloc rate              |           ~283 MB/s |           **~302 MB/s** |
| GC pause total / % file        |      53.5 s / 13.4% |      **46.9 s / 12.4%** |
| Pause P50 / P99 / max STW      | 25 / 1250 / 1270 ms | **25 / 1220 / 1360 ms** |
| `EvacuationFailed`             |               1,006 |                 **899** |
| `ConcurrentModeFailure`        |                  30 |                  **28** |
| Old GC events                  |                  97 |                  **90** |
| JVM user avg / max             |       29.3% / 73.0% |       **30.5% / 77.3%** |
| Leak detector                  |                   0 |                   **0** |
| `SubmissionManager.submit` max |              1.27 s |              **1.29 s** |
| CHM `computeIfAbsent` max wait |              1.17 s |              **1.19 s** |

Handle share rose because the handler spent ~16.5 s of the file in `dropExpiredPayerBuckets` (824 samples × ~20 ms). That is extra serial work, not a faster handler.

## Where CPU went

|           Thread group           |                          P7 |                          **P8** |
|----------------------------------|----------------------------:|--------------------------------:|
| `grpc-nio-worker-*`              | 49.8% (ingest 76%, sig 58%) | **49.5%** (ingest 77%, sig 59%) |
| `platformForkJoinThread-*`       |       29.1% (prehandle 89%) |       **26.5%** (prehandle 89%) |
| `<scheduler TransactionHandler>` |                        8.8% |                       **12.0%** |
| `VirtualHasherForkJoinThread-*`  |                        4.3% |                        **4.1%** |

### Handle thread (5,614 samples)

|                  Marker                  |          P7 |                                                 **P8** |
|------------------------------------------|------------:|-------------------------------------------------------:|
| `SavepointStack`                         |       30.0% |                                              **27.0%** |
| `VirtualMap`                             |       29.3% |                                              **25.5%** |
| `commitFullStack`                        |       21.2% |                                              **19.1%** |
| CHM / `java.util.HashMap`                | 27.9 / 20.0 |                                        **36.8 / 15.5** |
| `FinalizeRecord`                         |       14.3% |                                              **12.5%** |
| `RecordCacheImpl`                        |       16.2% | **24.0%** (add 4.0%, purge 16.5%, `hasDuplicate` 1.3%) |
| `dropExpiredPayerBuckets`                |         n/a |                                              **14.7%** |
| `VirtualNodeCache`                       |       14.2% |                                              **13.1%** |
| `ImmediateStateChangeListener`           |       11.4% |                                              **20.9%** |
| `BlockStreamManager`                     |       11.4% |                                              **22.2%** |
| `CryptoTransferHandler`                  |       10.6% |                                               **9.9%** |
| `AdjustHbarChangesStep`                  |        6.7% |                                               **6.4%** |
| `preHandleAllTransactions`               |        3.4% |                                               **3.2%** |
| `WrappedState` / `WrappedWritableStates` | 24.6 / 22.3 |                                        **22.3 / 20.6** |
| wrap / stack `.<init>` / `newRootStack`  |   0 / 0 / 0 |                                          **0 / 0 / 0** |
| WRB / streams / leak                     |           0 |                                                  **0** |

`VirtualMap` / `commitFullStack` shares fell because record-cache + listener/block-stream inclusive grew, not because those paths got cheaper. `ImmediateStateChangeListener` / `BlockStreamManager` overlap `commitReceipts` (**18.8%**) when the receipt queue is flushed at round end.

First app frames: **`dropExpiredPayerBuckets` 14.2%**, `WrappedState.getWritableStates` 2.8%, `putLeaf` 2.6%, `WritableStatesStack.getSingleton` 2.3%, `WrappedWritableStates.get` 2.0%. `addRecordSource` is no longer in the top 15.

Hottest ingest locks (max ≈ STW): `submit` 1.29 s, CHM 1.19 s, `hashCopy` 316 ms (no longer the max).

## Allocation

Handle **114 GB / 379 s ≈ 302 MB/s**. Wrap construction stays gone. The leftover is the same P7 lambda / store-factory / throttle mix, plus a bit more stream-builder churn.

|                   Site                   |        P7 |        **P8** |
|------------------------------------------|----------:|--------------:|
| `WrappedState.<init>` / wrap states ctor |         0 |         **0** |
| `DirectMethodHandle.allocateInstance`    |     19.2% |     **18.8%** |
| `usageSnapshot()`                        |      5.6% |      **3.1%** |
| `WritableStoreFactory.getStore`          |      3.6% |      **3.6%** |
| `HashMap.resize` (handle / all)          | 2.0 / 1.4 | **2.1 / 1.5** |
| `PayerTxnIndex` / `HistorySource`        |       n/a | **0.6 / 0.4** |

Top handle classes: `Object[]` 4.5%, `WritableStatesStack$$Lambda` 3.7%, `byte[]` 3.6%, `ThrottleUsageSnapshot` 3.4%, `WrappedWritableStates$$Lambda` 3.3%.

## JVM series so far

```
P1  BOTH + leak on + 1→16g      ~10k+ TPS, WRB + re-prehandle + streams
P2  BLOCKS + leak on + 1→16g     14,024
P3  BLOCKS + leak off + 1→16g    16,551
P4  BLOCKS + leak off + 12g pin  13,103
P5  BLOCKS + leak off + 16g pin  16,922  ← JVM baseline
P6  P5 + SavepointStack reuse    16,511  ← alloc win, TPS flat
P7  P6 + WrappedState reuse      16,767  ← ctor gone, alloc rate flat
P8  P7 + record-cache index      16,986  ← add cheaper, expiry scan worse
```

---

## Top 5 next (code)

### 1. Stop scanning `payerTxnIds` with `CHM.removeIf` (do this before P9)

**Why:** 14.2% first-app, 14.7% inclusive, 100% of those samples in `ConcurrentHashMap.Traverser`. The per-second bucket drop is cheap (`PayerTxnIndex.dropExpired` 3.2% of the drop stacks). The map walk is not.

**What to do:** keep a handle-thread list (or the single live `PayerTxnIndex` for the common payer) and expire that. Use the CHM only for `getRecords` lookup. Or, if the map is one entry, call `dropExpired` on that value and `remove` only when empty — never `removeIf`.

**What should move:** `dropExpiredPayerBuckets` / `purgeExpired` / `RecordCacheImpl` inclusive back toward P7’s 16% or below; handle share back toward ~9%.

### 2. `get` then `put` on wrap / stack adapter caches

**Why:** Unchanged from P7. `DirectMethodHandle.allocateInstance` still **18.8%** of handle alloc; `getWritableStates` / `get` still top first-app frames after the expiry scan.

### 3. Cut `commitFullStack` + VirtualMap put on the serial thread

**Why:** Still ~19% / ~26% once the expiry scan is gone. Soft-commit (wrap + block diffs now, `putLeaf` at `VirtualMap.copy`) is the TPS lever.

### 4. Throttle snapshots at round/block

**Why:** `ThrottleUsageSnapshot` still **3.4%** of handle alloc.

### 5. Batch receipt-queue / KV block items

**Why:** `commitReceipts` **18.8%**, `ImmediateStateChangeListener` **20.9%**, `BlockStreamManager` **22.2%** — inflated this file by sitting under the same round-end path as the CHM scan. Re-measure after (1).

|               Metric               |        P7 |            P8 |    After dropExpired fix     |
|------------------------------------|----------:|--------------:|------------------------------|
| NLG TPS                            |    16,767 |        16,986 | same band; handle share down |
| `RecordCacheImpl` inclusive        |     16.2% |     **24.0%** | ≤ 16%, `dropExpired` ≪ 1%    |
| `addRecordSource` / `hasDuplicate` | 6.5 / 1.7 | **4.0 / 1.3** | stay                         |
| `commitFullStack` / `VirtualMap`   | 21% / 29% |     19% / 26% | re-read after (1)            |
| Handle alloc rate                  | ~283 MB/s |     ~302 MB/s | ~P7                          |
| Leak detector / WRB / wrap ctors   | 0 / 0 / 0 | **0 / 0 / 0** | stay 0                       |
