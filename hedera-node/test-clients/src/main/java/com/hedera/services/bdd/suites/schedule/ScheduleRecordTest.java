// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.schedule;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.assertions.TransferListAsserts.exactParticipants;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.ADMIN;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.BEGIN;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.CREATION;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.INSOLVENT_PAYER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.PAYER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.PAYING_SENDER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.RECEIVER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SCHEDULE;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SIMPLE_XFER_SCHEDULE;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.TRIGGER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.TWO_SIG_XFER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.UNWILLING_PAYER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.scheduledVersionOf;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_ID_FIELD_NOT_ALLOWED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.TransactionID;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class ScheduleRecordTest {

    @HapiTest
    final Stream<DynamicTest> noFeesChargedIfTriggeredPayerIsUnwilling() {
        return hapiTest(
                cryptoCreate(UNWILLING_PAYER),
                scheduleCreate(
                                SCHEDULE,
                                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1))
                                        .fee(1L))
                        .alsoSigningWith(GENESIS, UNWILLING_PAYER)
                        .via(SIMPLE_XFER_SCHEDULE)
                        // prevent multiple runs of this test causing duplicates
                        .withEntityMemo("" + new SecureRandom().nextLong())
                        .designatingPayer(UNWILLING_PAYER)
                        .savingExpectedScheduledTxnId(),
                getTxnRecord(SIMPLE_XFER_SCHEDULE)
                        .scheduledBy(SCHEDULE)
                        .hasPriority(recordWith()
                                .transfers(exactParticipants(ignore -> Collections.emptyList()))
                                .status(INSUFFICIENT_TX_FEE)));
    }

    @HapiTest
    final Stream<DynamicTest> noFeesChargedIfTriggeredPayerIsInsolvent() {
        return hapiTest(
                cryptoCreate(INSOLVENT_PAYER).balance(0L),
                scheduleCreate(SCHEDULE, cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)))
                        .alsoSigningWith(GENESIS, INSOLVENT_PAYER)
                        .via(SIMPLE_XFER_SCHEDULE)
                        // prevent multiple runs of this test causing duplicates
                        .withEntityMemo("" + new SecureRandom().nextLong())
                        .designatingPayer(INSOLVENT_PAYER)
                        .savingExpectedScheduledTxnId(),
                getTxnRecord(SIMPLE_XFER_SCHEDULE)
                        .scheduledBy(SCHEDULE)
                        .hasPriority(recordWith()
                                .transfers(exactParticipants(ignore -> Collections.emptyList()))
                                .status(INSUFFICIENT_PAYER_BALANCE)));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> canScheduleChunkedMessages() {
        String ofGeneralInterest = "Scotch";
        AtomicReference<TransactionID> initialTxnId = new AtomicReference<>();

        // validation here is checking fees and staking, not message creation on the topic...
        return hapiTest(
                cryptoCreate(PAYING_SENDER).balance(ONE_HUNDRED_HBARS),
                createTopic(ofGeneralInterest),
                withOpContext((spec, opLog) -> {
                    var subOp = usableTxnIdNamed(BEGIN).payerId(PAYING_SENDER);
                    allRunFor(spec, subOp);
                    initialTxnId.set(spec.registry().getTxnId(BEGIN));
                }),
                sourcing(() -> scheduleCreate(
                                "firstChunk",
                                submitMessageTo(ofGeneralInterest)
                                        .chunkInfo(3, 1, scheduledVersionOf(initialTxnId.get())))
                        .txnId(BEGIN)
                        .logged()
                        .signedBy(PAYING_SENDER)),
                getTxnRecord(BEGIN)
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .transfers(exactParticipants(spec -> List.of(
                                        spec.setup().defaultNode(),
                                        spec.setup().fundingAccount(),
                                        spec.setup().stakingRewardAccount(),
                                        spec.setup().nodeRewardAccount(),
                                        spec.registry().getAccountID(PAYING_SENDER)))))
                        .assertingOnlyPriority()
                        .logged(),
                getTxnRecord(BEGIN)
                        .scheduled()
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .transfers(exactParticipants(spec -> List.of(
                                        spec.setup().fundingAccount(),
                                        spec.setup().stakingRewardAccount(),
                                        spec.setup().nodeRewardAccount(),
                                        spec.registry().getAccountID(PAYING_SENDER)))))
                        .logged(),
                scheduleCreate("secondChunk", submitMessageTo(ofGeneralInterest).chunkInfo(3, 2, PAYING_SENDER))
                        .via("end")
                        .logged()
                        .payingWith(PAYING_SENDER),
                getTxnRecord("end")
                        .scheduled()
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .transfers(exactParticipants(spec -> List.of(
                                        spec.setup().fundingAccount(),
                                        spec.setup().stakingRewardAccount(),
                                        spec.setup().nodeRewardAccount(),
                                        spec.registry().getAccountID(PAYING_SENDER)))))
                        .logged(),
                getTopicInfo(ofGeneralInterest).logged().hasSeqNo(2L));
    }

    @HapiTest
    final Stream<DynamicTest> schedulingTxnIdFieldsNotAllowed() {
        return hapiTest(
                usableTxnIdNamed("withScheduled").settingScheduledInappropriately(),
                cryptoCreate("nope").txnId("withScheduled").hasPrecheck(TRANSACTION_ID_FIELD_NOT_ALLOWED));
    }

    @HapiTest
    final Stream<DynamicTest> executionTimeIsAvailable() {
        return hapiTest(
                cryptoCreate(PAYER),
                cryptoCreate(RECEIVER).receiverSigRequired(true).balance(0L),
                scheduleCreate(
                                "tb",
                                cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, 1))
                                        .fee(ONE_HBAR))
                        .savingExpectedScheduledTxnId()
                        .payingWith(PAYER)
                        .via(CREATION),
                scheduleSign("tb").via(TRIGGER).alsoSigningWith(RECEIVER),
                getScheduleInfo("tb").logged().wasExecutedBy(TRIGGER));
    }

    @HapiTest
    final Stream<DynamicTest> deletionTimeIsAvailable() {
        return hapiTest(
                newKeyNamed(ADMIN),
                cryptoCreate(PAYER),
                cryptoCreate(RECEIVER).receiverSigRequired(true).balance(0L),
                scheduleCreate(
                                "ntb",
                                cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, 1))
                                        .fee(ONE_HBAR))
                        .payingWith(PAYER)
                        .adminKey(ADMIN)
                        .via(CREATION),
                scheduleDelete("ntb").via("deletion").signedBy(DEFAULT_PAYER, ADMIN),
                getScheduleInfo("ntb").wasDeletedAtConsensusTimeOf("deletion"));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> allRecordsAreQueryable() {
        return hapiTest(
                cryptoCreate(PAYER),
                cryptoCreate(RECEIVER).receiverSigRequired(true).balance(0L),
                scheduleCreate(
                                TWO_SIG_XFER,
                                cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, 1))
                                        .fee(ONE_HBAR))
                        .logged()
                        .savingExpectedScheduledTxnId()
                        .payingWith(PAYER)
                        .via(CREATION),
                getAccountBalance(RECEIVER).hasTinyBars(0L),
                scheduleSign(TWO_SIG_XFER).via(TRIGGER).alsoSigningWith(RECEIVER),
                getAccountBalance(RECEIVER).hasTinyBars(1L),
                getTxnRecord(TRIGGER),
                getTxnRecord(CREATION),
                getTxnRecord(CREATION).scheduled(),
                getTxnRecord(CREATION).scheduledBy(TWO_SIG_XFER));
    }
}
