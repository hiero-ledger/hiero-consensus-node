// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opsduration;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.throttleUsagePercentageLessThreshold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.throttleUsagePercentageMoreThanThreshold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.swirlds.metrics.impl.AtomicDouble;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("opsDurationThrottle")
@HapiTestLifecycle
@OrderedInIsolation
public class OpsDurationThrottleTest {
    private static final String OPS_DURATION_THROTTLE = "OpsDurationThrottle";

    @HapiTest
    @DisplayName("call function to exceed ops duration throttle")
    public Stream<DynamicTest> exceedOpsDuration() {
        final AtomicDouble duration = new AtomicDouble(0.0);
        return hapiTest(
                overriding("contracts.throttle.throttleByOpsDuration", "true"),
                uploadInitCode(OPS_DURATION_THROTTLE),
                contractCreate(OPS_DURATION_THROTTLE).gas(2_000_000L),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 450)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_THROTTLE, "run")
                                            .gas(200_000L)
                                            .hasKnownStatusFrom(
                                                    ResponseCodeEnum.SUCCESS, ResponseCodeEnum.THROTTLED_AT_CONSENSUS)
                                            .collectMaxOpsDuration(duration)))
                                    .toArray(HapiSpecOperation[]::new)));
                    allRunFor(spec, throttleUsagePercentageMoreThanThreshold(duration.get(), 95.0));
                }));
    }

    @HapiTest
    @DisplayName("call function to not exceed ops duration throttle")
    public Stream<DynamicTest> doNotExceedOpsDuration() {
        final AtomicDouble duration = new AtomicDouble(0.0);
        return hapiTest(
                overriding("contracts.throttle.throttleByOpsDuration", "true"),
                uploadInitCode(OPS_DURATION_THROTTLE),
                contractCreate(OPS_DURATION_THROTTLE).gas(2_000_000L),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 5)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_THROTTLE, "run")
                                            .gas(200_000L)
                                            .hasKnownStatusFrom(
                                                    ResponseCodeEnum.SUCCESS, ResponseCodeEnum.THROTTLED_AT_CONSENSUS)
                                            .collectMaxOpsDuration(duration)))
                                    .toArray(HapiSpecOperation[]::new)));
                    allRunFor(spec, throttleUsagePercentageLessThreshold(duration.get(), 20.0));
                }));
    }
}
