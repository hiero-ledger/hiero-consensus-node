// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.TestTags.RESTART;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsPairTimeframe;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupDuration;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.contract.Utils.asInstant;
import static com.hedera.services.bdd.suites.regression.system.MixedOperations.burstOfTps;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * First verifies that the network can quiesce and break quiescence.
 * <p>
 * Then submits a burst of mixed operations, freezes all nodes, shuts them down, restarts them, and submits the same
 * burst of mixed operations again.
 */
@Tag(RESTART)
public class QuiesceThenMixedOpsRestartTest implements LifecycleTest {
    private static final int MIXED_OPS_BURST_TPS = 50;

    @LeakyHapiTest(overrides = {"staking.periodMins", "nodes.nodeRewardsEnabled"})
    final Stream<DynamicTest> quiesceAndThenRestartMixedOps() {
        final AtomicReference<Instant> scheduleExpiry = new AtomicReference<>();
        final AtomicReference<Instant> logAssertionStart = new AtomicReference<>();
        return hapiTest(
                // Update 0.0.121 before the restart so the overrides persist in saved state
                // and survive the post-upgrade system file processing on restart
                overridingAllOf(Map.of(
                        "staking.periodMins", "1440",
                        "nodes.nodeRewardsEnabled", "false")),
                LifecycleTest.restartAtNextConfigVersion(),
                // Ensure the network is out of quiescence before the test logic
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)),
                // --- actual test workflow ---
                cryptoCreate("scheduledReceiver").via("txn").balance(41 * ONE_HBAR),
                doingContextual((ignored) -> logAssertionStart.set(Instant.now())),
                doWithStartupDuration("quiescence.tctDuration", duration -> scheduleCreate(
                                "schedule", cryptoTransfer(tinyBarsFromTo(GENESIS, "scheduledReceiver", ONE_HBAR)))
                        .payingWith(GENESIS)
                        .waitForExpiry()
                        .withRelativeExpiry("txn", 6 * duration.toSeconds())
                        .via("creation")
                        .recordingScheduledTxn()),
                getScheduleInfo("schedule")
                        .exposingInfoTo(info -> scheduleExpiry.set(asInstant(info.getExpirationTime())))
                        .logged(),
                doWithStartupDuration("quiescence.tctDuration", duration -> sleepForSeconds(2 * duration.toSeconds())),
                assertHgcaaLogContainsPairTimeframe(
                        NodeSelector.byNodeId(0),
                        logAssertionStart::get,
                        Duration.ofSeconds(60), // for both lines
                        Duration.ofSeconds(60),
                        "Started quiesced heartbeat",
                        "Stopping quiescence heartbeat",
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(40)),
                doWithStartupDuration("quiescence.tctDuration", duration -> sleepForSeconds(4 * duration.toSeconds())),
                getAccountBalance("scheduledReceiver").hasTinyBars(42 * ONE_HBAR),
                getTxnRecord("creation").scheduled().exposingTo(r -> {
                    final var expected = scheduleExpiry.get();
                    final var actual = asInstant(r.getConsensusTimestamp());
                    assertFalse(
                            actual.isBefore(expected),
                            "Execution time " + actual + " was before scheduled expiry " + expected);
                    final var maxDelay = Duration.ofSeconds(5);
                    assertTrue(
                            Duration.between(expected, actual).compareTo(maxDelay) < 0,
                            "Execution time " + actual + " was more than " + maxDelay + " after scheduled expiry "
                                    + expected);
                }),
                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                LifecycleTest.restartAtNextConfigVersion(),
                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION));
    }
}
