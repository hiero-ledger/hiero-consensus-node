// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opsduration;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.throttleUsagePercentageWithin;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("opsDurationThrottle")
@HapiTestLifecycle
public class OpsDurationThrottleTest {
    private static final String OPS_DURATION_THROTTLE = "OpsDurationThrottle";

    @HapiTest
    @DisplayName("call function to exceed ops duration throttle")
    public Stream<DynamicTest> updateFungibleTokenWithHbarFixedFee() {
        return hapiTest(
                overriding("contracts.throttle.throttleByOpsDuration", "true"),

                //                 overriding("contracts.maxOpsDuration", "10000000"),
                uploadInitCode(OPS_DURATION_THROTTLE),
                contractCreate(OPS_DURATION_THROTTLE).gas(2_000_000L),
                inParallel(IntStream.range(0, 450)
                        .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_THROTTLE, "run")
                                .gas(200_000L)
                                .hasKnownStatusFrom(ResponseCodeEnum.SUCCESS, ResponseCodeEnum.THROTTLED_AT_CONSENSUS)))
                        .toArray(HapiSpecOperation[]::new)),
                throttleUsagePercentageWithin(100));
    }
}
