// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicSubmitMessageFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicSubmitMessageNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CHUNK_NUMBER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_MESSAGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MESSAGE_SIZE_TOO_LARGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;
import static org.hiero.hapi.support.fees.Extra.STATE_BYTES;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

/**
 * Tests for SubmitMessage simple fees.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 * - Number of bytes (extras beyond included 100 bytes)
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TopicSubmitMessageSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String ADMIN_KEY = "adminKey";
    private static final String SUBMIT_KEY = "submitKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOPIC = "testTopic";
    private static final String submitMessageTxn = "submitMessageTxn";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("SubmitMessage Simple Fees Positive Test Cases")
    class SubmitMessageSimpleFeesPositiveTestCases {

        @HapiTest
        @DisplayName("SubmitMessage - within included bytes (100 bytes) - base fee only")
        final Stream<DynamicTest> submitMessageWithinIncludedBytes() {
            final String message = "x".repeat(100); // Exactly 100 bytes (included)

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via(submitMessageTxn),
                    validateChargedUsdWithinWithTxnSize(
                            submitMessageTxn,
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    STATE_BYTES, 100L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(submitMessageTxn, PAYER));
        }

        @HapiTest
        @DisplayName("SubmitMessage - extra bytes (101 bytes)")
        final Stream<DynamicTest> submitMessageExtraBytes101() {
            final String message = "x".repeat(101); // 101 bytes (1 extra byte)

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via(submitMessageTxn),
                    validateChargedUsdWithinWithTxnSize(
                            submitMessageTxn,
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    STATE_BYTES, 101L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(submitMessageTxn, PAYER));
        }

        @HapiTest
        @DisplayName("SubmitMessage - extra bytes (500 bytes)")
        final Stream<DynamicTest> submitMessageExtraBytes500() {
            final String message = "x".repeat(500); // 500 bytes (400 extra bytes)

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via(submitMessageTxn),
                    validateChargedUsdWithinWithTxnSize(
                            submitMessageTxn,
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    STATE_BYTES, 500L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(submitMessageTxn, PAYER));
        }

        @HapiTest
        @DisplayName("SubmitMessage - extra bytes (1024 bytes)")
        final Stream<DynamicTest> submitMessageExtraBytes1024() {
            final String message = "x".repeat(1024); // 1024 bytes (924 extra bytes)

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via(submitMessageTxn),
                    validateChargedUsdWithinWithTxnSize(
                            submitMessageTxn,
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    STATE_BYTES, 1024L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(submitMessageTxn, PAYER));
        }

        @HapiTest
        @DisplayName("SubmitMessage - with submit key (extra sigs)")
        final Stream<DynamicTest> submitMessageWithSubmitKey() {
            final String message = "x".repeat(50); // 50 bytes (within included)

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(SUBMIT_KEY),
                    createTopic(TOPIC)
                            .submitKeyName(SUBMIT_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUBMIT_KEY)
                            .via(submitMessageTxn),
                    validateChargedUsdWithinWithTxnSize(
                            submitMessageTxn,
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    STATE_BYTES, 50L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(submitMessageTxn, PAYER));
        }

        @HapiTest
        @DisplayName("SubmitMessage - with threshold submit key (multiple sigs)")
        final Stream<DynamicTest> submitMessageWithThresholdSubmitKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));
            final String message = "x".repeat(100); // 100 bytes (within included)

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(SUBMIT_KEY).shape(keyShape),
                    createTopic(TOPIC)
                            .submitKeyName(SUBMIT_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUBMIT_KEY)
                            .sigControl(forKey(SUBMIT_KEY, validSig))
                            .via(submitMessageTxn),
                    validateChargedUsdWithinWithTxnSize(
                            submitMessageTxn,
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    STATE_BYTES, 100L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(submitMessageTxn, PAYER));
        }

        @HapiTest
        @DisplayName("SubmitMessage - payer is submit key (no extra sig)")
        final Stream<DynamicTest> submitMessagePayerIsSubmitKey() {
            final String message = "x".repeat(100);

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC).submitKeyName(PAYER).payingWith(PAYER).signedBy(PAYER),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via(submitMessageTxn),
                    validateChargedUsdWithinWithTxnSize(
                            submitMessageTxn,
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    STATE_BYTES, 100L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(submitMessageTxn, PAYER));
        }

        @HapiTest
        @DisplayName("SubmitMessage - with submit key and extra bytes")
        final Stream<DynamicTest> submitMessageWithSubmitKeyAndExtraBytes() {
            final String message = "x".repeat(500); // 500 bytes (400 extra)

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(SUBMIT_KEY),
                    createTopic(TOPIC)
                            .submitKeyName(SUBMIT_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUBMIT_KEY)
                            .via(submitMessageTxn),
                    validateChargedUsdWithinWithTxnSize(
                            submitMessageTxn,
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    STATE_BYTES, 500L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(submitMessageTxn, PAYER));
        }

        @HapiTest
        @DisplayName("SubmitMessage - large payer key charges extra signatures and processing bytes")
        final Stream<DynamicTest> submitMessageLargePayerKeyExtraFee() {
            KeyShape largeKeyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigning = largeKeyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));
            final String message = "x".repeat(100);

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(largeKeyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC).payingWith(DEFAULT_PAYER),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigning))
                            .signedBy(PAYER)
                            .via(submitMessageTxn),
                    validateChargedUsdWithinWithTxnSize(
                            submitMessageTxn,
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                    SIGNATURES, 20L,
                                    STATE_BYTES, 100L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(submitMessageTxn, PAYER));
        }

        @HapiTest
        @DisplayName("SubmitMessage - very large payer key below oversize limit")
        final Stream<DynamicTest> submitMessageVeryLargePayerKeyBelowOversizeFee() {
            KeyShape veryLargeKeyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigning = veryLargeKeyShape.signedWith(sigs(
                    ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON,
                    ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));
            final String message = "x".repeat(100);

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(veryLargeKeyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC).payingWith(DEFAULT_PAYER),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigning))
                            .signedBy(PAYER)
                            .via(submitMessageTxn),
                    validateChargedUsdWithinWithTxnSize(
                            submitMessageTxn,
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                    SIGNATURES, 41L,
                                    STATE_BYTES, 100L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(submitMessageTxn, PAYER));
        }
    }

    @Nested
    @DisplayName("SubmitMessage Simple Fees Negative Test Cases")
    class SubmitMessageSimpleFeesNegativeTestCases {

        @Nested
        @DisplayName("SubmitMessage Failures on Ingest")
        class SubmitMessageFailuresOnIngest {

            @HapiTest
            @DisplayName("SubmitMessage - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> submitMessageInsufficientTxFeeFailsOnIngest() {
                final String message = "test message";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER),
                        submitMessageTo(TOPIC)
                                .message(message)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(1L) // Fee too low
                                .via(submitMessageTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(submitMessageTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("SubmitMessage - missing payer signature fails on ingest - no fee charged")
            final Stream<DynamicTest> submitMessageMissingPayerSignatureFailsOnIngest() {
                final String message = "test message";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUBMIT_KEY),
                        createTopic(TOPIC)
                                .submitKeyName(SUBMIT_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER),
                        submitMessageTo(TOPIC)
                                .message(message)
                                .payingWith(PAYER)
                                .signedBy(SUBMIT_KEY) // Missing payer signature
                                .via(submitMessageTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(submitMessageTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("SubmitMessage - empty message fails on ingest - no fee charged")
            final Stream<DynamicTest> submitMessageEmptyFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER),
                        submitMessageTo(TOPIC)
                                .message("") // Empty message
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(submitMessageTxn)
                                .hasPrecheck(INVALID_TOPIC_MESSAGE),
                        getTxnRecord(submitMessageTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("SubmitMessage - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> submitMessageInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(0L),
                        createTopic(TOPIC).payingWith(DEFAULT_PAYER),
                        submitMessageTo(TOPIC)
                                .message("test message")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(submitMessageTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(submitMessageTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("SubmitMessage - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> submitMessageMemoTooLongFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC).payingWith(DEFAULT_PAYER),
                        submitMessageTo(TOPIC)
                                .message("test message")
                                .memo("x".repeat(101))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(submitMessageTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(submitMessageTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("SubmitMessage - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> submitMessageExpiredFailsOnIngest() {
                final var expiredTxnId = "expiredTopicSubmitMessageTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC).payingWith(DEFAULT_PAYER),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        submitMessageTo(TOPIC)
                                .message("test message")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(expiredTxnId)
                                .via(submitMessageTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(submitMessageTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("SubmitMessage - too far in future fails on ingest - no fee charged")
            final Stream<DynamicTest> submitMessageTooFarInFutureFailsOnIngest() {
                final var futureTxnId = "futureTopicSubmitMessageTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC).payingWith(DEFAULT_PAYER),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(3_600L).payerId(PAYER),
                        submitMessageTo(TOPIC)
                                .message("test message")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(futureTxnId)
                                .via(submitMessageTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(submitMessageTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("SubmitMessage - invalid transaction duration fails on ingest - no fee charged")
            final Stream<DynamicTest> submitMessageInvalidDurationFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC).payingWith(DEFAULT_PAYER),
                        submitMessageTo(TOPIC)
                                .message("test message")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .validDurationSecs(0L)
                                .via(submitMessageTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(submitMessageTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("SubmitMessage - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> submitMessageDuplicateFailsOnIngest() {
                final String message = "test message";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC).payingWith(DEFAULT_PAYER),
                        submitMessageTo(TOPIC)
                                .message(message)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("firstSubmitTxn"),
                        submitMessageTo(TOPIC)
                                .message(message)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId("firstSubmitTxn")
                                .via(submitMessageTxn)
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }
        }

        @Nested
        @DisplayName("SubmitMessage Failures on Pre-Handle")
        class SubmitMessageFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("SubmitMessage - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> submitMessageInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "submit-message-txn-inner-id";
                final String message = "test message";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC).signedBy(DEFAULT_PAYER),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "4")),
                        submitMessageTo(TOPIC)
                                .message(message)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedTopicSubmitMessageNetworkFeeOnlyUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }
        }

        @Nested
        @DisplayName("SubmitMessage Failures on Handle")
        class SubmitMessageFailuresOnHandle {

            @HapiTest
            @DisplayName("SubmitMessage - message too large fails on handle - full fee charged")
            final Stream<DynamicTest> submitMessageTooLargeFailsOnHandle() {
                final String message = "x".repeat(1025); // Over 1024 byte limit

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER),
                        submitMessageTo(TOPIC)
                                .message(message)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(submitMessageTxn)
                                .hasPrecheckFrom(OK, TRANSACTION_OVERSIZE)
                                .hasKnownStatus(MESSAGE_SIZE_TOO_LARGE),
                        validateChargedUsdWithinWithTxnSize(
                                submitMessageTxn,
                                txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        STATE_BYTES, (long) message.length(),
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(submitMessageTxn, PAYER));
            }

            @HapiTest
            @DisplayName("SubmitMessage - invalid topic fails on handle - full fee charged")
            final Stream<DynamicTest> submitMessageInvalidTopicFails() {
                final String message = "test message";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        submitMessageTo("0.0.99999999") // Invalid topic
                                .message(message)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(submitMessageTxn)
                                .hasKnownStatus(INVALID_TOPIC_ID),
                        validateChargedUsdWithinWithTxnSize(
                                submitMessageTxn,
                                txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        STATE_BYTES, (long) message.length(),
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(submitMessageTxn, PAYER));
            }

            @HapiTest
            @DisplayName("SubmitMessage - deleted topic fails on handle - full fee charged")
            final Stream<DynamicTest> submitMessageDeletedTopicFailsOnHandle() {
                final String message = "test message";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY),
                        deleteTopic(TOPIC).payingWith(PAYER).signedBy(PAYER, ADMIN_KEY),
                        submitMessageTo(TOPIC)
                                .message(message)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(submitMessageTxn)
                                .hasKnownStatus(INVALID_TOPIC_ID),
                        validateChargedUsdWithinWithTxnSize(
                                submitMessageTxn,
                                txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        STATE_BYTES, (long) message.length(),
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(submitMessageTxn, PAYER));
            }

            @HapiTest
            @DisplayName("SubmitMessage - missing submit key signature fails on handle - full fee charged")
            final Stream<DynamicTest> submitMessageMissingSubmitKeySignatureFailsAtHandle() {
                final String message = "test message";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUBMIT_KEY),
                        createTopic(TOPIC)
                                .submitKeyName(SUBMIT_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER),
                        submitMessageTo(TOPIC)
                                .message(message)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing submit key signature
                                .sigMapPrefixes(uniqueWithFullPrefixesFor(PAYER))
                                .via(submitMessageTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                submitMessageTxn,
                                txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        STATE_BYTES, (long) message.length(),
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(submitMessageTxn, PAYER));
            }

            @HapiTest
            @DisplayName("SubmitMessage - invalid chunk number fails on handle - full fee charged")
            final Stream<DynamicTest> submitMessageInvalidChunkNumberFailsOnHandle() {
                final String message = "test message";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER),
                        submitMessageTo(TOPIC)
                                .message(message)
                                .chunkInfo(5, 10) // Invalid chunk info (chunk 5 of 10, but no initial txn ID)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(submitMessageTxn)
                                .hasKnownStatus(INVALID_CHUNK_NUMBER),
                        validateChargedUsdWithinWithTxnSize(
                                submitMessageTxn,
                                txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        STATE_BYTES, (long) message.length(),
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(submitMessageTxn, PAYER));
            }

            @Nested
            @DisplayName("SubmitMessage Duplicate on Handle")
            class SubmitMessageDuplicateOnHandle {
                private static final String DUPLICATE_TXN_ID = "duplicateSubmitMessageTxnId";

                @Tag(ONLY_SUBPROCESS)
                @HapiTest
                @DisplayName("SubmitMessage - duplicate on handle charges full fee to payer")
                final Stream<DynamicTest> submitMessageDuplicateFailsOnHandle() {
                    final String message = "test message";

                    return hapiTest(
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            createTopic(TOPIC).payingWith(DEFAULT_PAYER),
                            cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "4")),
                            usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                            submitMessageTo(TOPIC)
                                    .message(message)
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .txnId(DUPLICATE_TXN_ID)
                                    .setNode("4")
                                    .via(DUPLICATE_TXN_ID),
                            submitMessageTo(TOPIC)
                                    .message(message)
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .txnId(DUPLICATE_TXN_ID)
                                    .setNode("3")
                                    .hasPrecheck(DUPLICATE_TRANSACTION),
                            validateChargedUsdWithinWithTxnSize(
                                    DUPLICATE_TXN_ID,
                                    txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                            SIGNATURES, 1L,
                                            STATE_BYTES, (long) message.length(),
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateChargedAccount(DUPLICATE_TXN_ID, PAYER));
                }
            }
        }
    }
}
