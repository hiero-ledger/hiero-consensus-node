# SimulatedNetworkChannel Bugs And Improvements

Date: `2026-05-28`

Purpose: record important bugs and improvements found while reviewing the ReconnectBench MVP simulated network stack.
This list is intentionally limited to items that can affect benchmark correctness, reproducibility, diagnostics, or
result interpretation.

Scope: benchmark code, benchmark tests/support code, benchmark documentation, and task-local docs only. Do not change
production/runtime consensus-node behavior for these items without explicit approval.

## Correctness

### Make reconnect verification fail the benchmark

`ReconnectBench.reconnect()` calls `verifyMap(...)` after synchronization, but `VirtualMapBaseBench.verifyMap(...)` only
logs verification failures. It does not throw when bad or missing values are found.

Why this matters: a wrong reconnected map can still produce a valid-looking JMH score and network-stat output. For
ReconnectBench, a measured run with an incorrect reconstructed state should be considered failed data.

Recommended fix: add strict reconnect verification when `benchmark.verifyResult=true`. Either make `verifyMap(...)`
return verification counts so `ReconnectBench` can throw, or add a reconnect-specific verification helper that throws on
bad or missing values.

### Treat interrupted reconnect work as failed work

`MerkleBenchmarkUtils.testSynchronization(...)` catches `InterruptedException` from `workGroup.waitForTermination()`,
shuts the work group down, restores the interrupt flag, and then continues to the normal exception/success checks. The
wrapper methods for the teaching and learning synchronizer main threads also catch `InterruptedException` and only
restore the thread interrupt flag.

Why this matters: cancelled or interrupted reconnect work can continue into the normal return path, potentially returning
partial learner state and misleading stats instead of failing clearly.

Recommended fix: after interruption, abort learner state and throw a `MerkleSynchronizationException` with the
interruption as cause. In the synchronizer wrapper methods, propagate interruption through the work group instead of
silently ending the wrapper runnable.

### Preserve network diagnostics on failure paths

`MerkleBenchmarkUtils` returns channel stats only on successful synchronizer completion. `ReconnectBench` logs reconnect
and network stats only after `verifyMap(...)` returns. Work-group exceptions, disconnects, and future strict verification
failures can therefore lose the simulator diagnostics that would explain what happened.

Why this matters: failed reconnects and incorrect reconstructed maps are the cases where channel stats are most useful.
Without stats, it is harder to tell whether failure involved partial delivery, backpressure, arrival timing, premature
disconnect, or missing peer work.

Recommended fix: snapshot and log both channel directions before throwing on work-group exceptions. In `ReconnectBench`,
log reconnect and network stats before verification, or use a `try`/`finally` around verification so failure reports keep
the diagnostic counters.

## Simulator Semantics And Safety

### Validate the read coalescing constant against message timing

`MIN_PROGRESSIVE_READ_BYTES` intentionally avoids tiny bandwidth-limited reads. That is useful, but it is also an
artificial receiver-side policy. If the receiver buffer asks for many bytes, the channel may wait for up to 64 bytes even
when fewer bytes are enough for the next reconnect message boundary.

Why this matters: at low bandwidths or with very small reconnect messages, this can shift first-message visibility.
Traversal comparisons are sensitive to response timing, so this behavior should be proven acceptable rather than assumed.

Recommended fix: add timing tests around small length-prefixed messages at low bandwidth. If those tests show measurable
distortion, make the coalescing target profile-dependent, expose it as a benchmark parameter, or reduce it.

### Make wait-counter semantics explicit

`capacityWaitNanos`, `emptyReadWaitNanos`, and `arrivalWaitNanos` measure observed wall-clock time around condition
waits. They are not pure mathematical estimates of configured latency, bandwidth, or capacity pressure.

Why this matters: these counters can include thread scheduling delay, lock reacquisition time, early wakeups, and JVM
pauses. If benchmark output is read as pure simulated network delay, analysis can over-attribute runtime to latency or
bandwidth.

Recommended fix: label these counters in docs and logs as observed blocking time. If model-vs-runtime separation becomes
necessary, add separate counters for requested/scheduled wait time in addition to the existing observed wait time.

### Guard simulator timing arithmetic against overflow

`SimulatedNetworkChannel` computes send and arrival timestamps by adding transmit duration and latency to
`System.nanoTime()` values. `NetworkSimulationConfig.resolve(...)` also converts user-supplied benchmark parameters with
`Math.multiplyExact(...)`.

Why this matters: extreme benchmark parameters can currently fail with raw arithmetic errors or, with direct construction
using very large resolved values, overflow during channel scheduling. A bad benchmark configuration should fail clearly,
not produce invalid timing.

Recommended fix: validate upper bounds for latency, bandwidth, in-flight cap, and derived transmit duration. Convert
arithmetic overflow into `IllegalArgumentException` messages that name the offending benchmark parameter.

### Validate range-size interaction with in-flight caps

`SimulatedNetworkChannel.write(...)` splits writes into ranges capped by `DEFAULT_RANGE_SIZE` and
`networkInflightBytesLimit`. Once a range size is chosen, `writeRange(...)` waits until the whole range fits under the
current in-flight cap.

Why this matters: for small or non-multiple in-flight caps, the fixed range size can leave part of the configured window
unused until the reader frees enough capacity for the next whole range. The default `131072` byte cap divides cleanly by
`8192`, but sensitivity runs and bandwidth-delay-product-sized profiles may use different caps. In those cases,
benchmark output can reflect a smaller effective window than the configured value.

Recommended fix: add tests for non-multiple and small in-flight caps, then either document the effective window behavior
or adjust range acceptance to use currently available capacity when that better matches the intended simulation.

### Add bandwidth-delay-product diagnostics

The simulator logs bytes, max in-flight bytes, and capacity waits. It should also log the configured bandwidth-delay
product and compare it with `networkInflightBytesLimit`.

Why this matters: if the in-flight cap is far above the bandwidth-delay product, backpressure may never participate. If
it is below the bandwidth-delay product, the benchmark may be testing an artificially constrained connection. Both cases
can be valid, but they should be visible in the run output.

Recommended fix: compute and log:

```text
oneWayLatency
estimatedRtt
bandwidthBytesPerSecond
estimatedBdpBytes
networkInflightBytesLimit
inflightLimit / estimatedBdpBytes
```

## Test Coverage

### Add a buffered stream test for progressive delivery

`SimulatedNetworkChannel` is designed to make bytes progressively readable even when `BufferedOutputStream.flush()` sends
a large chunk. This is central to the benchmark: reconnect traversal decisions depend on when small request and response
messages become visible, not only when an entire flushed byte buffer has arrived.

Current tests cover raw channel behavior and basic `DataInputStream`/`DataOutputStream` framing, but they should also
cover the actual benchmark stack:

```text
DataOutputStream
BufferedOutputStream
SimulatedNetworkChannel
BufferedInputStream
DataInputStream
```

Recommended fix: add a test with multiple length-prefixed messages in one buffered flush under bandwidth limiting, then
verify the receiver can read the first complete message before the full flushed payload has finished transmitting.

### Assert wait diagnostics directly

Current tests cover byte counters and `maxInflightBytes`, but not the wait counters that are central to benchmark
interpretation: `capacityWait*`, `emptyReadWait*`, and `arrivalWait*`.

Why this matters: these counters are used to distinguish backpressure, peer idleness, and simulated network timing. If
they regress silently, benchmark analysis can point to the wrong bottleneck.

Recommended fix: assert capacity waits in the backpressure test, arrival waits in latency/bandwidth tests, and add an
empty-read wait test where a reader blocks before any writer queues bytes.

### Add upper bounds to timing tests

The current latency and bandwidth tests check lower bounds, but they would still pass if the simulator over-delayed reads
by a large amount.

Why this matters: over-throttling would distort benchmark results while keeping the simulator test suite green.

Recommended fix: add generous upper bounds or `assertTimeoutPreemptively` around latency and bandwidth reads. Include a
proportional bandwidth test with two payload sizes so the test verifies scaling, not only that reads are delayed.

### Test normal close while a reader is already blocked

Current close tests verify that queued bytes drain and EOF is stable, but they do not prove that output close wakes a
reader already blocked on an empty channel.

Why this matters: normal EOF behavior is part of benchmark cleanup. A missed wakeup can turn reconnect completion or
cleanup into a hang.

Recommended fix: start a reader thread blocked on an empty channel, close the output stream, and assert the reader
promptly returns `-1`.

## Benchmark Reproducibility And Interpretation

### Move local calibration settings out of the generic `jmhReconnect` task

Current local changes to `platform-sdk/swirlds-benchmarks/build.gradle.kts` make `jmhReconnect` carry machine-specific
and calibration-specific defaults, including an absolute GC log path under `/Users/thenswan/...`, a 24 GiB heap, large
default state shape, high default latency, and a large in-flight cap.

Why this matters:

- the task can fail or write logs to the wrong place on another machine;
- a normal `jmhReconnect` run becomes an expensive local calibration run by default;
- benchmark results become harder to compare with the design docs, which describe smaller and more neutral defaults.

Recommended fix: keep `jmhReconnect` portable and modest by default. Put cluster-profile or local-calibration values
behind explicit Gradle properties, a separate task, or a local-only run script under `25083-improve-reconnectbench`.

### Log effective benchmark provenance with every run

The benchmark can either generate teacher/learner maps or restore existing saved maps. When saved maps are restored,
divergence parameters such as add/remove/modify probabilities no longer describe the actual state shape.

Why this matters: benchmark output can list parameters that did not actually create the tested state, which can lead to
wrong interpretation of traversal and network results.

Recommended fix: log whether maps were generated or restored, saved-state paths when applicable, teacher and learner
metadata, effective size, seed, divergence parameters, traversal mode, and resolved network configuration in one compact
run header.

### Make network sweep runs explicit and preserve per-run artifacts

`jmhParamProperty(...)` currently treats each Gradle property as a single JMH value, and all `jmhReconnect` runs write to
the same default results file.

Why this matters: network/profile sweeps are central to calibration. One-off parameters and a shared result path make it
easy to overwrite or blur evidence across latency, bandwidth, in-flight cap, traversal mode, and state-shape runs.

Recommended fix: support comma-separated/list JMH parameter properties or add explicit sweep scripts under
`25083-improve-reconnectbench`. Make the result path property-driven or include key dimensions and a timestamp in the
output artifact names.

### Keep benchmark fixes inside the allowed edit scope

The current task scope allows benchmark code, benchmark tests/support code, benchmark documentation, and task-local docs.
If a problem appears to require production changes, stop and document why before editing production code.

Why this matters: the purpose of this work is to improve ReconnectBench without changing node behavior. Production
changes would make benchmark results harder to interpret and would need a separate review path.
