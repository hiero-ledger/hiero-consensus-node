# Subprocess Concurrent Test Execution

This branch enables concurrent execution of crypto tests in CI with proper validation.

## Key Changes

### 1. Gradle Tasks (`build.gradle.kts`)

- `hapiTestCrypto` PR check runs all three phases in order:
  1. `testSubprocessConcurrent` - parallel crypto tests
  2. `testEmbedded` - embedded crypto tests
  3. `testSubprocess` - sequential subprocess tests
- `testSubprocessConcurrent` excludes `SUBPROCESS_SEQUENTIAL` tests except `CONCURRENT_SUBPROCESS_VALIDATION`
- Parallel execution enabled with fixed parallelism

### 2. Validation (`ConcurrentSubprocessValidationTest.java`)

New test that runs after all concurrent tests complete:
- Uses `@Isolated` and `@Order(Integer.MAX_VALUE)` to ensure it runs last
- Runs log validation first (reads files, network still up)
- Runs stream validation second (freezes the network)
- Tagged with `CONCURRENT_SUBPROCESS_VALIDATION` to bypass exclusion

### 3. Retry Logic (`HapiTxnOp.java`, `HapiQueryOp.java`)

Auto-retry for transient errors:
- `PLATFORM_NOT_ACTIVE`, `PLATFORM_TRANSACTION_NOT_CREATED`, `BUSY`
- Max 10 retries, 100ms sleep between attempts

### 4. Sequential Tests

- `@OrderedInIsolation` classes are tagged with `SUBPROCESS_SEQUENTIAL` and run via `hapiTestCryptoSubprocessSequential`
- `testSubprocessConcurrent` excludes `SUBPROCESS_SEQUENTIAL` to avoid state conflicts during parallel execution

## Test Flow

1. Network starts
2. Crypto tests run concurrently
3. `ConcurrentSubprocessValidationTest` runs (isolated, last)
   - Log validation
   - Stream validation (freezes network)
4. Network terminates
