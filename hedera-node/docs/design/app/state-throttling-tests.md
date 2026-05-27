# Steady-state Throttling Test Suite (`STATE_THROTTLING`)

This document describes the HAPI test suite tagged `STATE_THROTTLING`, which validates that the
runtime throttle subsystem reaches the expected **steady-state** throughput when driven at
saturation under a deterministic set of bucket definitions.

For the throttling feature itself, see [`throttles.md`](throttles.md).

## What it tests

The single test class
`com.hedera.services.bdd.suites.throttling.SteadyStateThrottlingTest` measures observed TPS/QPS
for a small set of operation families while the network is held at saturation by background
"competing client" traffic, and asserts that each observed rate matches the expected per-node
rate within a tolerance.

Each case maps to one bucket in the test throttle definitions and exercises one operation
listed in that bucket:

|        Test case        |         Bucket         |         Operation         |                Expected per-node rate                 |
|-------------------------|------------------------|---------------------------|-------------------------------------------------------|
| `checkXfersTps`         | `ThroughputLimits`     | `CryptoTransfer`          | `THROUGHPUT_LIMITS_XFER_NETWORK_TPS / N`              |
| `checkFungibleMintsTps` | `ThroughputLimits`     | `TokenMint`               | `THROUGHPUT_LIMITS_FUNGIBLE_MINT_NETWORK_TPS / N`     |
| `checkContractCallsTps` | `PriorityReservations` | `ContractCall`            | `PRIORITY_RESERVATIONS_CONTRACT_CALL_NETWORK_TPS / N` |
| `checkCryptoCreatesTps` | `CreationLimits`       | `CryptoCreate`            | `CREATION_LIMITS_CRYPTO_CREATE_NETWORK_TPS / N`       |
| `checkBalanceQps`       | `FreeQueryLimits`      | `CryptoGetAccountBalance` | `BALANCE_QUERY_LIMITS_QPS / N`                        |

`N` is the network size (`hapi.spec.network.size`, default `4`, CI override `3`). Tolerated
deviation is `7%` of the expected rate.

The test class is `@OrderedInIsolation`; the seven cases run in this fixed order:

1. `setArtificialLimits` — uploads the test throttle definitions described below.
2. `checkXfersTps`
3. `checkFungibleMintsTps`
4. `checkContractCallsTps`
5. `checkCryptoCreatesTps`
6. `checkBalanceQps`
7. `restoreDevLimits` — restores the original throttle definitions so the embedded network
   returns to its dev configuration.

## Throttle definitions used

The test temporarily overrides the network's throttle definitions with
`hedera-node/test-clients/src/main/resources/testSystemFiles/artificial-limits.json` via
`SysFileOverrideOp(THROTTLES, ...)`. The file defines four buckets — `ThroughputLimits`,
`PriorityReservations`, `CreationLimits`, and `FreeQueryLimits` — whose group rates and
operation lists determine the expected per-node rates in the table above. See
`artificial-limits.json` for the current bucket contents.

The test does **not** rely on the production `throttles.json`; the artificial limits are chosen
small enough that the measurement window (180 s by default) reliably saturates them.

## Why the rates divide by `N`

Throttle definitions express network-wide rates. The frontend `ThrottleAccumulator` divides
capacity by the configured `capacitySplit` (number of nodes that participate in admission
control), so each node accepts roughly `network rate / N` of each bucket's traffic. The test
expectations encode that split directly. Setting `hapi.spec.network.size` to a value different
from the actual node count will skew the assertions.

## Background load

Each TPS check runs the target operation through `runWithProvider(...)` in parallel with a
*competing client* (constructed by `competingClientFor(txn)` in the test) so the relevant
buckets stay saturated while the measurement is taken:

- For `ContractCalls`, the competing client uploads a payable contract and issues `deposit`
  calls — keeping `PriorityReservations` and the `ContractCall` portion of `ThroughputLimits`
  pressured.
- For all other cases, the competing client creates a topic `"ntb"` and then submits messages
  to it with `omittingTopicId()` (expecting `INVALID_TOPIC_ID` or `BUSY` precheck). The point is
  to fill the `ConsensusSubmitMessage` lane of the 100 ops/s `ThroughputLimits` group without
  actually running consensus work — keeping that bucket pressured so the bucket-under-
  measurement is the binding constraint.

`PERMITTED_STATUSES = {BUSY, SUCCESS, RECEIPT_NOT_FOUND}`. The first is expected at the
throttle's limit; the third is tolerated only when client threads are starved on small CI
runners.

## How to run

Local Gradle target (single-machine subprocess network):

```
./gradlew :test-clients:hapiTestStateThrottling
```

In CI this is the same target, executed from the `Node: HAPI Test (State Throttling)` job in
`.github/workflows/zxc-execute-hapi-tests.yaml` and gated by
`enable-hapi-tests-state-throttling: true`.

### Tag expression and configuration

From `hedera-node/test-clients/build.gradle.kts`:

- Tag expression: `(STATE_THROTTLING&SERIAL)` — runs only `@Tag(STATE_THROTTLING)` classes that
  are also tagged `SERIAL`. Other suites exclude `STATE_THROTTLING` via the `miscTags`
  blocklist so steady-state assertions never compete with parallel test traffic on a shared
  network.
- Network size override: `hapiTestStateThrottling` is forced to `3` (see
  `prCheckNetSizeOverrides`), so the expected per-node rates above use `N = 3` under CI.
- Property overrides: `nodes.nodeRewardsEnabled=false,quiescence.enabled=true` — the same
  combination used by `hapiTestMisc`, `hapiTestMiscSerial`, `hapiTestAtomicBatch`, and other
  suites that need a minimally-busy background network for stable assertions.
- Initial port: `28600`.

### Tolerance

`TOLERATED_PERCENT_DEVIATION = 7` — `|actual/expected - 1| * 100 ≤ 7`. Flakes typically come
from CI scheduler jitter; if a single case fails by a small margin in isolation, re-run before
investigating the throttle subsystem.

## When to update this suite

Update the suite when any of the following change:

- The buckets, operations, or `opsPerSec` values in `artificial-limits.json`.
- The `capacitySplit` semantics or default network size handling in `ThrottleAccumulator`.
- The set of `PERMITTED_STATUSES` (e.g. a new precheck outcome appears at the throttle limit).

When `THROUGHPUT_LIMITS_*`, `PRIORITY_RESERVATIONS_*`, `CREATION_LIMITS_*`, or
`BALANCE_QUERY_LIMITS_QPS` constants in the test class drift from `artificial-limits.json`, the
assertions silently start measuring the wrong target — keep them in sync.

**SEE ALSO:** [`throttles.md`](throttles.md) — the throttle subsystem this suite validates.
