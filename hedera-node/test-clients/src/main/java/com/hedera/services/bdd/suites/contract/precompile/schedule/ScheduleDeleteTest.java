// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.asScheduleId;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Tests success scenarios of the HRC-1215 functions when enabled
 * {@code contracts.systemContract.scheduleService.deleteSchedule.enabled} feature flag. This tests checks just a happy
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
    static AtomicInteger COUNTER = new AtomicInteger();

    @BeforeAll
    public static void setup(TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                overriding("contracts.systemContract.scheduleService.scheduleCall.enabled", "true"),
                overriding("contracts.systemContract.scheduleService.deleteSchedule.enabled", "true"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("deleteSchedule for scheduleCall(address,uint256,uint256,uint64,bytes)")
    public Stream<DynamicTest> scheduleCallDeleteTest() {
        return Stream.of("deleteScheduleExample", "deleteScheduleProxyExample")
                .flatMap(deleteFunc -> deleteScheduleTest(
                        "scheduleCallExample", deleteFunc, BigInteger.valueOf(50 + COUNTER.getAndIncrement())));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("deleteSchedule for scheduleCallWithSender(address,address,uint256,uint256,uint64,bytes)")
    public Stream<DynamicTest> scheduleCallWithSenderDeleteTest() {
        return Stream.of("deleteScheduleExample", "deleteScheduleProxyExample")
                .flatMap(deleteFunc -> deleteScheduleTest(
                        "scheduleCallWithSenderExample",
                        deleteFunc,
                        sender,
                        BigInteger.valueOf(50 + COUNTER.getAndIncrement())));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("deleteSchedule for executeCallOnSenderSignature(address,address,uint256,uint256,uint64,bytes)")
    public Stream<DynamicTest> executeCallOnSenderSignatureDeleteTest() {
        return Stream.of("deleteScheduleExample", "deleteScheduleProxyExample")
                .flatMap(deleteFunc -> deleteScheduleTest(
                        "executeCallOnSenderSignatureExample",
                        deleteFunc,
                        sender,
                        BigInteger.valueOf(50 + COUNTER.getAndIncrement())));
    }

    private Stream<DynamicTest> deleteScheduleTest(
            String scheduleFunction, String deleteFunction, @NonNull final Object... parameters) {
        return hapiTest(withOpContext((spec, opLog) -> {
            // create schedule
            final var scheduleAddress = new AtomicReference<Address>();
            allRunFor(
                    spec,
                    contract.call(scheduleFunction, parameters)
                            .gas(2_000_000L)
                            .exposingResultTo(res -> scheduleAddress.set((Address) res[1]))
                            .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS)));
            final var scheduleID = asScheduleId(spec, scheduleAddress.get());
            final var scheduleIDString = String.valueOf(scheduleID.getScheduleNum());
            allRunFor(
                    spec,
                    // check schedule exists
                    getScheduleInfo(scheduleIDString)
                            .hasScheduleId(scheduleIDString)
                            .isNotExecuted()
                            .isNotDeleted(),
                    // delete schedule
                    contract.call(deleteFunction, scheduleAddress.get())
                            .gas(200_000L)
                            .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS)),
                    // check schedule deleted
                    getScheduleInfo(scheduleIDString)
                            .hasScheduleId(scheduleIDString)
                            .isDeleted());
        }));
    }
}
