// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asScheduleId;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
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

    @Contract(contract = "HIP1215Contract", creationGas = 4_000_000L, isImmutable = true)
    static SpecContract contract;

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                overriding("contracts.systemContract.scheduleService.scheduleCall.enabled", "true"));
    }

    @HapiTest
    @DisplayName("hasScheduleCapacity(uint256,uint256)")
    public Stream<DynamicTest> hasScheduleCapacityTest() {
        return hapiTest(contract.call("hasScheduleCapacityExample", BigInteger.valueOf(30))
                .gas(100_000)
                .andAssert(txn -> txn.hasResults(
                        ContractFnResultAsserts.resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, "hasScheduleCapacityExample", contract.name()),
                                        ContractFnResultAsserts.isLiteralResult(new Object[] {true})),
                        // for child record asserting, because hasScheduleCapacity is a view function
                        ContractFnResultAsserts.anyResult()))
                .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS)));
    }

    // default 'feeSchedules.json' do not contain HederaFunctionality.SCHEDULE_CREATE,
    // fee data for SubType.SCHEDULE_CREATE_CONTRACT_CALL
    // that is why we are reuploading 'scheduled-contract-fees.json' in tests
    @LeakyRepeatableHapiTest(value = NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, fees = "scheduled-contract-fees.json")
    @DisplayName("hasScheduleCapacity -> scheduleCall -> deleteSchedule")
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
