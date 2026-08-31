# Serial CryptoTransfer JFR analysis (`node-profile-6.jfr`)

Follow-up to [`serial-crypto-transfer-jfr-analysis-5.md`](serial-crypto-transfer-jfr-analysis-5.md). Same NLG CryptoTransfer shape, **serial handle**, after root-`SavepointStack` reuse and the `:app:run` **16g** default. NLG: **16,511 TPS** (`4,953,661` transfers in 300 s) — **−2.4%** vs P5 (16,922), **−0.2%** vs P3 (16,551).

Recording: 45 MB, JFR 2.1, **2026-08-30 00:18:38 UTC, 435 s**. PID 60313, Temurin 25.0.2, `:app:run` (default 16g pin, no `-PnodeHeap`). How it was read: `jfr summary` / `jfr view`, then `jfr print --stack-depth 64` on `jdk.ExecutionSample` (45,892 samples) and `--stack-depth 32` on `jdk.ObjectAllocationSample` (82,094 samples, weighted). The empty 0-byte file at start was an in-progress recording (`ct-serial-6`); the dump landed on JVM shutdown.

## Bottom line

**Reuse landed and is on the handle path. It cut handle garbage. It did not move TPS.** `newRootStack` / `SavepointStackImpl.<init>` are **0** handle samples; `resetForNextUserTxn` is **1.3%**. Handle alloc fell **148 GB → 120 GB** and **~384 MB/s → ~282 MB/s**. `HashMap.resize` is no longer a top-of-file alloc site (7.7% → 2.7% of all alloc). SavepointStack inclusive is still **~28%** because each CT still builds a new `WrappedState` / `WrappedWritableStates` graph.

1. **Reuse is real.** Zero ctor samples for a new root stack. `WritableKVStateStack` stays in the inclusive mix (19%) because the cached adapters are actually used.
2. **TPS 16,511 is P3-like, not a regression to chase.** Same 300 s NLG window. The −411 vs P5 is inside the P3–P5 noise band. Treat P6 as “P5 alloc, P3 TPS.”
3. **The leftover HashMap tax is the wrap, not the stack maps.** Handle first-app frames: `WrappedState.getWritableStates` 2.7%, `WrappedWritableStates.get` 2.6%. Handle alloc sites still include `HashMap.resize` **4.9%** and `ReadableKVStateBase.<init>` **2.9%**.
4. **G1 is unchanged.** Used heap still hits **16.0 GB**. STW **51.0 s / 435 s (11.7%)**, P99 **1.24 s**, `EvacuationFailed` **907**, `ConcurrentModeFailure` **31**.
5. **Next cut is `WrappedState` reuse-or-reset, then `commitFullStack`.** Do not take another JVM-only profile.

```180:188:hedera-node/hedera-app/build.gradle.kts
val heap = providers.gradleProperty("nodeHeap").orElse("16g").get()
jvmArgs(
    "-Xms$heap",
    "-Xmx$heap",
    "-XX:+AlwaysPreTouch",
    "-Dio.netty.leakDetection.level=DISABLED",
)
```

```226:254:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/stack/SavepointStackImpl.java
public boolean resetForNextUserTxn(...) {
    ...
    stack.clear();
    builderSink.reset();
    ...
    setupFirstSavepoint(USER);
    baseBuilder = createRootBaseBuilder(this.maxSerializedTraceDataBytes);
    return true;
}
```

## Recording shape

|              Item              |        P5 (16g pin) |      **P6 (16g + stack reuse)** |
|--------------------------------|--------------------:|--------------------------------:|
| NLG TPS (300 s)                |              16,922 |                      **16,511** |
| Heap                           |  16=16 GB, pretouch | **16=16 GB, pretouch, default** |
| Heap used (end)                |          13–16 / 16 |                **14.8–16 / 16** |
| Duration                       |               384 s |                       **435 s** |
| Execution samples              |              44,825 |                      **45,892** |
| Handle samples                 |                8.5% |                       **10.7%** |
| Handle alloc                   |        148 GB / 41% |                **120 GB / 38%** |
| Handle alloc rate              |           ~384 MB/s |                   **~282 MB/s** |
| GC pause total / % file        |      48.1 s / 12.5% |              **51.0 s / 11.7%** |
| Pause P50 / P99 / max STW      | 24 / 1160 / 1250 ms |         **25 / 1240 / 1660 ms** |
| `EvacuationFailed`             |               1,045 |                         **907** |
| `ConcurrentModeFailure`        |                  29 |                          **31** |
| Old GC events                  |                  95 |                          **97** |
| JVM user avg / max             |       30.7% / 77.5% |               **26.4% / 76.5%** |
| Leak detector                  |                   0 |                           **0** |
| `SubmissionManager.submit` max |              1.19 s |                      **1.17 s** |
| CHM `computeIfAbsent` max wait |              109 ms |                      **1.32 s** |

CHM max wait returning to ~1.3 s is STW-aligned again (P99 pause 1.24 s), not a new lock. The file is 51 s longer than P5 (startup + shutdown after NLG at 20:24:49), so MB/s is a lower bound on the loaded rate; the **absolute** handle alloc drop (148 → 120 GB) is the cleaner reuse signal.

## Where CPU went

|           Thread group           |                          P5 |                          **P6** |
|----------------------------------|----------------------------:|--------------------------------:|
| `grpc-nio-worker-*`              | 49.2% (ingest 76%, sig 59%) | **50.8%** (ingest 76%, sig 58%) |
| `platformForkJoinThread-*`       |       29.6% (prehandle 90%) |       **26.2%** (prehandle 87%) |
| `<scheduler TransactionHandler>` |                        8.5% |                       **10.7%** |
| `VirtualHasherForkJoinThread-*`  |                        4.8% |                        **4.3%** |

Handle is still the TPS ceiling. 10.7% of samples with a quieter machine average (26% JVM user) is “handler did not get faster.”

### Handle thread (4,922 samples)

|                  Marker                  |          P5 |                                                **P6** |
|------------------------------------------|------------:|------------------------------------------------------:|
| `SavepointStack`                         |       28.6% |                                             **27.5%** |
| `VirtualMap`                             |       28.0% |                                             **28.6%** |
| `commitFullStack`                        |       18.0% |                                             **19.5%** |
| CHM / `java.util.HashMap`                | 26.2 / 23.4 |                                       **26.5 / 18.8** |
| `FinalizeRecord`                         |       15.9% |                                             **15.1%** |
| `RecordCacheImpl`                        |       14.1% | **15.5%** (add 5.7%, purge 5.5%, `hasDuplicate` 1.9%) |
| `VirtualNodeCache`                       |       14.1% |                                             **14.0%** |
| `ImmediateStateChangeListener`           |       10.0% |                                             **10.0%** |
| `CryptoTransferHandler`                  |        9.9% |                                             **10.6%** |
| `AdjustHbarChangesStep`                  |        6.1% |                                              **7.1%** |
| `preHandleAllTransactions`               |        4.5% |                                              **4.5%** |
| `WrappedState` / `WrappedWritableStates` |  (in stack) |                                       **22.0 / 22.3** |
| `resetForNextUserTxn` / `newRootStack`   |         n/a |                                          **1.3% / 0** |
| WRB / streams                            |    0 / 1.0% |                                             **0 / 0** |

P5’s “HashMap 23.4%” was a looser substring. P6 `java.util.HashMap` **18.8%** plus `HashMap.resize` **0.5%** is the reuse effect on the stack maps. Inclusive `SavepointStack` barely moved because `setupFirstSavepoint` still allocates a `WrappedState` every CT.

First app frames: `RecordCacheImpl.addRecordSource` 4.4%, `WrappedState.getWritableStates` 2.7%, `WrappedWritableStates.get` 2.6%, `putLeaf` 2.5%, `WritableStatesStack.getSingleton` 2.3% (was 3.6% in P5). CT first frame is still `AdjustHbarChangesStep`.

Hottest ingest locks (max ≈ STW): `hashCopy` 1.56 s, query/ingest throttle 1.32 s, `submit` 1.17 s.

## Allocation

Handle **120 GB / 435 s ≈ 282 MB/s** — down vs P5’s ~384 MB/s. Top handle classes: `Object[]` 5.5%, `HashMap` 5.3%, `HashMap$Node[]` 4.9%, `ThrottleUsageSnapshot` **3.3%**. Sites: `DirectMethodHandle.allocateInstance` **14.8%**, `HashMap.resize` **4.9%**, `usageSnapshot()` **3.1%**, `ReadableKVStateBase.<init>` **2.9%**, `WritableStoreFactory.getStore` **2.5%**, `WrappedWritableStates.<init>` 1.2%, `createRootBaseBuilder` 1.4%.

File-wide `HashMap.resize` **2.66%** (was 7.7%). The intra-stack adapter cache now **survives across CTs**. The maps that still resize are the per-txn wrap (`WrappedState` / `WrappedWritableStates` / `ReadableKVStateBase`).

## JVM series so far

```
P1  BOTH + leak on + 1→16g      ~10k+ TPS, WRB + re-prehandle + streams
P2  BLOCKS + leak on + 1→16g     14,024
P3  BLOCKS + leak off + 1→16g    16,551
P4  BLOCKS + leak off + 12g pin  13,103
P5  BLOCKS + leak off + 16g pin  16,922  ← JVM baseline
P6  P5 + SavepointStack reuse    16,511  ← alloc win, TPS flat
```

---

## Top 5 next (code)

P5 item 1 is done. Same remaining list, now with a measured leftover.

### 1. Reuse or reset `WrappedState` / `WrappedWritableStates` across user txns

**Why:** Handle inclusive `WrappedState` **22%**, `WrappedWritableStates` **22%**. First-app frames are `getWritableStates` / `get`. Handle alloc still has `HashMap.resize` 4.9% and `ReadableKVStateBase.<init>` 2.9%. `resetForNextUserTxn` already does `setupFirstSavepoint(USER)` → `new WrappedState(state)` every CT.

**What should move:** handle alloc MB/s; remaining `HashMap.resize`; `SavepointStack` / `WrappedState` inclusive.

### 2. Cut `commitFullStack` + VirtualMap put on the serial thread

**Why:** commit **19.5%**, `VirtualMap` **28.6%**, `putLeaf` still a top first-app frame.

```142:144:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/DispatchProcessor.java
dispatchUsageManager.finalizeAndSaveUsage(dispatch);
recordFinalizer.finalizeRecord(dispatch);
dispatch.stack().commitFullStack();
```

**What should move:** those inclusive %; young-GC frequency if leaf churn drops.

### 3. Cheap record-cache index

**Why:** `RecordCacheImpl` **15.5%** (add 5.7%, purge 5.5%, `hasDuplicate` 1.9%). `addRecordSource` is now the hottest first-app frame (4.4%). NLG’s single-payer `HashSet` is unchanged.

### 4. Throttle snapshots at round/block

**Why:** `ThrottleUsageSnapshot` **3.3%** of handle alloc; `usageSnapshot()` **3.1%** site; `saveThrottleSnapshotsTo` 2.3% inclusive; `screenForCapacity` 5.1%. Same coupling as before.

### 5. Batch receipt-queue / KV block items

**Why:** `ImmediateStateChangeListener` **10%**, `BlockStreamManager` **11.4%**, `addItem` 2.6% first-app / 1.4% handle alloc.

Optional JVM-only check: `-PnodeHeap=20g` if this machine has the RAM. Do not block (1)–(5) on that.

|                Metric                |          P5 |              P6 |           After wrap reuse            |
|--------------------------------------|------------:|----------------:|---------------------------------------|
| NLG TPS                              |      16,922 |          16,511 | up, or same TPS at lower handle alloc |
| `SavepointStack` / `commitFullStack` |   29% / 18% |       28% / 20% | `WrappedState` down                   |
| Handle alloc rate                    |   ~384 MB/s |       ~282 MB/s | down again                            |
| `HashMap.resize` (all / handle)      | 7.7% / high | **2.7% / 4.9%** | down                                  |
| GC pause %                           |       12.5% |           11.7% | down if alloc drops                   |
| Leak detector / WRB / `newRootStack` | 0 / 0 / n/a |   **0 / 0 / 0** | stay 0                                |
