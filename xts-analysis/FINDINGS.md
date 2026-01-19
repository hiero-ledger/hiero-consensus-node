# XTS Test Suite Analysis - Findings

## Executive Summary

This document contains findings from analyzing the XTS (Extended Test Suite) test infrastructure. The analysis identified several areas of concern:

1. **63+ duplicate test files** - Atomic* tests that are explicitly documented as "direct copies" of other tests
2. **87+ uses of arbitrary sleeps** - `sleepFor()` calls across 46 test files
3. **Test organization issues** - Some tests may be redundant or could be consolidated

---

## 1. Duplicated Test Logic

### Problem Description

There are **63+ Atomic* test files** that are explicitly documented as "direct copies" of existing test files. These files contain the comment pattern:

```java
// This test cases are direct copies of [OriginalTest]. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
```

### Duplicated Test Files

|                      Atomic Test                      |                  Original Test                  |      Category       |
|-------------------------------------------------------|-------------------------------------------------|---------------------|
| `AtomicAutoAccountCreationSuite`                      | `AutoAccountCreationSuite`                      | Crypto              |
| `AtomicAutoAccountCreationUnlimitedAssociationsSuite` | `AutoAccountCreationUnlimitedAssociationsSuite` | Crypto              |
| `AtomicAutoAccountUpdateSuite`                        | `AutoAccountUpdateSuite`                        | Crypto              |
| `AtomicNodeCreateTest`                                | `NodeCreateTest`                                | Node (HIP-869)      |
| `AtomicNodeUpdateTest`                                | `NodeUpdateTest`                                | Node (HIP-869)      |
| `AtomicNodeDeleteTest`                                | `NodeDeleteTest`                                | Node (HIP-869)      |
| `AtomicContractCreateSuite`                           | `ContractCreateSuite`                           | Smart Contract      |
| `AtomicContractCallSuite`                             | `ContractCallSuite`                             | Smart Contract      |
| `AtomicContractDeleteSuite`                           | `ContractDeleteSuite`                           | Smart Contract      |
| `AtomicContractUpdateSuite`                           | `ContractUpdateSuite`                           | Smart Contract      |
| `AtomicTokenCreateSpecs`                              | `TokenCreateSpecs`                              | Token               |
| `AtomicTokenDeleteSpecs`                              | `TokenDeleteSpecs`                              | Token               |
| `AtomicTokenUpdateSpecs`                              | `TokenUpdateSpecs`                              | Token               |
| `AtomicTokenPauseSpecs`                               | `TokenPauseSpecs`                               | Token               |
| `AtomicTokenAssociationSpecs`                         | `TokenAssociationSpecs`                         | Token               |
| `AtomicTokenTransactSpecs`                            | `TokenTransactSpecs`                            | Token               |
| `AtomicTokenManagementSpecs`                          | `TokenManagementSpecs`                          | Token               |
| `AtomicTokenMetadataSpecs`                            | `TokenMetadataSpecs`                            | Token               |
| `AtomicTokenFeeScheduleUpdateSpecs`                   | `TokenFeeScheduleUpdateSpecs`                   | Token               |
| `AtomicUniqueTokenManagementSpecs`                    | `UniqueTokenManagementSpecs`                    | Token               |
| `AtomicHip17UnhappyAccountsSuite`                     | `Hip17UnhappyAccountsSuite`                     | Token               |
| `AtomicHip17UnhappyTokensSuite`                       | `Hip17UnhappyTokensSuite`                       | Token               |
| `AtomicTopicCustomFeeCreateTest`                      | `TopicCustomFeeCreateTest`                      | Consensus (HIP-991) |
| `AtomicTopicCustomFeeUpdateTest`                      | `TopicCustomFeeUpdateTest`                      | Consensus (HIP-991) |
| `AtomicTopicCustomFeeSubmitMessageTest`               | `TopicCustomFeeSubmitMessageTest`               | Consensus (HIP-991) |
| `AtomicTopicCustomFeeGetTopicInfoTest`                | `TopicCustomFeeGetTopicInfoTest`                | Consensus (HIP-991) |
| `AtomicFileAppendSuite`                               | `FileAppendSuite`                               | File                |
| `AtomicFileCreateSuite`                               | `FileCreateSuite`                               | File                |
| `AtomicFileDeleteSuite`                               | `FileDeleteSuite`                               | File                |
| `AtomicFileUpdateSuite`                               | `FileUpdateSuite`                               | File                |
| `AtomicProtectedFilesUpdateSuite`                     | `ProtectedFilesUpdateSuite`                     | File                |
| `AtomicExchangeRateControlSuite`                      | `ExchangeRateControlSuite`                      | File                |
| `AtomicEthereumSuite`                                 | `EthereumSuite`                                 | Ethereum            |
| `AtomicHelloWorldEthereumSuite`                       | `HelloWorldEthereumSuite`                       | Ethereum            |
| `AtomicEvm38ValidationSuite`                          | `Evm38ValidationSuite`                          | EVM                 |
| `AtomicEvm46ValidationSuite`                          | `Evm46ValidationSuite`                          | EVM                 |
| `AtomicEvm50ValidationSuite`                          | `Evm50ValidationSuite`                          | EVM                 |
| `AtomicConsensusServiceFeesSuite`                     | `ConsensusServiceFeesSuite`                     | Fees                |
| `AtomicTokenServiceFeesSuite`                         | `TokenServiceFeesSuite`                         | Fees                |
| `AtomicCryptoServiceFeesSuite`                        | `CryptoServiceFeesSuite`                        | Fees                |
| `AtomicFileServiceFeesSuite`                          | `FileServiceFeesSuite`                          | Fees                |
| `AtomicMiscellaneousFeesSuite`                        | `MiscellaneousFeesSuite`                        | Fees                |
| `AtomicSmartContractServiceFeesTest`                  | `SmartContractServiceFeesTest`                  | Fees                |
| `AtomicUtilPrngSuite`                                 | `UtilPrngSuite`                                 | Util                |
| ... and 20+ more                                      |                                                 |                     |

### Recommendation

Consider refactoring to:
1. Parameterize tests to run both standalone and atomic batch modes
2. Create a shared test base class with overridable execution strategy
3. Use test factories to generate both variants from a single source

---

## 2. Tests Using Arbitrary Sleeps

### Problem Description

Found **87+ uses of `sleepFor()`** across 46 test files. Arbitrary sleeps can cause:
- Flaky tests
- Unnecessarily slow test execution
- Hidden race conditions

### Files with Sleeps (sorted by count)

|                   File                    | Count |          Notes           |
|-------------------------------------------|-------|--------------------------|
| `DisabledNodeOperatorTest.java`           | 9     | Multiple poll/wait loops |
| `RepeatableHip423Tests.java`              | 5     | Schedule timing tests    |
| `DuplicateManagementTest.java`            | 4     | Transaction timing       |
| `IssueRegressionTests.java`               | 3     | Legacy issue tests       |
| `SubmitMessageSuite.java`                 | 3     | Message timing           |
| `AtomicSubmitMessageSuite.java`           | 3     | (Duplicate of above)     |
| `CongestionPricingTest.java`              | 3     | Congestion simulation    |
| `JumboTransactionsEnabledTest.java`       | 3     | Large tx tests           |
| `RepeatableScheduleLongTermSignTest.java` | 3     | Schedule tests           |
| `StreamValidationOp.java`                 | 3     | Stream file waits        |
| `SubmitMessageLoadTest.java`              | 3     | Load test timing         |
| `StakingSuite.java`                       | 2     | Staking rewards          |
| `ScheduleSignTest.java`                   | 2     | Schedule execution       |
| `FutureSchedulableOpsTest.java`           | 2     | Future scheduling        |
| `TokenServiceFeesSuite.java`              | 2     | Fee tests                |
| `FileServiceFeesSuite.java`               | 2     | Fee tests                |
| `ConsensusServiceFeesSuite.java`          | 2     | Fee tests                |
| `CryptoServiceFeesSuite.java`             | 2     | Fee tests                |
| `ContractCallLocalSuite.java`             | 2     | Contract calls           |
| `ContractSignScheduleTest.java`           | 2     | Contract schedule        |
| ... and 26 more files with 1 sleep each   |       |                          |

### Specific Sleep Patterns Found

#### Long Fixed Sleeps

```java
// ContractGetInfoSuite.java - 5 second wait
sleepFor(5_000)

// UtilVerbs.java - 20 second wait for fee reduction
Thread.sleep(20000)
```

#### Sleep in Poll Loops

```java
// AsNodeOperatorQueriesTest.java - Poll with 1 second sleep
Thread.sleep(1000);
```

#### Timing-dependent Waits

```java
// StreamValidationOp.java - Wait for block time
sleepFor(MAX_BLOCK_TIME_MS + BUFFER_MS)
```

### Recommendation

1. Replace fixed sleeps with polling/await mechanisms with timeouts
2. Use `Awaitility` or similar libraries for condition-based waiting
3. For stream/block tests, use event-based notification where possible
4. Consider virtual time for repeatable tests

---

## 3. Potential Pointless/Redundant Tests

### Issue Regression Tests

The following files in `suites/issues/` may be outdated regression tests for specific bug fixes:

|            File             | Issue Reference |
|-----------------------------|-----------------|
| `Issue1765Suite.java`       | Issue 1765      |
| `Issue2098Spec.java`        | Issue 2098      |
| `Issue2143Spec.java`        | Issue 2143      |
| `Issue2319Spec.java`        | Issue 2319      |
| `Issue305Spec.java`         | Issue 305       |
| `IssueRegressionTests.java` | Various issues  |

**Recommendation:** Review if these issues are still relevant. Consider:
- Removing tests for issues that are no longer applicable
- Consolidating into feature-based tests
- Adding documentation about which issues they prevent regression for

### Duplicate Validation

Some tests appear to test the same functionality:
- `TokenAssociationSpecs.java` and `AtomicTokenAssociationSpecs.java` - both test token association
- `CryptoServiceFeesSuite.java` and `AtomicCryptoServiceFeesSuite.java` - both test fees
- Multiple `*Records*` tests across suites

---

## 4. Test Organization Issues

### Inconsistent Naming Patterns

- Some use `*Suite` suffix (e.g., `TokenAssociationSpecs.java`)
- Some use `*Test` suffix (e.g., `NodeCreateTest.java`)
- Some use `*Specs` suffix (e.g., `TokenCreateSpecs.java`)

### Fragmented Test Structure

Tests for similar features are spread across multiple locations:
- HIP-991 (Topic Custom Fees): 4 regular + 4 atomic = 8 files
- HIP-869 (Node Management): 3 regular + 3 atomic = 6 files
- Token tests: 15 regular + 15 atomic = 30 files

---

## 5. Statistics Summary

|          Category           |     Count      |
|-----------------------------|----------------|
| **Total XTS Test Groups**   | 7 major groups |
| **HAPI Test Files**         | 400+ files     |
| **Duplicate Atomic* Tests** | 63+ files      |
| **Files with sleepFor()**   | 46 files       |
| **Total sleepFor() calls**  | 87+            |
| **Timing Sensitive Tests**  | 36 files       |
| **Hammer Tests**            | 3 files        |
| **Otter Tests**             | 14 files       |
| **Chaos Tests**             | 1 file         |

---

## 6. Priority Recommendations

### High Priority

1. **Consolidate Atomic* tests** - Reduce 63+ duplicate files by parameterizing tests
2. **Replace critical sleeps** - Focus on tests in the main CI path first

### Medium Priority

3. **Review issue regression tests** - Remove or consolidate outdated tests
4. **Standardize naming conventions** - Adopt consistent `*Test` suffix

### Low Priority

5. **Reorganize test structure** - Group related tests better
6. **Add test documentation** - Clarify purpose of each test suite

---

## File Locations

All analysis files are in: `xts-analysis/`

- `XTS_TEST_GROUPS_OVERVIEW.md` - Overview of all XTS test groups
- `lists/HAPI_TESTS_CRYPTO.md` - Crypto test files
- `lists/HAPI_TESTS_TOKEN.md` - Token test files
- `lists/HAPI_TESTS_SMART_CONTRACT.md` - Smart contract test files
- `lists/TIMING_SENSITIVE_TESTS.md` - Timing sensitive test files
- `lists/HAMMER_TESTS.md` - Hammer test files
- `lists/OTTER_TESTS.md` - Otter test files
- `lists/OTHER_HAPI_TESTS.md` - Other HAPI test categories
- `FINDINGS.md` - This document
