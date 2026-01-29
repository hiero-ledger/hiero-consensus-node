# Other HAPI Tests - Test Files

## HAPI Tests (Long Running)

Tag: `@Tag(LONG_RUNNING)`

### Test Files (7 files)

1. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/throttling/SteadyStateThrottlingTest.java`
2. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/contract/precompile/ERCPrecompileSuite.java`
3. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/contract/precompile/CryptoTransferHTSSuite.java`
4. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/contract/openzeppelin/ERC20ContractInteractions.java`
5. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/contract/ethereum/EthereumSuite.java`
6. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/staking/StakingSuite.java`
7. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/contract/precompile/ContractKeysHTSSuite.java`

---

## HAPI Tests (Restart/Upgrade)

Tag: `@Tag(RESTART)` or `@Tag(UPGRADE)`

### Test Files (3 files)

1. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/regression/system/QuiesceThenMixedOpsRestartTest.java`
2. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/regression/system/BesuNativeLibVerificationTest.java`
3. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/regression/system/DabEnabledUpgradeTest.java`

---

## HAPI Tests (ISS)

Tag: `@Tag(ISS)`

### Test Files (1 file)

1. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/misc/IssHandlingTest.java`

---

## HAPI Tests (ND Reconnect)

Tag: `@Tag(ND_RECONNECT)`

### Test Files (1 file)

1. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/regression/system/MixedOpsNodeDeathReconnectTest.java`

---

## HAPI Tests (Block Node)

Tag: `@Tag(BLOCK_NODE)`

### Test Files (4 files)

1. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/blocknode/BlockNodeSuite.java`
2. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/blocknode/BlockNodeBackPressureSuite.java`
3. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/blocknode/BlockNodeSoftwareUpgradeSuite.java`
4. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/blocknode/NodeDeathReconnectBlockNodeSuite.java`

---

## HAPI Tests (Simple Fees)

Tag: `@Tag(SIMPLE_FEES)`

### Test Files (12 files)

1. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/hip1261/TopicCreateSimpleFeesTest.java`
2. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/hip1261/CryptoCreateSimpleFeesTest.java`
3. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/fees/UtilServiceSimpleFeesTest.java`
4. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/fees/NetworkServiceSimpleFeesTest.java`
5. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/fees/TokenServiceSimpleFeesSuite.java`
6. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/fees/CryptoTransferSimpleFeesSuite.java`
7. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/fees/AirdropSimpleFeesTest.java`
8. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/fees/CryptoQuerySimpleFeesSuite.java`
9. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/fees/ScheduleServiceSimpleFeesTest.java`
10. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/fees/FileServiceSimpleFeesTest.java`
11. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/fees/ConsensusServiceSimpleFeesSuite.java`
12. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/fees/CryptoSimpleFeesSuite.java`

---

## Integration Tests (Embedded)

Tag: `@Tag(INTEGRATION)`

### Test Files (19 files)

1. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/integration/hip1195/Hip1195StorageTest.java`
2. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/integration/hip1195/Hip1195EnabledTest.java`
3. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/integration/hip1195/Hip1195BasicTests.java`
4. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/integration/RepeatableEvmHookStoreTests.java`
5. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/integration/hip1259/Hip1259EnabledTests.java`
6. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/integration/hip1259/Hip1259DisabledTests.java`
7. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/integration/RepeatableHip1064TestsDisabled.java`
8. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/integration/RepeatableHip1064Tests.java`
9. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/integration/AdditionalHip1064Tests.java`
10. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/integration/ConcurrentIntegrationTests.java`
11. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/integration/RepeatableTssTests.java`
12. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/integration/RepeatableIntegrationTests.java`
13. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/integration/RepeatableHip423Tests.java`
14. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/integration/RepeatableHip1215Tests.java`
15. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/hip551/AtomicBatchRepeatableTest.java`
16. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/hip551/AtomicBatchIntegrationTest.java`
17. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/integration/RepeatableScheduleLongTermSignTest.java`
18. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/integration/RepeatableScheduleLongTermExecutionTest.java`
19. `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/integration/CongestionPricingTest.java`
