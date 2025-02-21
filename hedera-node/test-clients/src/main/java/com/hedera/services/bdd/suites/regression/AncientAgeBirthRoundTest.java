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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(UPGRADE)
public class AncientAgeBirthRoundTest implements LifecycleTest {

    @HapiTest
    @Disabled("Disabled due to expected failures while birth round ancient age is completed")
    final Stream<DynamicTest> upgradeToUseBirthRounds() {
        /*
        Note: This test should be run in subprocess mode. This is done by executing it with the 'testSubprocess' task:
        :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.regression.AncientAgeBirthRoundTest"
         */

        return hapiTest(
                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(Map.of("event.useBirthRoundAncientThreshold", "true")),
                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION));
    }
}
