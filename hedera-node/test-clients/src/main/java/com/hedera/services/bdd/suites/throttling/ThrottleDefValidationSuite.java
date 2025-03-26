// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling;

import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.SysFileOverrideOp.Target.THROTTLES;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottlesFails;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_SCHEDULE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THROTTLE_DEFS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OPERATION_REPEATED_IN_BUCKET_GROUPS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.THROTTLE_GROUP_HAS_ZERO_OPS_PER_SEC;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.THROTTLE_GROUP_LCM_OVERFLOW;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.utilops.SysFileOverrideOp;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;

@OrderedInIsolation
public class ThrottleDefValidationSuite {
    private static final SysFileOverrideOp throttleRestorationOp = new SysFileOverrideOp(THROTTLES, () -> null);

    @HapiTest
    @Order(1)
    final Stream<DynamicTest> takeThrottleSnapshot() {
        return hapiTest(throttleRestorationOp);
    }

    @HapiTest
    @Order(2)
    final Stream<DynamicTest> updateWithMissingTokenMintFails() {
        return hapiTest(overridingThrottles("testSystemFiles/throttles-sans-mint.json"));
    }

    @HapiTest
    @Order(3)
    final Stream<DynamicTest> throttleUpdateWithZeroGroupOpsPerSecFails() {
        return hapiTest(
                overridingThrottlesFails("testSystemFiles/zero-ops-group.json", THROTTLE_GROUP_HAS_ZERO_OPS_PER_SEC));
    }

    @HapiTest
    @Order(4)
    final Stream<DynamicTest> throttleUpdateRejectsMultiGroupAssignment() {
        return hapiTest(overridingThrottlesFails(
                "testSystemFiles/duplicated-operation.json", OPERATION_REPEATED_IN_BUCKET_GROUPS));
    }

    @HapiTest
    @Order(5)
    final Stream<DynamicTest> throttleDefsRejectUnauthorizedPayers() {
        return defaultHapiSpec("ThrottleDefsRejectUnauthorizedPayers")
                .given(
                        cryptoCreate("civilian"),
                        cryptoTransfer(movingHbar(ONE_HUNDRED_HBARS).between(GENESIS, FEE_SCHEDULE_CONTROL)))
                .when()
                .then(
                        fileUpdate(THROTTLE_DEFS)
                                .contents("BOOM")
                                .payingWith("civilian")
                                .hasPrecheck(AUTHORIZATION_FAILED),
                        fileUpdate(THROTTLE_DEFS)
                                .contents("BOOM")
                                .payingWith(FEE_SCHEDULE_CONTROL)
                                .hasPrecheck(AUTHORIZATION_FAILED));
    }

    @HapiTest
    @Order(6)
    final Stream<DynamicTest> ensureDevLimitsRestored() {
        return hapiTest(withOpContext((spec, opLog) -> throttleRestorationOp.restoreContentsIfNeeded(spec)));
    }

    @HapiTest
    @Order(7)
    final Stream<DynamicTest> leastCommonMultipleOverflow() {
        return hapiTest(
                overridingThrottlesFails("testSystemFiles/lcm-overflow-throttles.json", THROTTLE_GROUP_LCM_OVERFLOW));
    }
}
