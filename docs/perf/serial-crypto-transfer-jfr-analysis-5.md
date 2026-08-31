# Serial CryptoTransfer JFR analysis (`node-profile-5.jfr`)

Follow-up to [`serial-crypto-transfer-jfr-analysis-4.md`](serial-crypto-transfer-jfr-analysis-4.md). Same NLG CryptoTransfer shape, **serial handle**. NLG: **16,922 TPS** (`5,076,679` transfers in 300 s) — **+29%** vs the 12g pin (13,103), **+2%** vs the unpinned 16g-max run (16,551).

Recording: 46 MB, JFR 2.1, **2026-08-29 23:59:54 UTC, 384 s**. PID 55081, Temurin 25.0.2, `:app:run -PnodeHeap=16g`. How it was read: `jfr summary` / `jfr view`, then `jfr print --stack-depth 64` on `jdk.ExecutionSample` (44,825 samples) and `--stack-depth 32` on `jdk.ObjectAllocationSample` (77,798 samples, weighted).

## Bottom line

**16g pinned is the first fair serial baseline.** The 12g pin was undersized. Leak detection off + `BLOCKS` + 16g pretouch recovered (and slightly beat) profile 3. The handle path did not change.

1. **Flags landed.** Heap min = max = initial = **16 GB**, `AlwaysPreTouch`, leak detection `DISABLED`. Zero `ResourceLeakDetector` throws.
2. **TPS 16,922 is real.** Same NLG 300 s window as P3/P4. The +371 vs 16,551 is small; treat it as “P3 plus no 1→16 grow,” not a new code win.
3. **G1 still does not fit comfortably.** Used heap hits **16.0 GB**, then drops to ~13 GB after old collections. STW **48.1 s / 384 s (12.5%)**, P99 **1.16 s**, `EvacuationFailed` **1,045**, `ConcurrentModeFailure` **29**. That is P3-like, not “young GC only.”
4. **Handle mix is stable across P3–P5:** `SavepointStack` ~29%, `VirtualMap` ~28%, `commitFullStack` ~18%, `RecordCacheImpl` ~14%, CT handler ~10%. WRB 0, streams 1%, pre-handle reuse ~4.5%.
5. **Stop changing the JVM for the next profile.** Further TPS on this machine is handle garbage and per-txn commit (items 2–5 from analysis 2/3). A 20g pin is optional if you want to see whether evacuation goes to zero; it is not required to start the code cuts.

```180:188:hedera-node/hedera-app/build.gradle.kts
val heap = providers.gradleProperty("nodeHeap").orElse("12g").get()
jvmArgs(
    "-Xms$heap",
    "-Xmx$heap",
    "-XX:+AlwaysPreTouch",
    "-Dio.netty.leakDetection.level=DISABLED",
)
```

Default is still `12g`. This run used `-PnodeHeap=16g`. Change the default to `16g` if that should be the usual `:app:run`.

## Recording shape

|              Item              |          P3 (1→16g) |      P4 (12g pin) |        **P5 (16g pin)** |
|--------------------------------|--------------------:|------------------:|------------------------:|
| NLG TPS (300 s)                |              16,551 |            13,103 |              **16,922** |
| Heap                           |             1→16 GB |          12=12 GB |  **16=16 GB, pretouch** |
| Heap used (end)                |            13–16 GB |    10.6–11.9 / 12 |          **13–16 / 16** |
| Duration                       |               396 s |             366 s |               **384 s** |
| Execution samples              |              44,519 |            35,876 |              **44,825** |
| Handle samples                 |               11.1% |              7.8% |                **8.5%** |
| Handle alloc                   |        144 GB / 42% |      118 GB / 41% |        **148 GB / 41%** |
| Handle alloc rate              |           ~364 MB/s |         ~322 MB/s |           **~384 MB/s** |
| GC pause total / % file        |      53.6 s / 13.5% |     119 s / 32.5% |      **48.1 s / 12.5%** |
| Pause P50 / P99 / max STW      | 22 / 1150 / 1210 ms | 18 / 819 / 918 ms | **24 / 1160 / 1250 ms** |
| `EvacuationFailed`             |               1,082 |             2,427 |               **1,045** |
| `ConcurrentModeFailure`        |                  33 |                95 |                  **29** |
| Old GC events                  |                 124 |               267 |                  **95** |
| JVM user avg / max             |       30.5% / 75.2% |     41.5% / 80.6% |       **30.7% / 77.5%** |
| Leak detector                  |                   0 |                 0 |                   **0** |
| `SubmissionManager.submit` max |              1.24 s |            7.12 s |              **1.19 s** |
| CHM `computeIfAbsent` max wait |              1.13 s |            850 ms |              **109 ms** |

P5 matches P3 on CPU and pause *shape*, without the grow-from-1GB tax and without P4’s 12g thrash. CHM waits collapsing to 109 ms is the pretouch/stable-heap effect: less Full-GC-aligned lock hold.

## Where CPU went

|           Thread group           |    P3 |    P4 |                          **P5** |
|----------------------------------|------:|------:|--------------------------------:|
| `grpc-nio-worker-*`              | 50.3% | 51.0% | **49.2%** (ingest 76%, sig 59%) |
| `platformForkJoinThread-*`       | 26.6% | 28.9% |       **29.6%** (prehandle 90%) |
| `<scheduler TransactionHandler>` | 11.1% |  7.8% |                        **8.5%** |
| `VirtualHasherForkJoinThread-*`  |  4.2% |  4.2% |                        **4.8%** |

Handle is still the TPS ceiling. 8.5% of samples is “other threads are busy + ~12% of wall is STW,” not a faster handler.

### Handle thread (3,799 samples)

|             Marker             |          P3 |          P4 |          **P5** |
|--------------------------------|------------:|------------:|----------------:|
| `SavepointStack`               |       29.5% |       32.8% |       **28.6%** |
| `VirtualMap`                   |       28.2% |       31.9% |       **28.0%** |
| `commitFullStack`              |       20.1% |       21.6% |       **18.0%** |
| CHM / `HashMap`                | 24.5 / 23.4 | 23.4 / 23.8 | **26.2 / 23.4** |
| `FinalizeRecord`               |       15.2% |       16.3% |       **15.9%** |
| `RecordCacheImpl`              |       13.3% |       10.5% |       **14.1%** |
| `VirtualNodeCache`             |       13.6% |       14.6% |       **14.1%** |
| `ImmediateStateChangeListener` |        8.1% |        7.9% |       **10.0%** |
| `CryptoTransferHandler`        |       10.5% |       11.2% |        **9.9%** |
| `AdjustHbarChangesStep`        |        6.8% |        7.3% |        **6.1%** |
| `preHandleAllTransactions`     |        4.8% |        4.7% |        **4.5%** |
| WRB / streams                  |    0 / 1.1% |    0 / 1.1% |    **0 / 1.0%** |

First app frames: `WritableStatesStack.getSingleton` 3.6%, `WrappedState.getWritableStates` 3.2%, `VirtualNodeCache.putLeaf` 3.2%. CT first frame is still `AdjustHbarChangesStep` (~5.2% combined).

Hottest ingest locks (max ≈ STW, not a new bug): `hashCopy` 1.35 s, query throttle 1.29 s, `submit` 1.19 s.

## Allocation

Handle **148 GB / 384 s ≈ 384 MB/s** — same order as P3. Top classes: `HashMap` + nodes **21.6%**, `Object[]` 3.6%, `OneOf` 3.0%, `ThrottleUsageSnapshot` **2.5%**. Sites: `DirectMethodHandle.allocateInstance` **13.5%**, `HashMap.resize` 7.7%, `HashMap.newNode` 6.7%, `usageSnapshot()` **2.3%**.

The intra-stack adapter cache is still in effect (`WritableKVStateStack` is not a top *class*). Each CT still allocates a new `SavepointStack` and per-service HashMaps. That plus `commitFullStack` → VirtualMap put is the remaining serial tax.

## JVM series so far

```
P1  BOTH + leak on + 1→16g     ~10k+ TPS, WRB + re-prehandle + streams
P2  BLOCKS + leak on + 1→16g    14,024  (GC 16% wall, cache looked huge)
P3  BLOCKS + leak off + 1→16g   16,551  (leak detector gone)
P4  BLOCKS + leak off + 12g pin 13,103  (heap too small)
P5  BLOCKS + leak off + 16g pin 16,922  ← baseline for code
```

---

## Top 5 next (code, not another JVM knob)

Same list as analysis 3, now with a clean baseline. Do these in order; take `node-profile-6.jfr` after the first one that should move handle alloc or `commitFullStack`.

### 1. Reuse one `SavepointStack` / store-factory for the user dispatch

**Why:** ~29% handle inclusive, ~21% handle alloc is HashMap resize/nodes, `getWritableStates` / `getSingleton` still first-app frames. Adapter cache only lives as long as a stack that dies every CT.

```337:341:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/stack/SavepointStackImpl.java
public WritableStates getWritableStates(@NonNull final String serviceName) {
    return writableStatesMap.computeIfAbsent(serviceName, s -> new WritableStatesStack(this, s));
}
```

**What should move:** handle alloc MB/s; `HashMap.resize`; `SavepointStack` inclusive.

### 2. Cut `commitFullStack` + VirtualMap put on the serial thread

**Why:** commit **18%**, `VirtualMap` **28%**, `VirtualNodeCache.putLeaf` still a top first-app frame.

```142:144:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/DispatchProcessor.java
dispatchUsageManager.finalizeAndSaveUsage(dispatch);
recordFinalizer.finalizeRecord(dispatch);
dispatch.stack().commitFullStack();
```

**What should move:** those inclusive %; young-GC frequency if leaf churn drops.

### 3. Cheap record-cache index

**Why:** `RecordCacheImpl` **14%** (add 4.9%, purge 5.1%, `hasDuplicate` 1.2%). NLG’s single payer `HashSet` is unchanged.

### 4. Throttle snapshots at round/block

**Why:** `ThrottleUsageSnapshot` **2.5%** of handle alloc; `usageSnapshot()` **2.3%** site. Same `screenForCapacity` coupling as before.

### 5. Batch receipt-queue / KV block items

**Why:** `ImmediateStateChangeListener` **10%**, `BlockStreamManager` **11.8%**, `addItem` still allocates a hashing task per item.

Optional JVM-only check: `-PnodeHeap=20g` if this machine has the RAM, to see whether `EvacuationFailed` goes to 0. Do not block (1)–(5) on that.

|                Metric                | P5 baseline |         After first code cut          |
|--------------------------------------|------------:|---------------------------------------|
| NLG TPS                              |      16,922 | up, or same TPS at lower handle alloc |
| `SavepointStack` / `commitFullStack` |   29% / 18% | down                                  |
| Handle alloc rate                    |   ~384 MB/s | down                                  |
| GC pause %                           |       12.5% | down if alloc drops                   |
| Leak detector / WRB                  |       0 / 0 | stay 0                                |
