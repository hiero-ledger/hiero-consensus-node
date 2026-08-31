# Serial CryptoTransfer JFR analysis (`node-profile-3.jfr`)

Follow-up to [`serial-crypto-transfer-jfr-analysis-2.md`](serial-crypto-transfer-jfr-analysis-2.md). Same NLG CryptoTransfer shape, **serial handle**. NLG reported **16,551 TPS** (`4,965,586` transfers in 300 s) — about **+18%** vs profile 2’s 14,024.

Recording: 52 MB, JFR 2.1, **2026-08-29 23:17:42 UTC, 396 s**. PID 48913, Temurin 25.0.2, started via `:app:run` after the leak-detection `jvmArgs` change. Dump includes JVM shutdown. How it was read: `jfr summary` / `jfr view`, then `jfr print --stack-depth 64` on `jdk.ExecutionSample` (44,519 samples) and `--stack-depth 32` on `jdk.ObjectAllocationSample` (79,319 samples, weighted).

## Bottom line

**Leak detection off worked. Heap pin did not land. Handle work shifted from record-cache stalls to commit + stack + VirtualMap.**

1. **`io.netty.leakDetection.level=DISABLED` is on this JVM.** `jdk.JavaExceptionThrow` fell from **84,781 → 1,348**. Zero `ResourceLeakDetector$TraceRecord`. gRPC workers spend that time on ingest/signatures instead (`IngestWorkflow` 76% of gRPC samples, was 69%).
2. **Heap is still 1 GB → 16 GB.** No `-Xms`/`-Xmx`/`AlwaysPreTouch`. STW pause total **53.6 s** (13.5% of the file), P99 **1.15 s**, max **1.21 s**. Better than profile 2’s 67 s / 3.17 s / 7.9 s old, **not** the “young GC only” target. `EvacuationFailed` **1,082** (worse). Heap used ~13–16 GB at the end.
3. **`RecordCacheImpl` is no longer the #1 handle slice** (31% → **13.3%**). `hasDuplicate` collapsed (9.5% → 1.3%). Profile 2’s cache numbers were inflated by multi-second GC/CHM stalls.
4. **The serial limiter is now commit + savepoint + VirtualMap:** `commitFullStack` **20.1%**, `SavepointStack` **29.5%**, `VirtualMap` **28.2%**, `HashMap` **23.4%**.
5. Handle is a **smaller** fraction of Java CPU (11.1% vs 15.8%) because gRPC/prehandle got cheaper. That matches the **16,551 TPS** NLG result: ingest/sig were no longer paying `TraceRecord` stack walks, so more transfers reached the serial thread. The handle path itself is still commit + stack + VirtualMap, still allocating ~**364 MB/s**, still pausing **13.5%** of wall in G1. This gain is the leak-detector half of item 1 — not evidence that commit/stack work got cheaper.

## What this JVM actually ran

|         Flag / setting         |                 Profile 3                 |               Asked for               |
|--------------------------------|-------------------------------------------|---------------------------------------|
| `io.netty.leakDetection.level` | **`DISABLED`** (`:app:run` `jvmArgs`)     | `DISABLED`                            |
| Heap                           | min 8 MB, initial **1 GB**, max **16 GB** | `-Xms12g -Xmx12g -XX:+AlwaysPreTouch` |
| `blockStream.streamMode`       | `BLOCKS`                                  | `BLOCKS`                              |

```178:183:hedera-node/hedera-app/build.gradle.kts
    mainModule = "com.hedera.node.app"

    // SIMPLE leak detection stack-walks a sample of every gRPC ByteBuf; under NLG that
    // is tens of thousands of Throwable constructions per minute. Local :app:run is not
    // a leak hunt.
    jvmArgs("-Dio.netty.leakDetection.level=DISABLED")
```

```17:17:hedera-node/configuration/dev/application.properties
blockStream.streamMode=BLOCKS
```

Serial handle: **zero** `ParallelRoundExecutor` / `OverlayApplier` / `SpeculativeState`. WRB: **zero**.

## Recording shape vs prior profiles

|                        Item                         |                       P1 |                     P2 |                       **P3** |
|-----------------------------------------------------|-------------------------:|-----------------------:|-----------------------------:|
| Duration                                            |                    450 s |                  410 s |                    **396 s** |
| Execution samples                                   |                   42,943 |                 53,985 |                   **44,519** |
| Handle samples                                      |            6,440 (15.0%) |          8,535 (15.8%) |            **4,931 (11.1%)** |
| Alloc weight                                        |                  ~393 GB |                ~303 GB |                  **~345 GB** |
| Handle alloc                                        |           183 GB (46.6%) |         125 GB (41.4%) |           **144 GB (41.8%)** |
| Handle alloc rate                                   |                ~400 MB/s |              ~306 MB/s |                **~364 MB/s** |
| GC pause total / P99 / max STW                      | 33.8 s / 1.17 s / 1.24 s | 67 s / 3.17 s / 3.56 s | **53.6 s / 1.15 s / 1.21 s** |
| Pause as % of file                                  |                     7.5% |                  16.3% |                    **13.5%** |
| `EvacuationFailed`                                  |                    1,257 |                    650 |                    **1,082** |
| `ConcurrentModeFailure`                             |                       18 |                     14 |                       **33** |
| Old GC events (duration field, includes concurrent) |                        — |         77, max 7.90 s |    **124**, several **~4 s** |
| Heap                                                |                  1→16 GB |                1→16 GB |                  **1→16 GB** |
| JVM user CPU avg / max                              |            23.7% / 71.8% |          20.7% / 44.6% |            **30.5% / 75.2%** |
| JVM system CPU avg / max                            |                        — |          11.4% / 28.4% |             **6.8% / 19.5%** |
| `ResourceLeakDetector`                              |                     ~98k |                 84,781 |                        **0** |
| `JavaExceptionThrow`                                |                     ~98k |                 84,940 |                    **1,348** |
| Native `Deflater`                                   |                    ~1.2% |                    131 |                      **154** |
| Reported TPS                                        |                     10k+ |                 14,024 |      **16,551** (NLG, 300 s) |

Old-collection *event* duration (~4 s) is not the same as STW. `jfr view gc-pauses` is the safepoint number (P99 1.15 s). Profile 2’s 7.9 s mixed a long concurrent old GC with a 3.5 s pause. P3’s STW is back to P1-like, but there are **more** evacuations and concurrent-mode failures, and the heap still grows into the 16 GB ceiling.

## Where CPU went

### Whole JVM

|           Thread group           |    P2 |    **P3** |          What changed           |
|----------------------------------|------:|----------:|---------------------------------|
| `grpc-nio-worker-*`              | 45.6% | **50.3%** | Leak detector gone → ingest/sig |
| `platformForkJoinThread-*`       | 20.8% | **26.6%** | PreHandle **87.4%** of FJP      |
| `<scheduler TransactionHandler>` | 15.8% | **11.1%** | Still the TPS ceiling           |
| `VirtualHasherForkJoinThread-*`  |  8.4% |  **4.2%** |                                 |
| MerkleDb / other                 |  rest |      rest |                                 |

gRPC: **76.0%** `IngestWorkflow` (was 68.9%), **58.1%** `Signature` (was 48.5%). Those threads were paying `fillInStackTrace` on `TraceRecord`; they now verify signatures. JVM user **30.5%** vs 20.7% is the same story.

Hottest monitors (max still tracks STW, not a new ingest bug):

|                          Site                          | Count |     Avg |        Max |
|--------------------------------------------------------|------:|--------:|-----------:|
| `SubmissionManager.submit`                             |   732 | 76.3 ms | **1.24 s** |
| `SynchronizedThrottleAccumulator.shouldThrottle` (txn) |   133 |  124 ms |     1.24 s |
| `ConcurrentHashMap.computeIfAbsent`                    |   561 |  136 ms |     1.13 s |
| `VirtualPipeline.hashCopy`                             |    48 | 98.9 ms |     1.24 s |

P2 max submit wait was **3.48 s**. The CHM `computeIfAbsent` row (561 events) is record-cache / VirtualNodeCache under GC, not a new lock.

Remaining exceptions are real control flow, not leak sampling: `PreCheckException` **568** at `SubmissionManager.submit` (ingest reject / busy), `UnsupportedOperationException` 188 in `TransactionDispatcher.getHandler`, file/socket noise.

### Handle thread (4,931 samples = 100%)

|                        Frame / marker                         |                      P1 |               P2 |              **P3** |
|---------------------------------------------------------------|------------------------:|-----------------:|--------------------:|
| `handlePlatformTransaction`                                   |                   83.4% |            88.4% |           **92.3%** |
| `executeSubmittedParent`                                      |                   63.5% |            76.1% |           **76.8%** |
| `DispatchProcessor.processDispatch`                           |                   45.1% |            56.4% |           **60.7%** |
| `SavepointStack`                                              |                   27.0% |            22.5% |           **29.5%** |
| `VirtualMap`                                                  |                       — |            19.8% |           **28.2%** |
| `ConcurrentHashMap`                                           |                       — |            38.3% |           **24.5%** |
| `java.util.HashMap`                                           |                   38.0% |            19.1% |           **23.4%** |
| `commitFullStack`                                             |                   16.9% |            13.9% |           **20.1%** |
| `WritableKVStateStack` (inclusive, cache hits still sit here) |                       — |            14.6% |           **19.0%** |
| `ParentTxnFactory`                                            |                   19.9% |            14.9% |           **17.6%** |
| `tryHandle`                                                   |                   12.8% |            15.6% |           **17.1%** |
| `FinalizeRecord`                                              |                    9.7% |            11.8% |           **15.2%** |
| `VirtualNodeCache`                                            |                    9.6% |             9.6% |           **13.6%** |
| `pbj`                                                         |                   18.3% |             9.4% |           **13.6%** |
| `RecordCacheImpl`                                             |                   10.6% |        **31.3%** |           **13.3%** |
| `BlockStream` / `BlockStreamManager`                          |                    8.0% |    16.2% / 12.9% |   **13.4% / 10.1%** |
| `CryptoTransferHandler`                                       |                   13.0% |             9.6% |           **10.5%** |
| `createTopLevelTxn`                                           |                   13.7% |             9.2% |           **10.4%** |
| `TransferExecutor`                                            |                   11.9% |             8.6% |           **10.1%** |
| `ImmediateStateChangeListener`                                |                       — |            12.6% |            **8.1%** |
| `AdjustHbarChangesStep`                                       |                       — |             5.5% |            **6.8%** |
| `preHandleAllTransactions` / reuse                            |                    ~10% |      3.6% / 3.5% |     **4.8% / 4.7%** |
| `addRecordSource` / `hasDuplicate` / `purge`                  |                       — | 11.0 / 9.5 / 9.1 | **5.4 / 1.3 / 3.9** |
| `commitReceipts`                                              |                       — |            10.8% |            **6.6%** |
| `screenForCapacity`                                           |                       — |             5.3% |            **5.5%** |
| `ConfigDataService.getConfigData`                             |                    4.0% |             2.0% |            **3.5%** |
| `java.util.stream`                                            |                   21.9% |             1.1% |            **1.1%** |
| `createSignedState`                                           |                   10.9% |            0.22% |           **0.24%** |
| WRB / custom fees / `Enum.values()`                           | 8.6% / yes / 1.7% alloc |                0 |               **0** |

First application frames are no longer cache-dominated:

|                First app frame                 |   P2 |   **P3** |
|------------------------------------------------|-----:|---------:|
| `RecordCacheImpl.hasDuplicate`                 | 9.4% | **1.3%** |
| `ConfigDataService.getConfigData`              | 2.0% | **3.5%** |
| `WrappedState.getWritableStates`               | 2.5% | **3.3%** |
| `WritableStatesStack.getSingleton` / `get`     | 5.4% | **5.6%** |
| `VirtualNodeCache.putLeaf` / `updateKeyAtPath` | 4.3% | **5.4%** |
| `WrappedWritableStates.get`                    | 2.7% | **2.7%** |
| `addRecordSource` (all sites)                  | 8.5% | **4.1%** |

CT-layer first frame is still `AdjustHbarChangesStep.modifyAggregatedTransfers` (~6.1% combined). Handler share **10.5%** — scaffolding is still most of the thread.

## Allocation (why G1 still loses)

Handle: **144 GB** of **345 GB** (41.8%). Rate **~364 MB/s** — *higher* than P2. Leak detection was gRPC-side; it did not cut handle garbage.

|          Handle alloc class          |              Share |
|--------------------------------------|-------------------:|
| `HashMap` + `$Node` + `$Node[]`      |          **20.1%** |
| `Object[]`                           |               4.4% |
| `ThrottleUsageSnapshot`              |           **3.0%** |
| `WritableStatesStack$$Lambda` (two)  |           **5.2%** |
| `ArrayList`                          |               3.0% |
| `byte[]` / `OneOf` / `LinkedHashMap` | 2.8% / 2.5% / 2.3% |
| `Account` / `Account$Builder`        |        1.5% / 1.3% |

Hottest sites: `DirectMethodHandle.allocateInstance` **14.4%** (PBJ), `HashMap.resize` **8.8%**, `HashMap.newNode` 4.5%, `DeterministicThrottle.usageSnapshot` **2.7%**, `WritableStoreFactory.getStore` **2.6%**, `WritableStatesStack.<init>` ~2.0% (two ctor lines).

The intra-stack adapter cache from profile 2 is still in effect (`WritableKVStateStack` is not a top *class*). Each CT still news a `SavepointStack` and per-service maps:

```337:341:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/stack/SavepointStackImpl.java
public WritableStates getWritableStates(@NonNull final String serviceName) {
    return writableStatesMap.computeIfAbsent(serviceName, s -> new WritableStatesStack(this, s));
}
```

Throttle snapshots are still per dispatch:

```185:191:hedera-node/hapi-utils/src/main/java/com/hedera/node/app/hapi/utils/throttles/DeterministicThrottle.java
public ThrottleUsageSnapshot usageSnapshot() {
    return new ThrottleUsageSnapshot(
            delegate.bucket().capacityUsed(),
            lastDecisionTime == null
                    ? null
                    : new Timestamp(lastDecisionTime.getEpochSecond(), lastDecisionTime.getNano()));
}
```

```142:144:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/DispatchProcessor.java
dispatchUsageManager.finalizeAndSaveUsage(dispatch);
recordFinalizer.finalizeRecord(dispatch);
dispatch.stack().commitFullStack();
```

## What “CryptoTransfer” means on this thread now

```
~30%  SavepointStack / getWritableStates / HashMap.computeIfAbsent
~28%  VirtualMap (+ cache) inside commit
~20%  commitFullStack / Wrapped+Merkle commit
~15%  FinalizeRecord / staking
~13%  RecordCache (add + purge + remaining CHM)
~13%  Block stream + ImmediateStateChangeListener
~10%  CryptoTransferHandler (AdjustHbar + fee count)
~ 5%  screenForCapacity
~ 5%  pre-handle reuse (payer exists)
~ 0%  WRB, leak detector, streams, custom fees, Enum.values()
```

---

## Top 5 things to do next

**(1) is still JVM-only and still missing.** Do it before treating P3 as a clean serial baseline.

### 1. Pin the heap (`-Xms == -Xmx`, `AlwaysPreTouch`)

**Why:** Leak detection was half of item 1 from the first note. The other half is why G1 still pauses **13.5%** of wall and still evacuates. `:app:run` must **not** get `-Xms12g` via `JAVA_TOOL_OPTIONS` (Gradle workers die). Use the direct `java` launch from analysis 2, or add a *separate* `-PnodeHeap=12g` only on the `run` JavaExec.

**What should move:** pause total / P99; `EvacuationFailed` → 0; `ConcurrentModeFailure` → 0; heap used stable, not 1→16 GB.

### 2. Cut `commitFullStack` + VirtualMap put on the serial thread

**Why:** Now the largest *work* slice: commit **20%**, `VirtualMap.put` **12.9%**, `WritableKVStateBase.commit` **11.6%**, `VirtualNodeCache` **13.6%**. Every successful CT still:

```142:144:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/DispatchProcessor.java
dispatch.stack().commitFullStack();
```

**Direction:** commit token account pairs in place without a full stack walk; defer VirtualMap leaf publish to round/block; keep diffs in the already-hot `ImmediateStateChangeListener` until then.

**What should move:** `commitFullStack` / `MerkleWritableStates.commit` / `VirtualMap.put` inclusive.

### 3. Reuse one `SavepointStack` (and store factories) for the user dispatch

**Why:** `SavepointStack` **29.5%** inclusive; HashMap **20%** of handle alloc; `WritableStoreFactory.getStore` **2.6%** alloc site; `WritableStatesStack.<init>` still per service per txn. Adapter cache only helps *within* a stack that dies at commit.

**What should move:** `HashMap.resize` / `newNode`; `SavepointStack` inclusive; handle alloc MB/s.

### 4. Cheap record-cache index (still 13%, just no longer first)

**Why:** `addRecordSource` 5.4%, `commitReceipts` 6.6%, `purge` 3.9%, `hasDuplicate` 1.3%. CHM `computeIfAbsent` still shows up in contention (561 waits). NLG’s single payer `HashSet` is unchanged.

**What should move:** remaining `RecordCacheImpl` inclusive; CHM leaves on handle.

### 5. Throttle snapshots at round/block, not per CT

**Why:** `ThrottleUsageSnapshot` still **3.0%** of handle alloc; `usageSnapshot()` **2.7%** site; `screenForCapacity` **5.5%**. Same coupling as last time (`ConsensusThrottling.ON` reloads snapshots).

**What should move:** that alloc class → ~0; `finalizeAndSaveUsage` / `saveThrottleSnapshotsTo`.

---

## Next recording (`node-profile-4.jfr`)

Restart with a **pinned** heap (not `JAVA_TOOL_OPTIONS` on Gradle). Confirm:

```bash
jcmd <PID> VM.flags | rg 'MaxHeapSize|InitialHeapSize|AlwaysPreTouch'
jcmd <PID> VM.system_properties | rg leakDetection
```

`InitialHeapSize` must equal `MaxHeapSize`. Then the same `JFR.start` / dump / stop pattern as `ct-serial-3`, filename `node-profile-4.jfr`.

|            Metric             |             P3 | After heap pin  | After (2)–(5) |
|-------------------------------|---------------:|-----------------|---------------|
| GC pause % / P99              | 13.5% / 1.15 s | ≪ 2% / ≪ 100 ms | same          |
| `EvacuationFailed`            |          1,082 | 0               | 0             |
| Leak detector                 |              0 | stay 0          | stay 0        |
| `commitFullStack`             |          20.1% | similar         | down          |
| `SavepointStack`              |          29.5% | similar         | ≪ 10%         |
| Handle alloc rate             |      ~364 MB/s | similar         | down sharply  |
| `RecordCacheImpl`             |          13.3% | similar         | ≪ 10%         |
| `CryptoTransferHandler` share |          10.5% | similar         | **up**        |

Do not re-litigate WRB, pre-handle reuse, or leak detection unless those frames return. If TPS is still flat after a pinned-heap JFR, the next code cuts are (2)–(5) on this file.
