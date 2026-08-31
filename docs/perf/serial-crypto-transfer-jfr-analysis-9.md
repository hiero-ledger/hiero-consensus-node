# Serial CryptoTransfer JFR analysis (`node-profile-9.jfr`)

Follow-up to [`serial-crypto-transfer-jfr-analysis-8.md`](serial-crypto-transfer-jfr-analysis-8.md). Same NLG CryptoTransfer shape, **serial handle**, after (1) `livePayerIndexes` compact instead of `CHM.removeIf`, (2) get-then-put on wrap/stack adapter caches, (3) wrap `commit()` still flushing merkle/`putLeaf` every user txn (the P8 soft-commit was reverted after `FAIL_INVALID` / account `1002`). NLG: **17,237 TPS** (`5,173,138` transfers in 300 s) — **+1.5%** vs P8 (16,986), **+1.9%** vs P5 (16,922). First clear step above the P5–P8 band.

Recording: 41 MB, JFR 2.1, **2026-08-30 13:56:32 UTC, 378 s**. Temurin 25.0.2, PID 85054, `:app:run` (default 16g pin). How it was read: `jfr summary` / `jfr view`, then `jfr print --stack-depth 48` on `jdk.ExecutionSample` (46,067 events) and `--stack-depth 24` on `jdk.ObjectAllocationSample` (81,372 events, weighted). Dump reason: JVM shutdown (`SIGTERM`).

## Bottom line

**Both intended P8 fixes landed. The CHM expiry scan is gone. The get-then-put alloc cut is real. TPS moved.** Handle `ConcurrentHashMap.Traverser` / `removeIf` is **0**. Handle `DirectMethodHandle.allocateInstance` fell **18.8% → 3.3%**. `WritableStatesStack$$Lambda` / `WrappedWritableStates$$Lambda` are off the handle alloc list. Handle alloc rate **~302 → ~245 MB/s**.

`dropExpiredPayerBuckets` is still **11.8%** inclusive (P8 14.7%), but the leaf is now `PayerTxnIndex.isEmpty` / `dropExpired` on the handle-thread list — not a table walk. `RecordCacheImpl` inclusive **24.0% → 23.0%**; `addRecordSource` **4.4%**, `hasDuplicate` **1.2%**. Handle share **12.0% → 11.3%**.

Soft-commit is **not** in this file. `putLeaf` **11.7%**, `commitFullStack` **20.4%**, `VirtualMap` **28.1%** — those are the P8 item-3 numbers with the expiry noise removed.

1. **`removeIf` is dead.** 0 handle samples in `Traverser` / `removeEntryIf`.
2. **Get-then-put did what P7 predicted.** Lambda tax on wrap/stack `get` is gone; `allocateInstance` is no longer the top handle alloc site.
3. **TPS 17,237 is the first series move above P5.** +251 vs P8, +315 vs P5. Same 300 s window.
4. **`commitFullStack` / VirtualMap are the TPS ceiling again.** Do not retry the P8 soft-commit as written — `getOriginalValue` and entity-id still read the previous VM copy, which produced genesis account 2 + account `1002` `FAIL_INVALID`.
5. **G1 is unchanged.** STW **50.5 s / 378 s (13.4%)**, P99 **1.23 s**, `EvacuationFailed` **854**, `ConcurrentModeFailure` **29**. Used heap **13.9–16 / 16 GB**.

```100:108:hedera-node/hedera-app/src/main/java/com/hedera/node/app/state/WrappedState.java
public WritableStates getWritableStates(@NonNull String serviceName) {
    final var cached = writableStatesMap.get(serviceName);
    if (cached != null) {
        return cached;
    }
    final var created = new WrappedWritableStates(delegate.getWritableStates(serviceName));
    writableStatesMap.put(serviceName, created);
    return created;
}
```

```667:677:hedera-node/hedera-app/src/main/java/com/hedera/node/app/state/recordcache/RecordCacheImpl.java
private void dropExpiredPayerBuckets(final long earliestValidStartSecond) {
    int w = 0;
    final int n = livePayerIndexes.size();
    for (int i = 0; i < n; i++) {
        final var index = livePayerIndexes.get(i);
        index.dropExpired(earliestValidStartSecond);
        if (index.isEmpty()) {
            payerTxnIds.remove(index.payerId, index);
```

## Recording shape

|              Item              |   P8 (record cache) | **P9 (expiry list + get-then-put)** |
|--------------------------------|--------------------:|------------------------------------:|
| NLG TPS (300 s)                |              16,986 |                          **17,237** |
| Heap                           |  16=16 GB, pretouch |              **16=16 GB, pretouch** |
| Heap used (end)                |        13.7–16 / 16 |                    **13.9–16 / 16** |
| Duration                       |               379 s |                           **378 s** |
| Execution samples              |              46,689 |                          **46,067** |
| Handle samples                 |               12.0% |                           **11.3%** |
| Handle alloc                   |      114 GB / 34.7% |                 **92.5 GB / 32.8%** |
| Handle alloc rate              |           ~302 MB/s |                       **~245 MB/s** |
| GC pause total / % file        |      46.9 s / 12.4% |                  **50.5 s / 13.4%** |
| Pause P50 / P99 / max STW      | 25 / 1220 / 1360 ms |             **25 / 1230 / 1300 ms** |
| `EvacuationFailed`             |                 899 |                             **854** |
| `ConcurrentModeFailure`        |                  28 |                              **29** |
| Old GC events                  |                  90 |                              **98** |
| JVM user avg / max             |       30.5% / 77.3% |                   **31.5% / 78.5%** |
| Leak detector                  |                   0 |                               **0** |
| `SubmissionManager.submit` max |              1.29 s |                          **1.21 s** |
| CHM `computeIfAbsent` max wait |              1.19 s |                          **1.17 s** |

Handle share fell while TPS rose: the handler did more useful work per sample (no CHM table walk, less alloc/GC on wrap `get`).

## Where CPU went

|           Thread group           |                          P8 |                          **P9** |
|----------------------------------|----------------------------:|--------------------------------:|
| `grpc-nio-worker-*`              | 49.5% (ingest 77%, sig 59%) | **49.6%** (ingest 77%, sig 60%) |
| `platformForkJoinThread-*`       |       26.5% (prehandle 89%) |       **27.1%** (prehandle 90%) |
| `<scheduler TransactionHandler>` |                       12.0% |                       **11.3%** |
| `VirtualHasherForkJoinThread-*`  |                        4.1% |                        **4.4%** |

### Handle thread (5,185 samples)

|                  Marker                  |             P8 |                                                       **P9** |
|------------------------------------------|---------------:|-------------------------------------------------------------:|
| `SavepointStack`                         |          27.0% |                                                    **27.5%** |
| `VirtualMap`                             |          25.5% |                                                    **28.1%** |
| `commitFullStack`                        |          19.1% |                                                    **20.4%** |
| CHM / `java.util.HashMap`                |    36.8 / 15.5 |                                              **24.0 / 14.2** |
| `FinalizeRecord`                         |          12.5% |                                                    **13.9%** |
| `RecordCacheImpl`                        |          24.0% |       **23.0%** (add 4.4%, purge 14.7%, `hasDuplicate` 1.2%) |
| `dropExpiredPayerBuckets`                |          14.7% | **11.8%** (0 `Traverser`; leaf `PayerTxnIndex.isEmpty` 8.1%) |
| `VirtualNodeCache`                       |          13.1% |                                                    **14.4%** |
| `ImmediateStateChangeListener`           |          20.9% |                                                    **20.1%** |
| `BlockStreamManager`                     |          22.2% |                                                    **24.9%** |
| `CryptoTransferHandler`                  |           9.9% |                                                     **8.7%** |
| `AdjustHbarChangesStep`                  |           6.4% |                                                     **5.8%** |
| `preHandleAllTransactions`               |           3.2% |                                                     **4.6%** |
| `WrappedState` / `WrappedWritableStates` |    22.3 / 20.6 |                                              **21.4 / 20.4** |
| `putLeaf`                                | 2.6% first-app |                          **11.7%** incl / **3.1%** first-app |
| `computeIfAbsent`                        |       (in CHM) |                                                     **1.1%** |
| wrap / stack `.<init>` / `newRootStack`  |      0 / 0 / 0 |                                                **0 / 0 / 0** |
| WRB / streams / leak                     |              0 |                                                        **0** |

CHM inclusive dropped **36.8% → 24.0%** with the traverser gone. `commitFullStack` / `VirtualMap` / `putLeaf` rose as a *share* because the expiry scan no longer crowds them — they did not get cheaper.

First app frames: `PayerTxnIndex.isEmpty` **8.1%**, `putLeaf` **3.1%**, `ConfigDataService.getConfigData` **2.9%**, `dropExpiredPayerBuckets` **2.0%**, `WrappedState.getWritableStates` **1.2%** (was 2.8%).

Hottest ingest locks (max ≈ STW): `hashCopy` 1.42 s, query/ingest throttle 1.22 s, `submit` 1.21 s.

## Allocation

Handle **92.5 GB / 378 s ≈ 245 MB/s** (P8 ~302). Wrap/stack mapping lambdas are gone. The leftover is throttle snapshots, per-dispatch store construction, and VM key bytes.

|                   Site                   |        P8 |      **P9** |
|------------------------------------------|----------:|------------:|
| `WrappedState.<init>` / wrap states ctor |         0 |       **0** |
| `DirectMethodHandle.allocateInstance`    |     18.8% |    **3.3%** |
| `WritableStatesStack$$Lambda`            |      3.7% |       **0** |
| `WrappedWritableStates$$Lambda`          |      3.3% |       **0** |
| `usageSnapshot()`                        |      3.1% |    **5.5%** |
| `WritableStoreFactory.getStore`          |      3.6% |    **4.8%** |
| `HashMap.resize` (handle)                |      2.1% |    **1.7%** |
| `PayerTxnIndex` / `HistorySource`        | 0.6 / 0.4 | **0.7 / —** |

Top handle classes: `byte[]` 5.6%, `Object[]` 5.5%, `ThrottleUsageSnapshot` **5.2%**, `LinkedHashMap` 4.2%, `WritableAccountStore` 2.9%. Remaining lambdas are VirtualMap / `VirtualNodeCache` (`$$Lambda` 1.1% + 0.9%), not wrap/stack `get`.

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
P9  P8 + list expiry + get-then-put
    (soft-commit reverted)       17,237  ← first move above P5
```

---

## Top 5 next (code)

### 1. Defer `putLeaf` without breaking originals / entity-id

**Why:** `commitFullStack` **20.4%**, `VirtualMap` **28.1%**, `putLeaf` **11.7%**. This is the TPS lever now that expiry and wrap `get` are out of the way.

**What not to do:** skip `MerkleWritableStates.commit()` and leave mods only in merkle buffers. P8 soft-commit made every `CryptoCreate` see genesis account 2 and allocate `1002` — `getOriginalValue` / entity-id / working-state readable still read the previous VM copy.

**What to do:** read-your-writes on the working `VirtualMapStateImpl` (readable and `getOriginalValue` see merkle mods as the committed baseline for the *next* txn, while wrap originals stay this-txn), or flush only the states finalize/entity-id need, or snapshot wrap originals at wrap reset. Listeners must still fire per user txn.

**What should move:** `putLeaf` / `commitInStateIdOrder` down on the handle thread; TPS if the flush left the serial path.

### 2. Throttle snapshots at round/block

**Why:** `ThrottleUsageSnapshot` is the top remaining handle class (**5.2%**). `usageSnapshot()` **5.5%** of handle alloc. Was hidden under the lambda tax.

### 3. Reuse `WritableStoreFactory` stores on the handle thread

**Why:** `getStore` **4.8%** of handle alloc; `WritableAccountStore` **2.9%**. It constructs a new store every call. Handle already reuses the stack adapters.

### 4. Batch receipt-queue / KV block items

**Why:** `commitReceipts` **17.4%**, `ImmediateStateChangeListener` **20.1%**, `BlockStreamManager` **24.9%**. These shares are no longer inflated by the CHM scan. Same work as P7.

### 5. Trim once-per-second record-cache purge

**Why:** `dropExpiredPayerBuckets` **11.8%** is the list + `PayerTxnIndex.isEmpty` (8.1% first-app), not a CHM walk. For NLG’s one genesis payer this should be ≪ 1%; either the compact loop is over-attributed, or `livePayerIndexes` is larger than expected. Do not put `removeIf` back. Optional: call `dropExpired` on the single live index and skip the compact when `n == 1`.

|              Metric               |         P8 |            P9 |            After (1) |
|-----------------------------------|-----------:|--------------:|---------------------:|
| NLG TPS                           |     16,986 |        17,237 | up if VM left handle |
| `RecordCacheImpl` inclusive       |      24.0% |     **23.0%** |            stay ~23% |
| `dropExpired` / `Traverser`       |  14.7 / 14 |  **11.8 / 0** |        11.8 / 0 stay |
| `allocateInstance` / wrap lambdas | 18.8 / 7.0 |   **3.3 / 0** |                 stay |
| `commitFullStack` / `VirtualMap`  |  19% / 26% | **20% / 28%** |       down on handle |
| Handle alloc rate                 |  ~302 MB/s | **~245 MB/s** |         ~P9 or below |
| Leak detector / WRB / wrap ctors  |  0 / 0 / 0 | **0 / 0 / 0** |               stay 0 |
