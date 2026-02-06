# Concurrent Crypto Test Execution

Enables concurrent execution of crypto tests in CI with proper validation.

## Test Execution Flow

1. Network starts
2. Crypto tests run concurrently (367 tests in parallel batches)
3. `ConcurrentSubprocessValidationTest` runs in isolation, last:
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
- Parallel execution uses fixed parallelism strategy

## Validation for Subprocess Concurrent

### How validation runs last

`ConcurrentSubprocessValidationTest` is guaranteed to run last via:

1. **`@Isolated`** - JUnit runs this test class alone (not concurrently with others)
2. **`@Order(Integer.MAX_VALUE)`** - ClassOrderer places it after all other classes
3. **`@Tag("CONCURRENT_SUBPROCESS_VALIDATION")`** - Bypasses `SERIAL` exclusion

### Why a wrapper class is required

**The problem with separate validation classes:**

The original `LogValidationTest` and `StreamValidationTest` are separate classes with:
- `LogValidationTest`: `@Order(Integer.MAX_VALUE - 1)`
- `StreamValidationTest`: `@Order(Integer.MAX_VALUE)`

In `testSubprocessConcurrent`, classes run with `junit.jupiter.execution.parallel.mode.classes.default=concurrent`.
Even though `@Order` determines the *start* order, concurrent classes can still overlap in execution.
This means `StreamValidationTest` could start while `LogValidationTest` is still running, or even finish first.

Since `StreamValidationTest` **freezes the network**, if it runs before or alongside `LogValidationTest`,
the log validation fails because the network is no longer responsive.

**How the wrapper solves this:**

`ConcurrentSubprocessValidationTest` wraps both validations in a **single test method**:

```java
return hapiTest(
    validateAllLogsAfter(VALIDATION_DELAY),  // First: log validation
    validateStreams());                       // Second: stream validation (freezes network)
```

This guarantees sequential execution because:
1. **`@Isolated`** ensures the entire class runs alone - no other test classes run concurrently
2. **`@Order(Integer.MAX_VALUE)`** with `ClassOrderer$OrderAnnotation` ensures it starts after all other classes
3. **Within the test method**, `hapiTest()` executes operations in declaration order

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

## Retry Logic for Transient Errors

### Retryable statuses

|                Status                |           Description            |     Why Safe to Retry     |
|--------------------------------------|----------------------------------|---------------------------|
| `PLATFORM_NOT_ACTIVE`                | Platform initializing/recovering | Transient startup state   |
| `PLATFORM_TRANSACTION_NOT_CREATED`   | Transaction backlog              | Temporary backpressure    |
| `BUSY`                               | Server overloaded                | Temporary load condition  |
| `INSUFFICIENT_TX_FEE` (queries only) | Fee estimation mismatch          | Resolves with updated fee |

### Parameters

- **Max retries:** 10 (hard limit)
- **Sleep between retries:** 100ms
- **Applies to:** `HapiTxnOp` (transactions) and `HapiQueryOp` (queries)

### Safety guarantees

- No state mutation occurs when these errors are returned
- Transactions/queries can be safely resubmitted
- The 100ms delay allows platform recovery
- **Does not retry when test expects the error** - If a test uses `.hasPrecheck(BUSY)` to explicitly test throttling behavior, the retry is skipped to preserve test intent

## Serial Tests

- Classes annotated with `@OrderedInIsolation` are tagged with `SERIAL`
- These run via `hapiTestCryptoSerial` in subprocess sequential mode
- `testSubprocessConcurrent` excludes `SERIAL` tests to avoid state conflicts during parallel execution
