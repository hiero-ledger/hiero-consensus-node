// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
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

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.asScheduleId;

/**
 * Tests success scenarios of the HRC-1215 functions when enabled
 * {@code contracts.systemContract.scheduleService.deleteSchedule.enabled} feature flag. This tests checks just a happy
 * path because more detailed tests with be added to
 * <a href="https://github.com/hashgraph/hedera-evm-testing">hedera-evm-testing</a> repo
 */
@Tag(SMART_CONTRACT)
@DisplayName("Schedule call")
@HapiTestLifecycle
public class ScheduleDeleteTest {

    @Contract(contract = "HIP1215Contract", creationGas = 4_000_000L, isImmutable = true)
    static SpecContract contract;

    @BeforeAll
    public static void setup(TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                overriding("contracts.systemContract.scheduleService.scheduleCall.enabled", "true"),
                overriding("contracts.systemContract.scheduleService.deleteSchedule.enabled", "true"),
                overriding("contracts.systemContract.scheduleService.hasScheduleCapacity.enabled", "true"));
    }

    @HapiTest
    @DisplayName("redirect proxy deleteSchedule()")
    public Stream<DynamicTest> deleteScheduleProxyTest() {
        return hapiTest(withOpContext((spec, opLog) -> {
            // create schedule
            final var scheduleAddress = new AtomicReference<Address>();
            allRunFor(
                    spec,
                    contract.call("scheduleCallExample", BigInteger.valueOf(60))
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
                            .isNotExecuted(),
                    // delete schedule
                    contract.call("deleteScheduleProxyExample", scheduleAddress.get())
                            .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS))
                    // check schedule deleted //TODO Glib: add delete checker
//                    getScheduleInfo(scheduleIDString)
//                            .hasScheduleId(scheduleIDString)
//                            .isNotDeleted()
            );
        }));
    }

}
