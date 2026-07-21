# 2026-07-16 Compression Comparison (10M state, production sync streams)

Status: `completed local validation`
Run date: `2026-07-16`
Related state calibration: [`2026-07-16-read-pacing-10m-matrix.md`](2026-07-16-read-pacing-10m-matrix.md)

## Purpose

Measure the end-to-end effect of `socket.gzipCompression` after the benchmark loopback transport was changed to use
the production `SyncInputStream.createSyncInputStream()` and `SyncOutputStream.createSyncOutputStream()` factories.
The comparison asks whether the reduction in modeled wire traffic outweighs compression overhead under the locally
calibrated `REALISTIC` profile: `270 us` one-way latency and `200 Mbit/s` per direction.

The configuration property retains the name `gzipCompression`; the production factory currently implements its
compressed path with `DeflaterOutputStream`/`InflaterInputStream` using raw DEFLATE.

## Environment and protocol

- Machine: MacBook Pro `Mac15,9`, Apple M3 Max (16 cores), 48 GB memory; connected to AC power.
- OS: macOS 26.5, Darwin 25.5.0.
- JVM: Temurin OpenJDK 25.0.2+10 LTS; JMH 1.37.
- Code: `c70410ce2498a5779ca2f5cf6003e7c82cf63f67` on branch
  `25083-improve-reconnect-bench-socket-net`, plus the current uncommitted production-sync-stream benchmark changes.
- JVM options: `-Xms24g -Xmx24g -XX:+AlwaysPreTouch` and the benchmark GC log configuration.
- JMH: six independent Gradle invocations; each invocation used one fresh fork, no warmup, and one single-shot
  measurement.
- Transport: loopback TCP, `REALISTIC`, `270 us` one-way latency, `200 Mbit/s` per direction.
- Traversal: `pullTopToBottom`; verification disabled.
- Compression was the only experimental variable: `socket.gzipCompression=false` or `true` in `settings.txt`.
- Explicit `SocketFactory` send/receive buffer setters were disabled. Every run reported the same effective OS values:
  server receive `131072`, client send `146988`, client receive `408300`, accepted send `146988`, and accepted receive
  `408300` bytes. The production stream buffer was `8192` bytes.
- Run order was counterbalanced: uncompressed, compressed, compressed, uncompressed, uncompressed, compressed.
- No manual OS cache clearing was performed.

Preflight validation passed:

- `LoopbackSocketTransportTest`: 9 tests, including the gzip wire-byte regression test;
- `:swirlds-benchmarks:compileJmhJava`;
- all six measured Gradle/JMH invocations completed successfully.

Raw local evidence is under
`platform-sdk/swirlds-benchmarks/build/reconnectbench-compression-10m-2026-07-16/`. Each run directory contains the
console log, JMH result, GC log, effective `settingsUsed.txt`, and the exact temporary `settings.txt`. The `preflight`
directory contains environment data, the measured source diff, original settings, and artifact SHA-256 hashes.

## Fixed state and command

Every run restored both maps from `data/ReconnectBench/{teacher,learner}/saved0`; no additional saved-state directory
was created. The fixed state parameters were:

```text
numFiles=1000
numRecords=10000
base records=10,000,000
randomSeed=9823452658
teacherAddProbability=0.10
teacherModifyProbability=0.40
teacherRemoveProbability=0.00
maxKey=10000000
keySize=32
recordSize=128
numThreads=32
```

Observed state sizes were learner `9,999,999` and teacher `11,150,666`. Parameters were passed explicitly because
the restore path does not fingerprint the saved state and the current Gradle default for `teacherAddProbability` is
`0.09`, not the saved state's `0.10`.

The measured command was:

```bash
./gradlew :swirlds-benchmarks:jmhReconnect \
  -PnumFiles=1000 -PnumRecords=10000 -PrandomSeed=9823452658 \
  -PteacherAddProbability=0.10 -PteacherModifyProbability=0.40 -PteacherRemoveProbability=0.00 \
  -PmaxKey=10000000 -PkeySize=32 -PrecordSize=128 -PnumThreads=32 \
  -PnetworkProfile=REALISTIC -PnetworkBandwidthMegabitsPerSecond=200 \
  -PnetworkLatencyMicroseconds=270 --console=plain
```

## Results

Wire-byte columns are production counters below the buffering/compression layer. Total wire bytes sum the two
directions; the directions operate concurrently and the sum is not a bandwidth-rate calculation.

| Run | Compression | Time (s/op) | Teacher to learner (bytes) | Learner to teacher (bytes) | Total wire bytes | GC pause total (s) |
|---:|---|---:|---:|---:|---:|---:|
| 1 | off | 78.079 | 878,400,066 | 842,124,208 | 1,720,524,274 | 3.032 |
| 2 | on | 148.141 | 95,212,516 | 590,973,218 | 686,185,734 | 2.134 |
| 3 | on | 128.352 | 95,208,115 | 590,893,311 | 686,101,426 | 3.010 |
| 4 | off | 78.480 | 878,400,066 | 842,124,208 | 1,720,524,274 | 2.058 |
| 5 | off | 76.466 | 878,400,066 | 842,124,208 | 1,720,524,274 | 1.933 |
| 6 | on | 134.112 | 95,269,782 | 590,938,837 | 686,208,619 | 3.500 |

Timing summary:

| Mode | Individual times (s/op) | Mean | Median | Range |
|---|---|---:|---:|---:|
| uncompressed | 78.079, 78.480, 76.466 | **77.675** | **78.079** | 2.014 |
| compressed | 148.141, 128.352, 134.112 | **136.868** | **134.112** | 19.789 |

Relative to uncompressed, compression was:

- `1.718x` the median duration, a **71.8% median slowdown**;
- `1.762x` the mean duration, a **76.2% mean slowdown**;
- slower in all three adjacent counterbalanced pairs by `89.7%`, `63.5%`, and `75.4%`.

The fastest compressed run (`128.352 s`) was still `49.872 s` slower than the slowest uncompressed run
(`78.480 s`). The uncompressed return legs reproduced the first result, so progressive machine drift does not explain
the separation.

Wire-byte summary uses the mean compressed byte count; uncompressed byte counts were identical across repeats.

| Direction | Uncompressed bytes | Compressed mean bytes | Reduction | Uncompressed / compressed |
|---|---:|---:|---:|---:|
| teacher to learner | 878,400,066 | 95,230,138 | **89.2%** | 9.224x |
| learner to teacher | 842,124,208 | 590,935,122 | **29.8%** | 1.425x |
| combined | 1,720,524,274 | 686,165,260 | **60.1%** | 2.507x |

The small compressed-byte variation was at most about `0.07%` in either direction and did not change the result.

## Evidence invariants

All six runs logged the same state shape and semantic reconnect work:

- learner/teacher sizes: `9,999,999` / `11,150,666`;
- `internalCleanHashesTotal=422367`;
- `internalHashesTotal=3594241`;
- `leafCleanDataTotal=4666556`;
- `leafDataTotal=9772810`;
- both transfer totals: `13367051`;
- traversal, latency, bandwidth, stream buffer, and effective socket diagnostics matched the intended fixed profile;
- each run's exported `settingsUsed.txt` matched its intended compression mode.

Read-pacing evidence also shows that compression removed most modeled network waiting. Uncompressed runs averaged
about `50.5 s` teacher-to-learner and `55.2 s` learner-to-teacher parked time. Compressed runs averaged effectively
zero teacher-to-learner parked time and `19.8 s` learner-to-teacher parked time. These totals overlap across directions
and threads and must not be subtracted from wall-clock time; their useful signal is that compressed runs were less
network-limited despite taking longer overall.

GC does not explain the timing difference. Per-run aggregate pause time ranged from `1.93 s` to `3.50 s`; compressed
and uncompressed means differed by only about `0.54 s`, versus a `59.19 s` difference in mean reconnect time. The
largest individual pause was `1.34 s`.

## Interpretation

1. **Production stream configuration is observable in the benchmark.** Enabling `socket.gzipCompression` changed the
   measured bytes below the compression layer while leaving semantic work unchanged.
2. **Compression loses decisively at this local profile.** A `60.1%` reduction in total wire bytes did not compensate
   for compression-path overhead at `270 us` and `200 Mbit/s`; median reconnect time increased by `71.8%`.
3. **The result is not a bandwidth-shaping artifact.** Compression substantially reduced pacing waits, especially in
   the teacher-to-learner direction, but the overall reconnect still became slower.
4. **This experiment does not isolate the exact overhead mechanism.** The additional time can include DEFLATE CPU
   cost, synchronous-flush behavior, and resulting protocol scheduling. A `LOOPBACK` comparison or CPU profile would
   be needed to divide those effects.
5. **This is a local operating point, not a universal compression policy.** A lower-bandwidth link, different CPU,
   different state entropy, or production contention can move the crossover. Cluster evidence is required before
   applying the result to deployment defaults.

## Cleanup and caveats

- N=3 per mode, one fork and one single-shot operation per invocation, no warmup: enough for the large local
  separation, not a broad statistical performance claim.
- This is macOS loopback TCP with read-side network modeling, not a physical inter-node link.
- Verification was disabled; equivalence evidence is the stable state shape and semantic reconnect counters.
- `platform-sdk/swirlds-benchmarks/settings.txt` was restored byte-for-byte to its pre-run contents after run 6.
- The experiment introduced no source, build, or module-info change; only this evidence documentation remains.
