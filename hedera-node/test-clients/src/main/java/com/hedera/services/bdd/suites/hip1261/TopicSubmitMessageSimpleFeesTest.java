// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicSubmitMessageFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicSubmitMessageNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.signedTxnSizeFor;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsdWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CHUNK_NUMBER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_MESSAGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MESSAGE_SIZE_TOO_LARGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TopicSubmitMessageSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String ADMIN_KEY = "adminKey";
    private static final String SUBMIT_KEY = "submitKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOPIC = "testTopic";

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
                    createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER).fee(ONE_HUNDRED_HBARS),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("submitMessageTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "submitMessageTxn",
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(1L, 100L, txnSize),
                            1.0));
        }

        @HapiTest
        @DisplayName("SubmitMessage - extra bytes (101 bytes)")
        final Stream<DynamicTest> submitMessageExtraBytes101() {
            final String message = "x".repeat(101); // 101 bytes (1 extra byte)

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER).fee(ONE_HUNDRED_HBARS),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("submitMessageTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "submitMessageTxn",
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(1L, 101L, txnSize),
                            1.0));
        }

        @HapiTest
        @DisplayName("SubmitMessage - extra bytes (500 bytes)")
        final Stream<DynamicTest> submitMessageExtraBytes500() {
            final String message = "x".repeat(500); // 500 bytes (400 extra bytes)

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER).fee(ONE_HUNDRED_HBARS),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("submitMessageTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "submitMessageTxn",
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(1L, 500L, txnSize),
                            1.0));
        }

        @HapiTest
        @DisplayName("SubmitMessage - extra bytes (1024 bytes)")
        final Stream<DynamicTest> submitMessageExtraBytes1024() {
            final String message = "x".repeat(1024); // 1024 bytes (924 extra bytes)

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER).fee(ONE_HUNDRED_HBARS),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("submitMessageTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "submitMessageTxn",
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(1L, 1024L, txnSize),
                            1.0));
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
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUBMIT_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("submitMessageTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "submitMessageTxn",
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(2L, 50L, txnSize),
                            1.0));
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
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUBMIT_KEY)
                            .sigControl(forKey(SUBMIT_KEY, validSig))
                            .fee(ONE_HUNDRED_HBARS)
                            .via("submitMessageTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "submitMessageTxn",
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(
                                    3L, 100L, txnSize), // 3 sigs (1 payer + 2 submit threshold), 100 bytes
                            1.0));
        }

        @HapiTest
        @DisplayName("SubmitMessage - payer is submit key (no extra sig)")
        final Stream<DynamicTest> submitMessagePayerIsSubmitKey() {
            final String message = "x".repeat(100);

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC)
                            .submitKeyName(PAYER)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("submitMessageTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "submitMessageTxn",
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(
                                    1L, 100L, txnSize), // 1 sig (payer is submit key), 100 bytes
                            1.0));
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
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    submitMessageTo(TOPIC)
                            .message(message)
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUBMIT_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("submitMessageTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "submitMessageTxn",
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(2L, 500L, txnSize),
                            1.0));
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
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final String message = "test message";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER).fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        submitMessageTo(TOPIC)
                                .message(message)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(1L) // Fee too low
                                .via("submitMessageTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("submitMessageTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("SubmitMessage - missing payer signature fails on ingest - no fee charged")
            final Stream<DynamicTest> submitMessageMissingPayerSignatureFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final String message = "test message";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUBMIT_KEY),
                        createTopic(TOPIC)
                                .submitKeyName(SUBMIT_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        submitMessageTo(TOPIC)
                                .message(message)
                                .payingWith(PAYER)
                                .signedBy(SUBMIT_KEY) // Missing payer signature
                                .fee(ONE_HUNDRED_HBARS)
                                .via("submitMessageTxn")
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord("submitMessageTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("SubmitMessage - empty message fails on ingest - no fee charged")
            final Stream<DynamicTest> submitMessageEmptyFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER).fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        submitMessageTo(TOPIC)
                                .message("") // Empty message
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("submitMessageTxn")
                                .hasPrecheck(INVALID_TOPIC_MESSAGE),
                        getTxnRecord("submitMessageTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }
        }

        @Nested
        @DisplayName("SubmitMessage Failures on Pre-Handle")
        class SubmitMessageFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("SubmitMessage - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> submitMessageInvalidPayerSigFailsOnPreHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "submit-message-txn-inner-id";
                final String message = "test message";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC).signedBy(DEFAULT_PAYER).fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "0.0.4"))
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance("0.0.4").exposingBalanceTo(initialNodeBalance::set),
                        submitMessageTo(TOPIC)
                                .message(message)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .setNode("0.0.4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("0.0.4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                expectedTopicSubmitMessageNetworkFeeOnlyUsd(1L),
                                1.0));
            }
        }

        @Nested
        @DisplayName("SubmitMessage Failures on Handle")
        class SubmitMessageFailuresOnHandle {

            @HapiTest
            @DisplayName("SubmitMessage - message too large fails at handle - fee charged")
            final Stream<DynamicTest> submitMessageTooLargeFailsAtHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final String message = "x".repeat(1025); // Over 1024 byte limit

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER).fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        submitMessageTo(TOPIC)
                                .message(message)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("submitMessageTxn")
                                .hasPrecheckFrom(OK, TRANSACTION_OVERSIZE)
                                .hasKnownStatus(MESSAGE_SIZE_TOO_LARGE),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                "submitMessageTxn",
                                initialBalance,
                                afterBalance,
                                txnSize -> expectedTopicSubmitMessageFullFeeUsd(1L, message.length(), txnSize),
                                1.0));
            }

            @HapiTest
            @DisplayName("SubmitMessage - invalid topic fails - fee charged")
            final Stream<DynamicTest> submitMessageInvalidTopicFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final String message = "test message";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        submitMessageTo("0.0.99999999") // Invalid topic
                                .message(message)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("submitMessageTxn")
                                .hasKnownStatus(INVALID_TOPIC_ID),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                "submitMessageTxn",
                                initialBalance,
                                afterBalance,
                                txnSize -> expectedTopicSubmitMessageFullFeeUsd(1L, message.length(), txnSize),
                                1.0));
            }

            @HapiTest
            @DisplayName("SubmitMessage - deleted topic fails - fee charged")
            final Stream<DynamicTest> submitMessageDeletedTopicFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final String message = "test message";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(ONE_HUNDRED_HBARS),
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        submitMessageTo(TOPIC)
                                .message(message)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("submitMessageTxn")
                                .hasKnownStatus(INVALID_TOPIC_ID),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                "submitMessageTxn",
                                initialBalance,
                                afterBalance,
                                txnSize -> expectedTopicSubmitMessageFullFeeUsd(1L, message.length(), txnSize),
                                1.0));
            }

            @HapiTest
            @DisplayName("SubmitMessage - missing submit key signature fails at handle - fee charged")
            final Stream<DynamicTest> submitMessageMissingSubmitKeySignatureFailsAtHandle() {
                final String message = "test message";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUBMIT_KEY),
                        createTopic(TOPIC)
                                .submitKeyName(SUBMIT_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        submitMessageTo(TOPIC)
                                .message(message)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing submit key signature
                                .sigMapPrefixes(uniqueWithFullPrefixesFor(PAYER))
                                .fee(ONE_HUNDRED_HBARS)
                                .via("submitMessageTxn")
                                .hasKnownStatus(INVALID_SIGNATURE),
                        withOpContext((spec, log) -> allRunFor(
                                spec,
                                validateChargedUsd(
                                        "submitMessageTxn",
                                        expectedTopicSubmitMessageFullFeeUsd(
                                                1L, message.length(), signedTxnSizeFor(spec, "submitMessageTxn"))))));
            }

            @HapiTest
            @DisplayName("SubmitMessage - invalid chunk number fails at handle - fee charged")
            final Stream<DynamicTest> submitMessageInvalidChunkNumberFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final String message = "test message";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER).fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        submitMessageTo(TOPIC)
                                .message(message)
                                .chunkInfo(5, 10) // Invalid chunk info (chunk 5 of 10, but no initial txn ID)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("submitMessageTxn")
                                .hasKnownStatus(INVALID_CHUNK_NUMBER),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                "submitMessageTxn",
                                initialBalance,
                                afterBalance,
                                txnSize -> expectedTopicSubmitMessageFullFeeUsd(1L, message.length(), txnSize),
                                1.0));
            }
        }
    }
}
