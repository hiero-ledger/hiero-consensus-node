// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling.hip1313;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class Hip1313DisabledTest {

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("fees.simpleFeesEnabled", "false", "networkAdmin.highVolumeThrottlesEnabled", "false"));
        testLifecycle.doAdhoc(cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> highVolumeTxnRejectedWhenFeatureDisabled() {
        return hapiTest(createTopic("hvDisabledTopic")
                .payingWith(CIVILIAN_PAYER)
                .withHighVolume()
                .hasPrecheck(NOT_SUPPORTED));
    }

    @LeakyHapiTest(
            requirement = {PROPERTY_OVERRIDES, THROTTLE_OVERRIDES},
            overrides = {"networkAdmin.highVolumeThrottlesEnabled", "fees.simpleFeesEnabled"},
            throttles = "testSystemFiles/hip1313-disabled-one-tps-create.json")
    final Stream<DynamicTest> existingThrottlesStillApplyWhenHip1313Disabled() {
        return hapiTest(
                overridingThrottles("testSystemFiles/hip1313-disabled-one-tps-create.json"),
                cryptoCreate("regularCreateA")
                        .payingWith(CIVILIAN_PAYER)
                        .withHighVolume()
                        .hasPrecheck(NOT_SUPPORTED),
                cryptoCreate("regularCreateA")
                        .payingWith(CIVILIAN_PAYER)
                        .deferStatusResolution()
                        .hasPrecheck(OK),
                cryptoCreate("regularCreateB").payingWith(CIVILIAN_PAYER).hasPrecheck(BUSY));
    }
}
