# Serial CryptoTransfer JFR analysis (`node-profile-13.jfr`)

Follow-up to [`serial-crypto-transfer-jfr-analysis-12.md`](serial-crypto-transfer-jfr-analysis-12.md). Same NLG CryptoTransfer shape, **serial handle**, after P12 items 1 and 3: Token singleton-only commit at block close (`commitSingleton`) and reused `WritableStoreFactory` / `TokenServiceApi` across sequential parent txns. NLG: **17,468 TPS** (`5,240,630` transfers in 300 s) — **+0.5%** vs P12 (17,389), **+1.3%** vs P9 (17,237). New series high. No `FAIL_INVALID` / account `1002`.

Recording: 43 MB, JFR 2.1, **2026-08-31 01:27:32 UTC, 391 s**. Temurin 25.0.2, PID 9108, `:app:run` 16g pin. How it was read: `jfr summary` / `jfr view`, then `jfr print --stack-depth 48` on `jdk.ExecutionSample` (45,735 events) and `--stack-depth 24` on `jdk.ObjectAllocationSample` (75,890 events, weighted). Also `jdk.ThreadPark` on `<scheduler TransactionHandler>` and `jdk.ThreadCPULoad`. File was already dumped (42 MB at 21:34).

## Bottom line

**P13 did what it was supposed to on the handle mix. TPS barely moved because the Token `putLeaf` work stayed on the same thread.** `NodeFeeManager.onCloseBlock` is gone. Factory reuse zeroed `getStore` / `WritableAccountStore` construction. Account leaves now flush in `VirtualMapStateImpl` copy → `flushPendingWrites()` (13.1% of handle), still on `<scheduler TransactionHandler>` at the block-boundary `copyMutableState()`.

**Overall NLG TPS is still limited by the serial handle / round pipeline, not by ingest or prehandle CPU.** Ingest and prehandle use many threads and show up as most of the JVM’s on-CPU samples. They are not the rate limiter. Handle is one `SEQUENTIAL_THREAD`. It is busy applying a round, then it blocks in `LinkedBlockingQueue.take()` waiting for the next consensus round. Ingest is already in backpressure (`SubmissionManager.submit` waits up to 1.20 s).

1. **Token flush left `onCloseBlock` and landed on state copy.** `NodeFee` **0.3%** (P12 13.9%). `MerkleWritableStates.commit` **2.2%** (P12 16.5%). `BlockStreamManager` **19.8%** (P12 33.2%) — almost all `commitReceipts` **16.0%**. `flushPendingWrites` **13.1%**, caller `VirtualMapStateImpl.<init>` (copy).
2. **Factory reuse landed.** `WritableStoreFactory.getStore` **0.8%** CPU / **0** alloc (P12 6.0% / 6.0%). `WritableAccountStore` class **0**. `TokenServiceApiImpl` ctor **0**.
3. **Handle is not a pegged core.** Load-minute parks on handle: **30–44 s / 60 s** in `SequentialThreadTaskScheduler.run()` → `LinkedBlockingQueue.take()`. Max park **1.37 s** matches GC P99. SequentialTask waits are tiny (28 parks, 1.2 s).
4. **Ingest / prehandle are the CPU majority, not the TPS ceiling.** `grpc` **50.6%** (ingest 78%, sig 59%). `platformForkJoin` **27.0%** (prehandle 90%). `ConsensusEngine` **0.2%**. One handle thread is **10.3%** of JVM samples and hotter than any single grpc worker.
5. **G1 is the tight 16g series.** STW **53.3 s / 391 s (13.6%)**, P99 **1.27 s**. Same band as P10/P12.

```134:137:platform-sdk/swirlds-state-impl/src/main/java/com/swirlds/state/merkle/VirtualMapStateImpl.java
    protected VirtualMapStateImpl(@NonNull final VirtualMapStateImpl from) {
        from.flushPendingWrites();
        this.virtualMap = from.virtualMap.copy();
```

```177:180:platform-sdk/consensus-wiring-framework/src/main/java/org/hiero/consensus/wiring/framework/schedulers/internal/SequentialThreadTaskScheduler.java
            if (tasks.drainTo(buffer, BUFFER_SIZE) == 0) {
                try {
                    final SequentialThreadTask task = tasks.take();
                    buffer.add(task);
```

## Is TPS still limited by the handle thread?

**Yes — by the serial handle + consensus-round handoff, not by ingest/prehandle running out of CPU.**

Lifecycle on this recording:

| Stage | Threads | On-CPU share | What it means for 17k TPS |
|-------|---------|-------------:|---------------------------|
| Ingest + sigverify | many `grpc-nio-worker-*` | **50.6%** of samples (78% ingest-ish, 59% sig) | Parallel. Already waits on `SubmissionManager.submit` (390 waits, max **1.20 s**). Faster ingest would queue, not raise TPS. |
| Prehandle | `platformForkJoinThread-*` | **27.0%** (90% prehandle) | Parallel, ahead of handle. Handle `preHandleAllTransactions` is still **6.1%** (reuse miss / fallback), not the limiter. |
| Consensus engine | `<scheduler ConsensusEngine>` | **0.2%** | Not the on-CPU cost. It *paces* handle: handle’s next task is a `ConsensusRound`. |
| Handle / apply | `<scheduler TransactionHandler>` one `SEQUENTIAL_THREAD` | **10.3%** of JVM samples | Serial apply + block-boundary `copyMutableState()`. CT handler **10.6%** of that thread. |
| State hash | `VirtualHasherForkJoinThread-*` | **4.5%** | Parallel, after copy. `hashCopy` max **1.16 s** (aligned with GC). |

Handle wall time during the load minutes is split:

- **~50% parked** in `tasks.take()` — no round on the sequential queue. Long parks (**1.2–1.37 s**) are GC STW, not empty-queue. Average park **~47 ms** is the wait between rounds (plus GC).
- **~50% running** — apply the round (CT + scaffolding + `commitReceipts`) and, on a block boundary, `copyMutableState()` → `flushPendingWrites()` (**13.1%** of handle on-CPU).

So the execution layer as a *whole* (ingest + prehandle + handle) is where the CPU goes. The **rate** is set by how fast consensus can deliver rounds times how much serial work handle does per round. Ingest/prehandle have spare parallelism. Handle does not.

Making handle apply cheaper still raises TPS if a round is still on the thread when the next one arrives, or if copy/flush delays the next `take()`. Making ingest or prehandle cheaper frees host CPU (this box is **96–99%** in the peak minutes) and shortens submit waits; it does not remove the serial apply.

```368:378:platform-sdk/consensus-transaction-handling/src/main/java/org/hiero/consensus/transaction/handling/internal/DefaultTransactionHandler.java
        if (isBoundary || freezeRoundReceived) {
            // ...
            handlerMetrics.setPhase(GETTING_STATE_TO_SIGN);
            stateLifecycleManager.copyMutableState();
```

## Recording shape

| Item | P12 | **P13 (singleton commit + factory reuse)** |
|------|----:|-------------------------------------------:|
| NLG TPS (300 s) | 17,389 | **17,468** |
| Heap | 16=16 GB | **16=16 GB, pretouch** |
| Heap used | 2.3–16 (p50 14.9) | **2.2–16 / 16** (p50 15.0, last 14.1) |
| Duration | 517 s | **391 s** |
| Execution samples | 46,867 | **45,735** |
| Handle samples | 10.8% | **10.3%** |
| Handle alloc | 89.3 GB / 29.8% | **76.8 GB / 26.8%** |
| Handle alloc rate (file) | ~173 MB/s | **~196 MB/s** |
| GC pause total / % file | 53.7 s / 10.4% | **53.3 s / 13.6%** |
| Pause P50 / P99 / max STW | 27 / 1220 / 1290 ms | **26 / 1270 / 1290 ms** |
| `EvacuationFailed` | 957 | **870** |
| `ConcurrentModeFailure` | 34 | **33** |
| Old GC events | 105 | **105** |
| JVM user avg / max (file) | 22.6% / 74.5% | **30.5% / 79.8%** |
| JVM user (NLG window) | 36.2% (21:06–21:11) | **33.8%** (21:28–21:33); peak min **40–45%** |
| Machine total (NLG window) | 97.6% | **96.5% / 100%** |
| Leak detector | 0 | **0** |
| `SubmissionManager.submit` max | 1.16 s | **1.20 s** |
| `hashCopy` max | 1.19 s | **1.16 s** |

P13’s file is shorter (less idle than P12). Compare peak minutes 21:30–21:32, not the file-wide JVM-user average.

## Where CPU went

| Thread group | P12 | **P13** |
|--------------|----:|--------:|
| `grpc-nio-worker-*` | 49.4% (ingest 77%, sig 58%) | **50.6%** (ingest 78%, sig 59%) |
| `platformForkJoinThread-*` | 27.9% (prehandle 84%) | **27.0%** (prehandle 90%) |
| `<scheduler TransactionHandler>` | 10.8% | **10.3%** |
| `VirtualHasherForkJoinThread-*` | 4.2% | **4.5%** |
| `<scheduler ConsensusEngine>` | — | **0.2%** |

### Handle thread (4,700 samples)

| Marker | P12 | **P13** |
|--------|----:|--------:|
| `SavepointStack` | 13.4% | **14.2%** |
| `VirtualMap` | 24.4% | **23.5%** |
| `commitFullStack` | 6.7% | **6.8%** |
| CHM / `java.util.HashMap` | 21.3 / 16.3 | **21.0 / 16.8** |
| `FinalizeRecord` | 9.8% | **9.7%** |
| `RecordCacheImpl` | 21.5% | **22.3%** (add 5.0%, purge 11.5%, receipts 16.0%) |
| `dropExpiredPayerBuckets` | 10.8% (`isEmpty` 8.1%) | **11.5%** (`isEmpty` **8.2%**) |
| `ImmediateStateChangeListener` | 19.4% | **19.1%** |
| `BlockStreamManager` | 33.2% | **19.8%** |
| `CryptoTransferHandler` | 11.0% | **10.6%** |
| `AdjustHbarChangesStep` | 8.2% | **8.7%** |
| `preHandleAllTransactions` | 5.8% | **6.1%** |
| `WrappedState` / `WrappedWritableStates` | 8.7 / 8.3 | **9.1 / 8.5** |
| `putLeaf` | 7.3 / 0.6 first | **6.3%** incl / **1.3%** first-app |
| `MerkleWritableStates.commit` | 16.5% | **2.2%** |
| `commitInStateIdOrder` | 20.7% | **19.7%** |
| `NodeFeeManager.onCloseBlock` | 13.9% | **0.3% / 0** |
| `flushPendingWrites` / `flushToDataSource` | — | **13.1% / 13.1%** |
| `WritableStoreFactory.getStore` | 6.0% | **0.8%** |
| `resetForNextUserTxn` / `createRootBaseBuilder` | 2.4 / 0.8 | **2.4% / 1.0%** |
| `BlockStreamBuilder.<init>` | 0 | **0** |
| `writeStateChanges` / `getStateChanges` | 0 | **0** |

BSM split: `commitReceipts` **81%** of BSM (16.0% handle), `writeItem` **18.6%** (3.7% handle), NodeFee **0**.

First-app: `PayerTxnIndex.isEmpty` **8.2%**, `Arrays.copyOf(byte[])` **4.1%**, `HashMap.getNode` **7.2%**, `putLeaf` **1.3%**.

## Allocation

Handle **76.8 GB / 391 s ≈ 196 MB/s**. Factory reuse holds: `getStore` **0**, `WritableAccountStore` class **0**, `TokenServiceApiImpl` **0**. Builder reuse holds: `BlockStreamBuilder.<init>` **0**.

| Site | P12 | **P13** |
|------|----:|--------:|
| `WritableStoreFactory.getStore` | 6.0% | **0** |
| `WritableAccountStore` (class) | 0.7% | **0** |
| `BlockStreamBuilder.<init>` | 0 | **0** |
| `resetForNextUserTxn` | 2.7% | **2.7%** |
| `usageSnapshot()` | 6.3% | **7.8%** |
| `ThrottleUsageSnapshot` (class) | 5.4% | **6.5%** |
| `HashMap.resize` (handle) | 4.0% | **2.8%** |
| `ParallelTask` / `SequentialTask` (class) | 0.9 / 1.4 | **1.8 / 2.5** |

Top handle classes: `ThrottleUsageSnapshot` **6.5%**, `Object[]` 4.7%, `Account` 4.3%, `byte[]` 3.7%, `ArrayList` 3.4%, `Account$Builder` 3.3%. The 16.4% `WritableAccountStore` *site* is store methods (mutates), not construction.

## JVM series so far

```
P1  BOTH + leak on + 1→16g      ~10k+ TPS, WRB + re-prehandle + streams
P2  BLOCKS + leak on + 1→16g     14,024
P3  BLOCKS + leak off + 1→16g    16,551
P4  BLOCKS + leak off + 12g pin  13,103  ← heap too small (do not compare)
P5  BLOCKS + leak off + 16g pin  16,922  ← JVM baseline
P6  P5 + SavepointStack reuse    16,511
P7  P6 + WrappedState reuse      16,767
P8  P7 + record-cache index      16,986
P9  P8 + list expiry + get-then-put
    (soft-commit reverted)       17,237
P10 P9 + wrap soft-commit
    + store cache                16,973
P11 P10 + builder reuse
    + StateChanges copy cuts     14,307  ← host CPU (do not compare)
P12 P11 binary, quieter window   17,389
P13 P12 + singleton Token commit
    + factory / Token API reuse  17,468  ← NodeFee gone; flush moved to copy()
```

---

## Top 5 next

### 1. Get account-leaf flush off the handle copy, or batch it

**Why:** `flushPendingWrites` **13.1%** on handle is the old NodeFee `putLeaf`. `copyMutableState()` still runs on `TransactionHandler` at a block boundary.

**What to do:** keep dirty account KV until a hasher/pipeline thread copies, or flush only changed state IDs. Do not put a full `MerkleWritableStates.commit()` back on `onCloseBlock`.

### 2. Throttle snapshots at round/block

**Why:** `ThrottleUsageSnapshot` is the top handle class again (**6.5%**). `usageSnapshot()` **7.8%** of handle alloc.

### 3. Receipt-queue merkle commit / `commitReceipts`

**Why:** `commitReceipts` **16.0%**. Now the largest `BlockStreamManager` slice. True `writeItem` is **3.7%**.

### 4. Skip the purge walk when `livePayerIndexes.size() == 1`

**Why:** `isEmpty` **8.2%** first-app, same as P10/P12. Optional. Do not put `removeIf` back.

### 5. Do not optimize ingest/prehandle for TPS

**Why:** They are **78%** of JVM samples and already backpressured. Host is **96–99%** in the peak minutes; cutting ingest CPU helps GC and submit waits, not the serial apply. Only touch ingest if the goal is host headroom.

| Metric | P12 | **P13** | After (1) |
|--------|----:|--------:|----------:|
| NLG TPS | 17,389 | **17,468** | up if copy/flush leaves handle |
| JVM user (load window) | 36.2% | **33.8%** (peak 40–45) | stay |
| `NodeFee` / `MerkleWritableStates.commit` | 13.9 / 16.5 | **0.3 / 2.2** | stay |
| `flushPendingWrites` | — | **13.1%** | down |
| `getStore` / AccountStore class | 6.0 / 0.7 | **0.8 / 0** | stay 0 |
| `commitReceipts` | 15.8% | **16.0%** | stay |
| `isEmpty` first-app | 8.1% | **8.2%** | stay |
| Handle park (load min) | — | **~50%** in `take()` | stay unless rounds arrive faster |
| Leak / WRB / wrap ctors | 0 | **0** | stay 0 |
