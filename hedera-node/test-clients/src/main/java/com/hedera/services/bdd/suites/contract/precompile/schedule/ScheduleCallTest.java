// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.junit.TestTags.NOT_REPEATABLE;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asScheduleId;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.hedera.services.bdd.spec.dsl.operations.transactions.CallContractOperation;
import com.hedera.services.bdd.spec.queries.schedule.HapiGetScheduleInfo;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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

    private static final AtomicInteger EXPIRY_SHIFT = new AtomicInteger(40);
    private static final String MAX_PRECEDING_RECORDS = "consensus.handle.maxPrecedingRecords";
    private static final String MAX_FOLLOWING_RECORDS = "consensus.handle.maxFollowingRecords";
    private static final BigInteger VALUE_MORE_THAN_LONG =
            BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN);

    @Contract(contract = "HIP1215Contract", creationGas = 4_000_000L, isImmutable = true)
    static SpecContract contract;

    @Account(tinybarBalance = HapiSuite.ONE_HUNDRED_HBARS)
    static SpecAccount payer;

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                UtilVerbs.overriding("contracts.systemContract.scheduleService.scheduleCall.enabled", "true"));
    }

    @AfterAll
    public static void shutdown(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(UtilVerbs.restoreDefault("contracts.systemContract.scheduleService.scheduleCall.enabled"));
    }

    @LeakyHapiTest
    @DisplayName("call scheduleCall(address,uint256,uint256,uint64,bytes) success")
    public Stream<DynamicTest> scheduledCallTest() {
        // contract is a default sender/payer for scheduleCall
        return hapiTest(withOpContext(scheduledCallTest(
                new AtomicReference<>(), "scheduleCallExample", BigInteger.valueOf(EXPIRY_SHIFT.incrementAndGet()))));
    }

    @LeakyHapiTest
    @DisplayName("call scheduleCall(address,uint256,uint256,uint64,bytes) fail by 0 expiry")
    public Stream<DynamicTest> scheduledCall0ExpiryTest() {
        // contract is a default sender/payer for scheduleCall
        return hapiTest(scheduledCall(
                null,
                ResponseCodeEnum.SCHEDULE_EXPIRATION_TIME_MUST_BE_HIGHER_THAN_CONSENSUS_TIME,
                "scheduleCallWithDefaultCallData",
                BigInteger.ZERO,
                BigInteger.valueOf(2_000_000)));
    }

    @LeakyHapiTest
    @DisplayName("call scheduleCall(address,uint256,uint256,uint64,bytes) fail by huge expiry")
    public Stream<DynamicTest> scheduledCallHugeExpiryTest() {
        // contract is a default sender/payer for scheduleCall
        return hapiTest(scheduledCall(
                null,
                ResponseCodeEnum.SCHEDULE_EXPIRATION_TIME_TOO_FAR_IN_FUTURE,
                "scheduleCallWithDefaultCallData",
                VALUE_MORE_THAN_LONG,
                BigInteger.valueOf(2_000_000)));
    }

    // NOT_REPEATABLE: the expiry below comes from wall-clock time, but repeatable mode runs on a virtual
    // consensus clock, so the schedule looks too far in the future instead of landing on a busy expiry second.
    @LeakyHapiTest
    @Tag(NOT_REPEATABLE)
    @DisplayName("call scheduleCall(address,uint256,uint256,uint64,bytes) fail by huge gasLimit")
    public Stream<DynamicTest> scheduledCallHugeGasLimitTest() {
        final BigInteger expirySecond =
                BigInteger.valueOf((System.currentTimeMillis() / 1000) + EXPIRY_SHIFT.getAndIncrement());
        // contract is a default sender/payer for scheduleCall
        return hapiTest(scheduledCall(
                null,
                ResponseCodeEnum.SCHEDULE_EXPIRY_IS_BUSY,
                "scheduleCallWithDefaultCallData",
                expirySecond,
                VALUE_MORE_THAN_LONG));
    }

    // LeakyRepeatableHapiTest: we should use Repeatable test for single threaded processing. In other case test fails
    // with 'StreamValidationTest' 'expected from generated but did not find in translated [contractID]'

    @LeakyRepeatableHapiTest(RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    @DisplayName("call scheduleCallWithPayer(address,address,uint256,uint256,uint64,bytes) success")
    public Stream<DynamicTest> scheduleCallWithPayerTest() {
        return hapiTest(withOpContext(scheduledCallWithSignTest(
                false,
                payer.name(),
                "scheduleCallWithPayerExample",
                payer,
                BigInteger.valueOf(EXPIRY_SHIFT.incrementAndGet()))));
    }

    // LeakyRepeatableHapiTest: we should use Repeatable test for single threaded processing. In other case test fails
    // with 'StreamValidationTest' 'expected from generated but did not find in translated [contractID]'

    @LeakyRepeatableHapiTest(RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    @DisplayName("call executeCallOnPayerSignature(address,address,uint256,uint256,uint64,bytes) success")
    public Stream<DynamicTest> executeCallOnPayerSignatureTest() {
        return hapiTest(withOpContext(scheduledCallWithSignTest(
                true,
                payer.name(),
                "executeCallOnPayerSignatureExample",
                payer,
                BigInteger.valueOf(EXPIRY_SHIFT.incrementAndGet()))));
    }

    /**
     * A top-level transaction hands out child transaction ids two different ways, and the two ranges must not
     * overlap. {@code SavepointStackImpl#nextPresetTxnId(boolean)} gives an HSS {@code scheduleCall} dispatch its
     * id up front as {@code baseNonce + numPresetIds * (maxPrecedingRecords + maxFollowingRecords)}, while
     * {@code SavepointStackImpl#buildHandleOutput} numbers every remaining PRECEDING/CHILD builder
     * {@code baseNonce + offset} for offsets {@code 1..(maxPrecedingRecords + maxFollowingRecords)}. The first
     * preset nonce therefore lands on the last sequentially assignable offset.
     * <p>
     * The child-record budget is shrunk here so a single {@code scheduleCall} plus a single {@code deleteSchedule}
     * reaches that boundary; with the production defaults (3 preceding, 50 following) it takes a contract that
     * saturates the whole budget to reach the same nonce 53.
     */
    @LeakyRepeatableHapiTest(
            value = RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW,
            overrides = {MAX_PRECEDING_RECORDS, MAX_FOLLOWING_RECORDS})
    @DisplayName("preset schedule txn id does not collide with a sequentially assigned child nonce")
    public Stream<DynamicTest> presetTxnIdDoesNotCollideWithSequentialChildNonce() {
        final AtomicReference<List<TransactionRecord>> allRecords = new AtomicReference<>();
        return hapiTest(
                // With 0 preceding and 2 following the stride is 2 and the sequential offsets are 1..2, so the
                // first preset nonce is 2 -- the same value the second sequentially numbered child receives.
                UtilVerbs.overriding(MAX_PRECEDING_RECORDS, "0"),
                UtilVerbs.overriding(MAX_FOLLOWING_RECORDS, "2"),
                // scheduleCall() consumes the preset id, then deleteSchedule() is numbered sequentially
                contract.call(
                                "scheduleCallWithCapacityCheckAndDeleteExample",
                                BigInteger.valueOf(EXPIRY_SHIFT.incrementAndGet()))
                        .gas(2_000_000L)
                        .via("scheduleCallThenDelete")
                        .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS)),
                getTxnRecord("scheduleCallThenDelete").andAllChildRecords().exposingAllTo(allRecords::set),
                withOpContext((_, _) -> {
                    final var repeated = allRecords.get().stream()
                            .map(TransactionRecord::getTransactionID)
                            .collect(groupingBy(identity(), LinkedHashMap::new, counting()))
                            .entrySet()
                            .stream()
                            .filter(entry -> entry.getValue() > 1)
                            .map(Map.Entry::getKey)
                            .toList();
                    assertTrue(
                            repeated.isEmpty(),
                            "Every record in a transactional unit must have a unique TransactionID, but " + repeated
                                    + " was externalized more than once");
                }));
    }

    private CallContractOperation scheduledCall(
            final AtomicReference<Address> scheduleAddressHolder,
            @NonNull final ResponseCodeEnum status,
            @NonNull final String functionName,
            @NonNull final Object... parameters) {
        CallContractOperation call = contract.call(functionName, parameters)
                .gas(2_000_000)
                .andAssert(txn -> txn.hasResults(
                        ContractFnResultAsserts.resultWith()
                                .resultThruAbi(getABIFor(FUNCTION, functionName, contract.name()), ignore -> res -> {
                                    Assertions.assertEquals(2, res.length);
                                    Assertions.assertEquals((long) status.getNumber(), res[0]);
                                    Assertions.assertInstanceOf(Address.class, res[1]);
                                    return Optional.empty();
                                }),
                        // for child record asserting, because executeCall* creating child schedule transaction
                        ContractFnResultAsserts.anyResult()))
                .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS));
        if (scheduleAddressHolder != null) {
            call.exposingResultTo(res -> scheduleAddressHolder.set((Address) res[1]));
        }
        return call;
    }

    private CustomSpecAssert.ThrowingConsumer scheduledCallTest(
            @NonNull final AtomicReference<String> scheduleIdHolder,
            @NonNull final String functionName,
            @NonNull final Object... parameters) {
        return (spec, opLog) -> {
            // run schedule call
            AtomicReference<Address> scheduleAddressHolder = new AtomicReference<>();
            allRunFor(spec, scheduledCall(scheduleAddressHolder, ResponseCodeEnum.SUCCESS, functionName, parameters));
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
