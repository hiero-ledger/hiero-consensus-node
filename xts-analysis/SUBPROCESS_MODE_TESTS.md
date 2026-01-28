# HAPI Tests Running in Subprocess Mode

This document lists all HAPI tests that run in subprocess mode (the slowest execution mode).

---

## ⚠️ Key Finding: Major Sleep Optimizations Already Complete

The longest-running sleep-based tests have **already been converted** to `@RepeatableHapiTest`:

|                                     Test                                      | Original Sleep  |   Status    |
|-------------------------------------------------------------------------------|-----------------|-------------|
| `DisabledLongTermExecutionScheduleTest.scheduledTestGetsDeletedIfNotExecuted` | **31 minutes**  | ✅ Converted |
| `TxnRecordRegression.receiptUnavailableAfterCacheTtl`                         | **179 seconds** | ✅ Converted |

Further conversions for time savings offer **diminishing returns** (~37 seconds total remaining). See `SUBPROCESS_TESTS_WITH_SLEEPS.md` for details.

---

## Understanding Subprocess Mode

**Subprocess mode** runs actual Hedera nodes as separate JVM processes:
- Communication via real gRPC over localhost
- Real consensus/gossip between nodes
- Real wall-clock time for sleeps and waits
- Full node lifecycle support (restart, upgrade, stop)

**Why it's the slowest:**
- Multiple JVM startup overhead
- Real network latency
- Real `Thread.sleep()` for all timing operations
- Resource-intensive (multiple processes)

---

## Test Categories Running in Subprocess Mode

Tests are executed via the `testSubprocess` Gradle task with various tag filters:

### 1. CRYPTO Tests (`hapiTestCrypto`)

**Tag:** `CRYPTO`
**Estimated files:** ~30+ test classes

Key test suites:
- `CryptoTransferSuite.java` - Crypto transfer operations
- `CryptoCreateSuite.java` - Account creation
- `CryptoUpdateSuite.java` - Account updates
- `CryptoDeleteSuite.java` - Account deletion
- `CryptoDeleteAllowanceSuite.java` - Allowance deletion
- `CryptoApproveAllowanceSuite.java` - Allowance approval
- `CryptoGetInfoRegression.java` - Account info queries
- `CryptoRecordsSanityCheckSuite.java` - Record validation
- `CryptoGetRecordsRegression.java` - Record queries
- `AutoAccountCreationSuite.java` - Auto account creation
- `AutoAccountCreationUnlimitedAssociationsSuite.java` - Unlimited associations
- `AutoAccountUpdateSuite.java` - Auto account updates
- `HollowAccountFinalizationSuite.java` - Hollow account tests
- `LeakyCryptoTestsSuite.java` - Tests with side effects
- `MiscCryptoSuite.java` - Miscellaneous crypto tests
- `QueryPaymentSuite.java` - Query payment tests
- `TransferWithCustomFixedFees.java` - Custom fee transfers
- `TransferWithCustomFractionalFees.java` - Fractional fee transfers
- `TransferWithCustomRoyaltyFees.java` - Royalty fee transfers
- `TxnRecordRegression.java` - Transaction record tests

### 2. TOKEN Tests (`hapiTestToken`)

**Tag:** `TOKEN`
**Estimated files:** ~40+ test classes

Key test suites:
- `TokenCreateSpecs.java` - Token creation
- `TokenUpdateSpecs.java` - Token updates
- `TokenDeleteSpecs.java` - Token deletion
- `TokenAssociationSpecs.java` - Token associations
- `TokenTransactSpecs.java` - Token transactions
- `TokenManagementSpecs.java` - Token management
- `TokenManagementSpecsStateful.java` - Stateful management
- `TokenPauseSpecs.java` - Token pause/unpause
- `TokenMetadataSpecs.java` - Token metadata
- `TokenFeeScheduleUpdateSpecs.java` - Fee schedule updates
- `TokenTotalSupplyAfterMintBurnWipeSuite.java` - Supply tracking
- `TokenUpdateNftsSuite.java` - NFT updates
- `UniqueTokenManagementSpecs.java` - NFT management
- `Hip17UnhappyTokensSuite.java` - Unhappy path tests
- `Hip17UnhappyAccountsSuite.java` - Account unhappy paths
- `Hip540Suite.java` - HIP-540 tests
- All `Atomic*` batch variants of above

### 3. SMART_CONTRACT Tests (`hapiTestSmartContract`)

**Tag:** `SMART_CONTRACT`
**Estimated files:** ~80+ test classes

Major categories:
- **Contract HAPI:** `ContractCreateSuite`, `ContractCallSuite`, `ContractStateSuite`, `ContractUpdateSuite`, `ContractGetInfoSuite`
- **Precompiles:** `ERCPrecompileSuite`, `CryptoTransferHTSSuite`, `ContractHTSSuite`, `TokenInfoHTSSuite`, `SigningReqsSuite`, `HRCPrecompileSuite`
- **Ethereum:** `EthereumSuite`, `JumboTransactionsEnabledTest`
- **Opcodes:** `Create2OperationSuite`, `SelfDestructSuite`, `PushZeroOperationSuite`
- **Traceability:** `TraceabilitySuite`
- **Schedule precompiles:** `ScheduleCallTest`, `ScheduleDeleteTest`, `HasScheduleCapacityTest`
- **Airdrops:** `AirdropSystemContractTest`, `TokenClaimAirdropSystemContractTest`, etc.
- **Validation:** `EvmValidationTest`, `Evm50ValidationSuite`

### 4. RESTART/UPGRADE Tests (`hapiTestRestart`)

**Tag:** `RESTART|UPGRADE`
**Files:** ~5 test classes

**MUST stay in subprocess - require actual node lifecycle:**
- `DabEnabledUpgradeTest.java` - DAB upgrade testing
- `QuiesceThenMixedOpsRestartTest.java` - Quiescence + restart
- `BesuNativeLibVerificationTest.java` - Native lib verification
- `UpgradeHashTest.java` - Upgrade hash validation

### 5. ND_RECONNECT Tests (`hapiTestNDReconnect`)

**Tag:** `ND_RECONNECT`
**Files:** ~3 test classes

**MUST stay in subprocess - require actual node death/reconnect:**
- `MixedOpsNodeDeathReconnectTest.java` - Node death + reconnect
- `AncientAgeBirthRoundTest.java` - Ancient age tests

### 6. LONG_RUNNING Tests (`hapiTestTimeConsuming`)

**Tag:** `LONG_RUNNING`
**Files:** ~7 test classes

- `SteadyStateThrottlingTest.java` - Throttling tests
- `ERCPrecompileSuite.java` (some tests)
- `CryptoTransferHTSSuite.java` (some tests)
- `ERC20ContractInteractions.java` - ERC20 tests
- `EthereumSuite.java` (some tests)
- `StakingSuite.java` - Staking tests
- `ContractKeysHTSSuite.java` - Contract keys tests

### 7. ISS Tests (`hapiTestIss`)

**Tag:** `ISS`
**Files:** ~1 test class

**MUST stay in subprocess - require actual ISS handling:**
- `IssHandlingTest.java` - ISS handling tests

### 8. BLOCK_NODE Tests (`hapiTestBlockNodeCommunication`)

**Tag:** `BLOCK_NODE`
**Files:** ~5 test classes

- `BlockNodeSuite.java` - Basic block node tests
- `BlockNodeBackPressureSuite.java` - Back pressure tests
- `BlockNodeSoftwareUpgradeSuite.java` - Upgrade tests
- `NodeDeathReconnectBlockNodeSuite.java` - Reconnect tests

### 9. SIMPLE_FEES Tests (`hapiTestSimpleFees`)

**Tag:** `SIMPLE_FEES`
**Files:** ~5 test classes

- `TokenServiceSimpleFeesSuite.java`
- `CryptoSimpleFeesSuite.java`
- `ConsensusServiceSimpleFeesSuite.java`
- `CryptoCreateSimpleFeesTest.java`
- `TopicCreateSimpleFeesTest.java`

### 10. MISC Tests (`hapiTestMisc`)

**Tag:** Everything not in above categories
**Estimated files:** ~100+ test classes

Includes:
- Schedule tests: `ScheduleCreateTest`, `ScheduleSignTest`, `ScheduleExecutionTest`, `ScheduleRecordTest`
- File tests: `FileUpdateSuite`, `FileAppendSuite`
- Throttling tests: `ThrottleDefValidationSuite`, `PrivilegedOpsTest`, `GasLimitThrottlingSuite`
- HIP tests: Various HIP implementation tests
- Fee tests: `CryptoServiceFeesSuite`, `AtomicCryptoServiceFeesSuite`
- Regression tests: `UmbrellaRedux`, various issue regressions
- Validation tests: `StreamValidationTest`, `LogValidationTest`

### 11. ONLY_SUBPROCESS Tagged Tests

**Tag:** `ONLY_SUBPROCESS`
**Files:** 5 test classes

**MUST stay in subprocess - explicitly marked:**
- `UpdateNodeAccountTest.java` - Node account updates
- `TopicCreateSimpleFeesTest.java` - Topic fee tests
- `CryptoCreateSimpleFeesTest.java` - Crypto fee tests
- `GovernanceTransactionsTests.java` - Governance tests
- `IssueRegressionTests.java` - Issue regression tests

---

## Total Count Summary

|    Category     |        Tag         | Estimated Test Classes | Must Stay Subprocess |
|-----------------|--------------------|------------------------|----------------------|
| CRYPTO          | `CRYPTO`           | ~30                    | No                   |
| TOKEN           | `TOKEN`            | ~40                    | No                   |
| SMART_CONTRACT  | `SMART_CONTRACT`   | ~80                    | No                   |
| RESTART/UPGRADE | `RESTART\|UPGRADE` | ~5                     | **YES**              |
| ND_RECONNECT    | `ND_RECONNECT`     | ~3                     | **YES**              |
| LONG_RUNNING    | `LONG_RUNNING`     | ~7                     | Mostly No            |
| ISS             | `ISS`              | ~1                     | **YES**              |
| BLOCK_NODE      | `BLOCK_NODE`       | ~5                     | Partially            |
| SIMPLE_FEES     | `SIMPLE_FEES`      | ~5                     | No                   |
| MISC            | (other)            | ~100                   | Mostly No            |
| ONLY_SUBPROCESS | `ONLY_SUBPROCESS`  | ~5                     | **YES**              |

**Total: ~280+ test classes running in subprocess mode**

---

## Tests Using @LeakyHapiTest (99 files)

These tests have side effects and run against the shared network. They use the `@LeakyHapiTest` annotation:

```
RecordCreationSuite.java
AtomicNodeUpdateTest.java
AtomicNodeCreateTest.java
NodeUpdateTest.java
NodeCreateTest.java
Hip1195EnabledTest.java
TokenServiceSimpleFeesSuite.java
AirdropToContractSystemContractTest.java
AtomicCryptoTransferHTSSuite.java
LeakyContractTestsSuite.java
CryptoServiceFeesSuite.java
EthereumSuite.java
Issue1765Suite.java
CryptoRecordsSanityCheckSuite.java
ContractRecordsSanityCheckSuite.java
ScheduleRecordTest.java
TargetNetworkPrep.java
CryptoCreateSimpleFeesTest.java
ThrottleDefValidationSuite.java
PrivilegedOpsTest.java
UmbrellaRedux.java
HollowAccountFuzzing.java
AtomicBatchFuzzing.java
AsNodeOperatorQueriesTest.java
AtomicBatchPrecompileTest.java
HollowAccountsAndKeysBatchTest.java
AtomicBatchTest.java
AtomicBatchNegativeTest.java
ContractStateSuite.java
ContractCreateSuite.java
CryptoGetInfoRegression.java
ScheduleDeleteTest.java
ConsensusServiceSimpleFeesSuite.java
GovernanceTransactionsTests.java
ScheduleExecutionTest.java
CryptoSimpleFeesSuite.java
JumboTransactionsEnabledTest.java
AirdropsFeatureFlagTest.java
AtomicNodeDeleteTest.java
ScheduleLongTermExecutionTest.java
DisabledLongTermExecutionScheduleTest.java
FeeScheduleUpdateWaiverTest.java
TraceabilitySuite.java
SigningReqsSuite.java
CryptoCreateSuite.java
NodeDeleteTest.java
HasScheduleCapacityTest.java
SelfDestructSuite.java
StreamValidationTest.java
AtomicTokenManagementSpecsStateful.java
AtomicTokenAssociationSpecs.java
TokenManagementSpecsStateful.java
TokenAssociationSpecs.java
GasLimitThrottlingSuite.java
StatefulScheduleExecutionTest.java
AllLeakyTestsWithLongTermFlagEnabledTest.java
UtilScalePricingCheck.java
IssueRegressionTests.java
Issue305Spec.java
Issue2319Spec.java
Issue2143Spec.java
Issue2098Spec.java
ThrottleOnDispatchTest.java
UnlimitedAutoAssociationSuite.java
TokenClaimAirdropTest.java
TokenAirdropTest.java
AirdropConsensusThrottleTest.java
AtomicFileUpdateSuite.java
AtomicFileAppendSuite.java
FileUpdateSuite.java
FileAppendSuite.java
AtomicCryptoServiceFeesSuite.java
AtomicAutoAccountCreationUnlimitedAssociationsSuite.java
MiscCryptoSuite.java
LeakyCryptoTestsSuite.java
CryptoUpdateSuite.java
CryptoDeleteSuite.java
CryptoDeleteAllowanceSuite.java
AutoAccountCreationUnlimitedAssociationsSuite.java
GasCalculationIntegrityTest.java
ScheduleCallTest.java
LazyCreateThroughPrecompileSuite.java
HRCPrecompileSuite.java
PushZeroOperationSuite.java
Create2OperationSuite.java
AtomicLeakyEthereumTestsSuite.java
AtomicLeakyContractTestsSuite.java
LeakyEthereumTestsSuite.java
AtomicContractUpdateSuite.java
ContractUpdateSuite.java
SmartContractServiceFeesTest.java
AtomicSmartContractServiceFeesTest.java
AtomicEvm50ValidationSuite.java
Evm50ValidationSuite.java
LogValidationTest.java
PrecompileMintThrottlingCheck.java
StakingSuite.java
ScheduleSignTest.java
ScheduleCreateTest.java
```

---

## Next Steps

See `SUBPROCESS_TESTS_CONVERTIBLE.md` for analysis of which tests can be converted to embedded or repeatable mode.
