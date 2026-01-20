// SPDX-License-Identifier: Apache-2.0
//// SPDX-License-Identifier: Apache-2.0
// package com.hedera.services.bdd.suites.demos;
//
// import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
// import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
// import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
// import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
// import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
// import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
// import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
// import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
// import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
// import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
// import static org.junit.jupiter.api.Assertions.assertTrue;
//
// import com.hedera.services.bdd.junit.HapiTest;
// import com.hedera.services.bdd.junit.RepeatableHapiTest;
// import java.time.Duration;
// import java.time.Instant;
// import java.util.concurrent.atomic.AtomicReference;
// import java.util.stream.Stream;
// import org.junit.jupiter.api.DisplayName;
// import org.junit.jupiter.api.DynamicTest;
// import org.junit.jupiter.api.Tag;
//
/// **
// * Demonstration test class showing how to improve HAPI tests that use sleepFor() by using
// * repeatable mode with virtual time advancement.
// *
// * <p>Key insight: In HAPI tests, sleepFor() already uses virtual time in embedded mode,
// * so the improvement is to use @RepeatableHapiTest to ensure the test runs in embedded mode.
// *
// * <p>Tests:
// * <ul>
// *   <li>{@link #originalTestWithSleepForConsensusTime()} - HAPI test using sleepFor() in subprocess mode</li>
// *   <li>{@link #improvedTestWithRepeatableModeForConsensusTime()} - Same test in repeatable mode with virtual
// time</li>
// * </ul>
// */
// @Tag("ADHOC")
// public class SleepImprovementDemoTest {
//
//    // ==================================================================================
//    // REPEATABLE MODE DEMONSTRATION
//    // Original test: Sleeps to wait for time to pass between transactions
//    // Improved test: Uses repeatable mode where sleepFor advances virtual time instantly
//    // ==================================================================================
//
//    @HapiTest
//    @DisplayName("Original: Sleep-based consensus time validation")
//    final Stream<DynamicTest> originalTestWithSleepForConsensusTime() {
//        final String firstTxn = "firstTxn";
//        final String secondTxn = "secondTxn";
//        final AtomicReference<Instant> firstConsensusTime = new AtomicReference<>();
//
//        return hapiTest(
//                // First transaction - record its consensus time
//                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)).via(firstTxn),
//
//                // Capture the first consensus time
//                withOpContext((spec, opLog) -> {
//                    var record = getTxnRecord(firstTxn);
//                    allRunFor(spec, record);
//                    var timestamp = record.getResponseRecord().getConsensusTimestamp();
//                    firstConsensusTime.set(Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()));
//                    opLog.info("First consensus time: {}", firstConsensusTime.get());
//                }),
//
//                // PROBLEM: Real 3-second sleep in subprocess mode
//                sleepFor(3_000),
//
//                // Second transaction - should have consensus time at least 3 seconds later
//                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)).via(secondTxn),
//
//                // Verify consensus times differ by at least 3 seconds
//                withOpContext((spec, opLog) -> {
//                    var record = getTxnRecord(secondTxn);
//                    allRunFor(spec, record);
//                    var timestamp = record.getResponseRecord().getConsensusTimestamp();
//                    Instant secondConsensusTime = Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
//                    opLog.info("Second consensus time: {}", secondConsensusTime);
//
//                    long diffSeconds = Duration.between(firstConsensusTime.get(), secondConsensusTime)
//                            .getSeconds();
//                    assertTrue(
//                            diffSeconds >= 3,
//                            "Consensus times should differ by at least 3 seconds, but diff was " + diffSeconds);
//                }));
//    }
//
//    /**
//     * IMPROVED TEST: Uses repeatable mode where sleepFor advances virtual time instantly.
//     *
//     * <p>Benefits:
//     * <ul>
//     *   <li>In repeatable mode, sleepFor(3_000) advances virtual time by 3 seconds instantly</li>
//     *   <li>The test completes in milliseconds instead of 3+ seconds</li>
//     *   <li>Same test logic, same assertions, just faster execution</li>
//     *   <li>Deterministic - virtual time is controlled, not subject to system clock</li>
//     * </ul>
//     *
//     * <p>Note: The {@code @RepeatableHapiTest} annotation marks this test to run in
//     * repeatable/embedded mode where time is virtualized. The sleepFor() call inside
//     * will advance consensus time via spec.sleepConsensusTime() -> tick(duration)
//     * without any real waiting.
//     */
//    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
//    @DisplayName("Improved: Repeatable mode with virtual time for consensus time")
//    final Stream<DynamicTest> improvedTestWithRepeatableModeForConsensusTime() {
//        final String firstTxn = "firstTxn";
//        final String secondTxn = "secondTxn";
//        final AtomicReference<Instant> firstConsensusTime = new AtomicReference<>();
//
//        return hapiTest(
//                // First transaction - record its consensus time
//                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)).via(firstTxn),
//
//                // Capture the first consensus time
//                withOpContext((spec, opLog) -> {
//                    var record = getTxnRecord(firstTxn);
//                    allRunFor(spec, record);
//                    var timestamp = record.getResponseRecord().getConsensusTimestamp();
//                    firstConsensusTime.set(Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()));
//                    opLog.info("First consensus time: {}", firstConsensusTime.get());
//                }),
//
//                // IMPROVEMENT: In repeatable mode, this advances virtual time INSTANTLY
//                // No real waiting - the embedded network's clock just jumps forward 3 seconds
//                sleepFor(3_000),
//
//                // Second transaction - will have consensus time at least 3 seconds later
//                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)).via(secondTxn),
//
//                // Verify consensus times differ by at least 3 seconds (same assertion as original)
//                withOpContext((spec, opLog) -> {
//                    var record = getTxnRecord(secondTxn);
//                    allRunFor(spec, record);
//                    var timestamp = record.getResponseRecord().getConsensusTimestamp();
//                    Instant secondConsensusTime = Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
//                    opLog.info("Second consensus time: {}", secondConsensusTime);
//
//                    long diffSeconds = Duration.between(firstConsensusTime.get(), secondConsensusTime)
//                            .getSeconds();
//                    assertTrue(
//                            diffSeconds >= 3,
//                            "Consensus times should differ by at least 3 seconds, but diff was " + diffSeconds);
//                }));
//    }
// }
