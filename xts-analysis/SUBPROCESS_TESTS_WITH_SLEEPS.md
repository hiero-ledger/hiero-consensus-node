# Subprocess Tests with Arbitrary Sleeps

This document lists all HAPI tests that:
1. Run in subprocess mode (`@HapiTest` or `@LeakyHapiTest` annotations)
2. Contain `sleepFor()` calls that cause real-time waiting

These are candidates for conversion to embedded/repeatable mode where `sleepFor()` would advance virtual time instantly.

---

## ⚠️ Key Finding: Major Optimizations Already Complete

**The largest sleep-related optimizations have already been implemented.**

### Already Converted Tests (Significant Time Savings Achieved)

|                                     Test                                      |  Original Sleep  |             Status              |       Time Savings       |
|-------------------------------------------------------------------------------|------------------|---------------------------------|--------------------------|
| `DisabledLongTermExecutionScheduleTest.scheduledTestGetsDeletedIfNotExecuted` | **31 minutes**   | ✅ Already `@RepeatableHapiTest` | **31 minutes → instant** |
| `TxnRecordRegression.receiptUnavailableAfterCacheTtl`                         | **179 seconds**  | ✅ Already `@RepeatableHapiTest` | **3 minutes → instant**  |
| `RepeatableHip423Tests` (all tests)                                           | 5-8 seconds each | ✅ Already `@RepeatableHapiTest` | Virtual time             |
| `RepeatableScheduleLongTermSignTest` (all tests)                              | 6 seconds each   | ✅ Already `@RepeatableHapiTest` | Virtual time             |

### Not Standard @HapiTest (Not Run in Regular CI)

|           Test            |   Sleep Time   |                   Reason                    |
|---------------------------|----------------|---------------------------------------------|
| `MidnightUpdateRateSuite` | **5 minutes**  | No annotation, requires running at midnight |
| `SimpleFreezeOnly`        | **40 seconds** | Old-style `HapiSuite`, freeze testing       |
| `AdjustFeeScheduleSuite`  | **30 seconds** | Old-style, perf test                        |
| `SubmitMessageLoadTest`   | **10 seconds** | Load/perf test                              |

### Remaining Tests Have Hard Blockers

|                          Test                          |   Sleep Time   |                    Blocker                     |
|--------------------------------------------------------|----------------|------------------------------------------------|
| `FileUpdateSuite.serviceFeeRefundedIfConsGasExhausted` | 6 seconds      | Uses `uncheckedSubmit` + ECDSA                 |
| `DuplicateManagementTest` (4 tests)                    | 6 seconds each | Uses `uncheckedSubmit` for duplicate detection |
| `DisabledNodeOperatorTest` (9 tests)                   | 3 seconds each | Tests node operator port behavior              |
| Node lifecycle tests                                   | ~30 seconds    | Uses `FakeNmt` for node death/reconnect        |

### Remaining Convertible Tests

The 17 tests in the "safe to convert" list below total only **~37 seconds of sleep time**. Due to subprocess parallelism (where tests run concurrently), **actual wall-clock savings would be minimal or potentially negative** since converted tests would run sequentially in embedded/repeatable mode.

**Bottom line:** The two biggest wins (31 minutes + 3 minutes) are already captured. Further conversion offers diminishing returns.

---

## Important: Subprocess Test Execution Model

Understanding how subprocess tests execute is critical for conversion decisions:

### How Subprocess Tests Work

1. **Per-tag network lifecycle:** For each tag (e.g., `CRYPTO`, `TOKEN`, `SMART_CONTRACT`), the network is started **once**.

2. **Parallel execution:** Tests within a tag run in **parallel** (random order, except validation tests which run last).

3. **Cumulative state changes:** Tests modify shared network state (accounts, tokens, contracts, etc.) and generate logs/streams.

4. **End-of-tag validation:** At the end of each tag's execution, **log and stream validation checks** (`StreamValidationTest`, `LogValidationTest`) run to verify:

   - All tests' output is valid
   - Total cumulative state changes are correct
   - Block stream integrity

### Parallelism: Subprocess vs Embedded/Repeatable

|      Mode      | Test Execution |  Sleep Behavior   |            Wall-Clock Time             |
|----------------|----------------|-------------------|----------------------------------------|
| **Subprocess** | **Parallel**   | Real wait         | = longest test (sleeps overlap)        |
| **Embedded**   | **Sequential** | Real wait         | = sum of all tests                     |
| **Repeatable** | **Sequential** | Virtual (instant) | = sum of all tests (no sleep overhead) |

**Critical implication for time savings:**
- In subprocess, if 10 tests each sleep 1 second but run in parallel → wall-clock ~1 second
- Converting to repeatable eliminates sleeps but tests run sequentially
- **True time savings** = (parallel sleep time) - (sequential execution overhead) + (eliminated sleep time)
- Tests with **long sleeps that run alone** (not overlapping) benefit most from conversion

### Implications for Conversion

|        Aspect         |                                              Impact                                              |
|-----------------------|--------------------------------------------------------------------------------------------------|
| **State changes**     | Tests converted to embedded/repeatable mode **do not contribute state** to subprocess validation |
| **Tag grouping**      | Converted tests would move to a **new tag** (e.g., `CRYPTO_REPEATABLE`)                          |
| **Validation scope**  | Original tag's validation only covers remaining subprocess tests                                 |
| **Test independence** | Most tests are independent (random order), but validation checks cumulative state                |

### Conversion Safety Assessment

|                Scenario                 | Safe to Convert? |                      Notes                       |
|-----------------------------------------|------------------|--------------------------------------------------|
| Simple validation tests (fees, queries) | ✅ Yes            | Don't need cumulative state validation           |
| Tests with unique entities              | ✅ Yes            | Create their own accounts/tokens, self-contained |
| Tests relying on prior state            | ⚠️ Maybe         | May need the state from other tests in the tag   |
| Tests critical to stream validation     | ❌ No             | Their output is needed for end-of-tag validation |

---

## Summary Statistics

|          Category          |     Files     |  Total Sleep Calls  |
|----------------------------|---------------|---------------------|
| Fee validation tests       | 7             | 12                  |
| Contract tests             | 8             | 10                  |
| Crypto tests               | 6             | 5                   |
| Token tests                | 2             | 2                   |
| Schedule tests             | 4             | 6                   |
| Consensus (HCS) tests      | 2             | 6                   |
| Query tests                | 2             | 11                  |
| Issue regression tests     | 1             | 3                   |
| Duplicate management tests | 1             | 4                   |
| Node lifecycle tests       | 3             | 3                   |
| Staking tests              | 1             | 2                   |
| **TOTAL**                  | **~37 files** | **~64 sleep calls** |

---

## Detailed Test List by Category

### 1. Fee Validation Tests

#### `CryptoServiceFeesSuite.java`

**Tag:** CRYPTO
**Annotation:** `@HapiTest`, `@LeakyHapiTest`

|              Test Method              | Sleep Duration |        Purpose         |
|---------------------------------------|----------------|------------------------|
| `cryptoGetAccountRecordsBaseUSDFee()` | 2000ms         | Wait for query payment |
| `cryptoGetAccountInfoBaseUSDFee()`    | 1000ms         | Wait for query payment |

#### `ConsensusServiceFeesSuite.java`

**Tag:** (none specific)
**Annotation:** `@HapiTest`

|           Test Method            | Sleep Duration |      Purpose      |
|----------------------------------|----------------|-------------------|
| `topicSubmitMessageBaseUSDFee()` | 1000ms         | Wait after submit |
| `tokenGetTopicInfoBaseUSDFee()`  | 1000ms         | Wait for query    |

#### `AtomicConsensusServiceFeesSuite.java`

**Tag:** (none specific)
**Annotation:** `@HapiTest`

|           Test Method            | Sleep Duration |         Purpose         |
|----------------------------------|----------------|-------------------------|
| `topicSubmitMessageBaseUSDFee()` | 1000ms         | Wait after batch submit |

#### `TokenServiceFeesSuite.java`

**Tag:** TOKEN
**Annotation:** `@HapiTest`

|               Test Method               | Sleep Duration |       Purpose       |
|-----------------------------------------|----------------|---------------------|
| `tokenGetInfoFeeChargedAsExpected()`    | 1000ms         | Wait for token info |
| `tokenGetNftInfoFeeChargedAsExpected()` | 3000ms         | Wait for NFT info   |

#### `FileServiceFeesSuite.java`

**Tag:** (none specific)
**Annotation:** `@HapiTest`

|         Test Method          | Sleep Duration |        Purpose        |
|------------------------------|----------------|-----------------------|
| `fileGetContentBaseUSDFee()` | 1000ms         | Wait for file content |
| `fileGetInfoBaseUSDFee()`    | 1000ms         | Wait for file info    |

#### `MiscellaneousFeesSuite.java`

**Tag:** (none specific)
**Annotation:** `@HapiTest`

|              Test Method               | Sleep Duration |     Purpose     |
|----------------------------------------|----------------|-----------------|
| `miscGetTransactionRecordBaseUSDFee()` | 1000ms         | Wait for record |

#### `SmartContractServiceFeesTest.java`

**Tag:** SMART_CONTRACT
**Annotation:** `@HapiTest`, `@LeakyHapiTest`

|        Test Method         | Sleep Duration |       Purpose       |
|----------------------------|----------------|---------------------|
| (contract local call test) | 5ms            | Wait for local call |

---

### 2. Contract Tests

#### `ContractCreateSuite.java`

**Tag:** SMART_CONTRACT
**Annotation:** `@HapiTest`, `@LeakyHapiTest`

|                Test Method                | Sleep Duration |             Purpose             |
|-------------------------------------------|----------------|---------------------------------|
| `blockTimestampChangesWithinFewSeconds()` | 3000ms         | Wait for block timestamp change |

#### `AtomicContractCreateSuite.java`

**Tag:** SMART_CONTRACT
**Annotation:** `@HapiTest`

|                Test Method                | Sleep Duration |             Purpose             |
|-------------------------------------------|----------------|---------------------------------|
| `blockTimestampChangesWithinFewSeconds()` | 3000ms         | Wait for block timestamp change |

#### `ContractGetInfoSuite.java`

**Tag:** SMART_CONTRACT
**Annotation:** `@HapiTest`

|   Test Method    | Sleep Duration |             Purpose             |
|------------------|----------------|---------------------------------|
| `getInfoWorks()` | 5000ms         | Wait for query payment handling |

#### `ContractGetBytecodeSuite.java`

**Tag:** SMART_CONTRACT
**Annotation:** `@HapiTest`

|     Test Method      | Sleep Duration |             Purpose             |
|----------------------|----------------|---------------------------------|
| `getByteCodeWorks()` | 5000ms         | Wait for query payment handling |

#### `ContractCallLocalSuite.java`

**Tag:** SMART_CONTRACT
**Annotation:** `@HapiTest`

|          Test Method          | Sleep Duration |         Purpose          |
|-------------------------------|----------------|--------------------------|
| `vanillaSuccess()`            | 3000ms         | Wait after contract call |
| `gasBelowIntrinsicGasFails()` | 3000ms         | Wait after contract call |

#### `LeakyContractTestsSuite.java`

**Tag:** SMART_CONTRACT
**Annotation:** `@HapiTest`, `@LeakyHapiTest`

|         Test Method          | Sleep Duration |     Purpose      |
|------------------------------|----------------|------------------|
| `payerCannotOverSendValue()` | 1000ms         | Wait for receipt |

#### `AtomicLeakyContractTestsSuite.java`

**Tag:** SMART_CONTRACT
**Annotation:** `@HapiTest`, `@LeakyHapiTest`

|         Test Method          | Sleep Duration |     Purpose      |
|------------------------------|----------------|------------------|
| `payerCannotOverSendValue()` | 1000ms         | Wait for receipt |

#### `ContractSignScheduleTest.java`

**Tag:** SMART_CONTRACT
**Annotation:** `@HapiTest`

|                    Test Method                     | Sleep Duration |           Purpose           |
|----------------------------------------------------|----------------|-----------------------------|
| `authorizeScheduleWithContract()` (CryptoTransfer) | 1000ms         | Wait for schedule execution |
| `authorizeScheduleWithContract()` (TokenMint)      | 1000ms         | Wait for schedule execution |

---

### 3. Crypto Tests

#### `CryptoRecordsSanityCheckSuite.java`

**Tag:** CRYPTO
**Annotation:** `@LeakyHapiTest`

|                   Test Method                    | Sleep Duration |          Purpose           |
|--------------------------------------------------|----------------|----------------------------|
| `insufficientAccountBalanceRecordSanityChecks()` | 1000ms         | Wait for record validation |

#### `CryptoTransferSuite.java`

**Tag:** CRYPTO
**Annotation:** `@HapiTest`

|                           Test Method                           | Sleep Duration |              Purpose               |
|-----------------------------------------------------------------|----------------|------------------------------------|
| `hbarAndFungibleSelfTransfersRejectedBothInPrecheckAndHandle()` | 5000ms         | Wait for unchecked submit handling |

#### `TxnRecordRegression.java`

**Tag:** CRYPTO
**Annotation:** `@HapiTest`

|     Test Method      | Sleep Duration |           Purpose           |
|----------------------|----------------|-----------------------------|
| (receipt purge test) | 179000ms       | Wait for receipt TTL expiry |

#### `LeakyCryptoTestsSuite.java`

**Tag:** CRYPTO
**Annotation:** `@LeakyHapiTest`

|                      Test Method                       | Sleep Duration |        Purpose        |
|--------------------------------------------------------|----------------|-----------------------|
| `cannotDissociateFromExpiredTokenWithNonZeroBalance()` | 2000ms         | Wait for token expiry |

#### `RecordCreationSuite.java`

**Tag:** (none specific)
**Annotation:** `@HapiTest`, `@LeakyHapiTest`

|                           Test Method                            |  Sleep Duration   |     Purpose     |
|------------------------------------------------------------------|-------------------|-----------------|
| `submittingNodeChargedNetworkFeeForIgnoringPayerUnwillingness()` | 2000ms (SLEEP_MS) | Wait for record |

---

### 4. Token Tests

#### `TokenAssociationSpecs.java`

**Tag:** TOKEN
**Annotation:** `@HapiTest`, `@LeakyHapiTest`

|                     Test Method                      |    Sleep Duration     |        Purpose        |
|------------------------------------------------------|-----------------------|-----------------------|
| `expiredAndDeletedTokensStillAppearInContractInfo()` | lifetimeSecs * 1000ms | Wait for token expiry |

#### `AtomicTokenAssociationSpecs.java`

**Tag:** TOKEN
**Annotation:** `@HapiTest`, `@LeakyHapiTest`

|                     Test Method                      |    Sleep Duration     |        Purpose        |
|------------------------------------------------------|-----------------------|-----------------------|
| `expiredAndDeletedTokensStillAppearInContractInfo()` | lifetimeSecs * 1000ms | Wait for token expiry |

---

### 5. Schedule Tests

#### `ScheduleSignTest.java`

**Tag:** (none specific)
**Annotation:** `@HapiTest`, `@LeakyHapiTest`

|             Test Method             | Sleep Duration |         Purpose          |
|-------------------------------------|----------------|--------------------------|
| `signFailsDueToDeletedExpiration()` | 2000ms (x2)    | Wait for schedule expiry |

#### `FutureSchedulableOpsTest.java`

**Tag:** (none specific)
**Annotation:** `@HapiTest`

|                     Test Method                      | Sleep Duration |        Purpose         |
|------------------------------------------------------|----------------|------------------------|
| `preservesRevocationServiceSemanticsForFileDelete()` | 1000ms (x2)    | Wait for file deletion |

#### `DisabledLongTermExecutionScheduleTest.java`

**Tag:** (none specific)
**Annotation:** `@HapiTest`, `@LeakyHapiTest`, `@RepeatableHapiTest`

|                Test Method                | Sleep Duration |         Purpose          |          Status          |
|-------------------------------------------|----------------|--------------------------|--------------------------|
| `scheduledTestGetsDeletedIfNotExecuted()` | 31 minutes     | Wait for schedule expiry | **ALREADY REPEATABLE** ✅ |

**Note:** The 31-minute sleep test is already converted to `@RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)` - the sleep is instant via virtual time.

---

### 6. Consensus (HCS) Tests

#### `SubmitMessageSuite.java`

**Tag:** (none specific)
**Annotation:** `@HapiTest`

|            Test Method            | Sleep Duration |            Purpose             |
|-----------------------------------|----------------|--------------------------------|
| `messageSubmissionMultiple()`     | 1000ms         | Wait after multiple submits    |
| `chunkTransactionIDIsValidated()` | 1000ms (x2)    | Wait between chunk submissions |

#### `AtomicSubmitMessageSuite.java`

**Tag:** (none specific)
**Annotation:** `@HapiTest`

|            Test Method            | Sleep Duration |            Purpose             |
|-----------------------------------|----------------|--------------------------------|
| `messageSubmissionMultiple()`     | 1000ms         | Wait after multiple submits    |
| `chunkTransactionIDIsValidated()` | 1000ms (x2)    | Wait between chunk submissions |

---

### 7. Query Tests

#### `DisabledNodeOperatorTest.java`

**Tag:** TOKEN
**Annotation:** `@HapiTest`

|                        Test Method                        | Sleep Duration |  Purpose  |
|-----------------------------------------------------------|----------------|-----------|
| `nodeOperatorQueryPortNotAccessibleForAccountBalance()`   | 3000ms         | Poll wait |
| `nodeOperatorQueryPortNotAccessibleForAccountInfo()`      | 3000ms         | Poll wait |
| `nodeOperatorQueryPortNotAccessibleForTopicInfo()`        | 3000ms         | Poll wait |
| `nodeOperatorQueryPortNotAccessibleForTokenInfo()`        | 3000ms         | Poll wait |
| `nodeOperatorQueryPortNotAccessibleForFileContents()`     | 3000ms         | Poll wait |
| `nodeOperatorQueryPortNotAccessibleForFileInfo()`         | 3000ms         | Poll wait |
| `nodeOperatorQueryPortNotAccessibleForContractCall()`     | 3000ms         | Poll wait |
| `nodeOperatorQueryPortNotAccessibleForContractBytecode()` | 3000ms         | Poll wait |
| `nodeOperatorQueryPortNotAccessibleForScheduleInfo()`     | 3000ms         | Poll wait |

---

### 8. Issue Regression Tests

#### `IssueRegressionTests.java`

**Tag:** MATS, ONLY_SUBPROCESS
**Annotation:** `@HapiTest`, `@LeakyHapiTest`

|               Test Method                | Sleep Duration |           Purpose            |
|------------------------------------------|----------------|------------------------------|
| `duplicatedTxnsSameTypeDetected()`       | 2000ms         | Wait for duplicate detection |
| `duplicatedTxnsDifferentNodesDetected()` | 2000ms (x2)    | Wait for duplicate detection |

---

### 9. Duplicate Management Tests

#### `DuplicateManagementTest.java`

**Tag:** MATS
**Annotation:** `@HapiTest`

|                   Test Method                   | Sleep Duration |      Purpose       |
|-------------------------------------------------|----------------|--------------------|
| `detectsDuplicatesWithReasonableTolerance()`    | 6000ms (x2)    | Wait for consensus |
| `usesUnclassifiableIfNoClassifiableAvailable()` | 6000ms         | Wait for consensus |
| `classifiableTakesPriorityOverUnclassifiable()` | 6000ms         | Wait for consensus |

---

### 10. Node Lifecycle Tests (MUST STAY IN SUBPROCESS)

These tests **cannot** be converted as they require actual node lifecycle operations.

#### `DabEnabledUpgradeTest.java`

**Tag:** UPGRADE
**Annotation:** `@HapiTest`

|    Test Method    |       Sleep Duration       |                Purpose                 |
|-------------------|----------------------------|----------------------------------------|
| `newNodeUpdate()` | PORT_UNBINDING_WAIT_PERIOD | Wait for port unbinding after shutdown |

#### `MixedOpsNodeDeathReconnectTest.java`

**Tag:** ND_RECONNECT
**Annotation:** `@HapiTest`

|      Test Method      |       Sleep Duration       |         Purpose         |
|-----------------------|----------------------------|-------------------------|
| `reconnectMixedOps()` | PORT_UNBINDING_WAIT_PERIOD | Wait for port unbinding |

#### `NodeDeathReconnectBlockNodeSuite.java`

**Tag:** BLOCK_NODE
**Annotation:** `@HapiTest`

|   Test Method    |       Sleep Duration       |         Purpose         |
|------------------|----------------------------|-------------------------|
| (reconnect test) | PORT_UNBINDING_WAIT_PERIOD | Wait for port unbinding |

---

### 11. Staking Tests

#### `StakingSuite.java`

**Tag:** LONG_RUNNING
**Annotation:** `@HapiTest`, `@LeakyHapiTest`

|                           Test Method                            |               Sleep Duration               |         Purpose          |
|------------------------------------------------------------------|--------------------------------------------|--------------------------|
| `zeroStakeAccountsHaveMetadataResetOnFirstDayTheyReceiveFunds()` | 5000ms                                     | Wait for staking period  |
| `stakeIsManagedCorrectlyInTxnsAroundPeriodBoundaries()`          | 2 * secsBeforePeriodEndToDoTransfer * 1000 | Wait for period boundary |

---

### 12. Ethereum/Jumbo Transaction Tests

#### `JumboTransactionsEnabledTest.java`

**Tag:** SMART_CONTRACT
**Annotation:** `@HapiTest`, `@LeakyHapiTest`

|                  Test Method                   | Sleep Duration |              Purpose              |
|------------------------------------------------|----------------|-----------------------------------|
| `nonJumboTransactionBiggerThan6KbShouldFail()` | 1000ms (x2)    | Wait between transactions         |
| `jumboTransactionGetsThrottledAtIngest()`      | 1000ms         | Wait for throttle bucket to empty |

---

### 13. File Update Tests

#### `FileUpdateSuite.java`

**Tag:** (none specific)
**Annotation:** `@HapiTest`, `@LeakyHapiTest`

|               Test Method                | Sleep Duration |              Purpose              |
|------------------------------------------|----------------|-----------------------------------|
| `serviceFeeRefundedIfConsGasExhausted()` | 6000ms         | Wait for consensus gas exhaustion |

---

## Conversion Recommendations

### High Priority (Most Benefit)

1. **Fee validation tests** - Simple business logic, 12 sleep calls totaling ~14 seconds
2. **Contract info/bytecode queries** - Pure query tests, 10+ second sleeps
3. **Schedule tests** - Would benefit greatly from virtual time

### Medium Priority

4. **Consensus (HCS) tests** - Chunk validation sleeps
5. **Token association tests** - Token expiry waits
6. **Crypto record tests** - Record validation waits

### Cannot Convert (Must Stay Subprocess)

- `DabEnabledUpgradeTest.java` - Uses `FakeNmt` for node lifecycle
- `MixedOpsNodeDeathReconnectTest.java` - Tests node death/reconnect
- `NodeDeathReconnectBlockNodeSuite.java` - Tests block node reconnection
- `IssueRegressionTests.java` (some tests) - Tagged `ONLY_SUBPROCESS`
- `DuplicateManagementTest.java` - Uses `uncheckedSubmit` for duplicate detection

### Requires Analysis

- `TxnRecordRegression.java` - 179-second sleep for receipt TTL test
- `DisabledLongTermExecutionScheduleTest.java` - 31-minute sleep for schedule expiry
- `StakingSuite.java` - Staking period boundary tests

---

## Total Time Spent Sleeping

|                   Category                   |       Estimated Sleep Time       |
|----------------------------------------------|----------------------------------|
| Fee validation tests                         | ~13 seconds                      |
| Contract tests                               | ~26 seconds                      |
| Crypto tests (excluding TxnRecordRegression) | ~10 seconds                      |
| TxnRecordRegression receipt TTL test         | ~179 seconds (3 min)             |
| Token tests                                  | ~20 seconds (variable)           |
| Schedule tests (excluding 31-min test)       | ~6 seconds                       |
| DisabledLongTermExecutionScheduleTest        | ~1,860 seconds (31 min)          |
| Consensus (HCS) tests                        | ~6 seconds                       |
| Query tests (DisabledNodeOperatorTest)       | ~27 seconds                      |
| Issue regression tests                       | ~6 seconds                       |
| Duplicate management tests                   | ~24 seconds                      |
| Staking tests                                | ~10 seconds (variable)           |
| Ethereum/Jumbo tests                         | ~3 seconds                       |
| File update tests                            | ~6 seconds                       |
| Node lifecycle tests                         | ~30 seconds (variable)           |
| **TOTAL**                                    | **~2,226 seconds (~37 minutes)** |

**Note:** The two longest sleeps dominate the total:
- `DisabledLongTermExecutionScheduleTest.expiryIgnoredWhenLongTermDisabledThenEnabled()` - 31 minutes
- `TxnRecordRegression` receipt TTL test - 179 seconds

**Convertible tests sleep time:** ~136 seconds (~2.3 minutes) excluding the long tests and subprocess-only tests.

---

## Tests Suitable for Conversion

The following table lists specific tests with sleep calls that are suitable for conversion to either **Repeatable** or **Embedded** mode.

### Conversion Criteria

|      Mode      |                                       Requirements                                        |
|----------------|-------------------------------------------------------------------------------------------|
| **Repeatable** | No ECDSA keys, no `inParallel()`, no `setNode()`, no `uncheckedSubmit`, no node lifecycle |
| **Embedded**   | No `setNode()`, no `uncheckedSubmit`, no node lifecycle (ECDSA and `inParallel()` OK)     |

---

### Convertible Tests Table

|                      Test Method                      |               Class               |         Tag          | Sleep (ms) | Target Mode |         Notes         |
|-------------------------------------------------------|-----------------------------------|----------------------|------------|-------------|-----------------------|
| `cryptoGetAccountRecordsBaseUSDFee`                   | `CryptoServiceFeesSuite`          | CRYPTO, MATS         | 2000       | Repeatable  | Pure fee validation   |
| `cryptoGetAccountInfoBaseUSDFee`                      | `CryptoServiceFeesSuite`          | CRYPTO               | 1000       | Repeatable  | Pure fee validation   |
| `topicSubmitMessageBaseUSDFee`                        | `ConsensusServiceFeesSuite`       | MATS                 | 1000       | Repeatable  | Pure fee validation   |
| `tokenGetTopicInfoBaseUSDFee`                         | `ConsensusServiceFeesSuite`       | -                    | 1000       | Repeatable  | Pure fee validation   |
| `topicSubmitMessageBaseUSDFee`                        | `AtomicConsensusServiceFeesSuite` | -                    | 1000       | Repeatable  | Pure fee validation   |
| `tokenGetInfoFeeChargedAsExpected`                    | `TokenServiceFeesSuite`           | TOKEN                | 1000       | Repeatable  | Pure fee validation   |
| `tokenGetNftInfoFeeChargedAsExpected`                 | `TokenServiceFeesSuite`           | TOKEN                | 3000       | Repeatable  | Pure fee validation   |
| `fileGetContentBaseUSDFee`                            | `FileServiceFeesSuite`            | MATS                 | 1000       | Repeatable  | Pure fee validation   |
| `fileGetInfoBaseUSDFee`                               | `FileServiceFeesSuite`            | -                    | 1000       | Repeatable  | Pure fee validation   |
| `miscGetTransactionRecordBaseUSDFee`                  | `MiscellaneousFeesSuite`          | MATS                 | 1000       | Repeatable  | Pure fee validation   |
| `contractLocalCallFeeTest`                            | `SmartContractServiceFeesTest`    | SMART_CONTRACT       | 5          | Repeatable  | Pure fee validation   |
| `blockTimestampChangesWithinFewSeconds`               | `ContractCreateSuite`             | SMART_CONTRACT       | 3000       | Repeatable  | No ECDSA in this test |
| `blockTimestampChangesWithinFewSeconds`               | `AtomicContractCreateSuite`       | SMART_CONTRACT       | 3000       | Repeatable  | No ECDSA in this test |
| `getInfoWorks`                                        | `ContractGetInfoSuite`            | SMART_CONTRACT       | 5000       | Repeatable  | Query test, no ECDSA  |
| `getByteCodeWorks`                                    | `ContractGetBytecodeSuite`        | SMART_CONTRACT       | 5000       | Repeatable  | Query test, no ECDSA  |
| `vanillaSuccess`                                      | `ContractCallLocalSuite`          | SMART_CONTRACT       | 3000       | Embedded    | Has ECDSA usage       |
| `gasBelowIntrinsicGasFails`                           | `ContractCallLocalSuite`          | SMART_CONTRACT       | 3000       | Embedded    | Has ECDSA usage       |
| `payerCannotOverSendValue`                            | `LeakyContractTestsSuite`         | SMART_CONTRACT       | 1000       | Embedded    | Heavy ECDSA usage     |
| `payerCannotOverSendValue`                            | `AtomicLeakyContractTestsSuite`   | SMART_CONTRACT       | 1000       | Embedded    | Heavy ECDSA usage     |
| `authorizeScheduleWithContract` (CryptoTransfer)      | `ContractSignScheduleTest`        | SMART_CONTRACT       | 1000       | Repeatable  | Schedule precompile   |
| `authorizeScheduleWithContract` (TokenMint)           | `ContractSignScheduleTest`        | SMART_CONTRACT       | 1000       | Repeatable  | Schedule precompile   |
| `insufficientAccountBalanceRecordSanityChecks`        | `CryptoRecordsSanityCheckSuite`   | CRYPTO, MATS         | 1000       | Repeatable  | No ECDSA              |
| `hbarAndFungibleSelfTransfersRejected...`             | `CryptoTransferSuite`             | CRYPTO               | 5000       | Embedded    | Has ECDSA usage       |
| `cannotDissociateFromExpiredTokenWithNonZeroBalance`  | `LeakyCryptoTestsSuite`           | CRYPTO               | 2000       | Embedded    | Has ECDSA usage       |
| `submittingNodeChargedNetworkFee...`                  | `RecordCreationSuite`             | -                    | 2000       | Repeatable  | No ECDSA              |
| `expiredAndDeletedTokensStillAppearInContractInfo`    | `TokenAssociationSpecs`           | TOKEN                | variable   | Repeatable  | No ECDSA              |
| `expiredAndDeletedTokensStillAppearInContractInfo`    | `AtomicTokenAssociationSpecs`     | TOKEN                | variable   | Repeatable  | No ECDSA              |
| `signFailsDueToDeletedExpiration`                     | `ScheduleSignTest`                | -                    | 4000       | Repeatable  | No ECDSA              |
| `preservesRevocationServiceSemanticsForFileDelete`    | `FutureSchedulableOpsTest`        | -                    | 2000       | Repeatable  | No ECDSA              |
| `messageSubmissionMultiple`                           | `SubmitMessageSuite`              | -                    | 1000       | Embedded    | Has `inParallel`      |
| `chunkTransactionIDIsValidated`                       | `SubmitMessageSuite`              | -                    | 2000       | Embedded    | Has `inParallel`      |
| `messageSubmissionMultiple`                           | `AtomicSubmitMessageSuite`        | MATS                 | 1000       | Embedded    | Has `inParallel`      |
| `chunkTransactionIDIsValidated`                       | `AtomicSubmitMessageSuite`        | MATS                 | 2000       | Embedded    | Has `inParallel`      |
| `zeroStakeAccountsHaveMetadataReset...`               | `StakingSuite`                    | LONG_RUNNING         | 5000       | Embedded    | Has `inParallel`      |
| `stakeIsManagedCorrectlyInTxnsAroundPeriodBoundaries` | `StakingSuite`                    | LONG_RUNNING         | variable   | Embedded    | Has `inParallel`      |
| `nonJumboTransactionBiggerThan6KbShouldFail`          | `JumboTransactionsEnabledTest`    | SMART_CONTRACT, MATS | 2000       | Embedded    | Has ECDSA             |
| `jumboTransactionGetsThrottledAtIngest`               | `JumboTransactionsEnabledTest`    | SMART_CONTRACT, MATS | 1000       | Embedded    | Has ECDSA             |
| `serviceFeeRefundedIfConsGasExhausted`                | `FileUpdateSuite`                 | MATS                 | 6000       | Repeatable  | No ECDSA              |

---

### Tests NOT Suitable for Conversion (Must Stay in Subprocess)

|              Test Method               |               Class                |                   Reason                   |
|----------------------------------------|------------------------------------|--------------------------------------------|
| All tests                              | `DabEnabledUpgradeTest`            | Uses `FakeNmt` node lifecycle              |
| `reconnectMixedOps`                    | `MixedOpsNodeDeathReconnectTest`   | Uses `FakeNmt` node death/reconnect        |
| All tests                              | `NodeDeathReconnectBlockNodeSuite` | Uses `FakeNmt` node death                  |
| `duplicatedTxnsDifferentNodesDetected` | `IssueRegressionTests`             | Uses `setNode()`, tagged `ONLY_SUBPROCESS` |
| `duplicatedTxnsSameTypeDetected`       | `IssueRegressionTests`             | Tagged `ONLY_SUBPROCESS`                   |
| All tests                              | `DuplicateManagementTest`          | Uses `uncheckedSubmit` (8 occurrences)     |
| All tests                              | `DisabledNodeOperatorTest`         | Tests node operator port behavior          |
| (receipt TTL test)                     | `TxnRecordRegression`              | Tests real receipt TTL timing (179s)       |

---

### Summary by Conversion Target

|       Target Mode        | Test Count |            Total Sleep Time            |
|--------------------------|------------|----------------------------------------|
| **Repeatable**           | 23 tests   | ~37 seconds                            |
| **Embedded**             | 14 tests   | ~30 seconds                            |
| **Must Stay Subprocess** | ~15 tests  | ~266 seconds                           |
| **Already Converted**    | 1 test     | 1860 seconds (was 31 min, now instant) |

**Note:** The 31-minute sleep in `DisabledLongTermExecutionScheduleTest.scheduledTestGetsDeletedIfNotExecuted` is **already converted** to `@RepeatableHapiTest`.

---

## Conversion Strategy Considering Validation

Given the subprocess execution model (see top of document), here's the recommended approach:

### Phase 1: Safe Conversions (Low Risk)

These tests are **self-contained** and don't significantly impact end-of-tag validation:

|     Category     |                                                              Tests                                                               |           New Tag           |                                     Rationale                                      |
|------------------|----------------------------------------------------------------------------------------------------------------------------------|-----------------------------|------------------------------------------------------------------------------------|
| Fee validation   | `CryptoServiceFeesSuite`, `ConsensusServiceFeesSuite`, `TokenServiceFeesSuite`, `FileServiceFeesSuite`, `MiscellaneousFeesSuite` | `FEES_REPEATABLE`           | Pure fee assertions, create own entities, don't affect cumulative state validation |
| Contract queries | `ContractGetInfoSuite`, `ContractGetBytecodeSuite`                                                                               | `SMART_CONTRACT_REPEATABLE` | Query-only tests, don't modify state                                               |
| Schedule tests   | `ScheduleSignTest`, `FutureSchedulableOpsTest`, `DisabledLongTermExecutionScheduleTest`                                          | `SCHEDULE_REPEATABLE`       | Self-contained schedule lifecycle tests                                            |

### Phase 2: Moderate Risk Conversions

These tests are independent but removing them changes validation coverage:

|     Category      |                         Tests                          |                             Consideration                              |
|-------------------|--------------------------------------------------------|------------------------------------------------------------------------|
| Token association | `TokenAssociationSpecs`                                | Creates entities but may contribute to token-related stream validation |
| Record tests      | `CryptoRecordsSanityCheckSuite`, `RecordCreationSuite` | Record output might be expected in validation                          |

### Phase 3: High Risk / Not Recommended

|         Category          |                              Reason to Keep in Subprocess                              |
|---------------------------|----------------------------------------------------------------------------------------|
| Tests with ECDSA          | Can only go to Embedded, but may be needed for stream validation of ECDSA transactions |
| Duplicate detection tests | Rely on actual network behavior and validation                                         |
| Staking tests             | Complex staking period calculations may need subprocess timing                         |

### New Tag Structure (Proposed)

```
Current:                          After Conversion:
├── hapiTestCrypto (CRYPTO)       ├── hapiTestCrypto (CRYPTO) - remaining tests
├── hapiTestToken (TOKEN)         ├── hapiTestToken (TOKEN) - remaining tests
├── hapiTestSmartContract (...)   ├── hapiTestSmartContract (...) - remaining tests
└── hapiTestMisc (MISC)           ├── hapiRepeatableFees (FEES_REPEATABLE) - new
                                  ├── hapiRepeatableSchedule (SCHEDULE_REPEATABLE) - new
                                  └── hapiRepeatableQueries (QUERIES_REPEATABLE) - new
```

### Validation Considerations

When creating new tags for converted tests:

1. **Repeatable mode tests** - Virtual time, no stream/log output to validate
2. **Embedded mode tests** - May produce streams but not validated the same way
3. **Consider adding lightweight validation** for converted test groups if needed

---

## Safe Conversion List (Ordered by Time Saved)

The following tests are **safe to convert** - they are self-contained, create their own entities, and don't impact end-of-tag validation:

```
ContractGetInfoSuite.getInfoWorks -> converts to repeatable -> saves 5 seconds
ContractGetBytecodeSuite.getByteCodeWorks -> converts to repeatable -> saves 5 seconds
ScheduleSignTest.signFailsDueToDeletedExpiration -> converts to repeatable -> saves 4 seconds
TokenServiceFeesSuite.tokenGetNftInfoFeeChargedAsExpected -> converts to repeatable -> saves 3 seconds
ContractCreateSuite.blockTimestampChangesWithinFewSeconds -> converts to repeatable -> saves 3 seconds
AtomicContractCreateSuite.blockTimestampChangesWithinFewSeconds -> converts to repeatable -> saves 3 seconds
FutureSchedulableOpsTest.preservesRevocationServiceSemanticsForFileDelete -> converts to repeatable -> saves 2 seconds
CryptoServiceFeesSuite.cryptoGetAccountRecordsBaseUSDFee -> converts to repeatable -> saves 2 seconds
ContractSignScheduleTest.authorizeScheduleWithContract -> converts to repeatable -> saves 2 seconds
ConsensusServiceFeesSuite.topicSubmitMessageBaseUSDFee -> converts to repeatable -> saves 1 seconds
ConsensusServiceFeesSuite.tokenGetTopicInfoBaseUSDFee -> converts to repeatable -> saves 1 seconds
AtomicConsensusServiceFeesSuite.topicSubmitMessageBaseUSDFee -> converts to repeatable -> saves 1 seconds
TokenServiceFeesSuite.tokenGetInfoFeeChargedAsExpected -> converts to repeatable -> saves 1 seconds
FileServiceFeesSuite.fileGetContentBaseUSDFee -> converts to repeatable -> saves 1 seconds
FileServiceFeesSuite.fileGetInfoBaseUSDFee -> converts to repeatable -> saves 1 seconds
MiscellaneousFeesSuite.miscGetTransactionRecordBaseUSDFee -> converts to repeatable -> saves 1 seconds
CryptoServiceFeesSuite.cryptoGetAccountInfoBaseUSDFee -> converts to repeatable -> saves 1 seconds
```

**Total sleep time eliminated: 37 seconds**

**Note:** `DisabledLongTermExecutionScheduleTest.scheduledTestGetsDeletedIfNotExecuted` (31-minute sleep) is **already converted** to `@RepeatableHapiTest` - it uses virtual time and the sleep is instant.

**Important notes on actual time savings:**
- In subprocess mode, many of the smaller sleeps (1-5 seconds) likely run in **parallel** and overlap
- Converting to repeatable mode eliminates sleeps but tests run **sequentially**
- **Actual wall-clock savings will be less than 37 seconds** due to parallelism trade-off
- Best candidates are tests with sleeps that don't overlap with other parallel tests

---

## Conclusion

### Major Optimizations Already Complete ✅

The two longest-running sleep tests have **already been converted**:

1. **`DisabledLongTermExecutionScheduleTest.scheduledTestGetsDeletedIfNotExecuted`** (31 minutes)
   - Now uses `@RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)`
   - Sleep is instant via virtual time
2. **`TxnRecordRegression.receiptUnavailableAfterCacheTtl`** (179 seconds)
   - Now uses `@RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)`
   - Sleep is instant via virtual time

### Remaining Tests: Diminishing Returns

The remaining 17 convertible tests total only **~37 seconds** of sleep time. Given that:

- Subprocess tests run in **parallel** (sleeps overlap)
- Embedded/repeatable tests run **sequentially**
- Tests have inherent execution overhead beyond sleep time

**Converting these tests would provide minimal to no wall-clock improvement**, and could potentially increase total test time due to the parallelism → sequential trade-off.

### Recommendations

1. **No further sleep-based conversions recommended** for time savings purposes
2. Consider conversion only if tests would benefit from:
   - **Determinism** (eliminating race conditions)
   - **Reduced flakiness** (virtual time eliminates timing-sensitive failures)
   - **Faster local development** (individual test runs)
3. Focus optimization efforts on other areas:
   - Unit test improvements (Awaitility patterns)
   - Test infrastructure optimizations
   - Reducing test setup/teardown overhead
