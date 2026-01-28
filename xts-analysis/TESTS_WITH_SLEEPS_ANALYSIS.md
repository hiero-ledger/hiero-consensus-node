# Tests with Sleeps - Analysis and Recommendations

---

## ⚠️ Key Finding: Major Optimizations Already Complete

**The largest sleep-related conversions have already been implemented.**

|                                     Test                                      |  Original Sleep  |         Current Status          |
|-------------------------------------------------------------------------------|------------------|---------------------------------|
| `DisabledLongTermExecutionScheduleTest.scheduledTestGetsDeletedIfNotExecuted` | **31 minutes**   | ✅ Already `@RepeatableHapiTest` |
| `TxnRecordRegression.receiptUnavailableAfterCacheTtl`                         | **179 seconds**  | ✅ Already `@RepeatableHapiTest` |
| `RepeatableHip423Tests` (all tests)                                           | 5-8 seconds each | ✅ Already `@RepeatableHapiTest` |
| `RepeatableScheduleLongTermSignTest` (all tests)                              | 6 seconds each   | ✅ Already `@RepeatableHapiTest` |

**The remaining convertible tests total only ~37 seconds of sleep time**, and due to subprocess parallelism (tests run concurrently), actual wall-clock savings would be minimal or potentially negative (since embedded/repeatable tests run sequentially).

**Recommendation:** Further conversions for time savings are **not recommended**. Consider conversion only for:
- **Determinism** (eliminating timing-based test flakiness)
- **Reduced flakiness** (virtual time eliminates race conditions)
- **Faster local development** (individual test runs)

---

## Key Insight: Understanding Sleep Behavior in Different Modes

### The Three Execution Modes

|          Mode           |      Network Type      |   `sleepFor()` Behavior    | `Thread.sleep()` Behavior |
|-------------------------|------------------------|----------------------------|---------------------------|
| **Subprocess**          | Real JVM processes     | Real wait                  | Real wait                 |
| **Embedded Concurrent** | In-process, parallel   | Real wait via `tick()`     | Real wait                 |
| **Embedded Repeatable** | In-process, sequential | **Virtual time** (instant) | Real wait                 |

### Critical Understanding

The `sleepFor()` utility in HAPI tests uses `sleepConsensusTime()` internally:

```java
// In HapiSpec.java
public void sleepConsensusTime(Duration duration) {
    if (targetNetworkOrThrow() instanceof EmbeddedNetwork embeddedNetwork) {
        embeddedNetwork.embeddedHederaOrThrow().tick(duration);  // Virtual time!
    } else {
        Thread.sleep(duration.toMillis());  // Real wait
    }
}
```

**This means:** HAPI tests using `sleepFor()` in repeatable mode already have "free" sleeps - no real waiting occurs.

---

## Optimization Strategy

### For HAPI Tests (BDD-style integration tests)

**Primary Strategy:** Convert tests from subprocess mode to embedded/repeatable mode

- Tests in **subprocess mode** → `sleepFor()` causes real waiting
- Tests in **embedded/repeatable mode** → `sleepFor()` advances virtual time instantly
- HAPI tests already have `sleepFor()` which works with virtual time

### For Unit Tests (JUnit tests with Thread.sleep)

**Primary Strategy:** Use Awaitility for condition-based polling

- Unit tests use `Thread.sleep()` which always waits in real time
- Awaitility provides polling until conditions are met
- Can significantly reduce test execution time

---

## Test Conversion Analysis Summary

Based on our comprehensive analysis of ~280 subprocess test classes:

|            Metric             |    Count     | Percentage |
|-------------------------------|--------------|------------|
| **Total subprocess tests**    | ~280 classes | 100%       |
| **Convertible to Embedded**   | ~224 classes | 80%        |
| **Convertible to Repeatable** | ~142 classes | 51%        |
| **Must stay in Subprocess**   | ~56 classes  | 20%        |

### Blockers for Repeatable Mode

- ECDSA key usage (57 files) - random signatures break determinism
- `inParallel()` usage (22 files) - requires concurrent execution
- Node lifecycle operations - `FakeNmt`, restart, reconnect tests

### Demonstration

See `ScheduleExecutionRepeatableTest.java` in `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/schedule/` for a working example of tests converted from `@HapiTest` to `@RepeatableHapiTest`.

---

## Detailed Analysis by Category

### Category 1: Tests That Should Convert to Repeatable Mode

These tests use `sleepFor()` but don't have blockers for repeatable mode:

|              Test File               |       Sleep Pattern        |    Conversion Benefit     |
|--------------------------------------|----------------------------|---------------------------|
| `CryptoRecordsSanityCheckSuite.java` | 1000ms wait                | Instant with virtual time |
| `RecordCreationSuite.java`           | Variable waits             | Instant with virtual time |
| `ContractCreateSuite.java`           | 3000ms for block.timestamp | Instant with virtual time |
| `CryptoServiceFeesSuite.java`        | 1000-2000ms waits          | Instant with virtual time |
| `StreamValidationOp.java`            | Multiple block waits       | Instant with virtual time |
| `ScheduleExecutionTest.java`         | Schedule timing            | Synchronous execution     |

**How to Convert:**

```java
// Change annotation from:
@HapiTest

// To:
@RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)

// Add class-level annotation:
@TargetEmbeddedMode(EmbeddedMode.REPEATABLE)
```

---

### Category 2: Tests That Must Stay in Subprocess Mode

These tests require actual subprocess behavior:

|               Test File               |            Reason             |     Sleep Purpose      |
|---------------------------------------|-------------------------------|------------------------|
| `DabEnabledUpgradeTest.java`          | Uses `FakeNmt` node lifecycle | Port unbinding wait    |
| `MixedOpsNodeDeathReconnectTest.java` | Tests node death/reconnect    | Wait for node recovery |
| `JustQuiesceTest.java`                | Tests quiescence behavior     | Real timing required   |
| `DuplicateManagementTest.java`        | Uses `uncheckedSubmit`        | Wait for duplicates    |
| `JumboTransactionsEnabledTest.java`   | Tests throttle behavior       | Throttle refill        |

---

### Category 3: Tests Already in Repeatable Mode

These tests already benefit from virtual time:

|             Test File             |                   Notes                    |
|-----------------------------------|--------------------------------------------|
| `RepeatableHip423Tests.java`      | Schedule expiry tests - sleeps are virtual |
| `RepeatableTssTests.java`         | TSS tests - sleeps are virtual             |
| `RepeatableIntegrationTests.java` | Integration tests - sleeps are virtual     |

**No changes needed** - sleeps are already instant.

---

### Category 4: Unit Tests with Thread.sleep()

For unit tests (not HAPI tests), use Awaitility:

|                Test File                |       Sleep Pattern       |     Recommendation     |
|-----------------------------------------|---------------------------|------------------------|
| `BlockNodeConnectionComponentTest.java` | Multiple 100-500ms sleeps | Use Awaitility polling |
| Various manager/service tests           | Thread.sleep() calls      | Use Awaitility polling |

**Example Pattern:**

```java
// Instead of:
Thread.sleep(500);
assertThat(value).isEqualTo(expected);

// Use:
Awaitility.await()
    .atMost(Duration.ofSeconds(2))
    .pollInterval(Duration.ofMillis(50))
    .until(() -> value == expected);
```

---

## Implementation Priority

### ✅ Already Complete (No Action Needed)

The highest-impact conversions have **already been done**:

1. **`DisabledLongTermExecutionScheduleTest.scheduledTestGetsDeletedIfNotExecuted`** - 31 minute sleep → instant
2. **`TxnRecordRegression.receiptUnavailableAfterCacheTtl`** - 179 second sleep → instant
3. **`RepeatableHip423Tests`** - All schedule expiry tests
4. **`RepeatableScheduleLongTermSignTest`** - All signature collection tests

### Low Priority (Minimal Time Savings)

The remaining convertible tests offer **diminishing returns**:

1. **Fee validation tests** - ~13 seconds total, run in parallel
2. **Contract query tests** - ~10 seconds total, run in parallel
3. **Token/Crypto tests** - Various small sleeps, run in parallel

**Note:** Due to subprocess parallelism, converting these tests may not improve wall-clock time and could potentially increase it (parallel → sequential execution).

### Not Recommended for Time Savings

4. **Tests with ECDSA** - Can only go to embedded mode
5. **Tests with parallelism** - Would need restructuring
6. **Subprocess-only tests** - Cannot convert

### Alternative: Consider for Determinism/Flakiness

If tests experience timing-related flakiness, conversion to repeatable mode may still be valuable regardless of time savings.

---

## Related Documentation

- **Mode Overview:** See `SUBPROCESS_MODE_TESTS.md` for complete list of subprocess tests
- **Conversion Analysis:** See `SUBPROCESS_TESTS_CONVERTIBLE.md` for detailed conversion analysis
- **Demonstration:** See `ScheduleExecutionRepeatableTest.java` for working example
- **XTS Overview:** See `XTS_TEST_GROUPS_OVERVIEW.md` for test group structure
