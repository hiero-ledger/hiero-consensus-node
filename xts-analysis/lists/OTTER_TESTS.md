# XTS Otter Tests - Test Files

**Module:** `platform-sdk/consensus-otter-tests`
**Gradle Tasks:** `testOtter`, `testContainer`, `testTurtle`, `testChaos`

## Otter Tests (14 files)

### testOtter (platform integration tests)

1. `platform-sdk/consensus-otter-tests/src/testOtter/java/org/hiero/otter/test/CheckingRecoveryTest.java`
2. `platform-sdk/consensus-otter-tests/src/testOtter/java/org/hiero/otter/test/DocExamplesTest.java`
3. `platform-sdk/consensus-otter-tests/src/testOtter/java/org/hiero/otter/test/FreezeTest.java`
4. `platform-sdk/consensus-otter-tests/src/testOtter/java/org/hiero/otter/test/HappyPathTest.java`
5. `platform-sdk/consensus-otter-tests/src/testOtter/java/org/hiero/otter/test/IssTest.java`
6. `platform-sdk/consensus-otter-tests/src/testOtter/java/org/hiero/otter/test/LargeStateTests.java`
7. `platform-sdk/consensus-otter-tests/src/testOtter/java/org/hiero/otter/test/PartitionTest.java`
8. `platform-sdk/consensus-otter-tests/src/testOtter/java/org/hiero/otter/test/QuiescenceTest.java`
9. `platform-sdk/consensus-otter-tests/src/testOtter/java/org/hiero/otter/test/ReconnectTest.java`
10. `platform-sdk/consensus-otter-tests/src/testOtter/java/org/hiero/otter/test/RestartTest.java`
11. `platform-sdk/consensus-otter-tests/src/testOtter/java/org/hiero/otter/test/RpcDisconnectionStressTest.java`
12. `platform-sdk/consensus-otter-tests/src/testOtter/java/org/hiero/otter/test/SigningSchemaTest.java`
13. `platform-sdk/consensus-otter-tests/src/testOtter/java/org/hiero/otter/test/SingleNodeNetworkTest.java`
14. `platform-sdk/consensus-otter-tests/src/testOtter/java/org/hiero/otter/test/StartFromStateTest.java`

## Chaos Tests (1 file)

### testChaos (chaos engineering tests)

1. `platform-sdk/consensus-otter-tests/src/testChaos/java/org/hiero/otter/chaos/ChaosTest.java`

## Test Fixtures (100+ files)

The test fixtures provide the infrastructure for Otter tests:
- Container environment support
- Turtle environment support
- Network simulation
- Node management
- Assertion helpers

## Test Environments

1. **Turtle Environment** - In-process simulated network
2. **Container Environment** - Docker container-based network

## Notes

- Otter tests run on dedicated runners (`hiero-cn-otter-linux`)
- They require Docker and depend on the `consensus-otter-docker-app` build
- Chaos tests only run during dry runs (contains `'Dry Run'` in job label)
