// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asScheduleId;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.queries.schedule.HapiGetScheduleInfo;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.contract.precompile.schedule.ContractSignScheduleTest.SignScheduleFromEOATest;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Tests success scenarios of the HRC-1215 functions when enabled
 * {@code contracts.systemContract.scheduleService.scheduleCall.enabled} feature flag. This tests checks just a happy
 * path because more detailed tests with be added to
 * <a href="https://github.com/hashgraph/hedera-evm-testing">hedera-evm-testing</a> repo
 */
@TestMethodOrder(OrderAnnotation.class)
@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class ScheduleCallTest {

    @Contract(contract = "HIP1215Contract", creationGas = 4_000_000L, isImmutable = true)
    static SpecContract contract;

    @Account(tinybarBalance = HapiSuite.ONE_HUNDRED_HBARS)
    static SpecAccount sender;

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(overriding("contracts.systemContract.scheduleService.scheduleCall.enabled", "true"));
    }

    // default 'feeSchedules.json' do not contain HederaFunctionality.SCHEDULE_CREATE,
    // fee data for SubType.SCHEDULE_CREATE_CONTRACT_CALL
    // that is why we are reuploading 'scheduled-contract-fees.json' in tests
    @LeakyRepeatableHapiTest(value = NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, fees = "scheduled-contract-fees.json")
    @DisplayName("scheduleCall(address,uint256,uint256,uint64,bytes)")
    public Stream<DynamicTest> scheduledCallTest() {
        // contract is a default sender/payer for scheduleCall
        return hapiTest(withOpContext(
                scheduledCallTest(2_600_000, new AtomicReference<>(), "scheduleCallExample", BigInteger.valueOf(40))));
    }

    // default 'feeSchedules.json' do not contain HederaFunctionality.SCHEDULE_CREATE,
    // fee data for SubType.SCHEDULE_CREATE_CONTRACT_CALL
    // that is why we are reuploading 'scheduled-contract-fees.json' in tests
    @LeakyRepeatableHapiTest(value = NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, fees = "scheduled-contract-fees.json")
    @DisplayName("scheduleCallWithSender(address,address,uint256,uint256,uint64,bytes)")
    public Stream<DynamicTest> scheduleCallWithSenderTest() {
        AtomicReference<String> scheduleIdHolder = new AtomicReference<>();
        return hapiTest(withOpContext(scheduledCallWithSignTest(
                2_800_000,
                scheduleIdHolder,
                false,
                sender.name(),
                "scheduleCallWithSenderExample",
                sender,
                BigInteger.valueOf(41))));
    }

    // default 'feeSchedules.json' do not contain HederaFunctionality.SCHEDULE_CREATE,
    // fee data for SubType.SCHEDULE_CREATE_CONTRACT_CALL
    // that is why we are reuploading 'scheduled-contract-fees.json' in tests
    @LeakyRepeatableHapiTest(value = NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, fees = "scheduled-contract-fees.json")
    @DisplayName("executeCallOnSenderSignature(address,address,uint256,uint256,uint64,bytes)")
    public Stream<DynamicTest> executeCallOnSenderSignatureTest() {
        AtomicReference<String> scheduleIdHolder = new AtomicReference<>();
        return hapiTest(withOpContext(scheduledCallWithSignTest(
                3_000_000,
                scheduleIdHolder,
                true,
                sender.name(),
                "executeCallOnSenderSignatureExample",
                sender,
                BigInteger.valueOf(42))));
    }

    private CustomSpecAssert.ThrowingConsumer scheduledCallTest(
            final long gas,
            @NonNull final AtomicReference<String> scheduleIdHolder,
            @NonNull final String functionName,
            @NonNull final Object... parameters) {
        return (spec, opLog) -> {
            // run schedule call
            final var scheduleAddress = new AtomicReference<Address>();
            allRunFor(
                    spec,
                    contract.call(functionName, parameters)
                            .gas(gas)
                            .via(functionName) // TODO add function name to tx name to identify tx
                            .exposingResultTo(res -> scheduleAddress.set((Address) res[1]))
                            .andAssert(txn -> txn.hasResults(ContractFnResultAsserts.resultWith()
                                    .resultThruAbi(
                                            getABIFor(FUNCTION, functionName, contract.name()), ignore -> res -> {
                                                Assertions.assertEquals(2, res.length);
                                                Assertions.assertEquals(
                                                        (long) ResponseCodeEnum.SUCCESS.getNumber(), res[0]);
                                                Assertions.assertInstanceOf(Address.class, res[1]);
                                                return Optional.empty();
                                            })))
                            .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS)));
            // check schedule exists
            final var scheduleId = asScheduleId(spec, scheduleAddress.get());
            final var scheduleIdString = String.valueOf(scheduleId.getScheduleNum());
            scheduleIdHolder.set(scheduleIdString);
            allRunFor(
                    spec,
                    getScheduleInfo(scheduleIdString)
                            .hasScheduleId(scheduleIdString)
                            .isNotExecuted()
                            .isNotDeleted());
        };
    }

    private CustomSpecAssert.ThrowingConsumer scheduledCallWithSignTest(
            final long gas,
            @NonNull final AtomicReference<String> scheduleIdHolder,
            final boolean executedAfterSigning,
            @NonNull final String payer,
            @NonNull final String functionName,
            @NonNull final Object... parameters) {
        return (spec, opLog) -> {
            scheduledCallTest(gas, scheduleIdHolder, functionName, parameters).assertFor(spec, opLog);
            HapiGetScheduleInfo info = getScheduleInfo(scheduleIdHolder.get())
                    .hasScheduleId(scheduleIdHolder.get())
                    .isNotDeleted();
            if (executedAfterSigning) {
                // check if the schedule was executed after signing
                info.isExecuted();
            } else {
                // check if the schedule was NOT executed after signing
                info.isNotExecuted();
            }
            allRunFor(
                    spec,
                    // sign schedule
                    contractCallWithFunctionAbi(
                            scheduleIdHolder.get(),
                            getABIFor(
                                    FUNCTION,
                                    SignScheduleFromEOATest.SIGN_SCHEDULE,
                                    SignScheduleFromEOATest.IHRC755))
                            .payingWith(payer)
                            .gas(1_000_000),
                    info);
        };
    }
}
