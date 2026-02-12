# Concurrent Crypto Test Execution

Enables concurrent execution of crypto tests in CI with proper validation.

## Test Execution Flow

1. Network starts
2. Crypto tests run concurrently
3. `ConcurrentSubprocessValidationTest` runs last:
   - Log validation (network still active)
   - Stream validation (freezes network)
4. Network terminates

## Gradle Task Configuration

The `hapiTestCrypto` PR check runs all three phases in order:

| Phase |       Gradle Task        |         Test Task          |      Description      |
|-------|--------------------------|----------------------------|-----------------------|
| 1     | `hapiTestCrypto`         | `testSubprocessConcurrent` | Parallel crypto tests |
| 2     | `hapiTestCryptoEmbedded` | `testEmbedded`             | Embedded crypto tests |
| 3     | `hapiTestCryptoSerial`   | `testSubprocess`           | Serial crypto tests   |

- `testSubprocessConcurrent` excludes `SERIAL` tests except `CONCURRENT_SUBPROCESS_VALIDATION`
- Parallel execution uses fixed parallelism strategy with 4 concurrent threads

## Validation for Subprocess Concurrent

### How validation is guaranteed to run last

`ConcurrentSubprocessValidationTest` uses a latch-based mechanism to ensure it runs after all other test classes:

1. **`@Tag("CONCURRENT_SUBPROCESS_VALIDATION")`** - Bypasses `SERIAL` exclusion in the Gradle tag filter
2. **`@BeforeAll` + `ConcurrentSubprocessValidationLatch`** - Blocks before lock acquisition until all other classes finish

The latch mechanism works as follows:

- `SharedNetworkLauncherSessionListener` detects subprocess concurrent mode via the `hapi.spec.subprocess.concurrent` system property
- On plan start, it counts all non-validation test classes and arms a `CountDownLatch`
- As each non-validation class finishes, the listener counts down the latch
- The validation class has a `@BeforeAll` method that awaits the latch

The critical detail: `@BeforeAll` runs **before** JUnit acquires the method-level `READ_WRITE` lock from `@LeakyHapiTest`. This means no resource locks are held while waiting, so other tests run freely with no risk of deadlock. Once the latch opens, JUnit acquires the exclusive lock and validation runs with full network access.

`ForkJoinPool.ManagedBlocker` is used in the await so the pool creates a compensation thread instead of losing parallelism.

### Why a wrapper class is required

The original `LogValidationTest` and `StreamValidationTest` are separate classes. In concurrent class mode, separate classes can overlap in execution. Since `StreamValidationTest` **freezes the network**, running it alongside `LogValidationTest` would cause log validation to fail.

`ConcurrentSubprocessValidationTest` wraps both in a single test method:

```java
return hapiTest(
    validateAllLogsAfter(VALIDATION_DELAY),  // First: log validation
    validateStreams());                       // Second: stream validation (freezes network)
```

`hapiTest()` executes operations in declaration order, guaranteeing logs are validated before the network freeze.

## Validation for Embedded

Embedded tests use the standalone `LogValidationTest` and `StreamValidationTest`:
- `LogValidationTest`: `@Order(Integer.MAX_VALUE - 1)` - runs second-to-last
- `StreamValidationTest`: `@Order(Integer.MAX_VALUE)` - runs last

**Block validators by network type:**

|             Validator              | Embedded | Subprocess |           Reason            |
|------------------------------------|----------|------------|-----------------------------|
| `BlockContentsValidator`           | Yes      | Yes        | Validates block structure   |
| `BlockNumberSequenceValidator`     | Yes      | Yes        | Validates block sequence    |
| `StateChangesValidator`            | No       | Yes        | Requires saved Merkle state |
| `TransactionRecordParityValidator` | No       | Yes        | Requires saved state        |

Embedded validation is lighter because there's no persistent Merkle tree to validate against.

## Serial Tests

- Classes annotated with `@OrderedInIsolation` or methods with `@LeakyHapiTest` are tagged `SERIAL`
- These run via `hapiTestCryptoSerial` in subprocess sequential mode
- `testSubprocessConcurrent` excludes `SERIAL` tests (except `CONCURRENT_SUBPROCESS_VALIDATION`) to avoid state conflicts during parallel execution
