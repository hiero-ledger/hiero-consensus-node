// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.*;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asScheduleId;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.RepeatableReason;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.operations.transactions.CallContractOperation;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@OrderedInIsolation
@HapiTestLifecycle
public class HasScheduleCapacitySerialTest {
    private static final AtomicInteger EXPIRY_SHIFT = new AtomicInteger(120);
    private static final String FUNCTION_NAME = "hasScheduleCapacityProxy";
    private static final String CAPACITY_CONFIG_NAME = "contracts.maxGasPerSecBackend";
    private static final String THROTTLE_BY_GAS_CONFIG_NAME = "contracts.throttle.throttleByGas";

    @Contract(contract = "HIP1215Contract", creationGas = 4_000_000L, isImmutable = true)
    static SpecContract contract;

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(overriding("contracts.systemContract.scheduleService.scheduleCall.enabled", "true"));
    }

    @AfterAll
    public static void shutdown(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(restoreDefault("contracts.systemContract.scheduleService.scheduleCall.enabled"));
    }

    private CallContractOperation hasScheduleCapacity(
            final boolean result, @NonNull final String function, @NonNull final Object... parameters) {
        return contract.call(function, parameters)
                .gas(100_000)
                .andAssert(txn -> txn.hasResults(ContractFnResultAsserts.resultWith()
                        .resultThruAbi(
                                getABIFor(FUNCTION, function, contract.name()),
                                ContractFnResultAsserts.isLiteralResult(new Object[] {result}))))
                .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS));
    }

    // default 'feeSchedules.json' do not contain HederaFunctionality.SCHEDULE_CREATE,
    // fee data for SubType.SCHEDULE_CREATE_CONTRACT_CALL
    // that is why we are reuploading 'scheduled-contract-fees.json' in tests

    // execute separately from other tests because it is changes 'contracts.maxGasPerSecBackend' config
    @LeakyHapiTest(
            overrides = {CAPACITY_CONFIG_NAME},
            fees = "scheduled-contract-fees.json")
    @DisplayName("call hasScheduleCapacity(uint256,uint256) success return false by no capacity")
    public Stream<DynamicTest> hasScheduleCapacityOverflowTest() {
        final BigInteger expirySecond =
                BigInteger.valueOf(System.currentTimeMillis() / 1000 + EXPIRY_SHIFT.getAndIncrement());
        final BigInteger testGasLimit = BigInteger.valueOf(2_000_000);
        final BigInteger closeToMaxGasLimit = BigInteger.valueOf(14_000_000);
        return hapiTest(
                overriding(CAPACITY_CONFIG_NAME, "15000000"),
                overriding(THROTTLE_BY_GAS_CONFIG_NAME, "false"), // It should work even if gas throttle is off
                hasScheduleCapacity(true, FUNCTION_NAME, expirySecond, testGasLimit),
                contract.call("scheduleCallWithDefaultCallData", expirySecond, closeToMaxGasLimit)
                        .gas(2_000_000)
                        // parent success and child success
                        .andAssert(txn -> txn.hasKnownStatuses(ResponseCodeEnum.SUCCESS, ResponseCodeEnum.SUCCESS)),
                hasScheduleCapacity(false, FUNCTION_NAME, expirySecond, testGasLimit),
                restoreDefault(THROTTLE_BY_GAS_CONFIG_NAME),
                restoreDefault(CAPACITY_CONFIG_NAME));
    }

    // execute separately from other tests because it is changes 'contracts.maxGasPerSecBackend' config
    @LeakyHapiTest(overrides = {CAPACITY_CONFIG_NAME})
    @DisplayName("call hasScheduleCapacity(uint256,uint256) success return false by max+1 gasLimit")
    public Stream<DynamicTest> hasScheduleCapacityMaxGasLimitTest() {
        final BigInteger expirySecond =
                BigInteger.valueOf(System.currentTimeMillis() / 1000 + EXPIRY_SHIFT.getAndIncrement());
        return hapiTest(
                // limit is controlled by 'contracts.maxGasPerSecBackend' property
                overriding(CAPACITY_CONFIG_NAME, "15000000"),
                overriding(THROTTLE_BY_GAS_CONFIG_NAME, "false"), // It should work even if gas throttle is off
                hasScheduleCapacity(false, FUNCTION_NAME, expirySecond, BigInteger.valueOf(15_000_001)),
                overriding(CAPACITY_CONFIG_NAME, "30000000"),
                hasScheduleCapacity(true, FUNCTION_NAME, expirySecond, BigInteger.valueOf(15_000_001)),
                restoreDefault(THROTTLE_BY_GAS_CONFIG_NAME),
                restoreDefault(CAPACITY_CONFIG_NAME));
    }

    // LeakyRepeatableHapiTest: we should use Repeatable test for single threaded processing. In other case test fails
    // with 'StreamValidationTest' 'expected from generated but did not find in translated [scheduleID]'

    // fees: default 'feeSchedules.json' do not contain HederaFunctionality.SCHEDULE_CREATE,
    // fee data for SubType.SCHEDULE_CREATE_CONTRACT_CALL
    // that is why we are reuploading 'scheduled-contract-fees.json' in tests
    @LeakyRepeatableHapiTest(
            value = RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW,
            fees = "scheduled-contract-fees.json")
    @DisplayName("call hasScheduleCapacity -> scheduleCall -> deleteSchedule -> success")
    public Stream<DynamicTest> scheduleCallWithCapacityCheckAndDeleteTest() {
        return hapiTest(withOpContext((spec, opLog) -> {
            // create schedule
            final var scheduleAddress = new AtomicReference<Address>();
            allRunFor(
                    spec,
                    // check schedule deleted
                    contract.call("scheduleCallWithCapacityCheckAndDeleteExample", BigInteger.valueOf(31))
                            .gas(2_000_000L)
                            .exposingResultTo(res -> scheduleAddress.set((Address) res[1]))
                            .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS)));
            final var scheduleId = asScheduleId(spec, scheduleAddress.get());
            final var scheduleIdString = String.valueOf(scheduleId.getScheduleNum());
            allRunFor(
                    spec,
                    getScheduleInfo(scheduleIdString)
                            .hasScheduleId(scheduleIdString)
                            .isDeleted());
        }));
    }
}
