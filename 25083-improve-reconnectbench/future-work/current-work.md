# ReconnectBench Current Work

Updated: `2026-07-02`

Purpose: concise task list for work that should be completed before the ReconnectBench update is considered ready for
main. Calibration-critical concerns have been resolved or deferred; there are no active critical calibration risks in
this list.

## Current Priorities

### 1. Make interrupted reconnect fail loudly

Source: Claude review P3 and previous bug inventory.

Tasks:

- In `MerkleBenchmarkUtils.testSynchronization(...)`, treat `InterruptedException` from
  `workGroup.waitForTermination()` as failed benchmark work.
- Abort or release the learner state on interruption.
- Throw a `MerkleSynchronizationException` with the interruption as the cause.
- In synchronizer wrapper runnables, propagate interruption through the work group instead of only restoring the
  interrupt flag and returning normally.

Done when:

- Interrupted or cancelled reconnect work cannot return a partial learner map as a valid timed result.

### 2. Clean benchmark-only debug artifacts and stale comments

Source: Claude review P3/nitpicks.

Tasks:

- Resolve the `StateBuilder` `storageOptimizer.accept(i + size)` no-op and the committed
  `// FIXME: redundant copies of learner map` comment. Either restore the old index/comment behavior or make a real
  benchmark-only change that addresses the redundant-copy concern.
- Clarify `ReconnectBench`'s `teacherMapCopy` comment/name. The copy currently freezes the teacher map and keeps a live
  data source for the trial; it is not a mutable workload head.
- Update `PairedStreams` javadocs/comments that still describe it as test code. It is benchmark infrastructure.

Done when:

- The benchmark code no longer carries misleading test/debug wording from MVP development.

### 3. Make diagnostic labels precise

Source: bug inventory and Claude review P3.

Tasks:

- Label `capacityWaitNanos`, `emptyReadWaitNanos`, and `arrivalWaitNanos` as observed blocking time, not pure modeled
  network delay.
- Document or log that `emptyReadWait*` means the consumer is waiting for the peer to produce bytes.
- Keep the existing counters; current A/B evidence says they are useful and not a correctness problem.

Done when:

- Benchmark output and nearby docs cannot be misread as saying these counters are only configured latency, bandwidth, or
  cap delay.

### 4. Improve run provenance and network diagnostics

Source: remaining bug inventory items.

Tasks:

- Preserve reconnect and network stats on failure paths: snapshot/log both channel directions before work-group
  exceptions are thrown, and log stats before verification or via `try/finally`.
- Log a compact run header with generated/restored state, saved-state paths when applicable, effective teacher/learner
  size, seed/divergence parameters, traversal mode, transport/profile, and resolved network configuration.
- Add bandwidth-delay-product diagnostics:

```text
oneWayLatency
estimatedRtt
bandwidthBytesPerSecond
estimatedBdpBytes
networkInflightBytesLimit
inflightLimit / estimatedBdpBytes
```

Done when:

- A benchmark log is enough to identify the state, traversal, transport/profile, and whether the in-flight cap is near,
  above, or below the configured bandwidth-delay product.

### 5. Keep simulator parameter failures clear

Source: remaining bug inventory items.

Tasks:

- Validate `MIN_PROGRESSIVE_READ_BYTES` against small length-prefixed reconnect messages at low bandwidth. If it shifts
  first-message visibility, reduce it, document the acceptable distortion, or make the coalescing target configurable by
  benchmark profile.
- Guard extreme latency, bandwidth, in-flight cap, and derived transmit-duration values.
- Convert overflow or unsupported parameter combinations into `IllegalArgumentException` messages that name the bad
  benchmark parameter.
- Review `DEFAULT_RANGE_SIZE` interactions with very small or non-multiple in-flight caps. Document the current
  effective-window behavior if it is intentional; adjust it only if it changes intended simulation semantics.

Done when:

- Bad benchmark parameters fail clearly and intentional simulator edge behavior is documented.

### 6. Main-readiness cleanup

Source: Claude review P2.2/P2.3 dispositions and project triage discussion.

Tasks:

- Before merging to main, remove local-only `jmhReconnect` heap and GC-log settings, especially absolute paths under a
  developer machine.
- Keep calibration-specific values as local run configuration or documented recommended run parameters, not as surprising
  portable defaults.

Done when:

- A normal main-branch checkout can run `jmhReconnect` without user-specific paths or machine-specific memory floors.
