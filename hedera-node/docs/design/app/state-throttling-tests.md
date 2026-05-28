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

`N` is the network size. Each test asserts that the observed rate is within a small percent of
the expected rate; the exact tolerance and per-bucket constants live in the test class.

The cases are run in a fixed order: an `@Order(1)` step uploads the artificial throttle
definitions, the TPS/QPS cases follow, and a final step restores the original definitions so
the embedded network returns to its dev configuration.

## Throttle definitions used

The test temporarily overrides the network's throttle definitions with an artificial-limits
file shipped under `test-clients` test resources. The file defines four buckets —
`ThroughputLimits`, `PriorityReservations`, `CreationLimits`, and `FreeQueryLimits` — whose
group rates and operation lists determine the expected per-node rates in the table above.

The test does **not** rely on the production throttle definitions; the artificial limits are
chosen small enough that the measurement window reliably saturates them.

## Why the rates divide by `N`

Throttle definitions express network-wide rates. The frontend `ThrottleAccumulator` divides
capacity by the number of nodes participating in admission control, so each node accepts
roughly `network rate / N` of each bucket's traffic. The test expectations encode that split
directly, which means the configured network size must match the actual node count under test
for the assertions to be meaningful.

## Background load

Each TPS check runs the target operation through `runWithProvider(...)` in parallel with a
*competing client* (constructed by `competingClientFor(txn)` in the test) so the relevant
buckets stay saturated while the measurement is taken:

- For `ContractCalls`, the competing client uploads a payable contract and issues `deposit`
  calls — keeping `PriorityReservations` and the `ContractCall` portion of `ThroughputLimits`
  pressured.
- For all other cases, the competing client creates a topic and then submits messages with
  the topic id omitted, so each request is rejected before consensus but still consumes
  capacity in the `ConsensusSubmitMessage` lane of `ThroughputLimits`. The effect is to keep
  that bucket pressured so the bucket-under-measurement is the binding constraint.

`BUSY` is expected at the throttle's limit. Other tolerated outcomes (e.g. transient
status starvation under resource-constrained CI runners) are enumerated in the test class
itself rather than redocumented here.

## Run characteristics

The suite is driven by a dedicated `hapiTestStateThrottling` Gradle task on `test-clients`,
and a corresponding CI job runs the same task. Two characteristics are essential to keep in
mind when interpreting or updating it:

- **It must run serially.** The cases measure observed TPS to within a small tolerance, so
  any parallel HAPI traffic on the same network would invalidate the assertions. The suite is
  excluded from the catch-all parallel test groupings and is the only suite using its tag.
- **The network is held quiet.** The CI configuration disables background activity that would
  otherwise add unrelated traffic to the buckets being measured. The exact override list is
  defined in the build script and may evolve; the principle is to keep the network minimally
  busy while the measurement is taken.

**SEE ALSO:** [`throttles.md`](throttles.md) — the throttle subsystem this suite validates.
