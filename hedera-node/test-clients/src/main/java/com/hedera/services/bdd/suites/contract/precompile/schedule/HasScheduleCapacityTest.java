// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asScheduleId;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.RepeatableReason;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.operations.transactions.CallContractOperation;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Tests success scenarios of the HRC-1215 functions when enabled
 * {@code contracts.systemContract.scheduleService.scheduleCall.enabled} feature flag. This tests checks just a happy
 * path because more detailed tests with be added to
 * <a href="https://github.com/hashgraph/hedera-evm-testing">hedera-evm-testing</a> repo
 */
@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class HasScheduleCapacityTest {

    private static final AtomicInteger EXPIRY_SHIFT = new AtomicInteger(30);
    private static final BigInteger VALUE_MORE_THAN_LONG = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN);

    @Contract(contract = "HIP1215Contract", creationGas = 4_000_000L, isImmutable = true)
    static SpecContract contract;

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                UtilVerbs.overriding("contracts.systemContract.scheduleService.scheduleCall.enabled", "true"));
    }

    @AfterAll
    public static void shutdown(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(UtilVerbs.restoreDefault("contracts.systemContract.scheduleService.scheduleCall.enabled"));
    }

    private CallContractOperation hasScheduleCapacity(final boolean result, @NonNull final String function, @NonNull final Object... parameters) {
        return contract.call(function, parameters)
                .gas(100_000)
                .andAssert(txn -> txn.hasResults(ContractFnResultAsserts.resultWith()
                        .resultThruAbi(
                                getABIFor(FUNCTION, function, contract.name()),
                                ContractFnResultAsserts.isLiteralResult(new Object[]{result}))))
                .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS));
    }

    @HapiTest
    @DisplayName("call hasScheduleCapacity(uint256,uint256) success return true")
    public Stream<DynamicTest> hasScheduleCapacityTest() {
        return hapiTest(hasScheduleCapacity(true, "hasScheduleCapacityExample", BigInteger.valueOf(EXPIRY_SHIFT.getAndIncrement())));
    }

    @HapiTest
    @DisplayName("call hasScheduleCapacity(uint256,uint256) success return false by no capacity")
    public Stream<DynamicTest> hasScheduleCapacityOverflowTest() {
        BigInteger expirySecond = BigInteger.valueOf((System.currentTimeMillis() / 1000) + EXPIRY_SHIFT.getAndIncrement());
        BigInteger testGasLimit = BigInteger.valueOf(2_000_000);
        BigInteger closeToMaxGasLimit = BigInteger.valueOf(1_499_000_000);
        return hapiTest(
                hasScheduleCapacity(true, "hasScheduleCapacityProxy", expirySecond, testGasLimit),
                contract.call("scheduleCallWithDefaultCallData", expirySecond, closeToMaxGasLimit)
                        .gas(2_000_000)
                        .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS)),
                hasScheduleCapacity(false, "hasScheduleCapacityProxy", expirySecond, testGasLimit)
        );
    }

    @HapiTest
    @DisplayName("call hasScheduleCapacity(uint256,uint256) success return false by 0 expiry")
    public Stream<DynamicTest> hasScheduleCapacity0ExpiryTest() {
        return hapiTest(
                hasScheduleCapacity(false, "hasScheduleCapacityProxy", BigInteger.ZERO, BigInteger.valueOf(2_000_000))
        );
    }

    @HapiTest
    @DisplayName("call hasScheduleCapacity(uint256,uint256) success return false by huge expiry")
    public Stream<DynamicTest> hasScheduleCapacityHugeExpiryTest() {
        return hapiTest(
                hasScheduleCapacity(false, "hasScheduleCapacityProxy", VALUE_MORE_THAN_LONG, BigInteger.valueOf(2_000_000))
        );
    }

    @HapiTest
    @DisplayName("call hasScheduleCapacity(uint256,uint256) success return false by huge gasLimit")
    public Stream<DynamicTest> hasScheduleCapacityHugeGasLimitTest() {
        BigInteger expirySecond = BigInteger.valueOf((System.currentTimeMillis() / 1000) + EXPIRY_SHIFT.getAndIncrement());
        return hapiTest(
                hasScheduleCapacity(false, "hasScheduleCapacityProxy", expirySecond, VALUE_MORE_THAN_LONG)
        );
    }

    @HapiTest
    @DisplayName("call hasScheduleCapacity(uint256,uint256) success return false by max+1 gasLimit")
    public Stream<DynamicTest> hasScheduleCapacityMaxGasLimitTest() {
        BigInteger expirySecond = BigInteger.valueOf((System.currentTimeMillis() / 1000) + EXPIRY_SHIFT.getAndIncrement());
        return hapiTest(
                // limit is controlled by 'contracts.maxGasPerSecBackend' property
                UtilVerbs.overriding("contracts.maxGasPerSecBackend", "15000000"),
                hasScheduleCapacity(false, "hasScheduleCapacityProxy", expirySecond, BigInteger.valueOf(15_000_001)),
                UtilVerbs.restoreDefault("contracts.maxGasPerSecBackend"),
                hasScheduleCapacity(true, "hasScheduleCapacityProxy", expirySecond, BigInteger.valueOf(15_000_001))
        );
    }

    // RepeatableHapiTest: we should use Repeatable test for single threaded processing. In other case test fails with
    // 'StreamValidationTest' 'expected from generated but did not find in translated [scheduleID]'
    @RepeatableHapiTest(value = RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    @DisplayName("call hasScheduleCapacity -> scheduleCall -> deleteSchedule -> success")
    public Stream<DynamicTest> scheduleCallWithCapacityCheckAndDeleteTest() {
        return hapiTest(withOpContext((spec, opLog) -> {
            // create schedule
            final var scheduleAddress = new AtomicReference<Address>();
            allRunFor(
                    spec,
                    contract.call("scheduleCallWithCapacityCheckAndDeleteExample", BigInteger.valueOf(31))
                            .gas(2_000_000L)
                            .exposingResultTo(res -> scheduleAddress.set((Address) res[1]))
                            .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS)));
            final var scheduleId = asScheduleId(spec, scheduleAddress.get());
            final var scheduleIdString = String.valueOf(scheduleId.getScheduleNum());
            allRunFor(
                    spec,
                    // check schedule deleted
                    getScheduleInfo(scheduleIdString)
                            .hasScheduleId(scheduleIdString)
                            .isDeleted());
        }));
    }
}
