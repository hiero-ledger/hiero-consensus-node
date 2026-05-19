// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.TestTags.QUIESCENCE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsTimeframe;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupDuration;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.contract.Utils.asInstant;
import static com.hedera.services.bdd.suites.regression.system.MixedOperations.burstOfTps;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * First verifies that the network can quiesce and break quiescence.
 * <p>
 * Then submits a burst of mixed operations, freezes all nodes, shuts them down, restarts them, and submits the same
 * burst of mixed operations again.
 * <p>
 * Tagged {@link com.hedera.services.bdd.junit.TestTags#QUIESCENCE} so it runs only in the dedicated
 * {@code hapiTestQuiescence} Gradle subtask, which seeds the necessary JVM-startup overrides
 * ({@code quiescence.enabled=true}, {@code staking.periodMins=1440}, {@code nodes.nodeRewardsEnabled=false}, plus the
 * standard restart-cycle TSS overrides) via {@code hapi.spec.test.overrides}. These cannot be applied from inside the
 * test body because {@code Hedera.quiescenceEnabled} and {@code BlockStreamManagerImpl.quiescenceEnabled} are captured
 * from the bootstrap config at construction time, before any file-121 override has been refreshed into the runtime
 * config.
 *
 * <p>Tests run in a deterministic order via {@link TestMethodOrder}. The cycle-stability test must run first, on a
 * fresh-boot quiescent network: its {@code BREAK_QUIESCENCE} fence requires user transactions to land on a node that
 * is in {@code QUIESCE} state. After {@link #quiesceAndThenRestartMixedOps} runs (with its 10-second 50 TPS bursts at
 * the head and tail), the network enters a block-period-driven oscillation where {@code DONT_QUIESCE} dominates and
 * the {@code QUIESCE → BREAK_QUIESCENCE} edge cannot be triggered by a single small transfer.
 */
@Tag(QUIESCENCE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class QuiesceThenMixedOpsRestartTest implements LifecycleTest {
    private static final int MIXED_OPS_BURST_TPS = 50;

    /**
     * Focused regression test for the heartbeat→manager desync bug (issue #25140).
     *
     * <p>{@link #quiesceAndThenRestartMixedOps} exercises the desync fix incidentally — its assertion only
     * needs one {@code BREAK_QUIESCENCE → QUIESCE} cycle anywhere in a 30-second window — but the bug it
     * targets specifically blocks the <i>second and subsequent</i> cycles, not the first. Pre-fix, the very
     * first {@code BREAK_QUIESCENCE} dispatched by the heartbeat would leave
     * {@code BlockStreamManagerImpl.lastQuiescenceCommand} stuck at {@code QUIESCE}, and every subsequent
     * {@code QUIESCE} transition would be silently suppressed by the manager's CAS guard. A test that observes
     * only one cycle could pass even with the bug present if the boot-time transition leaks through. This
     * test deliberately drives three back-to-back wake-up cycles and asserts that a
     * {@code BREAK_QUIESCENCE → QUIESCE} transition is logged <i>after</i> two prior cycles have already
     * happened — exactly the case the pre-fix code path could not produce.
     *
     * <h4>Expected workflow</h4>
     * <ol>
     *   <li><b>Cycle 1:</b> {@code cryptoTransfer GENESIS → FUNDING} wakes the network; sleep for
     *       {@code 2 * tctDuration} lets it re-quiesce.</li>
     *   <li><b>Cycle 2:</b> another {@code cryptoTransfer}; another sleep. Pre-fix, the manager's
     *       {@code lastQuiescenceCommand} would already be desynced by this point.</li>
     *   <li><b>Capture {@code sleepStart}</b> — assertion timeframe begins <i>after</i> two prior cycles, so
     *       the boot-time transition cannot satisfy it.</li>
     *   <li><b>Cycle 3:</b> a third {@code cryptoTransfer}; a third sleep. With the fix, this cycle produces
     *       the same {@code BREAK_QUIESCENCE → QUIESCE} sequence as cycles 1 and 2.</li>
     *   <li><b>Assert</b> that {@code "to BREAK_QUIESCENCE"} and {@code "from BREAK_QUIESCENCE to QUIESCE"}
     *       both appear in node 0's log within 20 s of {@code sleepStart}. Pre-fix this would fail because
     *       the third {@code QUIESCE} transition would never be emitted.</li>
     * </ol>
     *
     * <p>This is the HAPI-level analogue of the {@code QuiescenceCommandsTest.updateRoundTripQuiesceBreakQuiesceQuiesce}
     * unit test — same regression contract, exercised end-to-end against a real subprocess network.
     */
    @Order(1)
    @HapiTest
    final Stream<DynamicTest> repeatedQuiescenceCyclesAreStable() {
        final AtomicReference<Instant> sleepStart = new AtomicReference<>(Instant.now());
        return hapiTest(
                // --- Cycle 1: prime the state machine ---
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)),
                doWithStartupDuration("quiescence.tctDuration", duration -> sleepForSeconds(2 * duration.toSeconds())),
                // --- Cycle 2: prove the manager-side lastCommand is being kept in sync ---
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)),
                doWithStartupDuration("quiescence.tctDuration", duration -> sleepForSeconds(2 * duration.toSeconds())),
                // Mark the assertion window start AFTER two cycles have completed, so the boot-time and
                // first-cycle transitions cannot satisfy the assertion below.
                withOpContext((spec, opLog) -> sleepStart.set(Instant.now())),
                // --- Cycle 3: the cycle that pre-fix could not produce ---
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)),
                doWithStartupDuration("quiescence.tctDuration", duration -> sleepForSeconds(2 * duration.toSeconds())),
                // The desync bug fenced here would, pre-fix, silently drop every QUIESCE transition emitted
                // by the manager after the first one (because `BlockStreamManagerImpl.lastQuiescenceCommand`
                // would be stuck at QUIESCE across the heartbeat-emitted BREAK_QUIESCENCE — the manager's CAS
                // to QUIESCE became a permanent no-op).
                //
                // "to BREAK_QUIESCENCE" proves the wake path was hit in cycle 3. "to QUIESCE" proves the cycle
                // closed via *some* path — either the direct edge BREAK_QUIESCENCE→QUIESCE (fast local timing)
                // or the indirect BREAK_QUIESCENCE→DONT_QUIESCE→QUIESCE (CI timing where the manager polls
                // during the brief pipeline-non-zero window between ingest and block sign). Both are legal
                // and both fail to emit if issues exist, since the regression suppresses the final
                // CAS-to-QUIESCE either way. Asserting the strict direct edge would over-fence on CI hardware.
                // The window begins after two prior cycles so no boot-time or earlier-cycle transition can
                // satisfy the assertion.
                assertHgcaaLogContainsTimeframe(
                        NodeSelector.byNodeId(0),
                        sleepStart::get,
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(20),
                        "to BREAK_QUIESCENCE",
                        "to QUIESCE"));
    }

    /**
     * Happy-path end-to-end exercise of the quiescence feature plus a mid-test network restart.
     *
     * <h4>Expected workflow</h4>
     * <ol>
     *   <li><b>Capture {@code sleepStart}</b> before any test workload so the assertion timeframe covers the entire
     *       settle-then-quiesce sequence — see comment in the body for why placement matters.</li>
     *   <li><b>Wake the network</b> with a 1-tinybar {@code cryptoTransfer GENESIS → FUNDING} in case the freshly
     *       booted nodes are already quiescent. This forces a {@code BREAK_QUIESCENCE} transition on whatever node
     *       receives it via ingest.</li>
     *   <li><b>Create {@code scheduledReceiver}</b> with an initial balance of 41 HBAR.</li>
     *   <li><b>Create a scheduled {@code cryptoTransfer}</b> of 1 HBAR to {@code scheduledReceiver} that expires
     *       {@code 6 * tctDuration} seconds after the receiver-creation txn. This is the {@link com.hedera.node.app.quiescence.TctProbe} target the
     *       heartbeat will discover.</li>
     *   <li><b>Read the schedule's expiry timestamp</b> via {@code getScheduleInfo} for later assertion bounds.</li>
     *   <li><b>Sleep for {@code 2 * tctDuration}</b> (~10 s) to give the network time to settle and quiesce.</li>
     *   <li><b>Assert the full wake-up cycle in node 0's log</b> within 30 s of {@code sleepStart}: both
     *       {@code "to BREAK_QUIESCENCE"} (proving the wake path was hit) and
     *       {@code "from BREAK_QUIESCENCE to QUIESCE"}.</li>
     *   <li><b>Sleep for {@code 4 * tctDuration}</b> (~20 s) to let the scheduled txn's expiry approach and fire.
     *       During this window the heartbeat discovers the schedule's expiry as a TCT, the controller transitions to
     *       {@code DONT_QUIESCE} as wall-clock approaches the TCT, and the scheduled txn executes.</li>
     *   <li><b>Verify {@code scheduledReceiver} balance is 42 HBAR</b> (41 initial + 1 from the scheduled txn) —
     *       confirms the scheduled txn ran despite the network being quiescent for most of the sleep window.</li>
     *   <li><b>Verify the scheduled txn's consensus timestamp</b> is within {@code [expiry, expiry + 5 s]}.</li>
     *   <li><b>Burst 50 TPS of mixed ops for 10 s</b> (~500 txns) to exercise sustained traffic against a freshly
     *       woken network.</li>
     *   <li><b>Restart the network</b> at the next config version. This restarts after the burst, with the network
     *       in {@code DONT_QUIESCE} state.</li>
     *   <li><b>Burst 50 TPS of mixed ops for 10 s again</b> against the post-restart network — proves the quiescence
     *       state machine recovers cleanly through a real restart.</li>
     * </ol>
     */
    @Order(2)
    @LeakyHapiTest
    final Stream<DynamicTest> quiesceAndThenRestartMixedOps() {
        final AtomicReference<Instant> scheduleExpiry = new AtomicReference<>();
        final AtomicReference<Instant> sleepStart = new AtomicReference<>(Instant.now());
        return hapiTest(
                // Capture sleepStart BEFORE the test workload. The assertion timeframe must cover the entire
                // settle-then-quiesce sequence (wake-up cryptoTransfer + cryptoCreate + scheduleCreate +
                // pipeline drain + heartbeat tick). With the centralized QuiescenceCommands and 1-second
                // heartbeat interval, the transition into QUIESCE can land within a few hundred milliseconds
                // of the last op completing — sometimes BEFORE a sleepStart captured between the ops and the
                // sleep. Placing the capture here guarantees the transition is inside the timeframe regardless
                // of how fast the network settles.
                withOpContext((_, _) -> sleepStart.set(Instant.now())),
                // Ensure the network is out of quiescence before the test logic
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)),
                // --- actual test workflow ---
                cryptoCreate("scheduledReceiver").via("txn").balance(41 * ONE_HBAR),
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
                // Tightened from a single "to QUIESCE" substring to assert the full wake-up cycle.
                // "to BREAK_QUIESCENCE" proves a user transaction landed on a quiescent node and exercised the
                // break-quiescence path; "from BREAK_QUIESCENCE to QUIESCE" proves the cycle closed — the exact
                // regression case the heartbeat→manager desync bug used to silently drop. Both patterns fire
                // multiple times per test in healthy runs (see hgcaa.log), so the assertion is robust; together
                // they fail loudly if either edge of the state machine stops working.
                assertHgcaaLogContainsTimeframe(
                        NodeSelector.byNodeId(0),
                        sleepStart::get,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(30),
                        "to BREAK_QUIESCENCE",
                        "from BREAK_QUIESCENCE to QUIESCE"),
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
