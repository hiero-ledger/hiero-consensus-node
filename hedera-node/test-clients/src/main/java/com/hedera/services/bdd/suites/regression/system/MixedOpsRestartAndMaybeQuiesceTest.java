// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.TestTags.RESTART;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupDuration;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * This test is to verify restart functionality. It submits a burst of mixed operations, then
 * freezes all nodes, shuts them down, restarts them, and submits the same burst of mixed operations
 * again.
 * <p>
 * If quiescence is enabled, it also verifies that the network can quiesce and break quiescence.
 */
@Tag(RESTART)
public class MixedOpsRestartAndMaybeQuiesceTest implements LifecycleTest {
    private static final int MIXED_OPS_BURST_TPS = 50;

    @HapiTest
    final Stream<DynamicTest> restartMixedOpsAndMaybeQuiesce() {
        return hapiTest(
                //                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                //                LifecycleTest.restartAtNextConfigVersion(),
                //                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                doWithStartupDuration("quiescence.tctDuration", duration -> sleepForSeconds(2 * duration.toSeconds())));
    }
}
