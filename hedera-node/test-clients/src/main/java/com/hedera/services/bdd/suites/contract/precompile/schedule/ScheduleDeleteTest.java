// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.suites.contract.Utils.asScheduleId;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
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

/**
 * Tests success scenarios of the HRC-1215 functions when enabled
 * {@code contracts.systemContract.scheduleService.scheduleCall.enabled} feature flag. This tests checks just a happy
 * path because more detailed tests with be added to
 * <a href="https://github.com/hashgraph/hedera-evm-testing">hedera-evm-testing</a> repo
 */
@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class ScheduleDeleteTest {

    @Contract(contract = "HIP1215Contract", creationGas = 4_000_000L, isImmutable = true)
    static SpecContract contract;

    @Account(tinybarBalance = HapiSuite.ONE_HUNDRED_HBARS)
    static SpecAccount sender;
    // COUNTER is used to create scheduled with different expirySecond, to prevent identical schedule creation
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @BeforeAll
    public static void setup(TestLifecycle lifecycle) {
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
    @DisplayName(
            "call deleteSchedule/proxy deleteSchedule for scheduleCall(address,uint256,uint256,uint64,bytes) success")
    public Stream<DynamicTest> scheduleCallDeleteTest() {
        return Stream.of("deleteScheduleExample", "deleteScheduleProxyExample")
                .flatMap(deleteFunc -> deleteScheduleTest(
                        "scheduleCallExample", deleteFunc, BigInteger.valueOf(50 + COUNTER.getAndIncrement())));
    }

    // default 'feeSchedules.json' do not contain HederaFunctionality.SCHEDULE_CREATE,
    // fee data for SubType.SCHEDULE_CREATE_CONTRACT_CALL
    // that is why we are reuploading 'scheduled-contract-fees.json' in tests
    @LeakyHapiTest(fees = "scheduled-contract-fees.json")
    @DisplayName(
            "call deleteSchedule/proxy deleteSchedule for scheduleCallWithSender(address,address,uint256,uint256,uint64,bytes) success")
    @Tag(MATS)
    public Stream<DynamicTest> scheduleCallWithSenderDeleteTest() {
        return Stream.of("deleteScheduleExample", "deleteScheduleProxyExample")
                .flatMap(deleteFunc -> deleteScheduleTest(
                        "scheduleCallWithSenderExample",
                        deleteFunc,
                        sender,
                        BigInteger.valueOf(50 + COUNTER.getAndIncrement())));
    }

    // default 'feeSchedules.json' do not contain HederaFunctionality.SCHEDULE_CREATE,
    // fee data for SubType.SCHEDULE_CREATE_CONTRACT_CALL
    // that is why we are reuploading 'scheduled-contract-fees.json' in tests
    @LeakyHapiTest(fees = "scheduled-contract-fees.json")
    @DisplayName(
            "call deleteSchedule/proxy deleteSchedule for executeCallOnSenderSignature(address,address,uint256,uint256,uint64,bytes) success")
    @Tag(MATS)
    public Stream<DynamicTest> executeCallOnSenderSignatureDeleteTest() {
        return Stream.of("deleteScheduleExample", "deleteScheduleProxyExample")
                .flatMap(deleteFunc -> deleteScheduleTest(
                        "executeCallOnSenderSignatureExample",
                        deleteFunc,
                        sender,
                        BigInteger.valueOf(50 + COUNTER.getAndIncrement())));
    }

    private Stream<DynamicTest> deleteScheduleTest(
            @NonNull final String scheduleFunction,
            @NonNull final String deleteFunction,
            @NonNull final Object... parameters) {
        return hapiTest(UtilVerbs.withOpContext((spec, opLog) -> {
            // create schedule
            final var scheduleAddress = new AtomicReference<Address>();
            allRunFor(
                    spec,
                    contract.call(scheduleFunction, parameters)
                            .gas(2_000_000L)
                            .exposingResultTo(res -> scheduleAddress.set((Address) res[1]))
                            .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS)));
            final var scheduleId = asScheduleId(spec, scheduleAddress.get());
            final var scheduleIdString = String.valueOf(scheduleId.getScheduleNum());
            allRunFor(
                    spec,
                    // check schedule exists
                    getScheduleInfo(scheduleIdString)
                            .hasScheduleId(scheduleIdString)
                            .isNotExecuted()
                            .isNotDeleted(),
                    // delete schedule
                    contract.call(deleteFunction, scheduleAddress.get())
                            .gas(200_000L)
                            .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS)),
                    // check schedule deleted
                    getScheduleInfo(scheduleIdString)
                            .hasScheduleId(scheduleIdString)
                            .isDeleted());
        }));
    }
}
