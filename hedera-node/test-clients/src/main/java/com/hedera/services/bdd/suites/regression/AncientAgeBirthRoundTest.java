// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression;

import static com.hedera.services.bdd.junit.TestTags.UPGRADE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.suites.regression.system.MixedOperations.burstOfTps;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(UPGRADE)
@DisplayName("Ancient age birth round migration")
public class AncientAgeBirthRoundTest implements LifecycleTest {

    @HapiTest
    @DisplayName("Upgrade to use birth rounds")
    @Disabled("This test is required to run in isolation, but it is not clear how to do this with the HAPI framework")
    final Stream<DynamicTest> upgradeToUseBirthRounds() {
        /*
        Note: This test should be run in subprocess mode. This is done by executing it with the 'testSubprocess' task:
        :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.regression.AncientAgeBirthRoundTest"

        The purpose of this test is to validate the platform operates normally when birth round migration is enabled.
        "Operates normally" in this context means the migration is successful, the nodes start successfully and events
         are still being created and gossiped.
         */

        return hapiTest(
                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(Map.of("event.useBirthRoundAncientThreshold", "true")),
                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION));
    }
}
