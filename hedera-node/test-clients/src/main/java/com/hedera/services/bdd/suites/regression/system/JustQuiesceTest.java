// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsPattern;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupDuration;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.contract.Utils.asInstant;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Simple quiescence test to run in HAPI (Misc) check, which includes a {@code streamMode=RECORDS} variant.
 */
public class JustQuiesceTest {
    @HapiTest
    final Stream<DynamicTest> justQuiesce() {
        final AtomicReference<Instant> scheduleExpiry = new AtomicReference<>();
        return hapiTest(
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
                assertHgcaaLogContainsPattern(
                        NodeSelector.byNodeId(0),
                        "Updating quiescence command from .* to QUIESCE",
                        Duration.ofSeconds(1)),
                doWithStartupDuration("quiescence.tctDuration", duration -> sleepForSeconds(4 * duration.toSeconds())),
                getAccountBalance("scheduledReceiver").hasTinyBars(42 * ONE_HBAR),
                getTxnRecord("creation").scheduled().exposingTo(r -> {
                    final var expected = scheduleExpiry.get();
                    final var actual = asInstant(r.getConsensusTimestamp());
                    assertFalse(
                            actual.isBefore(expected),
                            "Execution time " + actual + " was before scheduled expiry " + expected);
                    final var maxDelay = Duration.ofSeconds(2);
                    assertTrue(
                            Duration.between(expected, actual).compareTo(maxDelay) < 0,
                            "Execution time " + actual + " was more than " + maxDelay + " after scheduled expiry "
                                    + expected);
                }));
    }
}
