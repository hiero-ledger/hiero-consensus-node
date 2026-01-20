# Subprocess Tests - Conversion Analysis

This document analyzes which subprocess-mode tests could potentially be converted to embedded or repeatable mode without breaking their purpose.

---

## ⚠️ Key Finding: Major Sleep Optimizations Already Complete

**The largest sleep-related conversions have already been implemented.**

|                                     Test                                      |  Original Sleep  |         Current Status          |
|-------------------------------------------------------------------------------|------------------|---------------------------------|
| `DisabledLongTermExecutionScheduleTest.scheduledTestGetsDeletedIfNotExecuted` | **31 minutes**   | ✅ Already `@RepeatableHapiTest` |
| `TxnRecordRegression.receiptUnavailableAfterCacheTtl`                         | **179 seconds**  | ✅ Already `@RepeatableHapiTest` |
| `RepeatableHip423Tests` (all tests)                                           | 5-8 seconds each | ✅ Already `@RepeatableHapiTest` |
| `RepeatableScheduleLongTermSignTest` (all tests)                              | 6 seconds each   | ✅ Already `@RepeatableHapiTest` |

**The remaining convertible tests total only ~37 seconds of sleep time**, and due to subprocess parallelism, actual time savings would be minimal.

**Recommendation:** Further conversions should be motivated by **determinism** or **flakiness reduction**, not time savings. See `SUBPROCESS_TESTS_WITH_SLEEPS.md` for detailed analysis.

---

## Critical Context: Subprocess Test Execution Model

Before analyzing conversions, it's essential to understand how subprocess tests execute:

### Execution Flow (Per Tag)

```
┌─────────────────────────────────────────────────────────────────┐
│                    TAG EXECUTION (e.g., CRYPTO)                 │
├─────────────────────────────────────────────────────────────────┤
│  1. Network starts (once per tag)                               │
│  2. Tests execute in RANDOM order                               │
│     ├── Test A → modifies state, generates logs/streams         │
│     ├── Test B → modifies state, generates logs/streams         │
│     ├── Test C → modifies state, generates logs/streams         │
│     └── ... (all tests in tag)                                  │
│  3. Validation tests run LAST:                                  │
│     ├── StreamValidationTest → verifies all stream output       │
│     └── LogValidationTest → verifies all log output             │
│  4. Network stops                                               │
└─────────────────────────────────────────────────────────────────┘
```

### Key Facts

|         Aspect         |                           Behavior                           |
|------------------------|--------------------------------------------------------------|
| **Network lifecycle**  | Started once per tag, stopped after validation               |
| **Test execution**     | **Subprocess: PARALLEL** / Embedded & Repeatable: SEQUENTIAL |
| **Test order**         | Random (except validation tests run last)                    |
| **State accumulation** | All tests modify shared state                                |
| **Validation scope**   | Validates cumulative output of ALL tests in tag              |

### Parallelism Impact

|      Mode      | Execution  |               Wall-Clock Time                |
|----------------|------------|----------------------------------------------|
| **Subprocess** | Parallel   | ≈ longest single test (sleeps overlap)       |
| **Embedded**   | Sequential | = sum of all test durations                  |
| **Repeatable** | Sequential | = sum of all test durations (sleeps instant) |

**Time savings reality check:** In subprocess mode, parallel test sleeps overlap. Converting to repeatable eliminates sleeps but adds sequential overhead. Tests with **isolated long sleeps** (like the 31-minute schedule expiry test) benefit most.

### Impact on Conversion

1. **State changes in embedded/repeatable mode are NOT applied to subprocess network**
   - Converted tests won't contribute to the state that gets validated
2. **Converted tests must move to a NEW tag**
   - Cannot mix subprocess and embedded tests in same tag
   - Example: `CRYPTO` → keep subprocess tests, create `CRYPTO_REPEATABLE` for converted tests
3. **Validation coverage changes**
   - Original tag's validation only covers remaining subprocess tests
   - May need new validation strategy for converted test groups
4. **Most tests are independent (no explicit dependencies)**
   - Random order means tests shouldn't rely on each other's state
   - But validation checks cumulative state of all tests together

---

## Conversion Criteria

### Tests That MUST Stay in Subprocess Mode

A test must remain in subprocess mode if it:
1. **Uses node lifecycle operations** (`FakeNmt.shutdownWithin`, `FakeNmt.restartNode`, `waitForActive`)
2. **Requires actual gossip/consensus** between multiple real nodes
3. **Tests network resilience** (node death, reconnection, ISS handling)
4. **Uses `uncheckedSubmit`** which needs actual network duplicate handling
5. **Targets specific nodes** with `setNode()` for multi-node behavior testing
6. **Is explicitly tagged** `ONLY_SUBPROCESS`

### Tests That Can Convert to Embedded (Concurrent) Mode

A test can convert to embedded mode if it:
1. Only tests transaction processing logic (not network behavior)
2. Doesn't require multiple actual nodes
3. Doesn't test node lifecycle operations
4. May use ECDSA keys (random signatures OK in concurrent mode)
5. May use `inParallel()` operations

### Tests That Can Convert to Repeatable Mode

A test can convert to repeatable mode if it:
1. Meets all embedded mode criteria, AND
2. Doesn't use ECDSA keys (random signatures break determinism)
3. Doesn't require `inParallel()` execution (will be serialized)
4. Would benefit from virtual time (has sleeps/waits)

---

## Analysis by Category

### 1. CRYPTO Tests (~30 files)

**Current mode:** Subprocess (`hapiTestCrypto`)

**Analysis:**

|   Subcategory    |                                  Files                                  | Can Convert to Embedded? | Can Convert to Repeatable? |                    Notes                    |
|------------------|-------------------------------------------------------------------------|--------------------------|----------------------------|---------------------------------------------|
| Basic transfers  | `CryptoTransferSuite.java`                                              | ✅ YES                    | ⚠️ PARTIAL                 | Uses ECDSA in some tests, uses `inParallel` |
| Account creation | `CryptoCreateSuite.java`                                                | ✅ YES                    | ⚠️ PARTIAL                 | Heavy ECDSA usage (46+ matches)             |
| Account updates  | `CryptoUpdateSuite.java`                                                | ✅ YES                    | ✅ YES                      | No special requirements                     |
| Account deletion | `CryptoDeleteSuite.java`                                                | ✅ YES                    | ✅ YES                      | No special requirements                     |
| Allowances       | `CryptoApproveAllowanceSuite.java`, `CryptoDeleteAllowanceSuite.java`   | ✅ YES                    | ✅ YES                      | No special requirements                     |
| Info queries     | `CryptoGetInfoRegression.java`                                          | ✅ YES                    | ✅ YES                      | No special requirements                     |
| Records          | `CryptoRecordsSanityCheckSuite.java`, `CryptoGetRecordsRegression.java` | ✅ YES                    | ✅ YES                      | No special requirements                     |
| Auto account     | `AutoAccountCreationSuite.java`                                         | ✅ YES                    | ❌ NO                       | Heavy ECDSA usage (34+ matches)             |
| Hollow accounts  | `HollowAccountFinalizationSuite.java`                                   | ✅ YES                    | ❌ NO                       | Heavy ECDSA usage (127+ matches)            |
| Query payment    | `QueryPaymentSuite.java`                                                | ⚠️ MAYBE                 | ⚠️ MAYBE                   | Uses `setNode()` (9 occurrences)            |
| Leaky tests      | `LeakyCryptoTestsSuite.java`                                            | ✅ YES                    | ⚠️ PARTIAL                 | Uses `setNode()`, `inParallel`, ECDSA       |
| Custom fees      | `TransferWithCustomFixedFees.java`, etc.                                | ✅ YES                    | ⚠️ PARTIAL                 | Some ECDSA usage                            |

**Conversion Summary for CRYPTO:**
- **Convertible to Embedded:** ~25 files (83%)
- **Convertible to Repeatable:** ~12 files (40%)
- **Must stay Subprocess:** ~5 files that use `setNode()` for multi-node queries

---

### 2. TOKEN Tests (~40 files)

**Current mode:** Subprocess (`hapiTestToken`)

**Analysis:**

|   Subcategory   |                              Files                               | Can Convert to Embedded? | Can Convert to Repeatable? |          Notes          |
|-----------------|------------------------------------------------------------------|--------------------------|----------------------------|-------------------------|
| Token CRUD      | `TokenCreateSpecs`, `TokenUpdateSpecs`, `TokenDeleteSpecs`       | ✅ YES                    | ✅ YES                      | Pure business logic     |
| Associations    | `TokenAssociationSpecs.java`                                     | ✅ YES                    | ✅ YES                      | No special requirements |
| Transactions    | `TokenTransactSpecs.java`                                        | ✅ YES                    | ⚠️ PARTIAL                 | Uses `inParallel`       |
| Management      | `TokenManagementSpecs.java`, `TokenManagementSpecsStateful.java` | ✅ YES                    | ✅ YES                      | Stateful tests work     |
| Pause/Unpause   | `TokenPauseSpecs.java`                                           | ✅ YES                    | ✅ YES                      | No special requirements |
| Metadata        | `TokenMetadataSpecs.java`                                        | ✅ YES                    | ✅ YES                      | No special requirements |
| Fee schedules   | `TokenFeeScheduleUpdateSpecs.java`                               | ✅ YES                    | ✅ YES                      | No special requirements |
| NFT management  | `UniqueTokenManagementSpecs.java`, `TokenUpdateNftsSuite.java`   | ✅ YES                    | ✅ YES                      | No special requirements |
| Supply tracking | `TokenTotalSupplyAfterMintBurnWipeSuite.java`                    | ✅ YES                    | ✅ YES                      | No special requirements |
| Unhappy paths   | `Hip17UnhappyTokensSuite.java`, `Hip17UnhappyAccountsSuite.java` | ✅ YES                    | ✅ YES                      | Error case testing      |
| HIP-540         | `Hip540Suite.java`                                               | ✅ YES                    | ✅ YES                      | No special requirements |
| Batch variants  | All `Atomic*` suites                                             | ✅ YES                    | ✅ YES                      | Same as base tests      |

**Conversion Summary for TOKEN:**
- **Convertible to Embedded:** ~38 files (95%)
- **Convertible to Repeatable:** ~35 files (87%)
- **Must stay Subprocess:** ~2 files (tests using `inParallel` heavily)

---

### 3. SMART_CONTRACT Tests (~80 files)

**Current mode:** Subprocess (`hapiTestSmartContract`)

**Analysis:**

|     Subcategory      |                               Files                               | Can Convert to Embedded? | Can Convert to Repeatable? |             Notes              |
|----------------------|-------------------------------------------------------------------|--------------------------|----------------------------|--------------------------------|
| Contract HAPI        | `ContractCreateSuite`, `ContractCallSuite`, `ContractStateSuite`  | ✅ YES                    | ⚠️ PARTIAL                 | Some ECDSA usage               |
| Contract info        | `ContractGetInfoSuite`                                            | ✅ YES                    | ✅ YES                      | Query tests                    |
| Contract updates     | `ContractUpdateSuite`                                             | ✅ YES                    | ✅ YES                      | No special requirements        |
| ERC precompiles      | `ERCPrecompileSuite`                                              | ✅ YES                    | ❌ NO                       | Long-running, timing sensitive |
| HTS precompiles      | `CryptoTransferHTSSuite`, `ContractHTSSuite`, `TokenInfoHTSSuite` | ✅ YES                    | ⚠️ PARTIAL                 | Some timing-sensitive          |
| Signing              | `SigningReqsSuite`                                                | ✅ YES                    | ✅ YES                      | No special requirements        |
| HRC                  | `HRCPrecompileSuite`                                              | ✅ YES                    | ✅ YES                      | No special requirements        |
| Create2              | `Create2OperationSuite`                                           | ✅ YES                    | ⚠️ PARTIAL                 | Uses `inParallel`              |
| Self-destruct        | `SelfDestructSuite`                                               | ✅ YES                    | ✅ YES                      | No special requirements        |
| Ethereum             | `EthereumSuite`                                                   | ✅ YES                    | ❌ NO                       | ECDSA required (7+ matches)    |
| Jumbo txns           | `JumboTransactionsEnabledTest`                                    | ✅ YES                    | ⚠️ PARTIAL                 | Throttle timing                |
| Traceability         | `TraceabilitySuite`                                               | ✅ YES                    | ⚠️ PARTIAL                 | ECDSA usage                    |
| Schedule precompiles | `ScheduleCallTest`, `ScheduleDeleteTest`                          | ✅ YES                    | ✅ YES                      | Pure logic                     |
| Airdrop contracts    | Various airdrop tests                                             | ✅ YES                    | ⚠️ PARTIAL                 | Some ECDSA                     |
| Validation           | `EvmValidationTest`, `Evm50ValidationSuite`                       | ✅ YES                    | ❌ NO                       | ECDSA heavy (70+ matches)      |
| Lazy create          | `LazyCreateThroughPrecompileSuite`                                | ✅ YES                    | ❌ NO                       | ECDSA heavy (25+ matches)      |
| Leaky                | `LeakyContractTestsSuite`, `LeakyEthereumTestsSuite`              | ⚠️ MAYBE                 | ❌ NO                       | Uses `FakeNmt`, ECDSA          |

**Conversion Summary for SMART_CONTRACT:**
- **Convertible to Embedded:** ~70 files (87%)
- **Convertible to Repeatable:** ~40 files (50%)
- **Must stay Subprocess:** ~10 files (node lifecycle, heavy ECDSA)

---

### 4. RESTART/UPGRADE Tests (~5 files)

**Current mode:** Subprocess (`hapiTestRestart`)

**Analysis:**

|                 File                  | Can Convert? |            Reason             |
|---------------------------------------|--------------|-------------------------------|
| `DabEnabledUpgradeTest.java`          | ❌ NO         | Uses `FakeNmt` node lifecycle |
| `QuiesceThenMixedOpsRestartTest.java` | ❌ NO         | Uses restart operations       |
| `BesuNativeLibVerificationTest.java`  | ❌ NO         | Uses `FakeNmt` operations     |
| `UpgradeHashTest.java`                | ❌ NO         | Tests upgrade process         |

**Conversion Summary:** 0% convertible - all require actual node lifecycle

---

### 5. ND_RECONNECT Tests (~3 files)

**Current mode:** Subprocess (`hapiTestNDReconnect`)

**Analysis:**

|                 File                  | Can Convert? |                                Reason                                 |
|---------------------------------------|--------------|-----------------------------------------------------------------------|
| `MixedOpsNodeDeathReconnectTest.java` | ❌ NO         | Uses `FakeNmt.shutdownWithin`, `FakeNmt.restartNode`, `waitForActive` |
| `AncientAgeBirthRoundTest.java`       | ❌ NO         | Tests reconnect scenarios                                             |

**Conversion Summary:** 0% convertible - all require actual node death/reconnection

---

### 6. ISS Tests (~1 file)

**Current mode:** Subprocess (`hapiTestIss`)

|          File          | Can Convert? |                       Reason                        |
|------------------------|--------------|-----------------------------------------------------|
| `IssHandlingTest.java` | ❌ NO         | Tests actual ISS (Illegal State Signature) handling |

**Conversion Summary:** 0% convertible - requires actual ISS behavior

---

### 7. LONG_RUNNING Tests (~7 files)

**Current mode:** Subprocess (`hapiTestTimeConsuming`)

**Analysis:**

|               File               | Can Convert to Embedded? | Can Convert to Repeatable? |                Notes                |
|----------------------------------|--------------------------|----------------------------|-------------------------------------|
| `SteadyStateThrottlingTest.java` | ✅ YES                    | ⚠️ PARTIAL                 | Uses `inParallel`, timing sensitive |
| `ERCPrecompileSuite.java`        | ✅ YES                    | ❌ NO                       | Long duration, timing               |
| `CryptoTransferHTSSuite.java`    | ✅ YES                    | ⚠️ PARTIAL                 | Some tests timing sensitive         |
| `ERC20ContractInteractions.java` | ✅ YES                    | ❌ NO                       | ECDSA usage                         |
| `EthereumSuite.java`             | ✅ YES                    | ❌ NO                       | ECDSA usage                         |
| `StakingSuite.java`              | ✅ YES                    | ⚠️ PARTIAL                 | Uses `inParallel`                   |
| `ContractKeysHTSSuite.java`      | ✅ YES                    | ✅ YES                      | No special requirements             |

**Conversion Summary:**
- **Convertible to Embedded:** ~7 files (100%)
- **Convertible to Repeatable:** ~2 files (28%)

---

### 8. BLOCK_NODE Tests (~5 files)

**Current mode:** Subprocess (`hapiTestBlockNodeCommunication`)

**Analysis:**

|                  File                   | Can Convert? |             Reason             |
|-----------------------------------------|--------------|--------------------------------|
| `BlockNodeSuite.java`                   | ⚠️ MAYBE     | Tests block node communication |
| `BlockNodeBackPressureSuite.java`       | ❌ NO         | Uses `FakeNmt` operations      |
| `BlockNodeSoftwareUpgradeSuite.java`    | ❌ NO         | Tests upgrade with block nodes |
| `NodeDeathReconnectBlockNodeSuite.java` | ❌ NO         | Uses node death operations     |

**Conversion Summary:** ~1 file (20%) potentially convertible

---

### 9. SIMPLE_FEES Tests (~5 files)

**Current mode:** Subprocess (`hapiTestSimpleFees`)

**Analysis:**

|                  File                  | Can Convert to Embedded? | Can Convert to Repeatable? |          Notes          |
|----------------------------------------|--------------------------|----------------------------|-------------------------|
| `TokenServiceSimpleFeesSuite.java`     | ✅ YES                    | ✅ YES                      | Fee calculation tests   |
| `CryptoSimpleFeesSuite.java`           | ✅ YES                    | ✅ YES                      | Fee calculation tests   |
| `ConsensusServiceSimpleFeesSuite.java` | ✅ YES                    | ✅ YES                      | Fee calculation tests   |
| `CryptoCreateSimpleFeesTest.java`      | ⚠️ MAYBE                 | ❌ NO                       | Uses `setNode()`, ECDSA |
| `TopicCreateSimpleFeesTest.java`       | ⚠️ MAYBE                 | ❌ NO                       | Uses `setNode()`        |

**Conversion Summary:**
- **Convertible to Embedded:** ~3 files (60%)
- **Convertible to Repeatable:** ~3 files (60%)

---

### 10. MISC Tests (~100+ files)

**Current mode:** Subprocess (`hapiTestMisc`)

This is the largest category. Key analysis:

**Schedule Tests (~10 files):**
- `ScheduleCreateTest`, `ScheduleSignTest`, `ScheduleExecutionTest`, `ScheduleRecordTest`
- **Convertible to Embedded:** ✅ YES (all)
- **Convertible to Repeatable:** ✅ YES (most) - Schedule tests benefit greatly from virtual time!

**File Tests (~4 files):**
- `FileUpdateSuite`, `FileAppendSuite` and Atomic variants
- **Convertible to Embedded:** ✅ YES
- **Convertible to Repeatable:** ✅ YES

**Throttling Tests (~5 files):**
- `ThrottleDefValidationSuite`, `PrivilegedOpsTest`, `GasLimitThrottlingSuite`
- **Convertible to Embedded:** ✅ YES
- **Convertible to Repeatable:** ⚠️ PARTIAL (throttle timing may differ)

**Fee Tests (~5 files):**
- `CryptoServiceFeesSuite`, `AtomicCryptoServiceFeesSuite`, `AllBaseOpFeesSuite`
- **Convertible to Embedded:** ✅ YES
- **Convertible to Repeatable:** ✅ YES

**HIP Tests (~30+ files):**
- Various HIP implementation tests
- **Convertible to Embedded:** ✅ YES (most)
- **Convertible to Repeatable:** ⚠️ PARTIAL (depends on ECDSA usage)

**Issue Regression Tests:**
- `IssueRegressionTests`, `Issue305Spec`, `Issue2319Spec`, etc.
- **Convertible to Embedded:** ⚠️ PARTIAL (some use `setNode()`, `inParallel`)
- **Convertible to Repeatable:** ⚠️ PARTIAL

---

## Tests That Can Be Converted (Summary List)

### High-Confidence Candidates for Embedded Mode

These tests have no blockers for embedded mode conversion:

```
# CRYPTO (25+ files)
CryptoUpdateSuite.java
CryptoDeleteSuite.java
CryptoApproveAllowanceSuite.java
CryptoDeleteAllowanceSuite.java
CryptoGetInfoRegression.java
CryptoRecordsSanityCheckSuite.java
CryptoGetRecordsRegression.java
TxnRecordRegression.java
TransferWithCustomFixedFees.java
TransferWithCustomFractionalFees.java
TransferWithCustomRoyaltyFees.java
MiscCryptoSuite.java

# TOKEN (35+ files)
TokenCreateSpecs.java
TokenUpdateSpecs.java
TokenDeleteSpecs.java
TokenAssociationSpecs.java
TokenManagementSpecs.java
TokenManagementSpecsStateful.java
TokenPauseSpecs.java
TokenMetadataSpecs.java
TokenFeeScheduleUpdateSpecs.java
TokenTotalSupplyAfterMintBurnWipeSuite.java
TokenUpdateNftsSuite.java
UniqueTokenManagementSpecs.java
Hip17UnhappyTokensSuite.java
Hip17UnhappyAccountsSuite.java
Hip540Suite.java
# Plus all Atomic* batch variants

# SMART_CONTRACT (40+ files)
ContractCreateSuite.java
ContractStateSuite.java
ContractUpdateSuite.java
ContractGetInfoSuite.java
SigningReqsSuite.java
HRCPrecompileSuite.java
SelfDestructSuite.java
ScheduleCallTest.java
ScheduleDeleteTest.java
HasScheduleCapacityTest.java
PrngPrecompileSuite.java
TokenInfoHTSSuite.java
TokenExpiryInfoSuite.java
RedirectPrecompileSuite.java
PauseUnpauseTokenAccountPrecompileSuite.java
WipeTokenAccountPrecompileSuite.java
GrantRevokeKycSuite.java
FreezeUnfreezeTokenPrecompileSuite.java
MiscTokenTest.java
UpdateTokenFeeScheduleTest.java

# SCHEDULE (10+ files)
ScheduleCreateTest.java
ScheduleSignTest.java
ScheduleExecutionTest.java
ScheduleRecordTest.java
StatefulScheduleExecutionTest.java
ScheduleLongTermExecutionTest.java
DisabledLongTermExecutionScheduleTest.java
AllLeakyTestsWithLongTermFlagEnabledTest.java

# FILE (4 files)
FileUpdateSuite.java
FileAppendSuite.java
AtomicFileUpdateSuite.java
AtomicFileAppendSuite.java

# FEES (5+ files)
CryptoServiceFeesSuite.java
AtomicCryptoServiceFeesSuite.java
AllBaseOpFeesSuite.java
TokenServiceFeesSuite.java
AtomicTokenServiceFeesSuite.java
SmartContractServiceFeesTest.java
AtomicSmartContractServiceFeesTest.java
```

### High-Confidence Candidates for Repeatable Mode

These tests would benefit from virtual time AND have no ECDSA/parallelism blockers:

```
# Tests with sleeps that would become instant
ScheduleLongTermExecutionTest.java      # Schedule expiry tests
ScheduleExecutionTest.java              # Schedule execution timing
StatefulScheduleExecutionTest.java      # Stateful schedule tests
DisabledLongTermExecutionScheduleTest.java

# Pure business logic tests
TokenCreateSpecs.java
TokenUpdateSpecs.java
TokenDeleteSpecs.java
TokenAssociationSpecs.java
TokenManagementSpecs.java
TokenPauseSpecs.java
TokenMetadataSpecs.java
TokenFeeScheduleUpdateSpecs.java
UniqueTokenManagementSpecs.java
CryptoUpdateSuite.java
CryptoDeleteSuite.java
CryptoApproveAllowanceSuite.java
CryptoDeleteAllowanceSuite.java
FileUpdateSuite.java
FileAppendSuite.java
ContractUpdateSuite.java
ContractGetInfoSuite.java
```

---

## Estimated Impact

|    Category     | Total Files | Embedded Convertible | Repeatable Convertible |
|-----------------|-------------|----------------------|------------------------|
| CRYPTO          | ~30         | 25 (83%)             | 12 (40%)               |
| TOKEN           | ~40         | 38 (95%)             | 35 (87%)               |
| SMART_CONTRACT  | ~80         | 70 (87%)             | 40 (50%)               |
| RESTART/UPGRADE | ~5          | 0 (0%)               | 0 (0%)                 |
| ND_RECONNECT    | ~3          | 0 (0%)               | 0 (0%)                 |
| ISS             | ~1          | 0 (0%)               | 0 (0%)                 |
| LONG_RUNNING    | ~7          | 7 (100%)             | 2 (28%)                |
| BLOCK_NODE      | ~5          | 1 (20%)              | 0 (0%)                 |
| SIMPLE_FEES     | ~5          | 3 (60%)              | 3 (60%)                |
| MISC            | ~100        | 80 (80%)             | 50 (50%)               |
| **TOTAL**       | **~280**    | **~224 (80%)**       | **~142 (51%)**         |

---

## Recommended Conversion Strategy

### Important: Tag-Level Considerations

Given the subprocess execution model (network per tag, validation at end), conversions should be planned at the **tag level** or create **new tags** for converted tests.

### Phase 1: Low-Risk, High-Value Conversions

Start with tests that:
1. Have no ECDSA usage
2. Don't use `setNode()` or `inParallel()`
3. Have sleeps that would benefit from virtual time
4. Are **self-contained** (create own entities, don't rely on cumulative state)
5. Are tagged with high execution frequency (MATS)

**Priority candidates (create new tags):**

|        New Tag        |       Tests to Move        |  Original Tag  |              Rationale               |
|-----------------------|----------------------------|----------------|--------------------------------------|
| `FEES_REPEATABLE`     | All fee validation suites  | Various        | Pure fee assertions, self-contained  |
| `SCHEDULE_REPEATABLE` | Schedule tests with sleeps | MISC           | Would save 31+ minutes of sleep time |
| `QUERIES_REPEATABLE`  | Contract query tests       | SMART_CONTRACT | Query-only, no state modification    |

### Phase 2: Embedded-Only Conversions

Tests that can convert to embedded but not repeatable:
- Tests using ECDSA keys
- Tests using `inParallel()` for load testing
- Tests that don't need timing optimization

**Note:** Embedded tests may still produce streams, consider validation needs.

### Phase 3: Careful Analysis Required

Tests needing individual analysis:
- Tests using `setNode()` - may be testing multi-node behavior
- Throttling tests - timing behavior may differ
- Leaky tests - may have subtle dependencies
- Tests whose output is critical to stream validation

### Phase 4: Not Recommended for Conversion

Keep in subprocess if:
- Tests are critical to end-of-tag stream/log validation
- Tests intentionally verify subprocess-specific behavior
- Tests have complex state dependencies with other tests in tag

---

## Implementation Checklist

When converting tests:

1. **Identify target tests** - Select self-contained tests with sleeps
2. **Create new tag** - e.g., `FEES_REPEATABLE`
3. **Update test annotations** - Change `@HapiTest` to `@RepeatableHapiTest`
4. **Add tag annotation** - `@Tag("FEES_REPEATABLE")`
5. **Update Gradle tasks** - Create new task like `hapiRepeatableFees`
6. **Verify original tag validation** - Ensure remaining subprocess tests still pass validation
7. **Consider new validation** - Add lightweight validation for converted group if needed
8. **Measure improvement** - Track execution time savings

---

## Related Documentation

- `SUBPROCESS_TESTS_WITH_SLEEPS.md` - Detailed list of tests with sleep calls and conversion targets
- `XTS_TEST_GROUPS_OVERVIEW.md` - Overview of all XTS test groups and tags
- `TESTS_WITH_SLEEPS_ANALYSIS.md` - Analysis of sleep patterns and optimization strategies
