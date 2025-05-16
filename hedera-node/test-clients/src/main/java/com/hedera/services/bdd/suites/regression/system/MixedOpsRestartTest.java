// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.suites.regression.system.MixedOperations.burstOfTps;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(INTEGRATION)
@TargetEmbeddedMode(CONCURRENT)
public class MixedOpsRestartTest implements LifecycleTest {
    private static final int MIXED_OPS_BURST_TPS = 50;

    @HapiTest
    final Stream<DynamicTest> restartMixedOps() {
        return hapiTest(
                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                LifecycleTest.restartAtNextConfigVersion(),
                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION));
    }
}
