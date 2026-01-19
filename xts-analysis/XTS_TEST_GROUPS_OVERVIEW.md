# XTS (Extended Test Suite) Test Groups Overview

## Summary

The XTS test suite is defined in `.github/workflows/zxc-xts-tests.yaml` and consists of multiple test groups that run in CI. This document provides an overview of all test groups, their structure, and execution modes.

---

## HAPI Test Execution Modes

Understanding the three execution modes is critical for test optimization:

### Mode Comparison

|          Aspect           |     Subprocess     |         Embedded Concurrent          |         Embedded Repeatable          |
|---------------------------|--------------------|--------------------------------------|--------------------------------------|
| **Gradle Task**           | `testSubprocess`   | `testEmbedded`                       | `testRepeatable`                     |
| **System Property**       | (default)          | `hapi.spec.embedded.mode=concurrent` | `hapi.spec.embedded.mode=repeatable` |
| **Network**               | Real JVM processes | In-process, single node              | In-process, single node              |
| **Communication**         | Real gRPC          | Direct method calls                  | Direct method calls                  |
| **`sleepFor()` behavior** | Real wait          | Real wait                            | **Virtual time (instant)**           |
| **Parallelism**           | ✅ Supported        | ✅ Supported                          | ❌ Sequential only                    |
| **ECDSA keys**            | ✅ Supported        | ✅ Supported                          | ❌ Not deterministic                  |
| **Node lifecycle**        | ✅ Supported        | ❌ Not available                      | ❌ Not available                      |
| **Speed**                 | Slowest            | Medium                               | **Fastest**                          |

### Mode Selection by Annotation

|      Annotation       |        Target Mode         |         Use Case          |
|-----------------------|----------------------------|---------------------------|
| `@HapiTest`           | Shared network (per-class) | Default tests             |
| `@EmbeddedHapiTest`   | Embedded concurrent        | State access, skip ingest |
| `@RepeatableHapiTest` | Embedded repeatable        | Virtual time, determinism |
| `@LeakyHapiTest`      | Subprocess                 | Tests with side effects   |
| `@GenesisHapiTest`    | Fresh embedded network     | Genesis-state tests       |

---

## Main XTS Test Groups

### 1. XTS Timing Sensitive Tests

**Workflow:** `zxc-execute-timing-sensitive-tests.yaml`
**Gradle Task:** `timingSensitive`
**Execution Mode:** Standard JUnit (unit tests)

Tests that are sensitive to timing and require isolation from other tests.

**Modules with Timing Sensitive Tests:**
- `platform-sdk/swirlds-common`
- `platform-sdk/consensus-metrics`
- `platform-sdk/base-crypto`
- `platform-sdk/swirlds-platform-core`
- `platform-sdk/consensus-model`
- `platform-sdk/base-concurrent`
- `platform-sdk/swirlds-logging`
- `platform-sdk/swirlds-base`

---

### 2. XTS Time Consuming Tests

**Workflow:** `zxc-execute-time-consuming-tests.yaml`
**Gradle Task:** `timeConsuming`
**Execution Mode:** Standard JUnit (unit tests)

Unit tests that take longer to execute.

**Modules with Time Consuming Tests:**
- `platform-sdk/swirlds-logging`

---

### 3. XTS Hammer Tests

**Workflow:** `zxc-execute-hammer-tests.yaml`
**Gradle Task:** `hammer`
**Execution Mode:** Standard JUnit (stress tests)

Stress/load tests for database operations.

**Modules with Hammer Tests:**
- `platform-sdk/swirlds-merkledb`
- `platform-sdk/swirlds-virtualmap`

---

### 4. XTS HAPI Tests

**Workflow:** `zxc-execute-hapi-tests.yaml`

The largest test group, divided into multiple sub-categories across three execution modes:

#### Subprocess Mode Tests (~280 classes)

|          Sub-group           |           Gradle Task            |              Tag              | Est. Classes |
|------------------------------|----------------------------------|-------------------------------|--------------|
| HAPI Tests (Misc)            | `hapiTestMisc`                   | Everything NOT in other tags  | ~100         |
| HAPI Tests (Misc Records)    | `hapiTestMiscRecords`            | Misc with RECORDS stream mode | ~100         |
| HAPI Tests (Crypto)          | `hapiTestCrypto`                 | `CRYPTO`                      | ~30          |
| HAPI Tests (Token)           | `hapiTestToken`                  | `TOKEN`                       | ~40          |
| HAPI Tests (Smart Contracts) | `hapiTestSmartContract`          | `SMART_CONTRACT`              | ~80          |
| HAPI Tests (Simple Fees)     | `hapiTestSimpleFees`             | `SIMPLE_FEES`                 | ~5           |
| HAPI Tests (Time Consuming)  | `hapiTestTimeConsuming`          | `LONG_RUNNING`                | ~7           |
| HAPI Tests (Restart)         | `hapiTestRestart`                | `RESTART\|UPGRADE`            | ~5           |
| HAPI Tests (ND Reconnect)    | `hapiTestNDReconnect`            | `ND_RECONNECT`                | ~3           |
| HAPI Tests (ISS)             | `hapiTestIss`                    | `ISS`                         | ~1           |
| HAPI Tests (Block Node)      | `hapiTestBlockNodeCommunication` | `BLOCK_NODE`                  | ~5           |

#### Embedded Mode Tests

|          Sub-group          |       Gradle Task        |             Tag             |
|-----------------------------|--------------------------|-----------------------------|
| HAPI Embedded (Misc)        | `hapiEmbeddedMisc`       | `EMBEDDED` excluding others |
| HAPI Embedded (Simple Fees) | `hapiEmbeddedSimpleFees` | `EMBEDDED & SIMPLE_FEES`    |

#### Repeatable Mode Tests

|       Sub-group        |     Gradle Task      |     Tag      |
|------------------------|----------------------|--------------|
| HAPI Repeatable (Misc) | `hapiRepeatableMisc` | `REPEATABLE` |

---

### 5. XTS Otter Tests

**Workflow:** `zxc-execute-otter-tests.yaml`
**Module:** `platform-sdk/consensus-otter-tests`
**Execution Mode:** Container-based integration tests

Platform-level integration tests using Otter framework.

**Test Targets:**
- `testOtter` - Fast otter tests
- `testContainer` - Full container-based tests
- `testTurtle` - Turtle environment tests

---

### 6. XTS Chaos Otter Tests

**Workflow:** `zxc-execute-otter-tests.yaml` (with `enable-chaos-otter-tests: true`)
**Module:** `platform-sdk/consensus-otter-tests`

**Test Target:** `testChaos`

Chaos engineering tests for platform resilience.

---

### 7. Regression Panels (External)

These panels run external integration tests:

|              Panel              |          Description          |
|---------------------------------|-------------------------------|
| Hedera Node JRS Panel           | JRS regression tests          |
| SDK TCK Regression Panel        | SDK TCK compatibility tests   |
| Mirror Node Regression Panel    | Mirror node integration tests |
| JSON-RPC Relay Regression Panel | JSON-RPC relay tests          |
| Block Node Regression Panel     | Block node integration tests  |

---

## Test Tag Definitions

From `com.hedera.services.bdd.junit.TestTags`:

|        Tag        |                   Description                    |      Can Run In      |
|-------------------|--------------------------------------------------|----------------------|
| `MATS`            | Minimum Acceptance Test Suite - quick validation | All modes            |
| `CRYPTO`          | Crypto service tests                             | Subprocess, Embedded |
| `TOKEN`           | Token service tests                              | Subprocess, Embedded |
| `SMART_CONTRACT`  | Smart contract tests                             | Subprocess, Embedded |
| `LONG_RUNNING`    | Long running/time consuming tests                | Subprocess           |
| `RESTART`         | Restart scenario tests                           | **Subprocess only**  |
| `UPGRADE`         | Upgrade scenario tests                           | **Subprocess only**  |
| `ND_RECONNECT`    | Node death reconnect tests                       | **Subprocess only**  |
| `ISS`             | Invalid State Signature tests                    | **Subprocess only**  |
| `BLOCK_NODE`      | Block node communication tests                   | Subprocess           |
| `SIMPLE_FEES`     | Simple fees tests                                | Subprocess, Embedded |
| `INTEGRATION`     | Embedded integration tests                       | Embedded             |
| `ONLY_SUBPROCESS` | Must run in subprocess mode                      | **Subprocess only**  |
| `ONLY_EMBEDDED`   | Must run in embedded mode                        | **Embedded only**    |
| `ONLY_REPEATABLE` | Must run in repeatable mode                      | **Repeatable only**  |
| `NOT_REPEATABLE`  | Cannot run in repeatable mode                    | Subprocess, Embedded |
| `ADHOC`           | Can run standalone                               | All modes            |

---

## Test Conversion Potential

Based on our analysis of subprocess-mode tests:

### Conversion Statistics

|    Category     |  Total   | → Embedded | → Repeatable |
|-----------------|----------|------------|--------------|
| CRYPTO          | ~30      | 83%        | 40%          |
| TOKEN           | ~40      | 95%        | 87%          |
| SMART_CONTRACT  | ~80      | 87%        | 50%          |
| RESTART/UPGRADE | ~5       | 0%         | 0%           |
| ND_RECONNECT    | ~3       | 0%         | 0%           |
| ISS             | ~1       | 0%         | 0%           |
| LONG_RUNNING    | ~7       | 100%       | 28%          |
| BLOCK_NODE      | ~5       | 20%        | 0%           |
| SIMPLE_FEES     | ~5       | 60%        | 60%          |
| MISC            | ~100     | 80%        | 50%          |
| **TOTAL**       | **~280** | **~80%**   | **~51%**     |

### Tests That Must Stay in Subprocess

- Tests using `FakeNmt` (node lifecycle): 19 files
- Tests with `ONLY_SUBPROCESS` tag: 5 files
- Tests using `setNode()` for multi-node testing: 22 files
- `RESTART`/`UPGRADE`/`ND_RECONNECT`/`ISS` tests: ~12 files

### Conversion Blockers for Repeatable Mode

- **ECDSA usage**: 57 files - random signatures break determinism
- **`inParallel()` operations**: 22 files - requires concurrent execution

---

## Optimization Recommendations

### High-Value Conversions

1. **Schedule tests** → Repeatable mode (virtual time benefits)
2. **Token CRUD tests** → Embedded mode (faster, no subprocess overhead)
3. **Fee validation tests** → Repeatable mode (deterministic)

### Demonstrations

- **Repeatable mode example:** `ScheduleExecutionRepeatableTest.java`
- **Comparison with original:** `ScheduleExecutionTest.java`

---

## File References

- **XTS Main Workflow:** `.github/workflows/zxc-xts-tests.yaml`
- **HAPI Tests:** `.github/workflows/zxc-execute-hapi-tests.yaml`
- **Timing Sensitive:** `.github/workflows/zxc-execute-timing-sensitive-tests.yaml`
- **Time Consuming:** `.github/workflows/zxc-execute-time-consuming-tests.yaml`
- **Hammer Tests:** `.github/workflows/zxc-execute-hammer-tests.yaml`
- **Otter Tests:** `.github/workflows/zxc-execute-otter-tests.yaml`
- **Test Tags:** `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/TestTags.java`
- **Test Clients Build:** `hedera-node/test-clients/build.gradle.kts`

---

## Related Analysis Documents

- `SUBPROCESS_MODE_TESTS.md` - Complete list of subprocess-mode tests
- `SUBPROCESS_TESTS_CONVERTIBLE.md` - Detailed conversion analysis
- `TESTS_WITH_SLEEPS_ANALYSIS.md` - Sleep optimization recommendations
