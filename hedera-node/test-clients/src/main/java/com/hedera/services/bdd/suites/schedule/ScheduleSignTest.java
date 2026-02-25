// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.schedule;

import static com.hedera.services.bdd.junit.TestTags.NOT_REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.randomUppercase;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.keyFromMutation;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.ADMIN;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.BASIC_XFER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.DEFERRED_XFER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.NEW_SENDER_KEY;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.PAYER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.RANDOM_KEY;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.RECEIVER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SENDER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SOMEBODY;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.TOKEN_A;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.TWO_SIG_XFER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.VALID_SCHEDULED_TXN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_NEW_VALID_SIGNATURES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_ALREADY_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_ALREADY_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_PENDING_EXPIRATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SOME_SIGNATURES_WERE_INVALID;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.keys.ControlForKey;
import com.hedera.services.bdd.spec.keys.OverlappingKeyGenerator;
import com.hederahashgraph.api.proto.java.Key;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class ScheduleSignTest {

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return hapiTest(
                newKeyNamed(ADMIN),
                cryptoCreate(SENDER),
                scheduleCreate(VALID_SCHEDULED_TXN, cryptoTransfer(tinyBarsFromTo(SENDER, FUNDING, 1)))
                        .adminKey(ADMIN),
                submitModified(withSuccessivelyVariedBodyIds(), () -> scheduleSign(VALID_SCHEDULED_TXN)
                        .alsoSigningWith(SENDER)));
    }

    @HapiTest
    final Stream<DynamicTest> signingDeletedSchedulesHasNoEffect() {
        String sender = "X";
        String receiver = "Y";
        String schedule = "Z";
        String adminKey = ADMIN;

        return hapiTest(
                newKeyNamed(adminKey),
                cryptoCreate(sender),
                cryptoCreate(receiver).balance(0L),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(sender, receiver, 1)))
                        .adminKey(adminKey)
                        .payingWith(DEFAULT_PAYER),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleDelete(schedule).signedBy(DEFAULT_PAYER, adminKey),
                scheduleSign(schedule).alsoSigningWith(sender).hasKnownStatus(SCHEDULE_ALREADY_DELETED),
                getAccountBalance(receiver).hasTinyBars(0L));
    }

    @HapiTest
    final Stream<DynamicTest> changeInNestedSigningReqsRespected() {
        var senderShape = threshOf(2, threshOf(1, 3), threshOf(1, 3), threshOf(1, 3));
        var sigOne = senderShape.signedWith(sigs(sigs(OFF, OFF, ON), sigs(OFF, OFF, OFF), sigs(OFF, OFF, OFF)));
        var sigTwo = senderShape.signedWith(sigs(sigs(OFF, OFF, OFF), sigs(ON, ON, ON), sigs(OFF, OFF, OFF)));
        var firstSigThree = senderShape.signedWith(sigs(sigs(OFF, OFF, OFF), sigs(OFF, OFF, OFF), sigs(ON, OFF, OFF)));
        var secondSigThree = senderShape.signedWith(sigs(sigs(OFF, OFF, OFF), sigs(OFF, OFF, OFF), sigs(ON, ON, OFF)));
        String sender = "X";
        String receiver = "Y";
        String schedule = "Z";
        String senderKey = "sKey";

        return hapiTest(
                newKeyNamed(senderKey).shape(senderShape),
                keyFromMutation(NEW_SENDER_KEY, senderKey).changing(this::bumpThirdNestedThresholdSigningReq),
                cryptoCreate(sender).key(senderKey),
                cryptoCreate(receiver).balance(0L),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(sender, receiver, 1)))
                        .payingWith(DEFAULT_PAYER)
                        .alsoSigningWith(sender)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(DEFAULT_PAYER, sender))
                        .sigControl(ControlForKey.forKey(senderKey, sigOne)),
                getAccountBalance(receiver).hasTinyBars(0L),
                cryptoUpdate(sender).key(NEW_SENDER_KEY),
                scheduleSign(schedule)
                        .alsoSigningWith(NEW_SENDER_KEY)
                        .sigControl(forKey(NEW_SENDER_KEY, firstSigThree)),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule)
                        .alsoSigningWith(NEW_SENDER_KEY)
                        .sigControl(forKey(NEW_SENDER_KEY, secondSigThree)),
                getAccountBalance(receiver).hasTinyBars(1L),
                scheduleSign(schedule)
                        .alsoSigningWith(NEW_SENDER_KEY)
                        .sigControl(forKey(NEW_SENDER_KEY, sigTwo))
                        .hasKnownStatus(SCHEDULE_ALREADY_EXECUTED),
                getAccountBalance(receiver).hasTinyBars(1L));
    }

    @HapiTest
    final Stream<DynamicTest> reductionInSigningReqsAllowsTxnToGoThrough() {
        var senderShape = threshOf(2, threshOf(1, 3), threshOf(1, 3), threshOf(2, 3));
        var sigOne = senderShape.signedWith(sigs(sigs(OFF, OFF, ON), sigs(OFF, OFF, OFF), sigs(OFF, OFF, OFF)));
        var sigTwo = senderShape.signedWith(sigs(sigs(OFF, OFF, OFF), sigs(ON, ON, ON), sigs(OFF, OFF, OFF)));
        var firstSigThree = senderShape.signedWith(sigs(sigs(OFF, OFF, OFF), sigs(OFF, OFF, OFF), sigs(ON, OFF, OFF)));
        String sender = "X";
        String receiver = "Y";
        String schedule = "Z";
        String senderKey = "sKey";

        return hapiTest(
                newKeyNamed(senderKey).shape(senderShape),
                keyFromMutation(NEW_SENDER_KEY, senderKey).changing(this::lowerThirdNestedThresholdSigningReq),
                cryptoCreate(sender).key(senderKey),
                cryptoCreate(receiver).balance(0L),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(sender, receiver, 1)))
                        .payingWith(DEFAULT_PAYER)
                        .alsoSigningWith(sender)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(DEFAULT_PAYER, sender))
                        .sigControl(ControlForKey.forKey(senderKey, sigOne)),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule)
                        .alsoSigningWith(NEW_SENDER_KEY)
                        .sigControl(forKey(NEW_SENDER_KEY, firstSigThree)),
                getAccountBalance(receiver).hasTinyBars(0L),
                cryptoUpdate(sender).key(NEW_SENDER_KEY),
                scheduleSign(schedule).via("signTxn"),
                getTxnRecord("signTxn").hasScheduledTransactionId().logged(),
                getAccountBalance(receiver).hasTinyBars(1L),
                scheduleSign(schedule)
                        .alsoSigningWith(NEW_SENDER_KEY)
                        .sigControl(forKey(NEW_SENDER_KEY, sigTwo))
                        .hasKnownStatus(SCHEDULE_ALREADY_EXECUTED),
                getAccountBalance(receiver).hasTinyBars(1L));
    }

    @HapiTest
    final Stream<DynamicTest> reductionInSigningReqsAllowsTxnToGoThroughWithRandomKey() {
        var senderShape = threshOf(2, threshOf(1, 3), threshOf(1, 3), threshOf(2, 3));
        var sigOne = senderShape.signedWith(sigs(sigs(OFF, OFF, ON), sigs(OFF, OFF, OFF), sigs(OFF, OFF, OFF)));
        var sigTwo = senderShape.signedWith(sigs(sigs(OFF, OFF, OFF), sigs(ON, ON, ON), sigs(OFF, OFF, OFF)));
        var firstSigThree = senderShape.signedWith(sigs(sigs(OFF, OFF, OFF), sigs(OFF, OFF, OFF), sigs(ON, OFF, OFF)));
        String sender = "X";
        String receiver = "Y";
        String schedule = "Z";
        String senderKey = "sKey";
        String newSenderKey = NEW_SENDER_KEY;

        return hapiTest(
                newKeyNamed(RANDOM_KEY),
                cryptoCreate("random").key(RANDOM_KEY),
                newKeyNamed(senderKey).shape(senderShape),
                keyFromMutation(newSenderKey, senderKey).changing(this::lowerThirdNestedThresholdSigningReq),
                cryptoCreate(sender).key(senderKey),
                cryptoCreate(receiver).balance(0L),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(sender, receiver, 1)))
                        .payingWith(DEFAULT_PAYER)
                        .alsoSigningWith(sender)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(DEFAULT_PAYER, sender))
                        .sigControl(ControlForKey.forKey(senderKey, sigOne)),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule).alsoSigningWith(newSenderKey).sigControl(forKey(newSenderKey, firstSigThree)),
                getAccountBalance(receiver).hasTinyBars(0L),
                cryptoUpdate(sender).key(newSenderKey),
                scheduleSign(schedule)
                        .signedBy(RANDOM_KEY)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(RANDOM_KEY))
                        .payingWith("random"),
                getAccountBalance(receiver).hasTinyBars(1L),
                scheduleSign(schedule)
                        .alsoSigningWith(newSenderKey)
                        .sigControl(forKey(newSenderKey, sigTwo))
                        .hasKnownStatus(SCHEDULE_ALREADY_EXECUTED),
                getAccountBalance(receiver).hasTinyBars(1L));
    }

    @HapiTest
    final Stream<DynamicTest> nestedSigningReqsWorkAsExpected() {
        var senderShape = threshOf(2, threshOf(1, 3), threshOf(1, 3), threshOf(1, 3));
        var sigOne = senderShape.signedWith(sigs(sigs(OFF, OFF, ON), sigs(OFF, OFF, OFF), sigs(OFF, OFF, OFF)));
        var sigTwo = senderShape.signedWith(sigs(sigs(OFF, OFF, OFF), sigs(OFF, ON, OFF), sigs(OFF, OFF, OFF)));
        var sigThree = senderShape.signedWith(sigs(sigs(OFF, OFF, OFF), sigs(OFF, OFF, OFF), sigs(ON, OFF, OFF)));
        String sender = "X";
        String receiver = "Y";
        String schedule = "Z";
        String senderKey = "sKey";

        return hapiTest(
                newKeyNamed(senderKey).shape(senderShape),
                cryptoCreate(sender).key(senderKey),
                cryptoCreate(receiver).balance(0L),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(sender, receiver, 1)))
                        .payingWith(DEFAULT_PAYER)
                        .alsoSigningWith(sender)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(DEFAULT_PAYER, senderKey))
                        .sigControl(ControlForKey.forKey(senderKey, sigOne)),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule).alsoSigningWith(senderKey).sigControl(forKey(senderKey, sigTwo)),
                getAccountBalance(receiver).hasTinyBars(1L),
                scheduleSign(schedule)
                        .alsoSigningWith(senderKey)
                        .sigControl(forKey(senderKey, sigThree))
                        .hasKnownStatus(SCHEDULE_ALREADY_EXECUTED),
                getAccountBalance(receiver).hasTinyBars(1L));
    }

    @HapiTest
    final Stream<DynamicTest> receiverSigRequiredNotConfusedByOrder() {
        var senderShape = threshOf(1, 3);
        var sigOne = senderShape.signedWith(sigs(ON, OFF, OFF));
        var sigTwo = senderShape.signedWith(sigs(OFF, ON, OFF));
        var sigThree = senderShape.signedWith(sigs(OFF, OFF, ON));
        String sender = "X";
        String receiver = "Y";
        String schedule = "Z";
        String senderKey = "sKey";

        return hapiTest(
                newKeyNamed(senderKey).shape(senderShape),
                cryptoCreate(sender).key(senderKey),
                cryptoCreate(receiver).balance(0L).receiverSigRequired(true),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(sender, receiver, 1)))
                        .payingWith(DEFAULT_PAYER),
                scheduleSign(schedule).alsoSigningWith(receiver),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule).alsoSigningWith(senderKey).sigControl(forKey(senderKey, sigOne)),
                getAccountBalance(receiver).hasTinyBars(1L),
                scheduleSign(schedule)
                        .alsoSigningWith(senderKey)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(DEFAULT_PAYER, senderKey))
                        .sigControl(forKey(senderKey, sigTwo))
                        .hasKnownStatus(SCHEDULE_ALREADY_EXECUTED),
                getAccountBalance(receiver).hasTinyBars(1L),
                scheduleSign(schedule)
                        .alsoSigningWith(senderKey)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(DEFAULT_PAYER, senderKey))
                        .sigControl(forKey(senderKey, sigThree))
                        .hasKnownStatus(SCHEDULE_ALREADY_EXECUTED),
                getAccountBalance(receiver).hasTinyBars(1L));
    }

    @HapiTest
    final Stream<DynamicTest> receiverSigRequiredNotConfusedByMultiSigSender() {
        var senderShape = threshOf(1, 3);
        var sigOne = senderShape.signedWith(sigs(ON, OFF, OFF));
        var sigTwo = senderShape.signedWith(sigs(OFF, ON, OFF));
        var sigThree = senderShape.signedWith(sigs(OFF, OFF, ON));
        String sender = "X";
        String receiver = "Y";
        String schedule = "Z";
        String senderKey = "sKey";

        return hapiTest(
                newKeyNamed(senderKey).shape(senderShape),
                cryptoCreate(sender).key(senderKey),
                cryptoCreate(receiver).balance(0L).receiverSigRequired(true),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(sender, receiver, 1)))
                        .payingWith(DEFAULT_PAYER),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule).alsoSigningWith(senderKey).sigControl(forKey(senderKey, sigOne)),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule).alsoSigningWith(senderKey).sigControl(forKey(senderKey, sigTwo)),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule).alsoSigningWith(receiver),
                getAccountBalance(receiver).hasTinyBars(1L),
                scheduleSign(schedule).alsoSigningWith(receiver).hasKnownStatus(SCHEDULE_ALREADY_EXECUTED),
                getAccountBalance(receiver).hasTinyBars(1),
                scheduleSign(schedule)
                        .alsoSigningWith(senderKey)
                        .sigControl(forKey(senderKey, sigThree))
                        .hasKnownStatus(SCHEDULE_ALREADY_EXECUTED),
                getAccountBalance(receiver).hasTinyBars(1L));
    }

    @HapiTest
    final Stream<DynamicTest> receiverSigRequiredUpdateIsRecognized() {
        var senderShape = threshOf(2, 3);
        var sigOne = senderShape.signedWith(sigs(ON, OFF, OFF));
        var sigTwo = senderShape.signedWith(sigs(OFF, ON, OFF));
        var sigThree = senderShape.signedWith(sigs(OFF, OFF, ON));
        String sender = "X";
        String receiver = "Y";
        String schedule = "Z";
        String senderKey = "sKey";

        return hapiTest(
                newKeyNamed(senderKey).shape(senderShape),
                cryptoCreate(sender).key(senderKey),
                cryptoCreate(receiver).balance(0L),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(sender, receiver, 1)))
                        .payingWith(DEFAULT_PAYER)
                        .alsoSigningWith(sender)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(DEFAULT_PAYER, sender))
                        .sigControl(forKey(senderKey, sigOne)),
                getAccountBalance(receiver).hasTinyBars(0L),
                cryptoUpdate(receiver).receiverSigRequired(true),
                scheduleSign(schedule).alsoSigningWith(senderKey).sigControl(forKey(senderKey, sigTwo)),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule).alsoSigningWith(receiver),
                getAccountBalance(receiver).hasTinyBars(1),
                scheduleSign(schedule)
                        .alsoSigningWith(senderKey)
                        .sigControl(forKey(senderKey, sigTwo))
                        .hasKnownStatus(SCHEDULE_ALREADY_EXECUTED),
                getAccountBalance(receiver).hasTinyBars(1),
                scheduleSign(schedule)
                        .alsoSigningWith(senderKey)
                        .sigControl(forKey(senderKey, sigThree))
                        .hasKnownStatus(SCHEDULE_ALREADY_EXECUTED),
                getAccountBalance(receiver).hasTinyBars(1));
    }

    @HapiTest
    final Stream<DynamicTest> scheduleAlreadyExecutedOnCreateDoesntRepeatTransaction() {
        var senderShape = threshOf(1, 3);
        var sigOne = senderShape.signedWith(sigs(ON, OFF, OFF));
        var sigTwo = senderShape.signedWith(sigs(OFF, ON, OFF));
        var sigThree = senderShape.signedWith(sigs(OFF, OFF, ON));
        String sender = "X";
        String receiver = "Y";
        String schedule = "Z";
        String senderKey = "sKey";

        return hapiTest(
                newKeyNamed(senderKey).shape(senderShape),
                cryptoCreate(sender).key(senderKey),
                cryptoCreate(receiver).balance(0L),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(sender, receiver, 1)))
                        .memo(randomUppercase(100))
                        .payingWith(sender)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(sender))
                        .sigControl(forKey(sender, sigOne)),
                getAccountBalance(receiver).hasTinyBars(1L),
                scheduleSign(schedule)
                        .alsoSigningWith(senderKey)
                        .sigControl(forKey(senderKey, sigTwo))
                        .hasKnownStatus(SCHEDULE_ALREADY_EXECUTED),
                getAccountBalance(receiver).hasTinyBars(1),
                scheduleSign(schedule)
                        .alsoSigningWith(senderKey)
                        .sigControl(forKey(senderKey, sigThree))
                        .hasKnownStatus(SCHEDULE_ALREADY_EXECUTED),
                getAccountBalance(receiver).hasTinyBars(1));
    }

    @HapiTest
    final Stream<DynamicTest> scheduleAlreadyExecutedDoesntRepeatTransaction() {
        var senderShape = threshOf(2, 3);
        var sigOne = senderShape.signedWith(sigs(ON, OFF, OFF));
        var sigTwo = senderShape.signedWith(sigs(OFF, ON, OFF));
        var sigThree = senderShape.signedWith(sigs(OFF, OFF, ON));
        String sender = "X";
        String receiver = "Y";
        String firstSchedule = "Z";
        String senderKey = "sKey";

        return hapiTest(
                newKeyNamed(senderKey).shape(senderShape),
                cryptoCreate(sender).balance(101L).key(senderKey),
                cryptoCreate(receiver),
                scheduleCreate(firstSchedule, cryptoTransfer(tinyBarsFromTo(sender, receiver, 1)))
                        .memo(randomUppercase(100))
                        .designatingPayer(DEFAULT_PAYER),
                getAccountBalance(sender).hasTinyBars(101),
                scheduleSign(firstSchedule).alsoSigningWith(senderKey).sigControl(forKey(senderKey, sigOne)),
                getAccountBalance(sender).hasTinyBars(101),
                scheduleSign(firstSchedule).alsoSigningWith(senderKey).sigControl(forKey(senderKey, sigTwo)),
                getAccountBalance(sender).hasTinyBars(100),
                scheduleSign(firstSchedule)
                        .alsoSigningWith(senderKey)
                        .sigControl(forKey(senderKey, sigThree))
                        .hasKnownStatus(SCHEDULE_ALREADY_EXECUTED),
                getAccountBalance(sender).hasTinyBars(100));
    }

    @HapiTest
    final Stream<DynamicTest> basicSignatureCollectionWorks() {
        var txnBody = cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1));

        return hapiTest(
                cryptoCreate(SENDER),
                cryptoCreate(RECEIVER).receiverSigRequired(true),
                scheduleCreate(BASIC_XFER, txnBody),
                scheduleSign(BASIC_XFER).alsoSigningWith(RECEIVER),
                getScheduleInfo(BASIC_XFER).hasSignatories(DEFAULT_PAYER, RECEIVER));
    }

    @HapiTest
    final Stream<DynamicTest> signalsIrrelevantSig() {
        var txnBody = cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1));

        return hapiTest(
                cryptoCreate(SENDER),
                cryptoCreate(RECEIVER),
                newKeyNamed("somebodyelse"),
                scheduleCreate(BASIC_XFER, txnBody),
                scheduleSign(BASIC_XFER)
                        .alsoSigningWith("somebodyelse")
                        .hasKnownStatusFrom(NO_NEW_VALID_SIGNATURES, SOME_SIGNATURES_WERE_INVALID));
    }

    @HapiTest
    final Stream<DynamicTest> signalsIrrelevantSigEvenAfterLinkedEntityUpdate() {
        var txnBody = mintToken(TOKEN_A, 50000000L);

        return hapiTest(
                newKeyNamed(ADMIN),
                newKeyNamed("mint"),
                newKeyNamed("newMint"),
                tokenCreate(TOKEN_A).adminKey(ADMIN).supplyKey("mint"),
                scheduleCreate("tokenMintScheduled", txnBody),
                tokenUpdate(TOKEN_A).supplyKey("newMint").signedByPayerAnd(ADMIN),
                scheduleSign("tokenMintScheduled")
                        .alsoSigningWith("mint")
                        /* In the rare, but possible, case that the the mint and newMint keys overlap
                         * in their first byte (and that byte is not shared by the DEFAULT_PAYER),
                         * we will get SOME_SIGNATURES_WERE_INVALID instead of NO_NEW_VALID_SIGNATURES.
                         *
                         * So we need this to stabilize CI. But if just testing locally, you may
                         * only use .hasKnownStatus(NO_NEW_VALID_SIGNATURES) and it will pass
                         * >99.99% of the time. */
                        .hasKnownStatusFrom(NO_NEW_VALID_SIGNATURES, SOME_SIGNATURES_WERE_INVALID));
    }

    @HapiTest
    final Stream<DynamicTest> addingSignaturesToNonExistingTxFails() {

        return hapiTest(
                cryptoCreate(SENDER),
                newKeyNamed(SOMEBODY),
                scheduleSign("0.0.123321")
                        .fee(ONE_HBAR)
                        .alsoSigningWith(SOMEBODY, SENDER)
                        .hasPrecheckFrom(OK, INVALID_SCHEDULE_ID)
                        .hasKnownStatus(INVALID_SCHEDULE_ID));
    }

    @HapiTest
    final Stream<DynamicTest> triggersUponFinishingPayerSig() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HBAR),
                cryptoCreate(SENDER).balance(1L),
                cryptoCreate(RECEIVER).balance(0L).receiverSigRequired(true),
                scheduleCreate(
                                "threeSigXfer",
                                cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1))
                                        .fee(ONE_HBAR))
                        .designatingPayer(PAYER)
                        .alsoSigningWith(SENDER, RECEIVER),
                getAccountBalance(RECEIVER).hasTinyBars(0L),
                scheduleSign("threeSigXfer").alsoSigningWith(PAYER),
                getAccountBalance(RECEIVER).hasTinyBars(1L));
    }

    @HapiTest
    final Stream<DynamicTest> triggersUponAdditionalNeededSig() {
        return hapiTest(
                cryptoCreate(SENDER).balance(1L),
                cryptoCreate(RECEIVER).balance(0L).receiverSigRequired(true),
                scheduleCreate(
                                TWO_SIG_XFER,
                                cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1))
                                        .fee(ONE_HBAR))
                        .alsoSigningWith(SENDER),
                getAccountBalance(RECEIVER).hasTinyBars(0L),
                scheduleSign(TWO_SIG_XFER).alsoSigningWith(RECEIVER),
                getAccountBalance(RECEIVER).hasTinyBars(1L));
    }

    @HapiTest
    @Tag(NOT_REPEATABLE)
    final Stream<DynamicTest> okIfAdminKeyOverlapsWithActiveScheduleKey() {
        var keyGen = OverlappingKeyGenerator.withAtLeastOneOverlappingByte(2);
        var adminKey = "adminKey";
        var scheduledTxnKey = "scheduledTxnKey";

        return hapiTest(
                newKeyNamed(adminKey).generator(keyGen).logged(),
                newKeyNamed(scheduledTxnKey).generator(keyGen).logged(),
                cryptoCreate(SENDER).key(scheduledTxnKey).balance(1L),
                scheduleCreate(DEFERRED_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, ADDRESS_BOOK_CONTROL, 1)))
                        .adminKey(adminKey));
    }

    @HapiTest
    @Tag(NOT_REPEATABLE)
    final Stream<DynamicTest> overlappingKeysTreatedAsExpected() {
        var keyGen = OverlappingKeyGenerator.withAtLeastOneOverlappingByte(2);

        return hapiTest(
                newKeyNamed("aKey").generator(keyGen),
                newKeyNamed("bKey").generator(keyGen),
                newKeyNamed("cKey"),
                cryptoCreate("aSender").key("aKey").balance(1L),
                cryptoCreate("cSender").key("cKey").balance(1L),
                balanceSnapshot("before", ADDRESS_BOOK_CONTROL),
                scheduleCreate(
                        DEFERRED_XFER,
                        cryptoTransfer(
                                tinyBarsFromTo("aSender", ADDRESS_BOOK_CONTROL, 1),
                                tinyBarsFromTo("cSender", ADDRESS_BOOK_CONTROL, 1))),
                scheduleSign(DEFERRED_XFER).alsoSigningWith("aKey"),
                scheduleSign(DEFERRED_XFER).alsoSigningWith("aKey").hasKnownStatus(NO_NEW_VALID_SIGNATURES),
                scheduleSign(DEFERRED_XFER).alsoSigningWith("aKey", "bKey").hasKnownStatus(NO_NEW_VALID_SIGNATURES),
                scheduleSign(DEFERRED_XFER)
                        .alsoSigningWith("bKey")
                        /* In the rare, but possible, case that the overlapping byte shared by aKey
                         * and bKey is _also_ shared by the DEFAULT_PAYER, the bKey prefix in the sig
                         * map will probably not collide with aKey any more, and we will get
                         * NO_NEW_VALID_SIGNATURES instead of SOME_SIGNATURES_WERE_INVALID.
                         *
                         * So we need this to stabilize CI. But if just testing locally, you may
                         * only use .hasKnownStatus(SOME_SIGNATURES_WERE_INVALID) and it will pass
                         * >99.99% of the time. */
                        .hasKnownStatusFrom(SOME_SIGNATURES_WERE_INVALID, NO_NEW_VALID_SIGNATURES),
                scheduleSign(DEFERRED_XFER).alsoSigningWith("aKey", "bKey", "cKey"),
                getAccountBalance(ADDRESS_BOOK_CONTROL).hasTinyBars(changeFromSnapshot("before", +2)));
    }

    @HapiTest
    final Stream<DynamicTest> retestsActivationOnSignWithEmptySigMap() {
        return hapiTest(
                newKeyNamed("a"),
                newKeyNamed("b"),
                newKeyListNamed("ab", List.of("a", "b")),
                newKeyNamed(ADMIN),
                cryptoCreate(SENDER).key("ab").balance(665L),
                scheduleCreate(
                                "deferredFall",
                                cryptoTransfer(tinyBarsFromTo(SENDER, FUNDING, 1))
                                        .fee(ONE_HBAR))
                        .alsoSigningWith("a"),
                getAccountBalance(SENDER).hasTinyBars(665L),
                cryptoUpdate(SENDER).key("a"),
                scheduleSign("deferredFall").alsoSigningWith(),
                getAccountBalance(SENDER).hasTinyBars(664L));
    }

    @LeakyHapiTest(overrides = {"ledger.schedule.txExpiryTimeSecs", "scheduling.longTermEnabled"})
    final Stream<DynamicTest> signFailsDueToDeletedExpiration() {
        return hapiTest(
                overriding("scheduling.longTermEnabled", "false"),
                cryptoCreate(SENDER).balance(1L),
                cryptoCreate(RECEIVER).balance(0L).receiverSigRequired(true),
                overriding("ledger.schedule.txExpiryTimeSecs", "1"),
                scheduleCreate(TWO_SIG_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1)))
                        .alsoSigningWith(SENDER),
                getAccountBalance(RECEIVER).hasTinyBars(0L),
                sleepFor(2000),
                scheduleSign(TWO_SIG_XFER)
                        .alsoSigningWith(RECEIVER)
                        .hasPrecheckFrom(OK, INVALID_SCHEDULE_ID)
                        .hasKnownStatusFrom(INVALID_SCHEDULE_ID, SCHEDULE_PENDING_EXPIRATION),
                sleepFor(2000),
                scheduleSign(TWO_SIG_XFER)
                        .alsoSigningWith(RECEIVER)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasPrecheckFrom(OK, INVALID_SCHEDULE_ID)
                        .hasKnownStatusFrom(INVALID_SCHEDULE_ID, SCHEDULE_PENDING_EXPIRATION),
                scheduleSign(TWO_SIG_XFER)
                        .alsoSigningWith(RECEIVER)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasPrecheckFrom(OK, INVALID_SCHEDULE_ID)
                        .hasKnownStatusFrom(INVALID_SCHEDULE_ID),
                getScheduleInfo(TWO_SIG_XFER).hasCostAnswerPrecheck(INVALID_SCHEDULE_ID));
    }

    private Key bumpThirdNestedThresholdSigningReq(Key source) {
        var newKey = source.getThresholdKey().getKeys().getKeys(2).toBuilder();
        newKey.setThresholdKey(newKey.getThresholdKeyBuilder().setThreshold(2));
        var newKeyList = source.getThresholdKey().getKeys().toBuilder().setKeys(2, newKey);
        var mutation = source.toBuilder()
                .setThresholdKey(source.getThresholdKey().toBuilder().setKeys(newKeyList))
                .build();
        return mutation;
    }

    private Key lowerThirdNestedThresholdSigningReq(Key source) {
        var newKey = source.getThresholdKey().getKeys().getKeys(2).toBuilder();
        newKey.setThresholdKey(newKey.getThresholdKeyBuilder().setThreshold(1));
        var newKeyList = source.getThresholdKey().getKeys().toBuilder().setKeys(2, newKey);
        var mutation = source.toBuilder()
                .setThresholdKey(source.getThresholdKey().toBuilder().setKeys(newKeyList))
                .build();
        return mutation;
    }
}
