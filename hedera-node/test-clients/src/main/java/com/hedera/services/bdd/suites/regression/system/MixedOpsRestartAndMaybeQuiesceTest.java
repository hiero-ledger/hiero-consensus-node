// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.TestTags.RESTART;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContains;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupDuration;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import java.time.Duration;
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
                cryptoCreate("scheduledReceiver").via("txn").balance(41 * ONE_HBAR),
                doWithStartupDuration("quiescence.tctDuration", duration -> scheduleCreate(
                                "schedule", cryptoTransfer(tinyBarsFromTo(GENESIS, "scheduledReceiver", ONE_HBAR)))
                        .payingWith(GENESIS)
                        .waitForExpiry()
                        .withRelativeExpiry("txn", 6 * duration.toSeconds())
                        .recordingScheduledTxn()),
                //                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                //                LifecycleTest.restartAtNextConfigVersion(),
                //                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                doWithStartupDuration("quiescence.tctDuration", duration -> sleepForSeconds(2 * duration.toSeconds())),
                assertHgcaaLogContains(
                        NodeSelector.byNodeId(0), "Updating quiescence command from", Duration.ofSeconds(1)),
                        doWithStartupDuration("quiescence.tctDuration", duration -> sleepForSeconds(4 * duration.toSeconds())));
    }
}
