# Serial CryptoTransfer JFR analysis (`node-profile-11.jfr`)

Follow-up to [`serial-crypto-transfer-jfr-analysis-10.md`](serial-crypto-transfer-jfr-analysis-10.md). Same NLG CryptoTransfer shape, **serial handle**, after P10 builder reuse + `StateChanges` copy cuts: root `BlockStreamBuilder` reset across sequential user txns, `ImmediateStateChangeListener.getStateChanges()` without `LinkedList`, `writeStateChanges(List)` + `takeKv`/`takeQueue` instead of `writeItem(Function)` + `new ArrayList<>(changes)`. NLG: **14,307 TPS** (`4,292,362` transfers in 300 s) — **−15.7%** vs P10 (16,973), **−16.9%** vs P9 (17,237). No `FAIL_INVALID` / account `1002`.

Recording: 36 MB, JFR 2.1, **2026-08-31 00:46:34 UTC, 448 s**. Temurin 25.0.2, PID 30313, `:app:run` (default 16g pin). How it was read: `jfr summary` / `jfr view`, then `jfr print --stack-depth 48` on `jdk.ExecutionSample` (41,676 events) and `--stack-depth 24` on `jdk.ObjectAllocationSample` (91,120 events, weighted). The file was **0 B** until `JFR.dump` from the still-running JVM (no `jdk.Shutdown`).

## Bottom line

**Do not treat 14,307 as a P11 code regression.** The handle mix is P10. Builder ctor and the `writeItem(Function)` / `LinkedList` copies are gone. GC is better, not worse. The node got less CPU while the machine sat at ~95% during the NLG window.

P10 peak minutes were **36–43% JVM user** on a **98%** machine. P11’s NLG minutes (20:48–20:52 local) were **21–28% JVM user** on a **92–98%** machine. TPS ratio **14,307 / 16,973 = 0.84**. Load-window JVM-user ratio **25.2 / ~34 ≈ 0.74–0.87** depending on which P10 minutes you call “load”. That is the drop.

1. **P11 cuts landed.** `BlockStreamBuilder.<init>` **0**. `writeStateChanges` / `takeKvStateChanges` / `getStateChanges` **0** handle samples. `createRootBaseBuilder` **0.6%**. `resetForNextUserTxn` **2.0%** CPU / **2.8%** handle alloc.
2. **Handle shape is P10.** `commitFullStack` **6.6%** (P10 7.0%), wrap **8.0 / 7.5** (P10 9.2 / 8.7), `putLeaf` incl **6.9%** (P10 6.3%), NodeFee block-close **13.3%** (P10 13.2%), CT handler **10.6%** (P10 10.7%).
3. **GC improved because throughput fell.** STW **22.9 s / 448 s (5.1%)** vs P10 **12.3%**. `EvacuationFailed` **586** (P10 902). `ConcurrentModeFailure` **7** (P10 28). Not a win to keep.
4. **`PayerTxnIndex.isEmpty` 13.7% first-app** is composition: same once-per-second purge over fewer txns/s. Inclusive `dropExpiredPayerBuckets` **16.0%** (P10 12.0%).
5. **Re-run P11 on a quiet box** (or the same Cursor/JMH/WindowServer load as P10’s 11:31–11:33) before changing more handle code.

```736:750:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/stack/SavepointStackImpl.java
    private StreamBuilder createRootBaseBuilder(final int maxSerializedTraceDataBytes) {
        if (reusableRootBuilder == null) {
            reusableRootBuilder = switch (streamMode) {
                case RECORDS ->
                    new RecordStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER, maxSerializedTraceDataBytes);
                case BLOCKS ->
                    new BlockStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER, maxSerializedTraceDataBytes);
                case BOTH ->
                    new PairedStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER, maxSerializedTraceDataBytes);
            };
        } else {
            reusableRootBuilder.resetForNextUserTxn();
        }
        peek().addFollowingOrThrow(reusableRootBuilder);
        return reusableRootBuilder;
    }
```

True `writeItem` / `addItem` is still **~3%** of handle. `BlockStreamManager` **38.1%** is still mostly `commitReceipts` (**21.2%**) + NodeFee Token flush (**13.3%**).

## Recording shape

|              Item              | P10 (soft-commit + store cache) | **P11 (builder reuse + StateChanges cuts)** |
|--------------------------------|--------------------------------:|--------------------------------------------:|
| NLG TPS (300 s)                |                          16,973 |                                  **14,307** |
| Heap                           |            16=16 GB, pretouch |                      **16=16 GB, pretouch** |
| Heap used                      |      1.8–16 / 16 (p50 14.9) |              **0.07–16 / 16** (p50 14.3, last 8.7) |
| Duration                       |                           391 s |                                   **448 s** |
| Execution samples              |                          46,989 |                                  **41,676** |
| Handle samples                 |                           11.4% |                                   **10.5%** |
| Handle alloc                   |                 83.7 GB / 30.5% |                         **75.9 GB / 28.2%** |
| Handle alloc rate (file)       |                       ~219 MB/s |                               **~169 MB/s** |
| GC pause total / % file        |                  48.0 s / 12.3% |                          **22.9 s / 5.1%** |
| Pause P50 / P99 / max STW      |             28 / 1280 / 1710 ms |                     **29 / 1120 / 1170 ms** |
| `EvacuationFailed`             |                             902 |                                     **586** |
| `ConcurrentModeFailure`        |                              28 |                                       **7** |
| Old GC events                  |                              92 |                                      **51** |
| JVM user avg / max (file)      |                   28.9% / 77.3% |                           **19.7% / 74.3%** |
| JVM user avg (NLG window)      |              ~34% (11:30–11:34) |                       **25.2% (20:48–20:53)** |
| Machine total avg / max (file) |                   89.1% / 100% |                           **79.4% / 100%** |
| Machine total (NLG window)     |              ~98% (11:30–11:34) |                       **94.4% / 100%** |
| Leak detector                  |                               0 |                                       **0** |
| `SubmissionManager.submit` max |                          1.30 s |                                  **1.13 s** |
| `hashCopy` max                 |                          1.78 s |                                   **151 ms** |

P11’s file-wide JVM-user average is pulled down by ~2 min of warmup (20:46–20:47) and cooldown after 20:53. Compare the NLG minutes.

## Where CPU went

|           Thread group           |                          P10 |                          **P11** |
|----------------------------------|-----------------------------:|---------------------------------:|
| `grpc-nio-worker-*`              | 49.3% (ingest 77%, sig 59%) | **61.8%** (ingest 73%, sig 54%) |
| `platformForkJoinThread-*`       |       27.1% (prehandle 89%) |       **18.4%** (prehandle 78%) |
| `<scheduler TransactionHandler>` |                       11.4% |                       **10.5%** |
| `VirtualHasherForkJoinThread-*`  |                        4.3% |                        **2.6%** |

Ingest share rose because the handle thread processed fewer txns/s, not because ingest got a new hot path. `SubmissionManager.submit` still 7,669 waits, max 1.13 s (P10 1.30 s).

### Handle thread (4,362 samples)

|                  Marker                  |                                                       P10 |                                                       **P11** |
|------------------------------------------|----------------------------------------------------------:|--------------------------------------------------------------:|
| `SavepointStack`                         |                                                     12.8% |                                                     **13.2%** |
| `VirtualMap`                             |                                                     24.1% |                                                     **24.6%** |
| `commitFullStack`                        |                                                      7.0% |                                                      **6.6%** |
| CHM / `java.util.HashMap`                |                                               20.2 / 17.0 |                                               **18.2 / 13.3** |
| `FinalizeRecord`                         |                                                     10.3% |                                                      **8.4%** |
| `RecordCacheImpl`                        | 23.1% (add 4.7%, purge 12.0%, receipts 17.1%) | **26.0%** (add 3.4%, purge 16.0%, `hasDuplicate` 1.4%, receipts 21.2%) |
| `dropExpiredPayerBuckets`                |                             12.0% (leaf `isEmpty` 8.5%) |                    **16.0%** (leaf `PayerTxnIndex.isEmpty` 13.7%) |
| `VirtualNodeCache`                       |                                                      9.1% |                                                      **8.7%** |
| `ImmediateStateChangeListener`           |                                                     19.9% |                                                     **24.1%** |
| `BlockStreamManager`                     |                                                     33.6% |                                                     **38.1%** |
| `CryptoTransferHandler`                  |                                                     10.7% |                                                     **10.6%** |
| `AdjustHbarChangesStep`                  |                                                      8.3% |                                                      **7.7%** |
| `preHandleAllTransactions`               |                                                      5.0% |                                                      **4.4%** |
| `WrappedState` / `WrappedWritableStates` |                                                 9.2 / 8.7 |                                                **8.0 / 7.5** |
| `putLeaf`                                |                                    6.3% incl / 3.1% first |                           **6.9%** incl / **0.4%** first-app |
| `MerkleWritableStates.commit`            |                          15.3% (NodeFee 13.2%, receipts 2.0%) | **15.7%** (NodeFee 13.3%, receipts in 21.2% `commitReceipts`) |
| `commitInStateIdOrder`                   |                                                     19.7% |                                                     **19.9%** |
| `NodeFeeManager.onCloseBlock`            |                                                     13.2% |                                                     **13.3%** |
| `resetForNextUserTxn` / `createRootBaseBuilder` |                                       — |                                              **2.0% / 0.6%** |
| `BlockStreamBuilder.<init>`              |                                          (in createRoot) |                                                         **0** |
| `writeStateChanges` / `getStateChanges`  |                                          (Function+copy) |                                                         **0** |
| wrap / stack `.<init>` / `newRootStack`  |                                                 0 / 0 / 0 |                                                 **0 / 0 / 0** |
| WRB / streams / leak                     |                                                         0 |                                                         **0** |

Of 1,664 handle `BlockStreamManager` samples: **926** `commitReceipts` (55.6%), **581** `onCloseBlock` / NodeFee (34.9%), **144** `writeItem` (8.7%). Same split as P10; the inclusive % rose because handle did fewer CT bodies per second.

First-app frames: `PayerTxnIndex.isEmpty` **13.7%**, `Arrays.copyOf(byte[])` **4.8%** (PBJ `WritableStreamingData` → VirtualMap key encode / NodeFee `putLeaf`), `HashMap.getNode` **5.2%**, `IdentityHashMap.get` **2.9%**, `dropExpiredPayerBuckets` **2.1%**.

`Arrays.copyOf` is not a new P11 list-copy. 209 of 231 first-app hits are `ByteArrayOutputStream.ensureCapacity` under `StateKeyUtils.kvKey` → `getOriginalValue` / NodeFee `commit`.

## Allocation

Handle **75.9 GB / 448 s ≈ 169 MB/s** (P10 ~219). File-wide rate is diluted by warmup/cooldown. Builder reuse did what it was supposed to: `BlockStreamBuilder` class **0.5%** of handle alloc, ctor **0**. `resetForNextUserTxn` **2.8%** (new `LinkedList`s / `TransactionResult.Builder` / replaced `stateChanges` list so in-flight items keep the old one).

|                   Site                   |     P10 |      **P11** |
|------------------------------------------|--------:|-------------:|
| `WrappedState.<init>` / wrap states ctor |       0 |        **0** |
| `BlockStreamBuilder.<init>`              |     — |        **0** |
| `resetForNextUserTxn`                    |     — |     **2.8%** |
| `writeStateChanges` / `getStateChanges`  |     — |        **0** |
| `DirectMethodHandle.allocateInstance`    |    1.9% |     **2.1%** |
| `usageSnapshot()`                        |    7.6% |     **6.7%** |
| `WritableStoreFactory.getStore`          |    4.8% |     **5.1%** |
| `WritableAccountStore` (class)           |   0.41% |     **0.6%** |
| `HashMap.resize` (handle)                |    3.2% |     **4.4%** |
| `ThrottleUsageSnapshot` (class)          |    6.9% |     **6.0%** |
| `ParallelTask` / `SequentialTask` (class)|     — | **0.7 / 1.7** |

Top handle classes: `ThrottleUsageSnapshot` **6.0%**, `Object[]` 4.6%, `HashMap$Node[]` 4.4%, `HashMap` 4.1%, `byte[]` 3.7%, `OneOf` 3.6%, `Account` 3.3%.

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
    (soft-commit reverted)       17,237  ← series high
P10 P9 + wrap soft-commit
    + store cache                16,973  ← wrap path cheaper; Token flush at block close
P11 P10 + builder reuse
    + StateChanges copy cuts     14,307  ← host CPU, not handle code
```

---

## Top 5 next

### 1. Re-record P11 on a quiet machine

**Why:** 14,307 tracks JVM user, not `resetForNextUserTxn`. P10 load minutes were 36–43% JVM user. P11’s were 21–28% with the machine at 94–98%. Cursor / WindowServer / extra Gradle daemons will do this again.

**What to do:** stop other heavy processes, same `:app:run` 16g, same NLG, dump `node-profile-12.jfr` after the 300 s window. If TPS is back in the P5–P10 band, this file is noise.

**What should move:** NLG TPS back to ~17k. Handle mix stays P10/P11.

### 2. Stop flushing all TokenService leaves on block close

**Why:** Unchanged. `NodeFeeManager.updateNodePaymentsState` → `writableTokenState.commit()` is still **13.3%** of handle.

**What to do:** commit only the `NODE_PAYMENTS` singleton. Leave account KV buffers for `VirtualMap.copy()` / `flushPendingWrites()`.

### 3. Throttle snapshots at round/block

**Why:** `ThrottleUsageSnapshot` is still the top handle class (**6.0%**). `usageSnapshot()` **6.7%** of handle alloc.

### 4. Reuse `WritableStoreFactory` across sequential handle txns

**Why:** Intra-dispatch cache still holds (`WritableAccountStore` class **0.6%**). `getStore` is still **5.1%** (new factory HashMap every dispatch).

### 5. Skip the purge walk when `livePayerIndexes.size() == 1`

**Why:** `isEmpty` **13.7%** first-app on this file because TPS was low. On a quiet re-run it should land nearer P10’s 8.5%. Still the hottest first-app frame. Do not put `removeIf` back.

|              Metric               |           **P10** |           **P11** |         After quiet re-run |
|-----------------------------------|------------------:|------------------:|---------------------------:|
| NLG TPS                           |        **16,973** |        **14,307** |           ~P10 if host was the cause |
| JVM user (load window)            |         **~34%** |         **25.2%** |                      ~P10 |
| `commitFullStack` / wrap          |     **7% / 9–9** |     **7% / 8–8** |                      stay |
| `BlockStreamBuilder.<init>`       |              — |             **0** |                      stay 0 |
| `writeStateChanges` / LinkedList  |              — |             **0** |                      stay 0 |
| `NodeFee` / `putLeaf` incl        |    13.2 / 6.3 |    **13.3 / 6.9** |                      stay |
| `isEmpty` first-app               |         8.5% |        **13.7%** |               ~P10 if TPS recovers |
| Handle alloc rate                 |   ~219 MB/s |       **~169 MB/s** |               ~P10 |
| Leak / WRB / wrap ctors           |   0 / 0 / 0 |     **0 / 0 / 0** |                  stay 0 |
