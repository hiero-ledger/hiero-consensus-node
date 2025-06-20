// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip423;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_NEW_VALID_SIGNATURES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_ALREADY_EXECUTED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class ScheduleLongTermSignTest {

    private static final long ONE_MINUTE = 60;
    private static final long THIRTY_MINUTES = 30 * ONE_MINUTE;
    private static final String PAYING_ACCOUNT = "payingAccount";
    private static final String RECEIVER = "receiver";
    private static final String SENDER = "sender";

    @HapiTest
    final Stream<DynamicTest> scheduleSignWhenAllSigPresent() {
        return hapiTest(
                cryptoCreate("receiver").balance(0L).receiverSigRequired(true),
                scheduleCreate("schedule", cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, "receiver", 1L)))
                        .waitForExpiry()
                        .expiringIn(TimeUnit.SECONDS.toMillis(5))
                        .via("one"),
                scheduleSign("schedule").signedBy(DEFAULT_PAYER, "receiver"),
                scheduleSign("schedule").hasKnownStatus(NO_NEW_VALID_SIGNATURES));
    }

    @HapiTest
    final Stream<DynamicTest> scheduleSignWhenAllSigPresentNoWaitForExpiry() {
        return hapiTest(
                cryptoCreate("receiver").balance(0L).receiverSigRequired(true),
                scheduleCreate("schedule", cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, "receiver", 1L)))
                        .via("one"),
                scheduleSign("schedule").signedBy(DEFAULT_PAYER, "receiver"),
                scheduleSign("schedule").hasKnownStatus(SCHEDULE_ALREADY_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledTransactionWithWaitForExpiryFalseLessThen30Mins() {
        final var schedule = "s";
        return hapiTest(
                cryptoCreate(SENDER),
                cryptoCreate(PAYING_ACCOUNT),
                cryptoCreate(RECEIVER).balance(0L),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1L)))
                        .waitForExpiry(false)
                        .expiringIn(ONE_MINUTE),
                getAccountBalance(RECEIVER).hasTinyBars(0L),
                scheduleSign(schedule).alsoSigningWith(SENDER),
                getAccountBalance(RECEIVER).hasTinyBars(1L),
                runWithProvider(ignore -> () -> Optional.of(cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1))))
                        .lasting(90, TimeUnit.SECONDS));
    }

    @HapiTest
    final Stream<DynamicTest> ensureUnExecutedScheduleIsPurgedDuringCi() {
        return hapiTest(
                cryptoCreate(SENDER),
                cryptoCreate(RECEIVER).balance(0L),
                scheduleCreate("toBePurgedWithoutExecution", cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1L)))
                        .waitForExpiry(true)
                        .expiringIn(4L),
                runWithProvider(ignore -> () -> Optional.of(cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1))))
                        .lasting(5, TimeUnit.SECONDS));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledTransactionWithWaitForExpiryFalseMoreThen30Mins() {
        final var schedule = "s";
        return hapiTest(
                cryptoCreate(SENDER),
                cryptoCreate(PAYING_ACCOUNT),
                cryptoCreate(RECEIVER).balance(0L),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1L)))
                        .waitForExpiry(false)
                        .expiringIn(THIRTY_MINUTES * 2),
                getAccountBalance(RECEIVER).hasTinyBars(0L),
                scheduleSign(schedule).alsoSigningWith(SENDER),
                getAccountBalance(RECEIVER).hasTinyBars(1L));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledTriggeredWhenAllKeysHaveSigned() {
        final var schedule = "s";

        return hapiTest(
                cryptoCreate(SENDER),
                cryptoCreate(PAYING_ACCOUNT),
                cryptoCreate(RECEIVER).receiverSigRequired(true).balance(0L),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1L)))
                        .waitForExpiry(false)
                        .expiringIn(THIRTY_MINUTES * 2),
                getAccountBalance(RECEIVER).hasTinyBars(0L),

                // only sign with one of the required keys
                scheduleSign(schedule).alsoSigningWith(SENDER),

                // the balance is not change
                getAccountBalance(RECEIVER).hasTinyBars(0L),

                // sign with the other required key
                scheduleSign(schedule).alsoSigningWith(RECEIVER),

                // the balance is changed
                getAccountBalance(RECEIVER).hasTinyBars(1L));
    }

    @HapiTest
    final Stream<DynamicTest> scheduleSignWithNotNeededSignature() {
        final var schedule = "s";

        return hapiTest(
                cryptoCreate(SENDER),
                cryptoCreate(PAYING_ACCOUNT),
                cryptoCreate("dummy"),
                cryptoCreate(RECEIVER).receiverSigRequired(true).balance(0L),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1L)))
                        .waitForExpiry()
                        .expiringIn(THIRTY_MINUTES),
                scheduleSign(schedule).alsoSigningWith("dummy").hasKnownStatus(NO_NEW_VALID_SIGNATURES));
    }

    @HapiTest
    final Stream<DynamicTest> scheduleSignWithEmptyKey() {
        final var schedule = "s";

        return hapiTest(
                cryptoCreate(SENDER),
                cryptoCreate(PAYING_ACCOUNT),
                cryptoCreate("dummy"),
                cryptoCreate(RECEIVER).receiverSigRequired(true).balance(0L),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1L)))
                        .waitForExpiry()
                        .expiringIn(THIRTY_MINUTES),
                scheduleSign(schedule).alsoSigningWith().hasKnownStatus(NO_NEW_VALID_SIGNATURES));
    }

    @HapiTest
    final Stream<DynamicTest> scheduleSignWithTwoSignatures() {
        final var schedule = "s";

        return hapiTest(
                cryptoCreate(SENDER),
                cryptoCreate(PAYING_ACCOUNT),
                cryptoCreate("dummy"),
                cryptoCreate(RECEIVER).receiverSigRequired(true).balance(0L),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1L)))
                        .waitForExpiry(false)
                        .expiringIn(THIRTY_MINUTES),
                scheduleSign(schedule).alsoSigningWith(RECEIVER),
                scheduleSign(schedule).alsoSigningWith(SENDER),
                cryptoCreate("trigger"),
                getAccountBalance(RECEIVER).hasTinyBars(1L));
    }
}
