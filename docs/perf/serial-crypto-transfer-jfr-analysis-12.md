# Serial CryptoTransfer JFR analysis (`node-profile-12.jfr`)

Follow-up to [`serial-crypto-transfer-jfr-analysis-11.md`](serial-crypto-transfer-jfr-analysis-11.md). Same P11 binary (root `BlockStreamBuilder` reuse + `writeStateChanges` / `takeKv` copy cuts), **serial handle**, re-run after P11’s 14,307 looked like host starvation. NLG: **17,389 TPS** (`5,217,090` transfers in 300 s) — **+21.5%** vs P11 (14,307), **+2.5%** vs P10 (16,973), **+0.9%** vs P9 (17,237). New series high. No `FAIL_INVALID` / account `1002`.

Recording: 42 MB, JFR 2.1, **2026-08-31 01:03:19 UTC, 517 s**. Temurin 25.0.2, PID 5628 (new `:app:run`, default 16g pin). How it was read: `jfr summary` / `jfr view`, then `jfr print --stack-depth 48` on `jdk.ExecutionSample` (46,867 events) and `--stack-depth 24` on `jdk.ObjectAllocationSample` (78,951 events, weighted). File was **0 B** until `JFR.dump` from the still-running JVM (no `jdk.Shutdown`).

## Bottom line

**P11 was host CPU. P12 is the same code at a fair load window, and it is the series high.** Load-window JVM user is back to P10’s band (**36.2%**, peak minutes **34–46%**) on a **98%** machine. Handle mix is P10. Builder ctor and the `StateChanges` copies stay gone.

1. **14,307 was not a P11 code regression.** P11 load minutes were 21–28% JVM user. P12’s 21:06–21:11 are **25.5 / 33.7 / 45.7 / 41.6 / 36.7**. TPS followed CPU.
2. **17,389 is a real step, small vs P9.** +152 vs P9, +416 vs P10, same 300 s window. Handle alloc cuts from builder reuse are visible (`BlockStreamBuilder.<init>` still **0**). Do not over-read +0.9% vs P9.
3. **`isEmpty` is back to P10.** First-app **8.1%** (P11 13.7%, P10 8.5%). Composition, not a purge rewrite.
4. **Token flush is still the leftover VM work.** `NodeFeeManager.onCloseBlock` **13.9%**, `putLeaf` incl **7.3%**, `MerkleWritableStates.commit` **16.5%**. `commitFullStack` stays **6.7%**.
5. **G1 is the tight 16g series again.** STW **53.7 s / 517 s (10.4%)**, P99 **1.22 s**, `EvacuationFailed` **957**, `ConcurrentModeFailure` **34**. P11’s “better GC” was low TPS.

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

`BlockStreamManager` **33.2%** (P10 33.6%, P11 38.1%): `commitReceipts` **15.8%**, NodeFee `onCloseBlock` **13.9%**, true `writeItem` **3.5%**.

## Recording shape

| Item | P10 | P11 (starved) | **P12 (quiet re-run, P11 code)** |
|------|----:|--------------:|---------------------------------:|
| NLG TPS (300 s) | 16,973 | 14,307 | **17,389** |
| Heap | 16=16 GB | 16=16 GB | **16=16 GB, pretouch** |
| Heap used | 1.8–16 (p50 14.9) | 0.07–16 (p50 14.3) | **2.3–16 / 16** (p50 14.9, last 13.7) |
| Duration | 391 s | 448 s | **517 s** |
| Execution samples | 46,989 | 41,676 | **46,867** |
| Handle samples | 11.4% | 10.5% | **10.8%** |
| Handle alloc | 83.7 GB / 30.5% | 75.9 GB / 28.2% | **89.3 GB / 29.8%** |
| Handle alloc rate (file) | ~219 MB/s | ~169 MB/s | **~173 MB/s** |
| GC pause total / % file | 48.0 s / 12.3% | 22.9 s / 5.1% | **53.7 s / 10.4%** |
| Pause P50 / P99 / max STW | 28 / 1280 / 1710 ms | 29 / 1120 / 1170 ms | **27 / 1220 / 1290 ms** |
| `EvacuationFailed` | 902 | 586 | **957** |
| `ConcurrentModeFailure` | 28 | 7 | **34** |
| Old GC events | 92 | 51 | **105** |
| JVM user avg / max (file) | 28.9% / 77.3% | 19.7% / 74.3% | **22.6% / 74.5%** |
| JVM user (NLG window) | ~34% (11:30–11:34) | 25.2% (20:48–20:53) | **36.2%** (21:06–21:11) |
| Machine total (NLG window) | ~98% | 94.4% | **97.6% / 100%** |
| Leak detector | 0 | 0 | **0** |
| `SubmissionManager.submit` max | 1.30 s | 1.13 s | **1.16 s** |
| `hashCopy` max | 1.78 s | 151 ms | **1.19 s** |

P12’s file-wide JVM-user average is pulled down by idle 21:03–21:05 and cooldown after 21:11. Compare 21:06–21:11.

## Where CPU went

| Thread group | P10 | P11 | **P12** |
|--------------|----:|----:|--------:|
| `grpc-nio-worker-*` | 49.3% (ingest 77%, sig 59%) | 61.8% (73 / 54) | **49.4%** (ingest 77%, sig 58%) |
| `platformForkJoinThread-*` | 27.1% (prehandle 89%) | 18.4% (78) | **27.9%** (prehandle 84%) |
| `<scheduler TransactionHandler>` | 11.4% | 10.5% | **10.8%** |
| `VirtualHasherForkJoinThread-*` | 4.3% | 2.6% | **4.2%** |

Thread groups match P10. P11’s ingest-heavy split was the starved handle, not a new ingest path.

### Handle thread (5,064 samples)

| Marker | P10 | P11 | **P12** |
|--------|----:|----:|--------:|
| `SavepointStack` | 12.8% | 13.2% | **13.4%** |
| `VirtualMap` | 24.1% | 24.6% | **24.4%** |
| `commitFullStack` | 7.0% | 6.6% | **6.7%** |
| CHM / `java.util.HashMap` | 20.2 / 17.0 | 18.2 / 13.3 | **21.3 / 16.3** |
| `FinalizeRecord` | 10.3% | 8.4% | **9.8%** |
| `RecordCacheImpl` | 23.1% | 26.0% | **21.5%** (add 4.7%, purge 10.8%, `hasDuplicate` 1.0%, receipts 15.8%) |
| `dropExpiredPayerBuckets` | 12.0% (`isEmpty` 8.5%) | 16.0% (13.7%) | **10.8%** (`isEmpty` **8.1%**) |
| `VirtualNodeCache` | 9.1% | 8.7% | **9.5%** |
| `ImmediateStateChangeListener` | 19.9% | 24.1% | **19.4%** |
| `BlockStreamManager` | 33.6% | 38.1% | **33.2%** |
| `CryptoTransferHandler` | 10.7% | 10.6% | **11.0%** |
| `AdjustHbarChangesStep` | 8.3% | 7.7% | **8.2%** |
| `preHandleAllTransactions` | 5.0% | 4.4% | **5.8%** |
| `WrappedState` / `WrappedWritableStates` | 9.2 / 8.7 | 8.0 / 7.5 | **8.7 / 8.3** |
| `putLeaf` | 6.3 / 3.1 first | 6.9 / 0.4 | **7.3%** incl / **0.6%** first-app |
| `MerkleWritableStates.commit` | 15.3% | 15.7% | **16.5%** |
| `commitInStateIdOrder` | 19.7% | 19.9% | **20.7%** |
| `NodeFeeManager.onCloseBlock` | 13.2% | 13.3% | **13.9%** |
| `resetForNextUserTxn` / `createRootBaseBuilder` | — | 2.0 / 0.6 | **2.4% / 0.8%** |
| `BlockStreamBuilder.<init>` | — | 0 | **0** |
| `writeStateChanges` / `getStateChanges` | — | 0 | **0** |
| wrap / stack `.<init>` / `newRootStack` | 0 | 0 | **0** |
| WRB / streams / leak | 0 | 0 | **0** |

First-app: `PayerTxnIndex.isEmpty` **8.1%**, `HashMap.getNode` **7.9%**, `Arrays.copyOf(byte[])` **4.3%** (same `StateKeyUtils.kvKey` / PBJ path as P11, not a builder-list copy), `IdentityHashMap.get` **3.2%**, `dropExpiredPayerBuckets` **2.0%**.

## Allocation

Handle **89.3 GB / 517 s ≈ 173 MB/s** file-wide (idle minutes at the start). Builder reuse holds: `BlockStreamBuilder.<init>` **0**, class **0.1%**. `resetForNextUserTxn` **2.7%**. `writeStateChanges` / `getStateChanges` **0**.

| Site | P10 | P11 | **P12** |
|------|----:|----:|--------:|
| `WrappedState.<init>` / wrap states ctor | 0 | 0 | **0** |
| `BlockStreamBuilder.<init>` | — | 0 | **0** |
| `resetForNextUserTxn` | — | 2.8% | **2.7%** |
| `writeStateChanges` / `getStateChanges` | — | 0 | **0** |
| `DirectMethodHandle.allocateInstance` | 1.9% | 2.1% | **2.3%** |
| `usageSnapshot()` | 7.6% | 6.7% | **6.3%** |
| `WritableStoreFactory.getStore` | 4.8% | 5.1% | **6.0%** |
| `WritableAccountStore` (class) | 0.41% | 0.6% | **0.7%** |
| `HashMap.resize` (handle) | 3.2% | 4.4% | **4.0%** |
| `ThrottleUsageSnapshot` (class) | 6.9% | 6.0% | **5.4%** |
| `ParallelTask` / `SequentialTask` (class) | — | 0.7 / 1.7 | **0.9 / 1.4** |

Top handle classes: `ThrottleUsageSnapshot` **5.4%**, `Object[]` 5.2%, `OneOf` 4.7%, `HashMap$Node[]` 4.0%, `Account` 3.9%, `byte[]` 3.7%.

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
    + store cache                16,973  ← wrap path cheaper; Token flush at block close
P11 P10 + builder reuse
    + StateChanges copy cuts     14,307  ← host CPU (do not compare)
P12 P11 binary, quieter window   17,389  ← series high; P11 diagnosis confirmed
```

---

## Top 5 next (code)

P11 item 1 (quiet re-run) is done. The leftover levers are the same as P10.

### 1. Stop flushing all TokenService leaves on block close

**Why:** `onCloseBlock` **13.9%**. Soft-commit still defers account `putLeaf` to `NodeFeeManager.updateNodePaymentsState` → `writableTokenState.commit()`.

**What to do:** commit only the `NODE_PAYMENTS` singleton. Leave account KV for `VirtualMap.copy()` / `flushPendingWrites()`.

**What should move:** `putLeaf` / `MerkleWritableStates.commit` / `BlockStreamManager` down on handle; TPS if that flush leaves the serial thread.

### 2. Throttle snapshots at round/block

**Why:** `ThrottleUsageSnapshot` is still the top handle class (**5.4%**). `usageSnapshot()` **6.3%** of handle alloc.

### 3. Reuse `WritableStoreFactory` across sequential handle txns

**Why:** AccountStore class **0.7%**. `getStore` **6.0%** — new factory `HashMap` every dispatch. `TokenServiceApiImpl` still `new WritableAccountStore`.

### 4. Batch receipt-queue / KV block items

**Why:** `commitReceipts` **15.8%**. Receipt-queue merkle `commit()` is the remaining per-txn `putLeaf`. True `writeItem` is **3.5%**.

### 5. Skip the purge walk when `livePayerIndexes.size() == 1`

**Why:** `isEmpty` **8.1%** first-app at 17k TPS (P10 8.5%). Optional. Do not put `removeIf` back.

| Metric | P10 | P11 | **P12** | After (1) |
|--------|----:|----:|--------:|----------:|
| NLG TPS | 16,973 | 14,307 | **17,389** | up if Token flush left handle |
| JVM user (load window) | ~34% | 25.2% | **36.2%** | stay |
| `commitFullStack` / wrap | 7 / 9–9 | 7 / 8–8 | **7 / 9–8** | stay |
| `BlockStreamBuilder.<init>` | — | 0 | **0** | stay 0 |
| `writeStateChanges` / LinkedList | — | 0 | **0** | stay 0 |
| `NodeFee` / `putLeaf` incl | 13.2 / 6.3 | 13.3 / 6.9 | **13.9 / 7.3** | down / down |
| `isEmpty` first-app | 8.5% | 13.7% | **8.1%** | stay |
| Handle alloc rate (file) | ~219 | ~169 | **~173** | ~P12 or below |
| Leak / WRB / wrap ctors | 0 | 0 | **0** | stay 0 |
