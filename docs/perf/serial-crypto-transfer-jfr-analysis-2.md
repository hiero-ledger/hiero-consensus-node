# Serial CryptoTransfer JFR analysis (`node-profile-2.jfr`)

Follow-up to [`serial-crypto-transfer-jfr-analysis.md`](serial-crypto-transfer-jfr-analysis.md). Same NLG CryptoTransfer shape, **serial handle**, reported platform TPS **14,024** (slightly below the previous run on this branch).

Recording: 44 MB, JFR 2.1, **2026-08-29 22:57:46 UTC, 410 s**. PID 42986, Temurin 25.0.2. Dump includes JVM shutdown (`jdk.Shutdown`). How it was read: `jfr summary` / `jfr view`, then `jfr print --stack-depth 64` on `jdk.ExecutionSample` (53,985 samples) and `--stack-depth 32` on `jdk.ObjectAllocationSample` (85,818 samples, weighted).

## Bottom line

The first-round cuts landed in the handle stacks. They did **not** raise TPS, because the recording mixed those wins with a worse GC story and a new handle-thread bottleneck:

1. **`streamMode=BLOCKS` worked.** Zero samples in `WrappedRecordFileBlockHashesCalculator`. Dual-record / WRB hashing is gone.
2. **Pre-handle reuse worked.** `preHandleAllTransactions` is **3.6%** of handle (was ~10%). The remaining cost is `reuseOrFailIfPayerGone` (payer still-exists).
3. **Stream API and enum `.values()` are gone** from the handle hot path (streams **1.1%** inclusive, was 21.9%). Custom-fee assessment for HBAR-only CT is ~0.
4. **`RecordCacheImpl` is now the largest named handle slice (31% inclusive).** `hasDuplicate` + `addRecordSource` + `purgeExpiredReceiptEntries` / `commitReceipts` plus `ConcurrentHashMap` (38% inclusive). NLG’s single genesis payer makes `payerTxnIds` a multi-million-entry `HashSet`.
5. **GC is why TPS went down.** Heap was still **1 GB → 16 GB** (item 5 from the first note was not on this JVM). Pause total **67 s** (16% of the file), P99 **3.17 s**, one old collection **7.90 s**. Netty leak detection was still on (**84,781** `ResourceLeakDetector` throws).

Handle allocation dropped (125 GB / 41% of process vs 183 GB / 47%), but not enough to keep G1 out of to-space failure while the heap was growing.

## What this JVM actually ran

`jdk.GCHeapConfiguration` / `jdk.JVMInformation` in the recording:

|         Flag / setting         |                        This JVM                        |          Asked for last time          |
|--------------------------------|--------------------------------------------------------|---------------------------------------|
| Heap                           | min 8 MB, initial **1 GB**, max **16 GB**              | `-Xms12g -Xmx12g -XX:+AlwaysPreTouch` |
| `io.netty.leakDetection.level` | not set (default tracking)                             | `DISABLED`                            |
| `blockStream.streamMode`       | `BLOCKS` in `configuration/dev/application.properties` | `BLOCKS`                              |

```17:17:hedera-node/configuration/dev/application.properties
blockStream.streamMode=BLOCKS
```

`:app:run` does not apply `JAVA_TOOL_OPTIONS` safely (Gradle workers inherit `-Xms12g` against a 4 GB worker `-Xmx`). This process was started as a plain module launch without the heap/Netty flags.

Serial handle is confirmed: **zero** samples in `ParallelRoundExecutor` / `OverlayApplier` / `SpeculativeState`.

## Recording shape vs profile 1

|                       Item                        |                         Profile 1 |                       Profile 2 |
|---------------------------------------------------|----------------------------------:|--------------------------------:|
| Duration                                          |                             450 s |                       **410 s** |
| Execution samples                                 |                            42,943 |                      **53,985** |
| Handle samples (`<scheduler TransactionHandler>`) |                     6,440 (15.0%) |               **8,535 (15.8%)** |
| Allocation weight                                 |                           ~393 GB |                     **~303 GB** |
| Handle alloc weight                               |                    183 GB (46.6%) |              **125 GB (41.4%)** |
| GC pauses                                         | 512 / **33.8 s** / P99 **1.17 s** | 421 / **67 s** / P99 **3.17 s** |
| `jdk.EvacuationFailed`                            |                             1,257 |                         **650** |
| `jdk.ConcurrentModeFailure`                       |                                18 |                          **14** |
| Old / mixed collections                           |          22 “mark live” × ~634 ms |  **77** old GCs, max **7.90 s** |
| Heap                                              |                   G1 1 GB → 16 GB |                        **same** |
| JVM user CPU avg / max                            |                     23.7% / 71.8% |               **20.7% / 44.6%** |
| Machine CPU avg / max                             |                      75.1% / 100% |                **85.9% / 100%** |
| `ResourceLeakDetector` throws                     |                              ~98k |                      **84,781** |
| Native `Deflater.deflateBytesBytes`               |                   ~1.2% of native |         **131 / 15,935** (0.8%) |
| Reported TPS                                      | 10k+ (then higher on this branch) |                      **14,024** |

Handle sample *count* rose in a *shorter* file because WRB/record gzip left the serial thread. That is not “handle got slower at the same work”; it is “handle is a larger fraction of Java CPU.” Wall TPS still fell because **16% of the recording is GC pause** (was 7.5%), including multi-second old collections.

## Where CPU went

### Whole JVM (execution samples)

|           Thread group           | Samples |     Share |        vs profile 1         |
|----------------------------------|--------:|----------:|-----------------------------|
| `grpc-nio-worker-ELG-3-*`        |  24,590 | **45.6%** | was 47.6%                   |
| `platformForkJoinThread-*`       |  11,238 |     20.8% | was 24.1%                   |
| `<scheduler TransactionHandler>` |   8,535 | **15.8%** | was 15.0% — **TPS ceiling** |
| `VirtualHasherForkJoinThread-*`  |   4,551 |  **8.4%** | was 3.7%                    |
| MerkleDb / other                 |    rest |           | compact, metrics, compiler  |

gRPC: **68.9%** of samples in `IngestWorkflow`, **48.5%** mention `Signature`. Platform FJP: **85.1%** PreHandle. Same conclusion as profile 1: do not chase ingest RSA for handle TPS.

Virtual hasher share **more than doubled**. `createSignedState` on the handle thread collapsed from **10.9% → 0.22%**. Hashing is no longer sitting on the serial core the way it did under `BOTH`; the FJP does the leaf work. That is a real win from `BLOCKS`, but it did not offset GC.

Hottest ingest locks (`jdk.JavaMonitorEnter`):

|                           Site                           | Count |     Avg |        Max |
|----------------------------------------------------------|------:|--------:|-----------:|
| `SubmissionManager.submit`                               | 1,966 | 31.7 ms | **3.48 s** |
| `SynchronizedThrottleAccumulator.shouldThrottle` (txn)   |   455 | 48.5 ms | **3.42 s** |
| `SynchronizedThrottleAccumulator.shouldThrottle` (query) |   216 | 62.1 ms |     2.78 s |
| `VirtualPipeline.hashCopy`                               |    81 |  173 ms |     2.06 s |

Those maxes line up with the multi-second old GCs, not with a new ingest bug.

### Handle thread phases (8,535 samples = 100%)

Inclusive occupancy (a sample can hit several frames):

|                         Frame / marker                         |           Profile 1 |         Profile 2 |                         Notes                         |
|----------------------------------------------------------------|--------------------:|------------------:|-------------------------------------------------------|
| `HandleWorkflow.handleRound`                                   |               88.7% |         **99.5%** | Less time parked in WRB / signed-state on this thread |
| `handlePlatformTransaction`                                    |               83.4% |         **88.4%** |                                                       |
| `executeSubmittedParent`                                       |               63.5% |         **76.1%** |                                                       |
| `DispatchProcessor.processDispatch`                            |               45.1% |         **56.4%** |                                                       |
| `java.util.concurrent.ConcurrentHashMap`                       | (in HashMap bucket) |         **38.3%** | Record cache + VirtualNodeCache                       |
| `RecordCacheImpl`                                              |               10.6% |         **31.3%** | **New #1 named cost**                                 |
| `SavepointStack`                                               |               27.0% |         **22.5%** | Down, still large                                     |
| `java.util.HashMap`                                            |               38.0% |         **19.1%** | Config + stack maps; CHM took the rest                |
| `java.util.stream`                                             |               21.9% |          **1.1%** | `commitInStateIdOrder` / extra-fee index landed       |
| `commitFullStack`                                              |               16.9% |         **13.9%** | Still every user dispatch                             |
| `WrappedWritableStates.commit` / `MerkleWritableStates.commit` |         (in commit) |     12.8% / 12.6% |                                                       |
| `ParentTxnFactory`                                             |               19.9% |         **14.9%** |                                                       |
| `createTopLevelTxn`                                            |               13.7% |          **9.2%** |                                                       |
| `preHandleAllTransactions`                                     |                ~10% |          **3.6%** | Reuse path                                            |
| `reuseOrFailIfPayerGone`                                       |                   — |          **3.5%** | Intended leftover                                     |
| `CryptoTransferHandler`                                        |               13.0% |          **9.6%** |                                                       |
| `TransferExecutor`                                             |               11.9% |          **8.6%** |                                                       |
| `AdjustHbarChangesStep`                                        |            (subset) |          **5.5%** | Real debit/credit                                     |
| `AssessmentResult` / custom fees                               |         ~2.9% alloc |            **~0** | HBAR-only skip landed                                 |
| `FinalizeRecord`                                               |                9.7% |         **11.8%** |                                                       |
| `StakingRewardsHandler`                                        |                7.6% |          **7.9%** |                                                       |
| `tryHandle` / `dispatchHandle`                                 |               12.8% |     15.6% / 10.3% |                                                       |
| `BlockStream` / `BlockStreamManager`                           |                8.0% | **16.2% / 12.9%** | Expected once records leave                           |
| `ImmediateStateChangeListener`                                 |          (in block) |         **12.6%** | KV diffs for block items                              |
| `VirtualMap` / `VirtualNodeCache`                              |          9.6% cache |  **19.8% / 9.6%** | Commit + put                                          |
| `createSignedState`                                            |               10.9% |         **0.22%** | Moved off handle                                      |
| `WrappedRecordFileBlockHashesCalculator`                       |                8.6% |             **0** | `BLOCKS`                                              |
| `ConfigDataService.getConfigData`                              |                4.0% |          **2.0%** | `IdentityHashMap` + single get                        |
| `pbj`                                                          |               18.3% |          **9.4%** | WRB encode gone                                       |
| `screenForCapacity`                                            |                   — |          **5.3%** | Still per user dispatch                               |
| `HederaFunctionality.values()`                                 |          1.7% alloc |             **0** | Static arrays landed                                  |

Handle leaves (not inclusive):

|                Leaf                 |            Share of handle |
|-------------------------------------|---------------------------:|
| `ConcurrentHashMap.get`             | **12.1%** (two line sites) |
| `ConcurrentHashMap.computeIfAbsent` |                       5.7% |
| `ConcurrentHashMap.replaceNode`     |                       5.1% |
| `HashMap.computeIfAbsent`           |    5.4% (three line sites) |
| `WritableStatesStack.get`           |                       1.5% |
| `WrappedState.getWritableStates`    |                       1.2% |
| `IdentityHashMap.get`               |                       1.9% |
| `HashMap.getNode`                   |            1.0% (was 5.6%) |

First application frame on the handle thread is now **record cache**, not CT:

|                          First app frame                           |                   Share |
|--------------------------------------------------------------------|------------------------:|
| `RecordCacheImpl.hasDuplicate`                                     |                **9.4%** |
| `RecordCacheImpl.purgeExpiredReceiptEntries`                       |                    6.7% |
| `RecordCacheImpl.addRecordSource`                                  | 8.5% (three line sites) |
| `WritableStatesStack.get` / `getSingleton`                         |                    5.4% |
| `WrappedWritableStates.get`                                        |                    2.7% |
| `WrappedState.getWritableStates`                                   |                    2.5% |
| `ConfigDataService.getConfigData`                                  |                    2.0% |
| `VirtualNodeCache.putLeaf` / `updateKeyAtPath` / `lookupLeafByKey` |                    4.3% |

CT-layer first frames are almost all `AdjustHbarChangesStep.modifyAggregatedTransfers` (~4.8% combined). `checkFungibleTokenTransfers` as a pre-handle leaf is gone.

## CryptoTransfer hot path (this recording)

```
SequentialThreadTaskScheduler
  DefaultTransactionHandler.doHandleConsensusRound
    HandleWorkflow.handlePlatformTransaction
      ParentTxnFactory.createTopLevelTxn            // 9.2%
        SavepointStack + ReadableStoreFactory
        getCurrentPreHandleResult                   // 6.8%
          reuseOrFailIfPayerGone                    // 3.5%  (fast path)
      executeSubmittedParent                        // 76.1%
        DispatchProcessor.processDispatch           // 56.4%
          charge fees
          screenForCapacity                         // 5.3%
          tryHandle → CryptoTransferHandler.handle  // 9.6%
            TransferExecutor / AdjustHbarChangesStep
          finalizeAndSaveUsage                      // throttle snapshots
          RecordFinalizer.finalizeRecord            // 12.9%
          stack.commitFullStack                     // 13.9%
        RecordCache.addRecordSource                 // 11.0%
        RecordCache.commitReceipts                  // 10.8% (purge + queue + VM)
        blockStreamManager.writeItem                // BLOCKS path
```

```648:654:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/HandleWorkflow.java
final var handleOutput = executeSubmittedParent(topLevelTxn, eventBirthRound, state);
if (streamMode != BLOCKS && !isNodeSubmittedTransaction) {
    final var records = ((LegacyListRecordSource) handleOutput.recordSourceOrThrow()).precomputedRecords();
    blockRecordManager.endUserTransaction(records.stream(), state);
}
if (streamMode != RECORDS) {
    handleOutput.blockRecordSourceOrThrow().forEachItem(blockStreamManager::writeItem);
}
```

```130:144:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/DispatchProcessor.java
public void processDispatch(...) {
    // charge → tryHandle → finalizeAndSaveUsage → finalizeRecord → commitFullStack
    dispatchUsageManager.finalizeAndSaveUsage(dispatch);
    recordFinalizer.finalizeRecord(dispatch);
    dispatch.stack().commitFullStack();
}
```

### What the last changes did on this path

**Pre-handle reuse** — `preHandleTransaction` now returns the previous `SO_FAR_SO_GOOD` result after a payer lookup:

```172:177:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/prehandle/PreHandleWorkflowImpl.java
if (previousResult != null && canReuseSuccessfulPreHandle(previousResult, creatorInfo, innerTransaction)) {
    return reuseOrFailIfPayerGone(previousResult, creatorInfo, accountStore);
}
```

That is visible: `preHandleAllTransactions` 3.6%, `canReuseSuccessfulPreHandle` 0.06% (cheap predicate), no `checkFungibleTokenTransfers` on handle.

**Adapter cache** — `WritableStatesStack` caches KV/singleton/queue by `stateId`:

```69:70:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/stack/WritableStatesStack.java
return (WritableKVState<K, V>) kvStates.computeIfAbsent(stateId, id -> new WritableKVStateStack<>(this, id));
```

`WritableKVStateStack` / `WritableSingletonStateStack` **dropped off the top allocation-class list** (they were ~8% of handle alloc in profile 1). The cache only lives as long as the `SavepointStack`, which is still **per transaction**:

```337:341:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/stack/SavepointStackImpl.java
public WritableStates getWritableStates(@NonNull final String serviceName) {
    return writableStatesMap.computeIfAbsent(serviceName, s -> new WritableStatesStack(this, s));
}
```

Each CT still allocates a new stack, per-service `WritableStatesStack` (three inner `HashMap`s), `ReadonlyStatesWrapper`, `WrappedWritableStates`, and `HashMap.computeIfAbsent` nodes. That is why `HashMap` + `HashMap$Node` + `HashMap$Node[]` are still **19.8% of handle allocation weight**, and why `WritableStatesStack.get` is still a 1.5% leaf.

**Streams / extras / custom fees** — `commitInStateIdOrder` + extra-fee `EnumMap` + skip `CustomFeeAssessmentStep` for HBAR-only CT. Streams 21.9% → 1.1%. `AssessmentResult` gone.

## Record cache (why it looks “worse”)

Absolute handle samples in `RecordCacheImpl` went from ~682 (10.6% of 6,440) to **2,668 (31.3% of 8,535)** — about **4×**, not just a larger slice after other work left.

Maps:

```122:129:hedera-node/hedera-app/src/main/java/com/hedera/node/app/state/recordcache/RecordCacheImpl.java
private final Map<TransactionID, HistorySource> historySources = new ConcurrentHashMap<>();
private final Map<AccountID, Set<TransactionID>> payerTxnIds = new ConcurrentHashMap<>();
```

Per user transaction on the handle thread:

1. **`hasDuplicate`** — `historySources.get(txnId)` (**9.5%** inclusive). `TransactionID.equals` / hash is a PBJ record compare (account + timestamp + nonce). At 14k TPS with `transactionMaxValidDuration` (~180 s) this map holds on the order of **2.5M** entries.
2. **`addRecordSource`** — `historySources.computeIfAbsent` + `payerTxnIds.computeIfAbsent(... HashSet).add` (**11.0%**). NLG pays every transfer from the genesis operator, so **one** `HashSet` collects every `TransactionID` until expiry.
3. **`commitReceipts` → `purgeExpiredReceiptEntries`** (**10.8% / 9.1%**). The once-per-consensus-second skip landed:

```412:415:hedera-node/hedera-app/src/main/java/com/hedera/node/app/state/recordcache/RecordCacheImpl.java
if (earliestValidStart.equals(lastPurgeEarliestValidStart)) {
    return;
}
lastPurgeEarliestValidStart = earliestValidStart;
```

The remaining once-per-second walk still does `historySources.remove` (`ConcurrentHashMap.replaceNode` **5.1%** leaf) and `HashSet.remove` on that giant payer set. Getters may run on query threads, so the CHM is not accidental — but handle is paying CHM + PBJ-key costs on every CT.

`BLOCKS` also writes receipt-queue state changes through `ImmediateStateChangeListener` into the block stream inside `commitReceipts`. That couples cache maintenance to VirtualMap + block items on the same serial thread.

## Allocation and GC (why 14,024 TPS is a regression)

Handle thread: **41.4%** of process allocation weight (125 GB of 303 GB) from 3,185 events. Rough rate: 125 GB / 410 s ≈ **306 MB/s** on one thread (was ~400 MB/s). Process-wide ~740 MB/s (was ~870 MB/s). Allocation improved; **pauses did not**.

|           GC fact           |                       Profile 1 |                            Profile 2 |
|-----------------------------|--------------------------------:|-------------------------------------:|
| Total pause                 |           33.8 s / 450 s (7.5%) |             **67 s / 410 s (16.3%)** |
| Pause P50 / P90 / P99 / max | 19 ms / 62 ms / 1.17 s / 1.24 s | **37 ms / 210 ms / 3.17 s / 3.56 s** |
| Old GC max (this file)      |                     ~1.2 s Full |                **7.90 s** (gcId 197) |
| Heap at end of load         |                   16 GB ceiling |         **12–14 GB used**, 16 GB max |

G1 still evacuates, still fails (`EvacuationFailed` 650), still falls back to long old collections while the heap is growing from 1 GB. A **7.9 s** pause at 14k TPS is ~110k transactions of silence. That dominates any per-txn CPU we saved.

Top handle allocation classes (weight):

|                      Class                      |     Share of handle alloc |
|-------------------------------------------------|--------------------------:|
| `HashMap` + `$Node` + `$Node[]`                 |                 **19.8%** |
| `Object[]`                                      |                      4.1% |
| `ThrottleUsageSnapshot`                         |                  **3.5%** |
| `WritableStatesStack$$Lambda` (computeIfAbsent) | 4.6% (two lambda classes) |
| `WrappedWritableStates$$Lambda`                 |                      2.9% |
| `byte[]` / `Account` / `Account$Builder`        |        2.7% / 2.0% / 1.6% |
| `OneOf` / `LinkedHashMap`                       |               2.2% / 2.1% |
| `BlockStreamManagerImpl$SequentialTask`         |                      1.5% |

Hottest handle allocation *sites*: `DirectMethodHandle.allocateInstance` **16.1%** (PBJ records), `HashMap.newNode` 5.8%, `HashMap.resize` 5.2%, `DeterministicThrottle.usageSnapshot` **2.8%**, `Account$Builder.build` 1.9%, `BlockStreamManagerTask.addItem` 1.5%, `WritableStatesStack.<init>` 1.2%.

Throttle snapshots were **not** deferred (they reset from saved snapshots on `screenForCapacity`):

```185:191:hedera-node/hapi-utils/src/main/java/com/hedera/node/app/hapi/utils/throttles/DeterministicThrottle.java
public ThrottleUsageSnapshot usageSnapshot() {
    return new ThrottleUsageSnapshot(
            delegate.bucket().capacityUsed(),
            lastDecisionTime == null
                    ? null
                    : new Timestamp(lastDecisionTime.getEpochSecond(), lastDecisionTime.getNano()));
}
```

`BlockStreamManagerImpl` still allocates a `ParallelTask` + `SequentialTask` pair per item on the handle thread:

```1195:1201:hedera-node/hedera-app/src/main/java/com/hedera/node/app/blocks/impl/BlockStreamManagerImpl.java
void addItem(BlockItem item) {
    new ParallelTask(item, currentTask, pipelineFailure).send();
    SequentialTask nextTask = new SequentialTask(pipelineFailure);
    currentTask.send(nextTask);
    prevTask = currentTask;
    currentTask = nextTask;
}
```

Native gzip of block files is small (`Deflater` 131 samples). Leak detection is not: **84,781** `io.netty.util.ResourceLeakDetector$TraceRecord` throws (`close` 47,414 / `track0` 37,367).

## What “CryptoTransfer” means now

NLG pairwise HBAR, serial handle, `BLOCKS`, this file:

```
~31%  RecordCache (duplicate check + add + once-per-second purge/commit)
~23%  SavepointStack / getWritableStates / adapter HashMaps
~20%  VirtualMap put + VirtualNodeCache (inside commit)
~16%  Block stream write + ImmediateStateChangeListener
~14%  commitFullStack / Merkle+Wrapped commit
~12%  FinalizeRecord / staking
~10%  CryptoTransferHandler (mostly AdjustHbar + fee count)
~ 5%  screenForCapacity + throttle snapshot/save
~ 4%  ParentTxnFactory leftover (stack ctor + payer reuse)
~ 0%  WRB, custom fees, Enum.values(), Stream.commit
```

Improving `AdjustHbarChangesStep` still will not move TPS. The last profile’s scaffolding items that **did** move are no longer the limiter. GC + record cache + per-txn stack construction are.

---

## Top 5 things to do, then take profile 3

Do **(1) before any more code** or the next JFR will again mix a growing heap with the CT path.

### 1. Pin the heap and disable Netty leak detection (still not on this JVM)

**Why:** Profile 2 ran the same 1 GB → 16 GB G1 heap as profile 1. Pause time **doubled**. P99 **3.17 s**, one old GC **7.90 s**. That is enough to make 14,024 TPS look like a regression even if handle work per txn is cheaper. 84k leak-detector exceptions are still in the file.

**Change:** start the node **without** putting heap flags on Gradle:

```bash
unset JAVA_TOOL_OPTIONS
./gradlew :app:assemble :app:generateNodeKeys

cd hedera-node/hedera-app/build/node
java -Xms12g -Xmx12g -XX:+AlwaysPreTouch \
  -Dio.netty.leakDetection.level=DISABLED \
  --enable-native-access=ALL-UNNAMED \
  --module-path "data/apps:data/lib" \
  --module com.hedera.node.app \
  -local 0
```

Confirm with `jcmd <PID> VM.flags` that `InitialHeapSize == MaxHeapSize == 12884901888` (or 16g) and `AlwaysPreTouch` is on.

**What should move:** GC pause total / P99; `EvacuationFailed` → 0; old GC max ≪ 200 ms; `ResourceLeakDetector` → 0; JVM user max up (less time in GC). TPS should rise **without** further code if the last run was pause-bound.

### 2. Make `RecordCacheImpl` cheap for high-TPS, single-payer CT

**Why:** 31% of handle samples. `hasDuplicate` 9.5%, `addRecordSource` 11%, `commitReceipts`/`purge` 10.8%. Leaves are `ConcurrentHashMap.get` / `computeIfAbsent` / `replaceNode` and `TransactionID.equals`. NLG’s genesis payer turns `payerTxnIds` into one huge `HashSet`.

**Direction (correctness-preserving):**

- Key the in-memory index by a cheaper identity than a full PBJ `TransactionID` (e.g. packed valid-start + account + nonce), keep the proto only for queries.
- Bucket `payerTxnIds` by consensus-second (or valid-start second) so purge is list-drop, not millions of `HashSet.remove`.
- Do not `computeIfAbsent` a new `HashSet` on the purge miss path.
- Keep CHM only if query threads still need a live map; otherwise a handle-thread map plus a published snapshot for queries.

**What should move:** `RecordCacheImpl` inclusive; CHM leaves; `hasDuplicate` first-app frame; handle samples in `TransactionID.equals`.

### 3. Stop allocating a new `SavepointStack` graph on every CT

**Why:** Intra-stack adapter cache removed `WritableKVStateStack` as an alloc *class*, but each txn still news `SavepointStackImpl`, per-service `WritableStatesStack` (3× `HashMap`), `ReadonlyStatesWrapper`, `WrappedWritableStates`. Handle alloc is still **HashMap-dominated (20%)**. `DirectMethodHandle.allocateInstance` is still **16%** of handle alloc (PBJ `Account` / `Timestamp` / `OneOf` / block items).

**Direction:** reuse or reset one root stack / store-factory for the user dispatch (clear maps, keep wrappers). Flatten `getWritableStates` so Token + EntityId + RecordCache do not each pay `computeIfAbsent`. Pool `Account.Builder` or mutate working copies for the HBAR debit/credit pair.

**What should move:** handle alloc GB and `HashMap.resize`/`newNode` sites; `WritableStatesStack.<init>`; `SavepointStack` inclusive (22% → well under 10%).

### 4. Snapshot throttles on a round/block boundary, not per dispatch — or stop materializing `ThrottleUsageSnapshot` objects

**Why:** `ThrottleUsageSnapshot` is **3.5%** of handle alloc; `usageSnapshot()` is a **2.8%** site; `screenForCapacity` is **5.3%** inclusive; `saveThrottleSnapshotsTo` still appears. We skipped this last time because `screenForCapacity` reloads from saved snapshots when `ConsensusThrottling.ON`. That coupling is now one of the remaining per-txn tax.

**Direction:** keep usage in the live throttle objects; persist snapshots once per round (or when the values actually change); make `screenForCapacity` read the live buckets. If a snapshot is required for reconnect determinism, write it at `endRound`, not after every CT.

**What should move:** `ThrottleUsageSnapshot` alloc class; `finalizeAndSaveUsage` / `saveThrottleSnapshotsTo` samples; young-GC frequency.

### 5. Batch receipt-queue + KV-change block items; do not enqueue a hashing task per item on handle

**Why:** After `BLOCKS`, the serial thread still does `ImmediateStateChangeListener` (**12.6%**), `BlockStreamManager` (**12.9%**), and `addItem` → new `ParallelTask`/`SequentialTask` per item (**1.5%** handle alloc). `commitFullStack` + `VirtualMap.put` remain **14% + 9%**. Record-file work left; **block-item bookkeeping replaced it**.

**Direction:** accumulate receipts and KV diffs for the whole round (or `receiptEntriesBatchSize` without a VirtualMap commit each time); one `writeItem` / one hash-pipeline task per batch; keep proof/hash at block close (already mostly on `VirtualHasherForkJoinThread-*`). Do not put `hashCopy` waits back on handle.

**What should move:** `ImmediateStateChangeListener` / `BlockStreamManager` inclusive; `SequentialTask` alloc; `commitReceipts` exclusive of the in-memory cache work in (2).

---

## Next recording (`node-profile-3.jfr`)

1. Stop the node. Restart with the **java** command in (1), not `./gradlew :app:run` + `JAVA_TOOL_OPTIONS`.
2. Warm NLG until TPS is steady (accounts already created: `-R`).
3. Then:

```bash
JCMD=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/jcmd
PID=$(jps -l | awk '/com.hedera.node.app/{print $1}')
$JCMD "$PID" JFR.start name=ct-serial-3 settings=profile dumponexit=true \
  filename="/Users/derektriley/git/workspace-1/hiero-consensus-node/node-profile-3.jfr"
# fixed window, e.g. 5 minutes at steady load
$JCMD "$PID" JFR.dump name=ct-serial-3 \
  filename="/Users/derektriley/git/workspace-1/hiero-consensus-node/node-profile-3.jfr"
$JCMD "$PID" JFR.stop name=ct-serial-3
```

### Comparison checklist (profile 2 → profile 3)

|                  Metric                  |              Profile 2 |      Target after (1) alone       |        After (2)–(5)        |
|------------------------------------------|-----------------------:|-----------------------------------|-----------------------------|
| Reported TPS                             |                 14,024 | **Up** (GC holes close)           | Up further                  |
| GC pause total / P99 / max old           | 67 s / 3.17 s / 7.90 s | Young only, P99 ≪ 100 ms          | same                        |
| `EvacuationFailed`                       |                    650 | 0                                 | 0                           |
| `ResourceLeakDetector`                   |                 84,781 | 0                                 | 0                           |
| `RecordCacheImpl` inclusive              |                  31.3% | similar                           | **≪ 10%**                   |
| `SavepointStack` inclusive               |                  22.5% | similar                           | **≪ 10%**                   |
| Handle alloc weight                      |           125 GB / 41% | similar or down (less leak churn) | **down sharply**            |
| `ThrottleUsageSnapshot`                  |      3.5% handle alloc | similar                           | ~0                          |
| `ImmediateStateChangeListener`           |                  12.6% | similar                           | down                        |
| `WrappedRecordFileBlockHashesCalculator` |                      0 | stay 0                            | stay 0                      |
| `preHandleAllTransactions`               |                   3.6% | stay low                          | stay low                    |
| `CryptoTransferHandler` share of handle  |                   9.6% | similar                           | **up** as scaffolding drops |

If after (1) TPS is still flat and the next JFR still shows `RecordCacheImpl` + `HashMap` + `commitFullStack` as the bulk, that is (2)–(5). Do not re-litigate WRB or pre-handle reuse unless those frames come back.
