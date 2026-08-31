# Serial CryptoTransfer JFR analysis (`node-profile-7.jfr`)

Follow-up to [`serial-crypto-transfer-jfr-analysis-6.md`](serial-crypto-transfer-jfr-analysis-6.md). Same NLG CryptoTransfer shape, **serial handle**, after root-`WrappedState` reuse (`resetForDelegate` + SPI `retarget`). NLG: **16,767 TPS** (`5,030,325` transfers in 300 s) — **+1.6%** vs P6 (16,511), **−0.9%** vs P5 (16,922).

Recording: 43 MB, JFR 2.1, **2026-08-30 00:35:46 UTC, 400 s**. PID 68373, Temurin 25.0.2, `:app:run` (default 16g pin). How it was read: `jfr summary` / `jfr view`, then `jfr print --stack-depth 64` on `jdk.ExecutionSample` (45,252 events; 45,322 parsed) and `--stack-depth 32` on `jdk.ObjectAllocationSample` (80,024 events; 81,294 parsed, weighted). The empty 0-byte file at start was an in-progress recording (`ct-serial-7`); dump was taken while the JVM was still running (no `jdk.Shutdown`).

## Bottom line

**Wrap reuse landed. Construction tax is gone. Alloc rate and TPS did not move.** `WrappedState.<init>` / `WrappedWritableStates.<init>` / `newRootStack` are **0** handle samples. `ReadableKVStateBase.<init>` fell **2.9% → 0.2%** of handle alloc. `HashMap.resize` on the handle thread fell **4.9% → 2.0%**. Handle alloc stayed **~283 MB/s** (P6 ~282). TPS is inside the P5–P6 noise band.

The leftover wrap cost is no longer “new HashMaps.” It is **`computeIfAbsent` lambdas on cache hits**. Handle `DirectMethodHandle.allocateInstance` is **19.2%** of handle alloc; **~71%** of those samples are `WrappedState.getWritableStates`, `WrappedWritableStates.get`, `WritableStatesStack.get` / `getSingleton`, and `ReadonlyStatesWrapper.get*`.

1. **Reuse is on the path.** `resetForDelegate` / `rootWrap` **3.0%**, `retarget` **2.1%**, `ReadableKVStateBase.reset` **1.7%** first-app. Zero wrap ctors.
2. **TPS 16,767 is P5-like.** +256 vs P6, −155 vs P5. Same 300 s NLG window. Do not chase it.
3. **Handle alloc is a wash.** Construction dropped; lambda + throttle + store-factory + stream-builder alloc replaced it. `ThrottleUsageSnapshot` is now the top handle class (**5.2%**).
4. **G1 is unchanged.** Used heap still hits **16.0 GB**. STW **53.5 s / 400 s (13.4%)**, P99 **1.25 s**, `EvacuationFailed` **1,006**, `ConcurrentModeFailure` **30**.
5. **Next cheap cut is get-then-put on the cached adapters. Next TPS cut is still `commitFullStack` / VirtualMap.**

```42:53:hedera-node/hedera-app/src/main/java/com/hedera/node/app/state/WrappedState.java
public void resetForDelegate(@NonNull final State newDelegate) {
    requireNonNull(newDelegate, "delegate must not be null");
    if (this.delegate != newDelegate) {
        this.delegate = newDelegate;
        writableStatesMap.clear();
        readableStatesMap.clear();
        return;
    }
    for (final var entry : writableStatesMap.entrySet()) {
        entry.getValue().retarget(this.delegate.getWritableStates(entry.getKey()));
    }
}
```

```93:96:hedera-node/hedera-app/src/main/java/com/hedera/node/app/state/WrappedState.java
public WritableStates getWritableStates(@NonNull String serviceName) {
    return writableStatesMap.computeIfAbsent(
            serviceName, s -> new WrappedWritableStates(delegate.getWritableStates(s)));
}
```

The lambda is allocated **before** `computeIfAbsent` runs, including on a hit.

## Recording shape

|              Item              |    P6 (stack reuse) |     **P7 (wrap reuse)** |
|--------------------------------|--------------------:|------------------------:|
| NLG TPS (300 s)                |              16,511 |              **16,767** |
| Heap                           |  16=16 GB, pretouch |  **16=16 GB, pretouch** |
| Heap used (end)                |        14.8–16 / 16 |        **14.5–16 / 16** |
| Duration                       |               435 s |               **400 s** |
| Execution samples              |              45,892 |              **45,322** |
| Handle samples                 |               10.7% |                **8.8%** |
| Handle alloc                   |        120 GB / 38% |      **113 GB / 34.5%** |
| Handle alloc rate              |           ~282 MB/s |           **~283 MB/s** |
| GC pause total / % file        |      51.0 s / 11.7% |      **53.5 s / 13.4%** |
| Pause P50 / P99 / max STW      | 25 / 1240 / 1660 ms | **25 / 1250 / 1270 ms** |
| `EvacuationFailed`             |                 907 |               **1,006** |
| `ConcurrentModeFailure`        |                  31 |                  **30** |
| Old GC events                  |                  97 |                  **97** |
| JVM user avg / max             |       26.4% / 76.5% |       **29.3% / 73.0%** |
| Leak detector                  |                   0 |                   **0** |
| `SubmissionManager.submit` max |              1.17 s |              **1.27 s** |
| CHM `computeIfAbsent` max wait |              1.32 s |              **1.17 s** |

P7 is a live dump (no shutdown chunk). Pause % looks worse than P6 in part because the file is less padded with idle start/stop. Max STW is actually tighter (1.27 s vs 1.66 s).

## Where CPU went

|           Thread group           |                          P6 |                          **P7** |
|----------------------------------|----------------------------:|--------------------------------:|
| `grpc-nio-worker-*`              | 50.8% (ingest 76%, sig 58%) | **49.8%** (ingest 76%, sig 58%) |
| `platformForkJoinThread-*`       |       26.2% (prehandle 87%) |       **29.1%** (prehandle 89%) |
| `<scheduler TransactionHandler>` |                       10.7% |                        **8.8%** |
| `VirtualHasherForkJoinThread-*`  |                        4.3% |                        **4.3%** |

Handle is still the TPS ceiling. A smaller handle share with the same TPS means the handler did not get faster.

### Handle thread (3,998 samples)

|                  Marker                  |          P6 |                                                **P7** |
|------------------------------------------|------------:|------------------------------------------------------:|
| `SavepointStack`                         |       27.5% |                                             **30.0%** |
| `VirtualMap`                             |       28.6% |                                             **29.3%** |
| `commitFullStack`                        |       19.5% |                                             **21.2%** |
| CHM / `java.util.HashMap`                | 26.5 / 18.8 |                                       **27.9 / 20.0** |
| `FinalizeRecord`                         |       15.1% |                                             **14.3%** |
| `RecordCacheImpl`                        |       15.5% | **16.2%** (add 6.5%, purge 5.7%, `hasDuplicate` 1.7%) |
| `VirtualNodeCache`                       |       14.0% |                                             **14.2%** |
| `ImmediateStateChangeListener`           |       10.0% |                                             **11.4%** |
| `CryptoTransferHandler`                  |       10.6% |                                             **10.6%** |
| `AdjustHbarChangesStep`                  |        7.1% |                                              **6.7%** |
| `preHandleAllTransactions`               |        4.5% |                                              **3.4%** |
| `WrappedState` / `WrappedWritableStates` | 22.0 / 22.3 |                                       **24.6 / 22.3** |
| `resetForNextUserTxn` / `newRootStack`   |     1.3 / 0 |                                          **2.5% / 0** |
| `resetForDelegate` / `rootWrap`          |         n/a |                                        **3.0% / 3.0** |
| wrap / stack `.<init>`                   |        high |                                             **0 / 0** |
| WRB / streams                            |       0 / 0 |                                             **0 / 0** |

Inclusive `WrappedState` did **not** fall. Those frames are now `getWritableStates` / `retarget` / `reset` on the reused object, not construction.

First app frames: `RecordCacheImpl.addRecordSource` 5.2%, `WritableStatesStack.getSingleton` 3.2%, `putLeaf` / `updateKeyAtPath` ~2.8%, `WrappedState.getWritableStates` 2.8%, `WritableStatesStack.get` 2.3%, `ReadableKVStateBase.reset` 1.7%.

Hottest ingest locks (max ≈ STW): `hashCopy` 1.35 s, `submit` 1.27 s, CHM 1.17 s.

## Allocation

Handle **113 GB / 400 s ≈ 283 MB/s** — flat vs P6. Construction sites that were supposed to move did move:

|                 Site                  |        P6 |        **P7** |
|---------------------------------------|----------:|--------------:|
| `WrappedState.<init>`                 |       yes |         **0** |
| `WrappedWritableStates.<init>`        |      1.2% |         **0** |
| `ReadableKVStateBase.<init>`          |      2.9% |      **0.2%** |
| `HashMap.resize` (handle / all)       | 4.9 / 2.7 | **2.0 / 1.4** |
| `DirectMethodHandle.allocateInstance` |     14.8% |     **19.2%** |
| `usageSnapshot()`                     |      3.1% |      **5.6%** |
| `WritableStoreFactory.getStore`       |      2.5% |      **3.6%** |
| `createRootBaseBuilder`               |      1.4% |      **1.9%** |

Top handle classes: `ThrottleUsageSnapshot` **5.2%**, `Object[]` 5.1%, `byte[]` 3.7%, `WritableStatesStack$$Lambda` 3.6%, `WrappedState$$Lambda` 3.4%, `WrappedWritableStates$$Lambda` 3.3%, `ArrayList` 3.3%, `Account$Builder` 3.0%. `HashMap` itself is **2.1%** (P6 5.3%).

`DirectMethodHandle.allocateInstance` on the handle thread (989 samples) is almost all capturing lambdas:

|                 Caller                 | Count |
|----------------------------------------|------:|
| `WrappedState.getWritableStates`       |   175 |
| `WrappedWritableStates.get`            |   139 |
| `WritableStatesStack.get`              |   111 |
| `ReadonlyStatesWrapper.getSingleton`   |    88 |
| `WritableStatesStack.getSingleton`     |    73 |
| `WrappedWritableStates.getSingleton`   |    57 |
| `SavepointStackImpl.getReadableStates` |    56 |

## JVM series so far

```
P1  BOTH + leak on + 1→16g      ~10k+ TPS, WRB + re-prehandle + streams
P2  BLOCKS + leak on + 1→16g     14,024
P3  BLOCKS + leak off + 1→16g    16,551
P4  BLOCKS + leak off + 12g pin  13,103
P5  BLOCKS + leak off + 16g pin  16,922  ← JVM baseline
P6  P5 + SavepointStack reuse    16,511  ← alloc win, TPS flat
P7  P6 + WrappedState reuse      16,767  ← ctor gone, alloc rate flat
```

---

## Top 5 next (code)

P6 item 1 is done. The wrap leftover is now a one-line cache-hit pattern, not another object graph.

### 1. `get` then `put` on wrap / stack adapter caches

**Why:** Java allocates the `computeIfAbsent` lambda at the call site even when the key is present. After reuse those maps are always hot. That is **~10%** of handle alloc (`*$$Lambda` classes + most of `DirectMethodHandle.allocateInstance`) and the `getWritableStates` / `get` first-app frames.

Same pattern in `WrappedState`, `WrappedWritableStates`, `WritableStatesStack`, `ReadonlyStatesWrapper`, `SavepointStackImpl.getReadableStates` / `getWritableStates`.

**What should move:** handle `DirectMethodHandle.allocateInstance`; `WrappedState$$Lambda` / `WrappedWritableStates$$Lambda` / `WritableStatesStack$$Lambda`; first-app `get*` frames. Expect a modest MB/s drop, not a TPS jump.

### 2. Cut `commitFullStack` + VirtualMap put on the serial thread

**Why:** commit **21.2%**, `VirtualMap` **29.3%**, `putLeaf` still a top first-app frame. This is still the TPS ceiling.

```142:144:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/DispatchProcessor.java
dispatchUsageManager.finalizeAndSaveUsage(dispatch);
recordFinalizer.finalizeRecord(dispatch);
dispatch.stack().commitFullStack();
```

**What should move:** those inclusive %; young-GC frequency if leaf churn drops.

### 3. Cheap record-cache index

**Why:** `RecordCacheImpl` **16.2%** (add 6.5%, purge 5.7%, `hasDuplicate` 1.7%). `addRecordSource` is still the hottest first-app frame (5.2%).

### 4. Throttle snapshots at round/block

**Why:** `ThrottleUsageSnapshot` is now the **#1 handle alloc class (5.2%)**; `usageSnapshot()` **5.6%** site; `saveThrottleSnapshotsTo` 2.3% inclusive; `screenForCapacity` 5.3%. Same `screenForCapacity` coupling as before.

### 5. Batch receipt-queue / KV block items

**Why:** `ImmediateStateChangeListener` **11.4%**, `BlockStreamManager` **11.4%**, `addItem` 3.1% handle alloc.

Do not take another JVM-only profile. Optional `-PnodeHeap=20g` is still optional.

|                Metric                 |        P6 |            P7 |       After get-then-put       |
|---------------------------------------|----------:|--------------:|--------------------------------|
| NLG TPS                               |    16,511 |        16,767 | same TPS at lower handle alloc |
| wrap / stack `.<init>`                |      high |         **0** | stay 0                         |
| Handle alloc rate                     | ~282 MB/s |     ~283 MB/s | down (lambda tax)              |
| `HashMap.resize` (handle)             |      4.9% |      **2.0%** | stay low                       |
| `DirectMethodHandle.allocateInstance` |     14.8% |     **19.2%** | down                           |
| `commitFullStack` / `VirtualMap`      | 20% / 29% |     21% / 29% | unchanged until (2)            |
| Leak detector / WRB / `newRootStack`  | 0 / 0 / 0 | **0 / 0 / 0** | stay 0                         |
