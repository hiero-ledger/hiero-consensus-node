// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.keyFromMutation;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THOUSAND_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.ADMIN;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.BASIC_XFER;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.BEFORE;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.CREATE_TXN;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.CREATION;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.DEFERRED_CREATION;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.DEFERRED_FALL;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.DEFERRED_XFER;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.EXTRA_KEY;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.NEW_SENDER_KEY;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.PAYER;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.SENDER_TXN;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.SHARED_KEY;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.THREE_SIG_XFER;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.TOKEN_A;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.TWO_SIG_XFER;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.triggerSchedule;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_NEW_VALID_SIGNATURES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SOME_SIGNATURES_WERE_INVALID;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.ControlForKey;
import com.hedera.services.bdd.spec.keys.OverlappingKeyGenerator;
import com.hederahashgraph.api.proto.java.Key;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Order(5)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
public class RepeatableScheduleLongTermSignTest {

    private static final String RECEIVER = "receiver";
    private static final String SENDER = "sender";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        // override and preserve old values
        lifecycle.overrideInClass(Map.of(
                "scheduling.longTermEnabled",
                "true",
                "scheduling.whitelist",
                "ConsensusSubmitMessage,CryptoTransfer,TokenMint,TokenBurn,"
                        + "CryptoCreate,CryptoUpdate,FileUpdate,SystemDelete,SystemUndelete,"
                        + "Freeze,ContractCall,ContractCreate,ContractUpdate,ContractDelete"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> reductionInSigningReqsAllowsTxnToGoThrough() {
        var senderShape = threshOf(2, threshOf(1, 3), threshOf(1, 3), threshOf(2, 3));
        var sigOne = senderShape.signedWith(sigs(sigs(OFF, OFF, ON), sigs(OFF, OFF, OFF), sigs(OFF, OFF, OFF)));
        var sigTwo = senderShape.signedWith(sigs(sigs(OFF, OFF, OFF), sigs(ON, ON, ON), sigs(OFF, OFF, OFF)));
        var firstSigThree = senderShape.signedWith(sigs(sigs(OFF, OFF, OFF), sigs(OFF, OFF, OFF), sigs(ON, OFF, OFF)));
        String sender = "X";
        String receiver = "Y";
        String schedule = "Z";
        String senderKey = "sKey";

        return hapiTest(flattened(
                newKeyNamed(senderKey).shape(senderShape),
                keyFromMutation(NEW_SENDER_KEY, senderKey).changing(this::lowerThirdNestedThresholdSigningReq),
                cryptoCreate(sender).key(senderKey).via(SENDER_TXN),
                cryptoCreate(receiver).balance(0L),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(sender, receiver, 1)))
                        .payingWith(DEFAULT_PAYER)
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, 7)
                        .recordingScheduledTxn()
                        .alsoSigningWith(sender)
                        .sigControl(ControlForKey.forKey(senderKey, sigOne)),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule)
                        .alsoSigningWith(NEW_SENDER_KEY)
                        .sigControl(forKey(NEW_SENDER_KEY, firstSigThree)),
                getAccountBalance(receiver).hasTinyBars(0L),
                cryptoUpdate(sender).key(NEW_SENDER_KEY),
                scheduleSign(schedule).hasKnownStatus(NO_NEW_VALID_SIGNATURES),
                getAccountBalance(receiver).hasTinyBars(0L),
                getScheduleInfo(schedule)
                        .hasScheduleId(schedule)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, 7)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(schedule, 8),
                scheduleSign(schedule)
                        .alsoSigningWith(NEW_SENDER_KEY)
                        .sigControl(forKey(NEW_SENDER_KEY, sigTwo))
                        .hasKnownStatus(INVALID_SCHEDULE_ID),
                getAccountBalance(receiver).hasTinyBars(1L)));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> reductionInSigningReqsAllowsTxnToGoThroughAtExpiryWithWaitForExpiry() {
        long scheduleLifetime = 10;
        var senderShape = threshOf(2, threshOf(1, 3), threshOf(1, 3), threshOf(2, 3));
        var sigOne = senderShape.signedWith(sigs(sigs(OFF, OFF, ON), sigs(OFF, OFF, OFF), sigs(OFF, OFF, OFF)));
        var firstSigThree = senderShape.signedWith(sigs(sigs(OFF, OFF, OFF), sigs(OFF, OFF, OFF), sigs(ON, OFF, OFF)));
        String sender = "X";
        String receiver = "Y";
        String schedule = "Z";
        String senderKey = "sKey";

        return hapiTest(flattened(
                newKeyNamed(senderKey).shape(senderShape),
                keyFromMutation(NEW_SENDER_KEY, senderKey).changing(this::lowerThirdNestedThresholdSigningReq),
                cryptoCreate(sender).key(senderKey).via(SENDER_TXN),
                cryptoCreate(receiver).balance(0L),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(sender, receiver, 1)))
                        .payingWith(DEFAULT_PAYER)
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, scheduleLifetime)
                        .recordingScheduledTxn()
                        .alsoSigningWith(sender)
                        .sigControl(ControlForKey.forKey(senderKey, sigOne)),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule)
                        .alsoSigningWith(NEW_SENDER_KEY)
                        .sigControl(forKey(NEW_SENDER_KEY, firstSigThree)),
                getAccountBalance(receiver).hasTinyBars(0L),
                cryptoUpdate(sender).key(NEW_SENDER_KEY),
                getAccountBalance(receiver).hasTinyBars(0L),
                getScheduleInfo(schedule)
                        .hasScheduleId(schedule)
                        .hasWaitForExpiry(true)
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, scheduleLifetime)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(schedule, scheduleLifetime),
                getAccountBalance(receiver).hasTinyBars(1L)));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> nestedSigningReqsWorkAsExpected() {
        var senderShape = threshOf(2, threshOf(1, 3), threshOf(1, 3), threshOf(1, 3));
        var sigOne = senderShape.signedWith(sigs(sigs(OFF, OFF, ON), sigs(OFF, OFF, OFF), sigs(OFF, OFF, OFF)));
        var sigTwo = senderShape.signedWith(sigs(sigs(OFF, OFF, OFF), sigs(OFF, ON, OFF), sigs(OFF, OFF, OFF)));
        String sender = "X";
        String receiver = "Y";
        String schedule = "Z";
        String senderKey = "sKey";

        return hapiTest(flattened(
                newKeyNamed(senderKey).shape(senderShape),
                cryptoCreate(sender).key(senderKey).via(SENDER_TXN),
                cryptoCreate(receiver).balance(0L),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(sender, receiver, 1)))
                        .payingWith(DEFAULT_PAYER)
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, 4)
                        .recordingScheduledTxn()
                        .alsoSigningWith(sender)
                        .sigControl(ControlForKey.forKey(senderKey, sigOne)),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule).alsoSigningWith(senderKey).sigControl(forKey(senderKey, sigTwo)),
                getAccountBalance(receiver).hasTinyBars(0L),
                getScheduleInfo(schedule)
                        .hasScheduleId(schedule)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, 4)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(schedule),
                getAccountBalance(receiver).hasTinyBars(1L)));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> receiverSigRequiredNotConfusedByOrder() {
        long scheduleLifetime = 10;
        var senderShape = threshOf(1, 3);
        var sigOne = senderShape.signedWith(sigs(ON, OFF, OFF));
        String sender = "X";
        String receiver = "Y";
        String schedule = "Z";
        String senderKey = "sKey";

        return hapiTest(flattened(
                newKeyNamed(senderKey).shape(senderShape),
                cryptoCreate(sender).key(senderKey).via(SENDER_TXN),
                cryptoCreate(receiver).balance(0L).receiverSigRequired(true),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(sender, receiver, 1)))
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, scheduleLifetime)
                        .recordingScheduledTxn()
                        .payingWith(DEFAULT_PAYER),
                scheduleSign(schedule).alsoSigningWith(receiver),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule).alsoSigningWith(senderKey).sigControl(forKey(senderKey, sigOne)),
                getAccountBalance(receiver).hasTinyBars(0L),
                getScheduleInfo(schedule)
                        .hasScheduleId(schedule)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, scheduleLifetime)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(schedule, scheduleLifetime),
                getAccountBalance(receiver).hasTinyBars(1L)));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> extraSigsDontMatterAtExpiry() {
        long scheduleLifetime = 20;
        var senderShape = threshOf(1, 3);
        var sigOne = senderShape.signedWith(sigs(ON, OFF, OFF));
        var sigTwo = senderShape.signedWith(sigs(OFF, ON, OFF));
        var sigThree = senderShape.signedWith(sigs(OFF, OFF, ON));
        String sender = "X";
        String receiver = "Y";
        String schedule = "Z";
        String senderKey = "sKey";

        return hapiTest(flattened(
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).payingWith(GENESIS),
                newKeyNamed(senderKey).shape(senderShape),
                newKeyNamed(EXTRA_KEY),
                cryptoCreate(sender).key(senderKey).via(SENDER_TXN),
                cryptoCreate(receiver).balance(0L).receiverSigRequired(true),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(sender, receiver, 1)))
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, scheduleLifetime)
                        .recordingScheduledTxn()
                        .payingWith(PAYER),
                scheduleSign(schedule).payingWith(PAYER).fee(THOUSAND_HBAR).alsoSigningWith(receiver),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule)
                        .payingWith(PAYER)
                        .fee(THOUSAND_HBAR)
                        .alsoSigningWith(senderKey)
                        .sigControl(forKey(senderKey, sigOne)),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule)
                        .payingWith(PAYER)
                        .fee(THOUSAND_HBAR)
                        .alsoSigningWith(senderKey)
                        .sigControl(forKey(senderKey, sigTwo)),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule)
                        .payingWith(PAYER)
                        .fee(THOUSAND_HBAR)
                        .alsoSigningWith(EXTRA_KEY)
                        .hasKnownStatus(NO_NEW_VALID_SIGNATURES),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule)
                        .payingWith(PAYER)
                        .fee(THOUSAND_HBAR)
                        .alsoSigningWith(senderKey)
                        .sigControl(forKey(senderKey, sigTwo))
                        .hasKnownStatus(NO_NEW_VALID_SIGNATURES),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule)
                        .payingWith(PAYER)
                        .fee(THOUSAND_HBAR)
                        .alsoSigningWith(senderKey)
                        .sigControl(forKey(senderKey, sigThree)),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule)
                        .payingWith(PAYER)
                        .fee(THOUSAND_HBAR)
                        .alsoSigningWith(senderKey)
                        .sigControl(forKey(senderKey, sigTwo))
                        .hasKnownStatus(NO_NEW_VALID_SIGNATURES),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule)
                        .payingWith(PAYER)
                        .fee(THOUSAND_HBAR)
                        .alsoSigningWith(EXTRA_KEY)
                        .hasKnownStatus(NO_NEW_VALID_SIGNATURES),
                getAccountBalance(receiver).hasTinyBars(0L),
                scheduleSign(schedule)
                        .payingWith(PAYER)
                        .fee(THOUSAND_HBAR)
                        .alsoSigningWith(senderKey)
                        .sigControl(forKey(senderKey, sigOne))
                        .hasKnownStatus(NO_NEW_VALID_SIGNATURES),
                getAccountBalance(receiver).hasTinyBars(0L),
                getScheduleInfo(schedule)
                        .hasScheduleId(schedule)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, scheduleLifetime)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(schedule, scheduleLifetime),
                getAccountBalance(receiver).hasTinyBars(1L)));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> basicSignatureCollectionWorks() {
        var txnBody = cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1));

        return hapiTest(
                cryptoCreate(SENDER).via(SENDER_TXN),
                cryptoCreate(RECEIVER).receiverSigRequired(true),
                scheduleCreate(BASIC_XFER, txnBody)
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, 4)
                        .payingWith(SENDER),
                scheduleSign(BASIC_XFER).alsoSigningWith(RECEIVER),
                getScheduleInfo(BASIC_XFER).hasSignatories(RECEIVER, SENDER),
                // note: the sleepFor and cryptoCreate operations are added only to clear the schedule before
                // the next state. This was needed because an edge case in the BaseTranslator occur.
                // When scheduleCreate trigger the schedules execution scheduleRef field is not the correct one.
                sleepFor(6000),
                cryptoCreate("foo"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> signalsIrrelevantSig() {
        var txnBody = cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1));

        return hapiTest(
                cryptoCreate(SENDER).via(SENDER_TXN),
                cryptoCreate(RECEIVER),
                newKeyNamed("somebodyelse"),
                scheduleCreate(BASIC_XFER, txnBody).waitForExpiry().withRelativeExpiry(SENDER_TXN, 4),
                scheduleSign(BASIC_XFER)
                        .alsoSigningWith("somebodyelse")
                        .hasKnownStatusFrom(NO_NEW_VALID_SIGNATURES, SOME_SIGNATURES_WERE_INVALID),
                // note: the sleepFor and cryptoCreate operations are added only to clear the schedule before
                // the next state. This was needed because an edge case in the BaseTranslator occur.
                // When scheduleCreate trigger the schedules execution scheduleRef field is not the correct one.
                sleepFor(6000),
                cryptoCreate("foo"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> signalsIrrelevantSigEvenAfterLinkedEntityUpdate() {
        var txnBody = mintToken(TOKEN_A, 50000000L);

        return hapiTest(
                newKeyNamed(ADMIN),
                newKeyNamed("mint"),
                newKeyNamed("newMint"),
                tokenCreate(TOKEN_A).adminKey(ADMIN).supplyKey("mint").via(CREATE_TXN),
                scheduleCreate("tokenMintScheduled", txnBody).waitForExpiry().withRelativeExpiry(CREATE_TXN, 4),
                tokenUpdate(TOKEN_A).supplyKey("newMint"),
                scheduleSign("tokenMintScheduled")
                        .alsoSigningWith("mint")
                        /* In the rare, but possible, case that the the mint and newMint keys overlap
                         * in their first byte (and that byte is not shared by the DEFAULT_PAYER),
                         * we will get SOME_SIGNATURES_WERE_INVALID instead of NO_NEW_VALID_SIGNATURES.
                         *
                         * So we need this to stabilize CI. But if just testing locally, you may
                         * only use .hasKnownStatus(NO_NEW_VALID_SIGNATURES) and it will pass
                         * >99.99% of the time. */
                        .hasKnownStatusFrom(NO_NEW_VALID_SIGNATURES, SOME_SIGNATURES_WERE_INVALID),

                // note: the sleepFor and cryptoCreate operations are added only to clear the schedule before
                // the next state. This was needed because an edge case in the BaseTranslator occur.
                // When scheduleCreate trigger the schedules execution scheduleRef field is not the correct one.
                sleepFor(6000),
                cryptoCreate("foo"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    public Stream<DynamicTest> triggersUponFinishingPayerSig() {
        return hapiTest(flattened(
                cryptoCreate(PAYER).balance(ONE_HBAR),
                cryptoCreate(SENDER).balance(1L).via(SENDER_TXN),
                cryptoCreate(RECEIVER).balance(0L).receiverSigRequired(true),
                scheduleCreate(
                                THREE_SIG_XFER,
                                cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1))
                                        .fee(ONE_HBAR))
                        .designatingPayer(PAYER)
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, 4)
                        .recordingScheduledTxn()
                        .alsoSigningWith(SENDER, RECEIVER),
                getAccountBalance(RECEIVER).hasTinyBars(0L),
                scheduleSign(THREE_SIG_XFER).alsoSigningWith(PAYER),
                getAccountBalance(RECEIVER).hasTinyBars(0L),
                getScheduleInfo(THREE_SIG_XFER)
                        .hasScheduleId(THREE_SIG_XFER)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, 4)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(THREE_SIG_XFER),
                getAccountBalance(RECEIVER).hasTinyBars(1L)));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    public Stream<DynamicTest> triggersUponAdditionalNeededSig() {
        return hapiTest(flattened(
                cryptoCreate(SENDER).balance(1L).via(SENDER_TXN),
                cryptoCreate(RECEIVER).balance(0L).receiverSigRequired(true),
                scheduleCreate(
                                TWO_SIG_XFER,
                                cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1))
                                        .fee(ONE_HBAR))
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, 4)
                        .recordingScheduledTxn()
                        .alsoSigningWith(SENDER),
                getAccountBalance(RECEIVER).hasTinyBars(0L),
                scheduleSign(TWO_SIG_XFER).alsoSigningWith(RECEIVER),
                getAccountBalance(RECEIVER).hasTinyBars(0L),
                getScheduleInfo(TWO_SIG_XFER)
                        .hasScheduleId(TWO_SIG_XFER)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, 4)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(TWO_SIG_XFER),
                getAccountBalance(RECEIVER).hasTinyBars(1L)));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    public Stream<DynamicTest> sharedKeyWorksAsExpected() {
        return hapiTest(flattened(
                newKeyNamed(SHARED_KEY),
                cryptoCreate("payerWithSharedKey").key(SHARED_KEY).via(CREATE_TXN),
                scheduleCreate(
                                DEFERRED_CREATION,
                                cryptoCreate("yetToBe")
                                        .signedBy()
                                        .receiverSigRequired(true)
                                        .key(SHARED_KEY)
                                        .balance(123L)
                                        .fee(ONE_HBAR))
                        .waitForExpiry()
                        .withRelativeExpiry(CREATE_TXN, 4)
                        .recordingScheduledTxn()
                        .payingWith("payerWithSharedKey")
                        .via(CREATION),
                getTxnRecord(CREATION).scheduled().hasAnswerOnlyPrecheck(RECORD_NOT_FOUND),
                getScheduleInfo(DEFERRED_CREATION)
                        .hasScheduleId(DEFERRED_CREATION)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(CREATE_TXN, 4)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(DEFERRED_CREATION),
                getTxnRecord(CREATION).scheduled()));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    public Stream<DynamicTest> overlappingKeysTreatedAsExpected() {
        var keyGen = OverlappingKeyGenerator.withAtLeastOneOverlappingByte(2);
        final long scheduleLifetime = 10;

        return hapiTest(flattened(
                newKeyNamed("aKey").generator(keyGen),
                newKeyNamed("bKey").generator(keyGen),
                newKeyNamed("cKey"),
                cryptoCreate("aSender").key("aKey").balance(1L).via(SENDER_TXN),
                cryptoCreate("cSender").key("cKey").balance(1L),
                balanceSnapshot(BEFORE, ADDRESS_BOOK_CONTROL),
                scheduleCreate(
                                DEFERRED_XFER,
                                cryptoTransfer(
                                        tinyBarsFromTo("aSender", ADDRESS_BOOK_CONTROL, 1),
                                        tinyBarsFromTo("cSender", ADDRESS_BOOK_CONTROL, 1)))
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, scheduleLifetime)
                        .recordingScheduledTxn(),
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
                getAccountBalance(ADDRESS_BOOK_CONTROL).hasTinyBars(changeFromSnapshot(BEFORE, 0)),
                getScheduleInfo(DEFERRED_XFER)
                        .hasScheduleId(DEFERRED_XFER)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, scheduleLifetime)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(DEFERRED_XFER, scheduleLifetime),
                getAccountBalance(ADDRESS_BOOK_CONTROL).hasTinyBars(changeFromSnapshot(BEFORE, +2))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    public Stream<DynamicTest> retestsActivationOnSignWithEmptySigMap() {
        return hapiTest(flattened(
                newKeyNamed("a"),
                newKeyNamed("b"),
                newKeyListNamed("ab", List.of("a", "b")),
                newKeyNamed(ADMIN),
                cryptoCreate(SENDER).key("ab").balance(667L).via(SENDER_TXN),
                scheduleCreate(
                                DEFERRED_FALL,
                                cryptoTransfer(tinyBarsFromTo(SENDER, FUNDING, 1))
                                        .fee(ONE_HBAR))
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, 4)
                        .recordingScheduledTxn()
                        .alsoSigningWith("a"),
                getAccountBalance(SENDER).hasTinyBars(667L),
                cryptoUpdate(SENDER).key("a"),
                scheduleSign(DEFERRED_FALL).alsoSigningWith().hasKnownStatus(NO_NEW_VALID_SIGNATURES),
                getAccountBalance(SENDER).hasTinyBars(667L),
                getScheduleInfo(DEFERRED_FALL)
                        .hasScheduleId(DEFERRED_FALL)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, 4)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(DEFERRED_FALL),
                getAccountBalance(SENDER).hasTinyBars(666L)));
    }

    private Key lowerThirdNestedThresholdSigningReq(Key source) {
        var newKey = source.getThresholdKey().getKeys().getKeys(2).toBuilder();
        newKey.setThresholdKey(newKey.getThresholdKeyBuilder().setThreshold(1));
        var newKeyList = source.getThresholdKey().getKeys().toBuilder().setKeys(2, newKey);
        return source.toBuilder()
                .setThresholdKey(source.getThresholdKey().toBuilder().setKeys(newKeyList))
                .build();
    }
}
