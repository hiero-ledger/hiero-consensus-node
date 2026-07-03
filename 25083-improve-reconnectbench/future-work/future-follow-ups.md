# ReconnectBench Future Follow-Ups

Updated: `2026-07-02`

Purpose: concise list of deferred follow-up issues. These are not required for the current calibration phase or main
readiness unless new evidence makes them relevant.

## Follow-Up Issues To Create Later

### 1. Strict reconnect verification

Status: future follow-up; pre-existing benchmark issue to spin off separately.

Why:

- `VirtualMapBaseBench.verifyMap(...)` logs failures but does not throw.
- A wrong reconnected map can still produce a valid-looking JMH time if verification is enabled but non-fatal.

Future issue:

- Make reconnect verification fail the benchmark when `benchmark.verifyResult=true`.
- Either make `verifyMap(...)` return verification counts or add a reconnect-specific verifier that throws on bad or
  missing values.

### 2. Direction-specific network bandwidth and cap modeling

Status: future calibration improvement.

Why:

- Current `PairedStreams` uses one `NetworkSimulationConfig` for both directions.
- Historical cluster observations showed directional traffic differences:
  learner-to-teacher around `233-270 Mbps`, teacher-to-learner around `108-192 Mbps`.
- The current symmetric profile successfully predicted cluster results, so this is not needed now.

Future issue:

- Add optional benchmark-only teacher-to-learner and learner-to-teacher bandwidth/cap settings if future calibration
  needs asymmetric network behavior.
- Compare directional settings against the current symmetric model on the same saved state.

### 3. Loopback TCP transport validation

Status: future validation option only.

Why:

- The simulated channel versus loopback TCP validation supports the intended realistic operating-point trend.
- The benchmark now predicts cluster results in the accepted calibration regime.
- The zero-latency floor remains diagnostic-only and is not an evaluation regime.
- A permanent loopback-socket transport is therefore unnecessary for the current calibration phase.

Future issue:

- Reintroduce or keep a benchmark-only loopback TCP transport only if local runs stop predicting cluster behavior, or if
  future evidence points specifically to lower-level transport artifacts.
- If revived, compare loopback TCP and `SimulatedNetworkChannel` on the same saved state and capture JFR evidence for
  thread parking, scheduling, and monitor behavior.

### 4. Teacher workload and multi-process harness

Status: future calibration improvement.

Why:

- The MVP intentionally runs an idle teacher.
- The teacher and learner currently share one JVM and one process.
- The shared-JVM setup is a known limitation of this benchmark shape, not a current defect.

Future issue:

- Add an optional benchmark-only teacher-load profile if future calibration needs to study live teacher load effects.
- Consider a two-process harness only if a future local/cluster mismatch cannot be explained inside the current harness.

### 5. Extra model-vs-runtime diagnostics

Status: optional follow-up.

Why:

- Current wait counters are observed blocking time and are useful as-is.
- If future analysis needs a stricter model/runtime split, observed waits alone may be insufficient.

Future issue:

- Add separate requested/scheduled wait counters alongside the existing observed counters.
- Keep the existing observed counters for practical bottleneck interpretation.

## Explicitly Not Follow-Up Issues

Do not create new issues for these unless new evidence changes the premise:

- Saved-state shape overriding cluster-like parameters.
- Test coverage items from MVP development; those tests were temporary scaffolding.
- Generic network sweep scripts or per-run artifact naming from calibration work.
- `jmhReconnect` local calibration settings as an issue; clean them before merge instead.
- Gradle default divergence from design values; ship recommended run documentation instead of treating local defaults as
  the contract.
- Verification-array `int` overflow in the intended range; current runs are far below the threshold and verification is
  disabled for calibration-scale runs.
- Reused learner-map compaction degradation; reconnect uses a fresh detached copy per invocation.
- Transport validity at the realistic operating point; simulated-vs-loopback and cluster evidence validated the current
  benchmark trend.
