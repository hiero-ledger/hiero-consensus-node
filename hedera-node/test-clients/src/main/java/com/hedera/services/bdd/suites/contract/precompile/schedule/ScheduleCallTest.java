// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asScheduleId;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.RepeatableReason;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.queries.schedule.HapiGetScheduleInfo;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
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
public class ScheduleCallTest {

    @Contract(contract = "HIP1215Contract", creationGas = 4_000_000L, isImmutable = true)
    static SpecContract contract;

    @Account(tinybarBalance = HapiSuite.ONE_HUNDRED_HBARS)
    static SpecAccount sender;

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                UtilVerbs.overriding("contracts.systemContract.scheduleService.scheduleCall.enabled", "true"));
    }

    @AfterAll
    public static void shutdown(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(UtilVerbs.restoreDefault("contracts.systemContract.scheduleService.scheduleCall.enabled"));
    }

    // default 'feeSchedules.json' do not contain HederaFunctionality.SCHEDULE_CREATE,
    // fee data for SubType.SCHEDULE_CREATE_CONTRACT_CALL
    // that is why we are reuploading 'scheduled-contract-fees.json' in tests
    @LeakyHapiTest(fees = "scheduled-contract-fees.json")
    @DisplayName("call scheduleCall(address,uint256,uint256,uint64,bytes) success")
    public Stream<DynamicTest> scheduledCallTest() {
        // contract is a default sender/payer for scheduleCall
        return hapiTest(UtilVerbs.withOpContext(
                scheduledCallTest(new AtomicReference<>(), "scheduleCallExample", BigInteger.valueOf(40))));
    }

    // LeakyRepeatableHapiTest: we should use Repeatable test for single threaded processing. In other case test fails
    // with
    // 'StreamValidationTest' 'expected from generated but did not find in translated [contractID]'

    // fees: default 'feeSchedules.json' do not contain HederaFunctionality.SCHEDULE_CREATE,
    // fee data for SubType.SCHEDULE_CREATE_CONTRACT_CALL
    // that is why we are reuploading 'scheduled-contract-fees.json' in tests
    @LeakyRepeatableHapiTest(
            value = RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW,
            fees = "scheduled-contract-fees.json")
    @DisplayName("call scheduleCallWithSender(address,address,uint256,uint256,uint64,bytes) success")
    public Stream<DynamicTest> scheduleCallWithSenderTest() {
        return hapiTest(UtilVerbs.withOpContext(scheduledCallWithSignTest(
                false, sender.name(), "scheduleCallWithSenderExample", sender, BigInteger.valueOf(41))));
    }

    // default 'feeSchedules.json' do not contain HederaFunctionality.SCHEDULE_CREATE,
    // fee data for SubType.SCHEDULE_CREATE_CONTRACT_CALL
    // that is why we are reuploading 'scheduled-contract-fees.json' in tests
    @LeakyRepeatableHapiTest(
            value = RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW,
            fees = "scheduled-contract-fees.json")
    @DisplayName("call executeCallOnSenderSignature(address,address,uint256,uint256,uint64,bytes) success")
    public Stream<DynamicTest> executeCallOnSenderSignatureTest() {
        return hapiTest(UtilVerbs.withOpContext(scheduledCallWithSignTest(
                true, sender.name(), "executeCallOnSenderSignatureExample", sender, BigInteger.valueOf(42))));
    }

    private CustomSpecAssert.ThrowingConsumer scheduledCallTest(
            @NonNull final AtomicReference<String> scheduleIdHolder,
            @NonNull final String functionName,
            @NonNull final Object... parameters) {
        return (spec, opLog) -> {
            // run schedule call
            AtomicReference<Address> scheduleAddressHolder = new AtomicReference<>();
            allRunFor(
                    spec,
                    contract.call(functionName, parameters)
                            .gas(2_000_000)
                            .exposingResultTo(res -> scheduleAddressHolder.set((Address) res[1]))
                            .andAssert(txn -> txn.hasResults(
                                    ContractFnResultAsserts.resultWith()
                                            .resultThruAbi(
                                                    getABIFor(FUNCTION, functionName, contract.name()),
                                                    ignore -> res -> {
                                                        Assertions.assertEquals(2, res.length);
                                                        Assertions.assertEquals(
                                                                (long) ResponseCodeEnum.SUCCESS.getNumber(), res[0]);
                                                        Assertions.assertInstanceOf(Address.class, res[1]);
                                                        return Optional.empty();
                                                    }),
                                    // for child record asserting, because executeCall* creating child schedule
                                    // transaction
                                    ContractFnResultAsserts.anyResult()))
                            .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS)));
            // check schedule exists
            final var scheduleId = asScheduleId(spec, scheduleAddressHolder.get());
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
            final boolean executedAfterSigning,
            @NonNull final String payer,
            @NonNull final String functionName,
            @NonNull final Object... parameters) {
        return (spec, opLog) -> {
            AtomicReference<String> scheduleIdHolder = new AtomicReference<>();
            scheduledCallTest(scheduleIdHolder, functionName, parameters).assertFor(spec, opLog);
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
                    scheduleSign(scheduleIdHolder.get()).alsoSigningWith(payer),
                    info);
        };
    }
}
