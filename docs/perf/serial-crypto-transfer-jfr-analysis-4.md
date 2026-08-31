# Serial CryptoTransfer JFR analysis (`node-profile-4.jfr`)

Follow-up to [`serial-crypto-transfer-jfr-analysis-3.md`](serial-crypto-transfer-jfr-analysis-3.md). Same NLG CryptoTransfer shape, **serial handle**. NLG: **13,103 TPS** (`3,931,158` transfers in 300 s) — **−21%** vs profile 3’s 16,551.

Recording: 46 MB, JFR 2.1, **2026-08-29 23:51:00 UTC, 366 s**. PID 53198, Temurin 25.0.2, `:app:run` with pinned heap. How it was read: `jfr summary` / `jfr view`, then `jfr print --stack-depth 64` on `jdk.ExecutionSample` (35,876 samples) and `--stack-depth 32` on `jdk.ObjectAllocationSample` (63,105 samples, weighted).

## Bottom line

**The heap pin landed. 12 GB is too small for this load. That is why TPS fell.**

1. **Flags are correct.** `jdk.GCHeapConfiguration` is min = max = initial = **12 GB**, `AlwaysPreTouch` on, `io.netty.leakDetection.level=DISABLED`. No leak-detector throws.
2. **Live set does not fit.** Profile 3 (16 GB *max*, growing) sat at **13–16 GB used**. This JVM is hard-capped at 12 GB and ends at **10.6–11.9 GB used** — G1 is collecting constantly to stay under the cap.
3. **GC ate the run.** STW pause **1 m 59 s / 366 s (32.5% of wall)**. 661 pauses, P99 **819 ms**, max **918 ms**. `EvacuationFailed` **2,427** (P3: 1,082). `ConcurrentModeFailure` **95** (P3: 33). 267 old GCs; event duration sum **296 s** (concurrent + pause). Ingest `SubmissionManager.submit` blocked **avg 231 ms, max 7.12 s**.
4. **Handle code did not get worse.** Same shape as P3: `SavepointStack` 33%, `VirtualMap` 32%, `commitFullStack` 22%, `RecordCacheImpl` 10%, WRB 0, leak detector 0, streams 1%. Handle is only **7.8%** of execution samples because it spent a third of the file in safepoints, not because commit got cheaper.
5. **Do not treat 13,103 as a code regression.** Re-run with **16g** pinned (`-PnodeHeap=16g`) so the cap is at or above the P3 live set. Then compare TPS to 16,551.

```180:188:hedera-node/hedera-app/build.gradle.kts
// Pin the node heap (not Gradle workers) so G1 is not resizing 1 GB → 16 GB under
// NLG. Override with -PnodeHeap=16g. AlwaysPreTouch makes the first seconds slower.
val heap = providers.gradleProperty("nodeHeap").orElse("12g").get()
jvmArgs(
    "-Xms$heap",
    "-Xmx$heap",
    "-XX:+AlwaysPreTouch",
    "-Dio.netty.leakDetection.level=DISABLED",
)
```

## Recording shape vs prior profiles

|                Item                 |                  P2 |                  P3 |                            **P4** |
|-------------------------------------|--------------------:|--------------------:|----------------------------------:|
| NLG TPS (300 s)                     |              14,024 |          **16,551** |                        **13,103** |
| Heap                                |             1→16 GB |             1→16 GB |          **12 = 12 GB, pretouch** |
| Heap used (end of load)             |            12–14 GB |            13–16 GB | **10.6–11.9 GB** (at the ceiling) |
| Duration                            |               410 s |               396 s |                         **366 s** |
| Execution samples                   |              53,985 |              44,519 |                        **35,876** |
| Handle samples                      |               15.8% |               11.1% |                          **7.8%** |
| Handle alloc                        |        125 GB / 41% |        144 GB / 42% |                  **118 GB / 41%** |
| Handle alloc rate                   |           ~306 MB/s |           ~364 MB/s |                     **~322 MB/s** |
| GC pause total / % of file          |          67 s / 16% |      53.6 s / 13.5% |                 **119 s / 32.5%** |
| Pause P50 / P99 / max STW           | 37 / 3170 / 3560 ms | 22 / 1150 / 1210 ms |             **18 / 819 / 918 ms** |
| `EvacuationFailed`                  |                 650 |               1,082 |                         **2,427** |
| `ConcurrentModeFailure`             |                  14 |                  33 |                            **95** |
| Old GC events                       |                  77 |                 124 |                           **267** |
| JVM user CPU avg / max              |       20.7% / 44.6% |       30.5% / 75.2% |                 **41.5% / 80.6%** |
| Leak detector throws                |              84,781 |                   0 |                             **0** |
| `SubmissionManager.submit` max wait |              3.48 s |              1.24 s |                        **7.12 s** |

P99 *per pause* looks “better” than P3 (819 ms vs 1.15 s). Frequency and evacuation failures do not: **almost twice as many pauses**, **2× evacuation failures**, **32% of wall stopped**. NLG cannot push 16k TPS through that.

JVM user 41% is not a win — it is G1 + retry/churn while the mutator is stopped a third of the time. Machine CPU avg **91%**.

## Where CPU went (when not in GC)

Same split as P3, smaller handle slice:

|           Thread group           |    P3 |                          **P4** |
|----------------------------------|------:|--------------------------------:|
| `grpc-nio-worker-*`              | 50.3% | **51.0%** (ingest 76%, sig 59%) |
| `platformForkJoinThread-*`       | 26.6% |       **28.9%** (prehandle 90%) |
| `<scheduler TransactionHandler>` | 11.1% |                        **7.8%** |
| `VirtualHasherForkJoinThread-*`  |  4.2% |                        **4.2%** |

Handle inclusive (2,794 samples) — compare to P3, not to P2’s GC-inflated cache:

|            Marker             |            P3 |            **P4** |
|-------------------------------|--------------:|------------------:|
| `SavepointStack`              |         29.5% |         **32.8%** |
| `VirtualMap`                  |         28.2% |         **31.9%** |
| `commitFullStack`             |         20.1% |         **21.6%** |
| `HashMap` / CHM               | 23.4% / 24.5% | **23.8% / 23.4%** |
| `FinalizeRecord`              |         15.2% |         **16.3%** |
| `VirtualNodeCache`            |         13.6% |         **14.6%** |
| `RecordCacheImpl`             |         13.3% |         **10.5%** |
| `CryptoTransferHandler`       |         10.5% |         **11.2%** |
| `AdjustHbarChangesStep`       |          6.8% |          **7.3%** |
| `preHandleAllTransactions`    |          4.8% |          **4.7%** |
| WRB / streams / leak detector |  0 / 1.1% / 0 |  **0 / 1.1% / 0** |

First app frames: `VirtualNodeCache.putLeaf` 3.8%, `WritableStatesStack.getSingleton` 3.6%, `WrappedState.getWritableStates` 3.3%. Record-cache `hasDuplicate` **0.8%**. The serial limiter is still commit + stack + VM.

Handle alloc still HashMap-dominated (`HashMap` + nodes **22.4%**), `DirectMethodHandle.allocateInstance` **18.4%**, `ThrottleUsageSnapshot` **2.1%**. Rate ~322 MB/s — similar to P3. Pinning 12g did not reduce garbage; it reduced *room*.

## Why 12g lost to an unpinned 16g max

Profile 3’s heap was allowed to grow to 16 GB. Used memory at the end of that load was **already above 12 GB**. A pinned 12g heap is a smaller old generation for the same VirtualMap + record-cache + gRPC buffers. G1 then:

- young-collects more often (453 young GCs in 366 s),
- fails evacuation (**2,427**),
- falls into concurrent-mode failure (**95**),
- holds ingest on `SubmissionManager.submit` for up to **7 s**.

That is a configuration miss, not a handle-path change.

---

## What to run next (`node-profile-5.jfr`)

Keep leak detection disabled. Pin at **16g** so Xms == Xmx is at or above the P3 live set:

```bash
unset JAVA_TOOL_OPTIONS
./gradlew :app:run -PnodeHeap=16g
```

Confirm:

```bash
jcmd <PID> VM.flags | rg 'MaxHeapSize|InitialHeapSize|AlwaysPreTouch'
```

`InitialHeapSize` and `MaxHeapSize` should both be `17179869184`. Then the same NLG `-R` command and `JFR.start` / dump / stop as `ct-serial-4`, filename `node-profile-5.jfr`.

|        Metric        |            P4 (12g) |                Target at 16g pin                 |
|----------------------|--------------------:|--------------------------------------------------|
| NLG TPS              |              13,103 | **≥ 16,551** (P3), ideally higher (no 1→16 grow) |
| GC pause % of file   |               32.5% | ≪ 13% (better than P3)                           |
| `EvacuationFailed`   |               2,427 | toward 0                                         |
| Heap used            |    11.9 / 12 GB cap | stable, not glued to the cap                     |
| Handle inclusive mix | stack / VM / commit | **same** until the next code cut                 |

If 16g still evacuates, this machine may need 20g or less NLG state (fewer accounts / shorter valid-duration cache). Do not start `commitFullStack` / SavepointStack code until a pinned heap **fits**. Changing `:app:run`’s default from `12g` to `16g` is the one-line follow-up if you want that to be the usual local launch.
