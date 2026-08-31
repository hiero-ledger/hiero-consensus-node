# Serial CryptoTransfer JFR analysis (`node-profile.jfr`)

NLG CryptoTransfer load at 10k+ TPS, **serial handle** on this branch (no `OverlayApplier` / `ParallelRoundExecutor` samples). Recording is 48 MB, JFR 2.1, **2026-08-29 22:09:16–22:16:45 UTC (7 m 30 s)**. Dump reason: JVM shutdown.

This note is the baseline for a second recording after the five changes at the end.

How it was read: `jfr summary` / `jfr view`, then `jfr print --stack-depth 64` on `jdk.ExecutionSample` (42,943 samples) and `jdk.ObjectAllocationSample` (94,541 samples, weighted). Default `jfr view hot-methods` uses **5-frame** stacks and attributes **41%** of samples to `LambdaForm$MH.invokeExact_MT`. That is a truncation artifact; with 64-frame stacks those samples resolve to PBJ, HashMap, gRPC, and BouncyCastle.

## Bottom line

The CryptoTransfer **handler** is not the limiter. On the consensus handle thread (`<scheduler TransactionHandler>`), `CryptoTransferHandler` is only **13% inclusive**. The expensive work is scaffolding around each transfer:

1. Dual record + block streaming (`blockStream.streamMode=BOTH`, default), including live wrapped-record-file hashing on the handle thread.
2. Re-running pre-handle (`getCurrentPreHandleResult` → `preHandleAllTransactions`) on the serial thread even when ingest/prehandle already attached a `PreHandleResult`.
3. Per-access `SavepointStack` adapters (`new WritableKVStateStack` on every `get()`).
4. `commitFullStack` / VirtualMap cache / PBJ `StateValue` encode plus HashMap and Stream API churn.
5. Allocation rate on that one thread (~**400+ MB/s**, **183 GB** sampled over the recording) driving G1 to the **16 GB** ceiling and **~1.2 s Full GC** pauses.

Ingest/pre-handle signature CPU is large **across the process** but runs on gRPC workers and `platformForkJoinThread-*`, so it does not serialize TPS the way handle does.

## Recording shape

|        Item        |                        Value                        |
|--------------------|-----------------------------------------------------|
| Duration           | 450 s (7 m 30 s)                                    |
| Execution samples  | 42,943                                              |
| Native samples     | 19,500 (88.5% `KQueue.poll` — idle I/O wait)        |
| Allocation samples | 94,541 (~393 GB weighted)                           |
| GC pauses          | 512 pauses, **33.8 s** wall (7.5% of the recording) |
| Heap               | G1, initial 1 GB, max **16 GB**, compressed oops    |
| JVM user CPU       | avg 23.7%, max 71.8%                                |
| Machine CPU        | avg 75.1%, max 100%                                 |

Load does not fill the whole file: young GCs stay ~300 MB until ~18:09:54, then the heap climbs to 16 GB. Treat the last ~6.5 minutes as the NLG window.

Serial handle is confirmed: **zero** samples in `ParallelRoundExecutor` / `OverlayApplier` / `SpeculativeState`. `ForkJoinPool-7-*` appears (likely the unused parallel-handle pool) at ~0% user CPU.

## Where CPU actually went

### Whole JVM (execution samples)

|              Thread group              | Samples |     Share |                      What it is doing                      |
|----------------------------------------|--------:|----------:|------------------------------------------------------------|
| `<scheduler TransactionHandler>`       |   6,440 | **15.0%** | Serial consensus handle — **this is the TPS ceiling**      |
| `grpc-nio-worker-ELG-3-*` (16 threads) |  20,427 | **47.6%** | Ingest: parse, fee, throttle, **signature verify**, submit |
| `platformForkJoinThread-*`             |  10,339 |     24.1% | **83.9% PreHandle**, plus event RSA sign / tipset          |
| `VirtualHasherForkJoinThread-*`        |   1,575 |      3.7% | VirtualMap hashing at copy / block close                   |
| Everything else                        |    rest |           | MerkleDb compact, metrics, compiler, cache cleaner         |

gRPC workers: **73%** of their samples are in `IngestWorkflow`, **54%** mention `Signature`. Platform FJP: **14.8%** `TipsetEventCreator`, **12.5%** RSA (`BigInteger.oddModPow` / BouncyCastle) for **event** signing, not HAPI CryptoTransfer body crypto.

Native: `Deflater.deflateBytesBytes` is 1.2% of native samples — record/block file gzip, not the handle hot path.

**Do not optimize ingest RSA first if the goal is handle TPS.** Those threads already run in parallel. The single-threaded handle path is what NLG waits on.

### Handle thread phases (6,440 samples = 100%)

Inclusive occupancy (a sample can hit several frames):

|           Frame / marker            |   Inclusive |                          Role                           |
|-------------------------------------|------------:|---------------------------------------------------------|
| `HandleWorkflow.handleRound`        |       88.7% | Round loop                                              |
| `handlePlatformTransaction`         |       83.4% | One user txn                                            |
| `executeSubmittedParent`            |       63.5% | Dispatch + commit + records                             |
| `DispatchProcessor.processDispatch` |       45.1% | Fees, `tryHandle`, finalize, `commitFullStack`          |
| `java.util.HashMap`                 |       38.0% | Config, record cache, stack maps, VirtualNodeCache      |
| `SavepointStack`                    |       27.0% | Per-service writable views                              |
| `java.util.stream`                  |       21.9% | Commit, record stream, fee extras, custom fees          |
| `ParentTxnFactory`                  |       19.9% | Stack + pre-handle reuse + dispatch construction        |
| `pbj`                               |       18.3% | Encode accounts, state keys, records, WRB files         |
| `commitFullStack`                   |       16.9% | Flush savepoint → wrapped state → VirtualMap            |
| `createTopLevelTxn`                 |       13.7% | Pre-handle on handle + `SavepointStack`                 |
| `CryptoTransferHandler`             |       13.0% | Actual CT, **plus** preHandle/pureChecks on this thread |
| `TransferExecutor`                  |       11.9% | Steps: hbar adjust, custom fees, aliases                |
| `createSignedState`                 |       10.9% | Round-close hashing/signing on the **handle** thread    |
| `RecordCacheImpl`                   |       10.6% | `addRecordSource` + `purgeExpiredReceiptEntries`        |
| `FinalizeRecord` / staking          | 9.7% / 7.6% | Post-handle staking + record finalize                   |
| `VirtualNodeCache`                  |        9.6% | `putLeaf` / `lookupLeafByKey` / `updateKeyAtPath`       |
| `BlockStream`                       |        8.0% | Block items + `BoundaryStateChangeListener`             |
| `tryHandle`                         |       12.8% | `dispatchHandle` → CT handler                           |
| `ConfigDataService.getConfigData`   |        4.0% | HashMap lookup per config record class                  |

`jfr view` leaf methods on the handle thread (not inclusive):

|                      Leaf                       | Share of handle samples |
|-------------------------------------------------|------------------------:|
| `HashMap.getNode`                               |                    5.6% |
| `ProtoArrayWriterTools.writeUnsignedVarInt`     |                    4.1% |
| `ConcurrentHashMap.get`                         |                    3.6% |
| `WritableSequentialData.writeVarInt`            |                    3.1% |
| `HashMap.putVal`                                |                    3.0% |
| `AbstractPipeline.wrapSink` (streams)           |                    3.0% |
| `ConcurrentHashMap.computeIfAbsent` / `compute` |             2.7% + 2.5% |
| `MessageDigest.engineUpdate`                    |                    2.3% |
| `WrappedState.getWritableStates`                |                    1.3% |
| `SavepointStackImpl.getWritableStates`          |                    1.2% |

## CryptoTransfer hot path (serial)

Call chain on this recording:

```
SequentialThreadTaskScheduler
  DefaultTransactionHandler.doHandleConsensusRound
    Hedera.onHandleConsensusRound
      HandleWorkflow.handleRound / handleEvents / handlePlatformTransaction
        ParentTxnFactory.createTopLevelTxn          // 13.7% inclusive
          SavepointStack + ReadableStoreFactory
          PreHandleWorkflow.getCurrentPreHandleResult
            preHandleAllTransactions                // re-entered on handle
        executeSubmittedParent                      // 63.5%
          ParentTxnFactory.createDispatch
          DispatchProcessor.processDispatch         // 45.1%
            charge fees
            tryHandle → CryptoTransferHandler.handle
              TransferExecutor.executeCryptoTransfer
            RecordFinalizer.finalizeRecord
            stack.commitFullStack                   // 16.9%
          stack.buildHandleOutput
          RecordCache.addRecordSource
        blockRecordManager.endUserTransaction       // streamMode != BLOCKS
        blockStreamManager.writeItem                // streamMode != RECORDS
```

Relevant source:

```635:655:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/HandleWorkflow.java
final var topLevelTxn =
        parentTxnFactory.createTopLevelTxn(state, creator, txn, consensusNow, shortCircuitCallback);
// ...
final var handleOutput = executeSubmittedParent(topLevelTxn, eventBirthRound, state);
if (streamMode != BLOCKS && !isNodeSubmittedTransaction) {
    final var records = ((LegacyListRecordSource) handleOutput.recordSourceOrThrow()).precomputedRecords();
    blockRecordManager.endUserTransaction(records.stream(), state);
}
if (streamMode != RECORDS) {
    handleOutput.blockRecordSourceOrThrow().forEachItem(blockStreamManager::writeItem);
}
```

```196:200:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/steps/ParentTxnFactory.java
final var stack = createRootSavepointStack(state);
final var readableStoreFactory = new ReadableStoreFactoryImpl(stack);
final var preHandleResult = preHandleWorkflow.getCurrentPreHandleResult(
        creatorInfo, platformTxn, readableStoreFactory, shortCircuitCallback);
```

```154:186:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/prehandle/PreHandleWorkflow.java
default PreHandleResult getCurrentPreHandleResult(...) {
    // always calls preHandleAllTransactions, even when metadata is already a PreHandleResult
    return preHandleAllTransactions(
            creator, storeFactory, storeFactory.readableStore(ReadableAccountStore.class),
            platformTxn.getApplicationTransaction(), previousResult, shortCircuitCallback);
}
```

```130:144:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/DispatchProcessor.java
// charge → tryHandle → finalize → commit every user dispatch
dispatchUsageManager.finalizeAndSaveUsage(dispatch);
recordFinalizer.finalizeRecord(dispatch);
dispatch.stack().commitFullStack();
```

### 1. Dual stream + wrapped record hashing (config, not CT logic)

Defaults:

```29:30:hedera-node/hedera-config/src/main/java/com/hedera/node/config/data/BlockStreamConfig.java
@ConfigProperty(defaultValue = "BOTH") @NetworkProperty
StreamMode streamMode,
```

```63:67:hedera-node/hedera-config/src/main/java/com/hedera/node/config/data/BlockRecordStreamConfig.java
@ConfigProperty(defaultValue = "true") @NetworkProperty
boolean computeHashesFromWrappedRecordBlocks,
@ConfigProperty(defaultValue = "true") @NetworkProperty
boolean liveWritePrevWrappedRecordHashes) {}
```

So this node wrote **records and blocks**, and hashed wrapped record files **synchronously on the handle thread**.

Of handle samples whose leaf was in PBJ/codec (**1,243**), the first non-codec caller was:

|                          Caller                           | Share of those PBJ samples |
|-----------------------------------------------------------|---------------------------:|
| `WrappedRecordFileBlockHashesCalculator.computeWithItems` |                  **44.3%** |
| `StateValue$StateValueCodec.measureRecord`                |                      15.7% |
| `StateKeyUtils.kvKey`                                     |                       5.6% |
| `BlockStreamBuilder.build`                                |                       4.4% |

`computeWithItems` alone is **~8.6% of all handle samples** (551 / 6,440). That is SHA-384 + protobuf size/write of an entire record file at block close, on the same thread that executes transfers.

`HandleWorkflow` also always builds a **paired** record+block builder under `BOTH`. Allocation site `PairedStreamBuilder.<init>` is **1.3%** of handle allocation weight.

`BlockRecordManagerImpl.endUserTransaction` + `StreamFileProducerConcurrent.writeRecordStreamItems` show up as first app frames (~1.6% + 1.3%). Native `Deflater` is the gzip of those files.

### 2. Pre-handle runs again on the serial thread

`createTopLevelTxn` always calls `getCurrentPreHandleResult`, which **always** calls `preHandleAllTransactions`. Reuse only happens inside `expandAndVerifySignatures` for signature results. Payer lookup, `PreHandleContextImpl` construction, `dispatchPureChecks`, and `dispatchPreHandle` still run.

On this profile that is visible as:

- ~**10%** of handle samples with `PreHandleWorkflowImpl` / `preHandleAllTransactions` as the first application-adjacent frame.
- First CT-layer method: `TransferExecutor.checkFungibleTokenTransfers` **197 samples** (preHandle path), vs `AdjustHbarChangesStep` **141**, vs `CryptoTransferHandler.handle` **12**.

So a large fraction of “CryptoTransfer” CPU on the handle thread is **repeating ingest/prehandle work**, not debiting hbar.

`PreHandleWorkflowImpl` even documents a double account lookup:

```296:301:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/prehandle/PreHandleWorkflowImpl.java
// NOTE: ... we will change the constructor, so I can pass the payer account in directly,
// since I've already looked it up. ... so for now, we do a double lookup. Boo.
context = new PreHandleContextImpl(
        storeFactory, txBody, configuration, dispatcher, transactionChecker, creatorInfo);
```

### 3. `SavepointStack` allocates a wrapper on every state `get()`

`SavepointStackImpl.getWritableStates` caches `WritableStatesStack` per service name. `WritableStatesStack.get` / `getSingleton` do **not** cache:

```62:71:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/stack/WritableStatesStack.java
    public <K, V> WritableKVState<K, V> get(final int stateId) {
        return new WritableKVStateStack<>(this, stateId);
    }

    public <T> WritableSingletonState<T> getSingleton(final int stateId) {
        return new WritableSingletonStateStack<>(this, stateId);
    }
```

Allocation weight on the handle thread:

|              Type / site              |                                         Share of handle alloc weight |
|---------------------------------------|---------------------------------------------------------------------:|
| All `SavepointStack` frames           |                                                            **35.2%** |
| `WritableStatesStack.getSingleton`    |                                                                 4.0% |
| `WritableStatesStack.get`             |                                                                 3.9% |
| `WritableKVStateStack` objects        |                                                                 3.9% |
| `WritableSingletonStateStack` objects | 4.0% (class) / 1.8% (if counting only that class in the class table) |
| `ParentTxnFactory`                    |                                                            **23.1%** |
| `commitFullStack`                     |                                                            **18.5%** |

`getWritableStates` itself is a HashMap `computeIfAbsent` (`SavepointStackImpl` line 340) and `WrappedState.getWritableStates` is **1.3%** of handle leaf samples. `ConfigDataService.getConfigData` is the #1 **HashMap caller** on this thread (259 samples) — a `Class` → config-record lookup on almost every subsystem (fees, throttle, token, block listener).

### 4. What `CryptoTransferHandler.handle` actually costs

Once `tryHandle` calls `dispatchHandle`, the transfer-layer first frames are:

|            First transfer-layer method            | Samples |                     Meaning                      |
|---------------------------------------------------|--------:|--------------------------------------------------|
| `checkFungibleTokenTransfers` (preHandle)         |     197 | Repeat of pre-handle on handle thread            |
| `AdjustHbarChangesStep.modifyAggregatedTransfers` |     141 | Real hbar debit/credit                           |
| `AssessmentResult.<init>` + `assessCustomFees`    | 81 + 63 | Custom-fee machinery even for simple NLG HBAR CT |
| `TransferContextImpl.getAssessedCustomFees`       |      56 | Stream over assessed fees                        |
| `executeCryptoTransfer`                           |      24 | Orchestration                                    |
| `CryptoTransferHandler.handle`                    |      12 | Thin wrapper                                     |
| `AdjustFungibleTokenChangesStep`                  |       5 | Token units (NLG pairwise HBAR is mostly unused) |

The business update is **account `copyBuilder` / `build` + VirtualMap put**. Handle allocation sites: `Account$Builder.build` 1.5%, `Account.copyBuilder` 1.2%, `Codec.toBytes` 1.5%, `StateKeyUtils.kvKey` 2.9% inclusive. Commit then hits `VirtualNodeCache.putLeaf` / `updateKeyAtPath` / `lookupLeafByKey` (~1.8% + 1.6% + 1.3% first-app-frame).

Staking/finalize is smaller but real: `FinalizeRecord` 9.7% inclusive, `StakingRewardsHandlerImpl` 7.6%, and CHM contention on `VirtualMapStateImpl.getReadableStates` from that path (max 44.9 ms on the handle thread).

### 5. Record cache, throttles, enum `.values()`, streams

- `RecordCacheImpl.addRecordSource` + `purgeExpiredReceiptEntries`: **10.6%** inclusive; lots of HashMap/CHM. First app frame ~4.8% combined.
- `DeterministicThrottle.usageSnapshot()`: **1.8%** of handle allocation; `ThrottleUsageSnapshot` objects **1.7%**. `DispatchProcessor` always calls `finalizeAndSaveUsage`.
- `HederaFunctionality.values()`: **1.7%** of handle allocation. `Enum.values()` **clones the array every call**:

```292:300:hedera-node/hapi-utils/src/main/java/com/hedera/node/app/hapi/utils/CommonPbjConverters.java
public static @NonNull HederaFunctionality toPbj(
        @NonNull com.hederahashgraph.api.proto.java.HederaFunctionality function) {
    return HederaFunctionality.values()[requireNonNull(function).ordinal()];
}
public static @NonNull com.hederahashgraph.api.proto.java.HederaFunctionality fromPbj(
        @NonNull final HederaFunctionality function) {
    return com.hederahashgraph.api.proto.java.HederaFunctionality.values()[
            requireNonNull(function).ordinal()];
}
```

- Streams: **21.9%** of handle samples include `java.util.stream`. Callers: `WrappedWritableStates.commit`, `MerkleWritableStates.commit`, `getAssessedCustomFees`, `SimpleFeeCalculatorImpl.getExtraFee`, `RecordStreamBuilder.build`, `BlockRecordManagerImpl.endUserTransaction`. `AbstractPipeline.wrapSink` is a **3.0%** leaf.

### 6. Round-close hashing still sits on the handle thread

`createSignedState` is **10.9%** inclusive of the handle thread. Virtual hasher FJP does the bulk of leaf hashing (1,575 samples, 93% `VirtualHasher`), but the sequential thread still waits / coordinates. That is expected for serial execution; it is not CT logic, but it steals the same core that runs transfers.

## Allocation and GC (why pauses eat TPS)

Handle thread: **46.6% of process allocation weight** (183 GB of 393 GB sampled) from **6.0%** of allocation *events*. That is a small number of threads allocating huge volume.

Rough rate: 183 GB / ~450 s ≈ **400 MB/s** on one thread (higher during the NLG window). Young GCs every few hundred ms at 15–16 GB, then Full GC:

|                  GC fact                   |                        Value                         |
|--------------------------------------------|------------------------------------------------------|
| Total pause                                | **33.8 s** in 450 s                                  |
| Pause P50 / P90 / P99 / max                | 19.3 ms / 62 ms / **1.17 s** / **1.24 s**            |
| Full-GC-style “Phase 1: Mark live objects” | **22** pauses, avg **634 ms**, total **13.9 s**      |
| `jdk.EvacuationFailed`                     | **1,257**                                            |
| `jdk.ConcurrentModeFailure`                | **18**                                               |
| Heap at end of load                        | pinned at **16 GB** max; old collections 1.07–1.24 s |

G1 is losing: to-space evacuation failure → Full GC. Every 1.2 s pause is a multi-thousand-txn hole at 10k TPS.

Top handle allocation classes: `byte[]`, `Object[]`, **`WritableSingletonStateStack`**, **`WritableKVStateStack`**, `LinkedHashMap`, `stream.ReferencePipeline$Head`, `HashMap`, lambdas from `WrappedWritableStates.commit`, `ThrottleUsageSnapshot`, `HederaFunctionality[]` (from `.values()`), `Account`.

`DirectMethodHandle.allocateInstance` is **9.0%** of handle allocation weight (PBJ records / generated ctors).

## Contention and exceptions (not the handle limiter, but noisy)

`jdk.JavaMonitorEnter`: 1,493 events, 2 m 31 s total blocked.

Hottest **ingest** locks (gRPC workers, avg ~35–52 ms, max ~1.2 s — aligned with Full GC):

- `SubmissionManager.submit` (783)
- `SynchronizedThrottleAccumulator.shouldThrottle` txn (156) and query (81)

Handle-thread monitor enter is rare (6 events, max 44.9 ms) on `VirtualMapStateImpl.getReadableStates` from staking.

`jdk.JavaExceptionThrow`: **98,163**. Almost all:

- `ResourceLeakDetector$DefaultResourceLeak.close` 56,300
- `ResourceLeakDetector.track0` 41,644

Netty leak detection is on. That is useless cost under NLG and pollutes exception profiles. Disable for the next recording.

## What “CryptoTransfer” means in this profile

NLG pairwise HBAR transfers on serial handle spend time in this order of magnitude:

```
~17%  commitFullStack + VirtualMap cache + PBJ StateValue encode
~14%  ParentTxnFactory / savepoint / store factories
~13%  CryptoTransferHandler (much of it preHandle + custom-fee scaffolding)
~11%  createSignedState (round hash, not CT)
~11%  RecordCache
~10%  preHandle re-entry on handle
~ 9%  FinalizeRecord / staking
~ 9%  WrappedRecordFileBlockHashesCalculator (BOTH + live WRB)
~ 8%  Block stream write + boundary listener
~ 4%  Config HashMap
rest  HashMap/CHM/streams that cut across the above
```

Improving `AdjustHbarChangesStep` alone will not move TPS. The next profile should show those scaffolding slices shrink.

---

## Top 5 things to do, then take another profile

Do these in order. (1) and (5) are config/JVM only — no code — and should be in the **next** recording even if (2)–(4) slip.

### 1. Run the load as block-stream only (drop dual record path)

**Why:** Default `streamMode=BOTH` plus `liveWritePrevWrappedRecordHashes=true` puts record-file protobuf + SHA-384 on the handle thread (~8.6% of handle samples in `WrappedRecordFileBlockHashesCalculator` alone), allocates `PairedStreamBuilder` per txn, and gzip-writes `.rcd` files.

**Change** (node properties for `:app:run`):

```properties
blockStream.streamMode=BLOCKS
hedera.recordStream.liveWritePrevWrappedRecordHashes=false
```

Keep `execution.parallelCryptoTransfer.enabled=false` so this stays a serial baseline.

**What should move in the next JFR:** handle inclusive `WrappedRecordFileBlockHashesCalculator` → ~0; `PairedStreamBuilder` alloc gone; `BlockRecordManagerImpl.endUserTransaction` / `StreamFileProducerConcurrent` down; native `Deflater` down; handle samples in `pbj` down.

### 2. Fast-path `getCurrentPreHandleResult` when metadata is already `SO_FAR_SO_GOOD`

**Why:** Handle thread re-enters `preHandleAllTransactions` for every txn (~10% of handle CPU, and the largest CT-layer leaf is `checkFungibleTokenTransfers`). Ingest/prehandle already did that work on FJP/gRPC.

**Change:** If `platformTxn.getMetadata()` is a `PreHandleResult` with `status == SO_FAR_SO_GOOD` and a non-null `txInfo`, skip `dispatchPreHandle` / `dispatchPureChecks` / `PreHandleContextImpl`. Keep a cheap payer-still-exists check if that is required for correctness. Do not rebuild expanded keys unless the previous result is missing or failed.

**What should move:** `PreHandleWorkflowImpl` / `checkFungibleTokenTransfers` on `<scheduler TransactionHandler>` should collapse. `ParentTxnFactory.createTopLevelTxn` inclusive should drop well below 13.7%. Platform FJP prehandle share can stay high — that is the right place for it.

### 3. Cache `WritableKVStateStack` / singleton / queue on `WritableStatesStack`

**Why:** Every `states.get(ACCOUNTS)` allocates a new adapter. That is **~8%** of handle allocation weight on `get`/`getSingleton` alone and feeds HashMap/`getWritableStates` leaf time. Same pattern as the already-cached `writableStatesMap` on `SavepointStackImpl`.

**Change:** Cache stacks by `stateId` on `WritableStatesStack` (identity of the current savepoint frame). Optionally cache `ReadonlyStatesWrapper` in `getReadableStates` instead of `new` every call.

**What should move:** `WritableKVStateStack` / `WritableSingletonStateStack` allocation share; `DirectMethodHandle.allocateInstance`; handle alloc weight (183 GB) and young-GC frequency.

### 4. Cut per-txn garbage that is not required for a simple HBAR CT

Highest-signal, still-serial items (do not have to land all of them before the next JFR):

|                               Item                               |                         Evidence                         |                               Direction                               |
|------------------------------------------------------------------|----------------------------------------------------------|-----------------------------------------------------------------------|
| `HederaFunctionality.values()` / protobuf enum `.values()`       | 1.7% handle alloc                                        | Static `HederaFunctionality[]` / ordinal map in `CommonPbjConverters` |
| `AssessmentResult` for HBAR-only CT                              | 2.9% handle alloc inclusive; 81+63 samples               | Skip custom-fee assessment when the op has no tokens                  |
| `DeterministicThrottle.usageSnapshot()` every dispatch           | 1.8% handle alloc                                        | Snapshot on block/round boundary, not per CT                          |
| Stream API in `commit` / `getExtraFee` / `getAssessedCustomFees` | 21.9% handle samples include streams; `wrapSink` 3% leaf | Indexed loops over tiny maps                                          |
| `RecordCacheImpl` HashMap + purge per txn                        | 10.6% inclusive                                          | Batch purge; cheaper index structure                                  |
| `ConfigDataService.getConfigData`                                | #1 HashMap caller on handle                              | Thread-local / handle-scoped snapshot of the few records CT needs     |

This is also the lever for GC: 400 MB/s → Full GC at 16 GB. Reducing alloc is more important than `-Xmx` alone.

**What should move:** handle allocation GB; `EvacuationFailed` count; Full GC count; pause P99; `HashMap.getNode` leaf %.

### 5. Next recording: disable Netty leak detection and pin the heap

**Why:** 98k exception throws from `ResourceLeakDetector`. Heap grew from 1 GB → 16 GB during the run, then 1.2 s Full GCs. The last profile mixed **warmup + resize + Full GC** with the CT hot path.

**JVM / Netty (same Temurin 25, attach with `jcmd` as before):**

```text
-Dio.netty.leakDetection.level=DISABLED
-Xms12g -Xmx12g
-XX:+AlwaysPreTouch
```

12 GB is a suggestion for this machine if 16 GB was thrashing; if RSS allows, `12g` or `16g` is fine as long as **Xms == Xmx** and pretouch so the NLG window is not the first time G1 expands.

**JFR (after the node is up, before NLG, or shortly after NLG starts):**

```bash
jcmd <PID> JFR.start name=ct-serial-2 settings=profile dumponexit=true filename="$PWD/node-profile-2.jfr"
# run the same NLG CryptoTransfer command for a fixed window (e.g. 5 minutes of steady 10k+)
jcmd <PID> JFR.dump name=ct-serial-2 filename="$PWD/node-profile-2.jfr"
jcmd <PID> JFR.stop name=ct-serial-2
```

Use an **absolute** `filename`. Do not rely on Gradle `-PjvmArgs` (the `:app:run` task does not apply it). Prefer `JFR.dump` at the end of the load window so shutdown is not in the file.

Parse the follow-up with stack depth 64:

```bash
jfr view --width 200 --cell-height 20 hot-methods node-profile-2.jfr
jfr view --width 200 gc-pauses node-profile-2.jfr
jfr view --width 200 allocation-by-thread node-profile-2.jfr
jfr print --events jdk.ExecutionSample --stack-depth 64 node-profile-2.jfr | rg "TransactionHandler" | wc -l
```

### Comparison checklist (profile 1 → profile 2)

|                       Metric                       | This recording  |                            Target after (1)+(5), even without code                             |
|----------------------------------------------------|-----------------|------------------------------------------------------------------------------------------------|
| Handle thread share of execution samples           | 15.0%           | Similar or higher (less competing gzip/WRB); TPS up if handle does more useful work per second |
| `WrappedRecordFileBlockHashesCalculator` on handle | ~8.6%           | ~0 with `streamMode=BLOCKS`                                                                    |
| `CryptoTransferHandler` inclusive on handle        | 13.0%           | **Up** as a fraction if scaffolding drops (handler is not the waste)                           |
| `preHandleAllTransactions` on handle               | ~10%            | Near 0 after (2)                                                                               |
| `commitFullStack` inclusive                        | 16.9%           | Down after (3)/(4)                                                                             |
| Handle alloc weight                                | 183 GB / 46.6%  | Down sharply after (3)/(4)                                                                     |
| GC pause total / P99                               | 33.8 s / 1.17 s | Young GC only, P99 ≪ 100 ms                                                                    |
| `EvacuationFailed`                                 | 1,257           | 0                                                                                              |
| `ResourceLeakDetector` exceptions                  | ~98k            | 0                                                                                              |
| Platform TPS (Grafana)                             | ~10k+ (user)    | Higher wall TPS for the **same** NLG offered load, or same TPS at lower handle CPU             |

If after (1) and (5) TPS is still flat, the next JFR should show `commitFullStack` + HashMap + `ParentTxnFactory` as the remaining serial bulk — that is (2)–(4).
