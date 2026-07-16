# 10M Read-Pacing Matrix Experiment Design

Status: `approved for implementation planning`
Date: `2026-07-16`
Predecessor: [`2026-07-08-read-pacing-smoke-matrix.md`](../evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-08-read-pacing-smoke-matrix.md)

## Objective

Repeat the July 8 read-pacing socket-buffer matrix with a newly generated 10-million-record base state. Treat the new
matrix as a fresh, internally paired experiment because the hardware, operating system, JVM patch version, benchmark
code, and benchmark defaults differ from the July 8 environment. Compare cells within the new matrix. Compare the new
matrix with July 8 only by qualitative shape and normalized effects, never by absolute wall-clock time.

The experiment tests whether the current read-side pacer still exposes the expected relationship between live socket
windows and reconnect wall-clock at a larger state:

- the 270-microsecond control leg should remain bandwidth-governed and should not show a meaningful, stable
  socket-buffer ordering;
- the 50,000-microsecond binding leg is expected to show `32 KiB > unset > 1 MiB` wall-clock time;
- any different result is evidence to analyze, not a reason to discard or reshape the data.

## Scope And Authorization

The user explicitly approved temporary edits to production
`platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java`
between runs. No other production/runtime file may be changed. Keep the existing socket-buffer diagnostics in every
variant and restore `SocketFactory.java` to its current committed 1 MiB configuration after the final cell.

Benchmark configuration, task-local documentation, temporary run-control files, and generated benchmark artifacts may
be changed or created as required. Do not change `platform-sdk/swirlds-benchmarks/settings.txt`: it already has the
required values:

```text
benchmark.benchmarkData,                       data
benchmark.saveDataDirectory,                   true
benchmark.verifyResult,                        false
virtualMap.reconnectMode,                      pullTopToBottom
```

The user removed `platform-sdk/swirlds-benchmarks/data/ReconnectBench` before planning. Its absence must be verified
again immediately before state generation.

## Fixed Experiment Inputs

| Area | Value |
|---|---|
| Base-state request | `numFiles=1000`, `numRecords=10000` |
| Requested base records | `10,000,000` |
| Random seed | `9823452658` |
| Teacher add / modify / remove | `0.10 / 0.40 / 0.00` |
| Key / record size | `32 / 128` bytes |
| `maxKey` / `numThreads` | `10000000 / 32` |
| Traversal | `pullTopToBottom` |
| Network profile | `REALISTIC`, loopback socket transport |
| Bandwidth | `200 Mbit/s` per direction |
| Control latency | `270 us` one-way (`0.54 ms` modeled RTT) |
| Binding latency | `50,000 us` one-way (`100 ms` modeled RTT) |
| JMH shape | single-shot, one fork, no warmup, three measurement iterations per cell |
| Benchmark JVM | current Gradle defaults: `-Xms24g -Xmx24g -XX:+AlwaysPreTouch` |
| Buffer variants | unset, `32 KiB`, `1 MiB` |

The current socket-only benchmark no longer exposes `networkTransport` or `networkInflightBytesLimit`. This does not
remove a control from the effective July 8 socket experiment: the old socket transport was selected explicitly and
ignored the simulated in-flight limit.

The current source annotation requests one measurement iteration. Use a temporary Gradle init script outside the
repository to set the `jmhReconnect` task's `iterations` property to `3` for measured cells. Do not change
`ReconnectBench.java` or `build.gradle.kts` for this run-control override. Accept a measured invocation only if its JMH
header reports three single-shot measurement iterations, one fork, and no warmup.

## Canonical State Lifecycle

1. Verify that `platform-sdk/swirlds-benchmarks/data/ReconnectBench` does not exist.
2. Run one preparation invocation with the fixed state parameters and the committed 1 MiB socket configuration. This
   invocation generates and saves `teacher/saved0` and `learner/saved0`; its reconnect score is discarded.
3. Require a successful preparation task, a learner size of exactly `9,999,999` (the builder populates
   `[1, 10,000,000)`), and a teacher size in `[10,990,000, 11,010,000]` after the deterministic 10% add phase.
4. Record the exact learner and teacher sizes and verify both saved-state directories exist.
5. Every measured cell must log restoration of both maps from `saved0` and must report the exact sizes recorded during
   preparation. A measured cell that generates state or reports different sizes is invalid.
6. Preserve the canonical state after the experiment for possible confirmation runs.

All measured commands continue to pass the complete fixed state parameter set even though restored states are not
parameter-fingerprinted and those parameters do not mutate a restored map. This keeps JMH metadata truthful about the
state that was generated.

## Socket Variants

Each variant changes `SocketFactory.java` immediately before the cell that needs it:

- **unset:** do not call `setReceiveBufferSize()` on the server socket and do not call `setSendBufferSize()` or
  `setReceiveBufferSize()` on the client socket; retain all pre/post bind/connect readback logs;
- **32 KiB:** set the server receive buffer and client send/receive buffers to `32768`; retain readback logs;
- **1 MiB:** use the current committed implementation, setting those three requested buffers to `1 << 20`; retain
  readback logs.

The accepted socket's send buffer remains untouched in all variants, matching the July 8 experiment. Capture a source
snapshot or diff and all effective readbacks for each cell. OS-clamped or autotuned values need not equal the request,
but the pre-bind/pre-connect diagnostics must demonstrate that the intended source variant was active.

## Run Schedule

The preparation invocation runs first under the committed 1 MiB variant and is excluded from the matrix. The six
measured cells then run in this balanced order:

| Cell | Socket buffer | One-way latency | Leg |
|---:|---|---:|---|
| 1 | unset | `270 us` | control |
| 2 | `32 KiB` | `50,000 us` | binding |
| 3 | `1 MiB` | `270 us` | control |
| 4 | unset | `50,000 us` | binding |
| 5 | `32 KiB` | `270 us` | control |
| 6 | `1 MiB` | `50,000 us` | binding |

This interleaves buffer variants and latency legs so neither one is fully confounded with elapsed experiment time. It
also leaves `SocketFactory.java` in its committed 1 MiB state after cell 6.

## Execution Guards And Cell Acceptance

- Record the commit, tracked working-tree diff, hardware, macOS version, Java version, free disk space, and effective
  Gradle/JMH command before generation.
- Run the reconnect socket transport tests and JMH compilation preflight before generating the state.
- Use the macOS `caffeinate -i` wrapper for generation and every measured invocation. It prevents idle system sleep
  while the wrapped Gradle process is alive but does not alter benchmark/JVM parameters. Keep the machine powered,
  plugged in, and open.
- Avoid concurrent CPU-, disk-, or memory-heavy work. Record identifiable interference rather than silently accepting
  it.
- Do not run `clean` after state generation or during the matrix because it would remove build-side run artifacts.
- A cell is valid only if the task succeeds, all three scores are present, the JMH shape is correct, both maps are
  restored with canonical sizes, the intended socket variant is evidenced by source and readbacks, and no sleep or
  identified heavy interference occurred.
- If a cell is invalid, retain its artifacts with the reason, rerun the entire three-iteration cell, and mark the first
  attempt superseded. Never discard a run solely because its timing is an outlier.

## Artifact Capture

Create a per-run artifact directory under
`platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/`. Preserve the following before a later cell can
overwrite them:

- complete Gradle/JMH console output for preparation and every cell;
- JMH result file;
- reconnect GC log;
- effective `settingsUsed.txt`;
- `SocketFactory.java` snapshot or diff identifying the active variant;
- environment and preflight output;
- a small manifest mapping cell number, variant, latency, command, start/end time, exit status, and validity.

The build directory is working evidence, not the durable result. Do not remove it until the result note is complete and
reviewed.

## Analysis And Durable Documentation

After all cells are accepted, create
`25083-improve-reconnectbench/evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-16-read-pacing-10m-matrix.md`
and register it in the local calibration-notes hub and `25083-improve-reconnectbench/Index.md`.

Mirror the July 8 note's useful structure:

1. purpose, fresh-comparison caveat, environment, commit, and exact state-generation inputs;
2. commands, run order, JMH shape, state restoration evidence, and buffer readbacks;
3. all three `s/op` values per cell, mean, median, and iterations-2/3 warm mean;
4. per-direction pacing summaries: windows opened, last live window bytes, and total parked time;
5. transfer bytes, reconnect counters, GC anomalies, invalid or superseded attempts, and identifiable host interference;
6. control-leg interpretation, binding-leg ordering, unset autotuning trajectory, and agreement between observed
   throughput and the first-order `min(25 MB/s, W / RTT)` expectation;
7. qualitative comparison with July 8 using ordering, within-matrix ratios, pacing cadence, and window behavior, while
   explicitly refusing absolute-time comparison across machines/environments;
8. a direct conclusion on whether the buffer signal, control behavior, autotuning ramp, or pacing mechanism changed at
   10M, including evidence-backed hypotheses for any deviation.

Report every accepted iteration. Present means, medians, and warm means as descriptive statistics only; three
single-shot iterations do not support strong inferential claims.

## Completion State

The experiment is complete only when all six cells are accepted, the analysis note and indexes are updated, the raw
working artifacts remain available for review, and `SocketFactory.java` matches its committed 1 MiB state. The generated
10M saved state remains under `platform-sdk/swirlds-benchmarks/data/ReconnectBench`.
