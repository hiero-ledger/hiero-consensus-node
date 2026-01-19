# Tests with Sleeps - Analysis and Recommendations

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

### High Priority (Greatest Time Savings)

1. **Convert schedule-related tests to repeatable mode**
   - `ScheduleExecutionTest.java` (40+ tests)
   - `ScheduleLongTermExecutionTest.java`
   - Schedule tests benefit most from virtual time
2. **Convert fee validation tests to repeatable mode**
   - Multiple 1-2 second sleeps per test
   - Pure business logic - easy to convert

### Medium Priority

3. **Token/Crypto tests without ECDSA**
   - Large number of tests
   - Many are pure business logic

### Low Priority

4. **Tests with ECDSA** - Require more careful analysis
5. **Tests with parallelism** - May need restructuring
6. **Subprocess-only tests** - Cannot convert

---

## Related Documentation

- **Mode Overview:** See `SUBPROCESS_MODE_TESTS.md` for complete list of subprocess tests
- **Conversion Analysis:** See `SUBPROCESS_TESTS_CONVERTIBLE.md` for detailed conversion analysis
- **Demonstration:** See `ScheduleExecutionRepeatableTest.java` for working example
- **XTS Overview:** See `XTS_TEST_GROUPS_OVERVIEW.md` for test group structure
