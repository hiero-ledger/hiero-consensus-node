# Serial CryptoTransfer JFR analysis (`node-profile-15.jfr`)

Follow-up to [`serial-crypto-transfer-jfr-analysis-13.md`](serial-crypto-transfer-jfr-analysis-13.md). Same NLG CryptoTransfer shape, **serial handle**, after ingest cuts 1–5: CHM dedup + `putIfAbsent`, Ed25519 ingest fast path, unlocked submit / concurrent pool, factory/Result/OK reuse, and ingest→prehandle handoff. NLG: **18,070 TPS** (`5,421,325` transfers in 300 s) — **+3.4%** vs P13 (17,468), **+3.9%** vs P12 (17,389). New series high. No `FAIL_INVALID` / account `1002`. `event.creation.maxCreationRate` is still the default **20 Hz**. `node-profile-14.jfr` is on disk and not in this note.

Recording: 40 MB, JFR 2.1, **2026-08-31 02:37:43 UTC, 427 s**. Temurin 25.0.2, PID 18048, `:app:run` 16g pin + pretouch, leak `DISABLED`. How it was read: `jfr summary` / `jfr view`, then `jfr print --stack-depth 48` on `jdk.ExecutionSample` (35,688 events) and `--stack-depth 24` on `jdk.ObjectAllocationSample` (81,401 events, weighted). Also `jdk.ThreadPark` on `<scheduler TransactionHandler>` and `jdk.CPULoad`.

## Bottom line

**1–5 did what they were supposed to. TPS only moved +3.4% because the ingest floor is still libsodium, once per accept.** On this single-node load every tx is self-submitted, so handoff skipped prehandle parse and payer verify entirely. FJP fell **27.0% → 6.0%** of Java samples; prehandle `Libsodium` / `parseSignedAndCheck` are **0**. Ingest still spends **83%** of its first-app samples in `cryptoSignVerifyDetachedNoChecks`. Dedup skip-list, `DefaultKeyVerifier` on ingest, and `SubmissionManager` monitor waits are gone.

**TPS is still `20 Hz × ingest-in-the-gap`, not serial apply.** Handle parks **~50%** of each load minute in `tasks.take()`, average park **47 ms** (same 20 Hz gap as P13). 18,070 / 20 = **904 txs/event** vs P13’s 873. Handle work per event is P13: `flushPendingWrites` **13.0%**, `commitReceipts` **19.8%**. Those matter only after events get larger.

1. **Handoff landed.** FJP `PreHandleWorkflow` **16.3%** of a much smaller pool; `IngestHandoff` **11.2%**; `dispatchPreHandle` still **8.9%**. Remaining FJP is event RSA (`JcaSigner` **65.5%**, `oddModPow` **61.4%**).
2. **Ed25519 fast path landed.** grpc `verifyEd25519` **63.0%** = `Libsodium` **63.0%**. `DefaultKeyVerifier` **0**, `SignatureExpander` **0**, `ExpandedSignaturePair` **0**.
3. **Dedup / submit unlocked.** `ConcurrentSkipList` **0**. `DeduplicationCache` **1.2%** of grpc (`putIfAbsent` **0.7%** of ingest first-app). `SubmissionManager` monitor waits **0** (P13 max **1.20 s**). Remaining ingest lock: `SynchronizedThrottle` (150 enters, **5.4 s**).
4. **Reuse landed.** Handle `getStore` alloc **0**, `TokenServiceApiImpl` **0**, `BlockStreamBuilder.<init>` **0**. grpc `TransactionResponse` alloc **0**.
5. **Host still pegged.** NLG window JVM user **32.6%** (peak min **42%**, file max **78.5%**) on machine **97.7% / 100%**. Same band as P13. Freed FJP cores did not become spare host capacity.

```608:618:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/ingest/IngestChecker.java
        if (!isHollow(account) && payerKey != null && payerKey.hasEd25519()) {
            final var match = findEd25519Match(payerKey, sigPairs);
            if (match == null
                    || !signatureVerifier.verifyEd25519(
                            txInfo.signedBytes(), payerKey.ed25519OrThrow(), match.ed25519OrThrow())) {
                throw new PreCheckException(INVALID_SIGNATURE);
            }
            if (result != null) {
                result.setPayerVerification(
                        payerKey, Map.of(payerKey, new CompletedSignatureVerificationFuture(payerKey, null, true)));
```

```186:200:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/prehandle/PreHandleWorkflowImpl.java
        final var ingestHandoff = previousResult == null ? ingestHandoffCache.take(serializedSignedTx) : null;
        // ...
            if (previousResult == null && ingestHandoff == null) {
                txInfo = transactionChecker.parseSignedAndCheck(serializedSignedTx, maxBytes);
            } else if (ingestHandoff != null) {
                txInfo = ingestHandoff.txInfo();
```

## Is TPS still limited by the handle thread?

**No — still by ingest accept rate at 20 Hz.** Handle is idle half the time because each event only contains what ingest put in the pool in the last ~50 ms.

```
TPS  =  events/s  ×  txs/event
     =  20 Hz     ×  min(ingest_in_the_gap, byte_budget)
     =  20        ×  904
     =  18,070
```

The 904 is gap fill, not the 2.5 MB cap. Raising `maxCreationRate` to 40 splits the same ingest into ~452 txs twice as often unless accept rate also rises.

| Stage | Threads | On-CPU share | What it means for 18k TPS |
|-------|---------|-------------:|---------------------------|
| Ingest + sigverify | many `grpc-nio-worker-*` | **63.2%** of Java samples (ingest 76%, sig 63%) | Parallel. **83%** of ingest first-app is libsodium. Share rose because FJP left the mix, not because ingest got heavier (grpc samples ~22.5k vs P13 ~23k). |
| Prehandle | `platformForkJoinThread-*` | **6.0%** (P13 27.0%) | Handoff: parse **0**, payer verify **0**. Leftover is event RSA + `dispatchPreHandle`. |
| Consensus engine | `<scheduler ConsensusEngine>` | **0.4%** | Paces handle. Not the on-CPU cost. |
| Handle / apply | `<scheduler TransactionHandler>` | **13.6%** of Java samples (4,846; P13 4,700) | Same absolute work. Share rose because the sample denominator shrank. |
| State hash | `VirtualHasherForkJoinThread-*` | **6.3%** | Parallel, after copy. `hashCopy` max **1.22 s** (aligned with GC). |

Handle wall time during load minutes:

- **~50% parked** in `tasks.take()` — 31–39 s / 60 s. Average **38–53 ms**. Long parks (**1.15–1.23 s**) are GC STW.
- **~50% running** — apply + block-boundary `copyMutableState()` → `flushPendingWrites` (**13.0%** of handle on-CPU).

```177:180:platform-sdk/consensus-wiring-framework/src/main/java/org/hiero/consensus/wiring/framework/schedulers/internal/SequentialThreadTaskScheduler.java
            if (tasks.drainTo(buffer, BUFFER_SIZE) == 0) {
                try {
                    final SequentialThreadTask task = tasks.take();
                    buffer.add(task);
```

```38:39:platform-sdk/consensus-event-creator/src/main/java/org/hiero/consensus/event/creator/config/EventCreationConfig.java
public record EventCreationConfig(
        @ConfigProperty(defaultValue = "20") double maxCreationRate,
```

## Recording shape

| Item | P13 | **P15 (ingest 1–5)** |
|------|----:|---------------------:|
| NLG TPS (300 s) | 17,468 | **18,070** |
| txs / event @ 20 Hz | 873 | **904** |
| Heap | 16=16 GB, pretouch | **16=16 GB, pretouch** |
| Heap used | 2.2–16 / 16 (p50 15.0) | **2.1–16 / 16** (p50 14.9, last 14.8) |
| Duration | 391 s | **427 s** |
| Execution samples | 45,735 | **35,688** |
| Handle samples | 10.3% (4,700) | **13.6% (4,846)** |
| Handle alloc | 76.8 GB / 26.8% | **83.7 GB / 29.5%** |
| Handle alloc rate (file) | ~196 MB/s | **~196 MB/s** |
| GC pause total / % file | 53.3 s / 13.6% | **53.7 s / 12.6%** |
| Pause P50 / P99 / max STW | 26 / 1270 / 1290 ms | **28 / 1160 / 1170 ms** |
| `EvacuationFailed` | 870 | **954** |
| `ConcurrentModeFailure` | 33 | **34** |
| Old GC events | 105 | **104** |
| JVM user avg / max (file) | 30.5% / 79.8% | **24.6% / 78.5%** |
| JVM user (NLG window) | 33.8% (21:28–21:33) | **32.6%** (22:38:49–22:43:49); peak min **42%** |
| Machine total (NLG window) | 96.5% / 100% | **97.7% / 100%** |
| Leak detector | 0 | **0** |
| `SubmissionManager` wait max | 1.20 s | **0** (no monitor waits) |
| `hashCopy` max | 1.16 s | **1.22 s** |
| Handle park avg / load-min sum | ~47 ms / 30–44 s | **47 ms / 31–39 s** |

File-wide JVM-user average is pulled down by idle 22:37 and cooldown 22:44. Compare 22:39–22:42.

## Where CPU went

| Thread group | P13 | **P15** |
|--------------|----:|--------:|
| `grpc-nio-worker-*` | 50.6% (ingest 78%, sig 59%) | **63.2%** (ingest 76%, sig 63%) |
| `platformForkJoinThread-*` | 27.0% (prehandle 90%) | **6.0%** (prehandle 16%; RSA/tipset 77%) |
| `<scheduler TransactionHandler>` | 10.3% | **13.6%** |
| `VirtualHasherForkJoinThread-*` | 4.5% | **6.3%** |
| `<scheduler ConsensusEngine>` | 0.2% | **0.4%** |

grpc Java-sample *count* is flat (~22.5k vs ~23k). FJP count collapsed (~12k → 2.1k). That is the whole mix shift.

### Handle thread (4,846 samples)

| Marker | P13 | **P15** |
|--------|----:|--------:|
| `SavepointStack` | 14.2% | **14.6%** |
| `VirtualMap` | 23.5% | **24.5%** |
| `commitFullStack` | 6.8% | **7.0%** |
| CHM / `HashMap` (excl. CHM) | 21.0 / 16.8 | **19.6 / 18.9** |
| `FinalizeRecord` | 9.7% | **8.9%** |
| `RecordCacheImpl` | 22.3% | **24.7%** |
| `dropExpiredPayerBuckets` | 11.5% (`isEmpty` 8.2%) | **13.1%** (`isEmpty` **9.9%** first-app) |
| `ImmediateStateChangeListener` | 19.1% | **22.5%** |
| `BlockStreamManager` | 19.8% | **23.8%** |
| `CryptoTransferHandler` | 10.6% | **11.3%** |
| `AdjustHbarChangesStep` | 8.7% | **9.6%** |
| `preHandleAllTransactions` | 6.1% | **5.7%** |
| `WrappedState` / `WrappedWritableStates` | 9.1 / 8.5 | **9.6 / 8.6** |
| `putLeaf` | 6.3 / 1.3 first | **6.7%** incl / **2.7%** first-app |
| `MerkleWritableStates.commit` | 2.2% | **2.8%** |
| `commitInStateIdOrder` | 19.7% | **20.3%** |
| `NodeFeeManager.onCloseBlock` | 0.3% | **0.2%** |
| `flushPendingWrites` / `flushToDataSource` | 13.1 / 13.1 | **13.0% / 12.9%** |
| `WritableStoreFactory.getStore` | 0.8% | **1.2%** |
| `resetForNextUserTxn` / `createRootBaseBuilder` | 2.4 / 1.0 | **2.2% / 0.6%** |
| `BlockStreamBuilder.<init>` | 0 | **0** |
| `writeStateChanges` / `getStateChanges` | 0 | **0** |
| `commitReceipts` | 16.0% | **19.8%** |
| `writeItem` | 3.7% | **3.9%** |

BSM split: `commitReceipts` **83%** of BSM (19.8% handle), `writeItem` **17%** (3.9% handle), NodeFee **0**.

First-app: `PayerTxnIndex.isEmpty` **9.9%**, `Arrays.copyOf` **5.3%**, `putLeaf` **2.7%**.

### Ingest / prehandle (the 1–5 check)

| Marker | P13 | **P15** |
|--------|----:|--------:|
| grpc ingest-ish | 78% | **76%** |
| grpc / ingest first-app libsodium | ~75% of ingest | **83%** of ingest |
| `DefaultKeyVerifier` on grpc | present (expand path) | **0** |
| `DeduplicationCache` of grpc | ~6% | **1.2%** |
| skip-list | yes | **0** |
| `SubmissionManager` wait max | 1.20 s | **0** |
| FJP prehandle | 90% of FJP | **16%** of FJP |
| FJP libsodium / parse | ~80% of prehandle | **0 / 0** |
| FJP `IngestHandoff` | — | **11.2%** |
| FJP `JcaSigner` / tipset | ~8% of FJP | **66% / 77%** of leftover FJP |

## Allocation

Handle **83.7 GB / 427 s ≈ 196 MB/s** — same rate as P13. Factory / builder reuse holds: `getStore` **0**, `TokenServiceApiImpl` **0**, `BlockStreamBuilder.<init>` **0**. grpc `TransactionResponse` **0** (interned OK/0). Skip-list **0**. `DefaultKeyVerifier` / `ExpandedSignaturePair` **0**.

| Site | P13 | **P15** |
|------|----:|--------:|
| `WritableStoreFactory.getStore` | 0 | **0** |
| `BlockStreamBuilder.<init>` | 0 | **0** |
| `resetForNextUserTxn` | 2.7% | **3.4%** |
| `usageSnapshot()` | 7.8% | **5.4%** |
| `ThrottleUsageSnapshot` (class) | 6.5% | **4.7%** |
| `HashMap.resize` (handle) | 2.8% | **3.3%** |
| grpc `DeduplicationCache` | — | **0.7%** |
| grpc `IngestHandoff` | — | **0.2%** |
| FJP `SignatureVerifier` | (verify maps) | **0** |

Top handle classes: `Object[]` **6.3%**, `byte[]` **4.7%**, `ThrottleUsageSnapshot` **4.7%**, `Account` **3.8%**, `Account$Builder` **3.5%**. The 14.2% `WritableAccountStore` *site* is store methods, not construction.

FJP alloc **17.5 GB / 6.1%**; **65%** of that is the handoff/`dispatchPreHandle` path, not verify.

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
    + factory / Token API reuse  17,468
P15 P13 + ingest 1–5
    (still 20 Hz)                18,070  ← handoff/dedup/submit landed;
                                           libsodium still the accept-rate floor
```

---

## Top 5 next

### 1. The ingest floor is libsodium. Wrapper cuts are done.

**Why:** **83%** of ingest first-app is `cryptoSignVerifyDetachedNoChecks`. Dedup, submit lock, `DefaultKeyVerifier`, and double-verify are gone. Another ingest wrapper pass will not move TPS.

**What to do:** only a cheaper Ed25519 verify, more host CPU, or accepting fewer door-check verifies (do not skip ingest payer verify). Do not chase libsodium bindings in this repo.

### 2. Do not raise `maxCreationRate` expecting TPS

**Why:** Parks are still **~47 ms**. 40 Hz just makes 452-tx events unless ingest accepts more than 18k/s. This box is **96–99%** in the peak minutes.

### 3. Handle leftovers are unchanged and still not the limiter

**Why:** `flushPendingWrites` **13.0%**, `commitReceipts` **19.8%**, `isEmpty` **9.9%** first-app. Handle is half-idle. Touch these when events get larger (after ingest/host headroom), not to raise this 20 Hz run.

### 4. `SynchronizedThrottle` is the last ingest monitor

**Why:** 150 `JavaMonitorEnter`s, **5.4 s**. Small vs libsodium. Only worth it after verify is cheaper.

### 5. Do not optimize leftover FJP RSA for TPS

**Why:** Event `JcaSigner` is now most of FJP because prehandle verify vanished. It is not the accept-rate path.

| Metric | P13 | **P15** | After (1) if verify were cheaper |
|--------|----:|--------:|--------------------------------:|
| NLG TPS | 17,468 | **18,070** | up with accept rate |
| txs / event @ 20 Hz | 873 | **904** | up |
| FJP / prehandle libsodium | 27% / ~80% | **6% / 0** | stay 0 |
| ingest first-app libsodium | ~75% | **83%** | down |
| skip-list / SM wait / `DefaultKeyVerifier` | yes / 1.20 s / yes | **0 / 0 / 0** | stay 0 |
| Handle park (load min) | ~50% | **~50%** | stay unless events grow |
| `flushPendingWrites` / `commitReceipts` | 13.1 / 16.0 | **13.0 / 19.8** | stay |
| Leak / WRB / wrap ctors / getStore alloc | 0 | **0** | stay 0 |
