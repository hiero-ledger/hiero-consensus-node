// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicDeleteFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
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
 * Tests for TopicDelete simple fees.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
@OrderedInIsolation
public class TopicDeleteSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String ADMIN_KEY = "adminKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOPIC = "testTopic";
    private static final String DUPLICATE_TXN_ID = "duplicateTopicDeleteTxnId";
    private static final String topicDeleteTxn = "topicDeleteTxn";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("TopicDelete Simple Fees Positive Test Cases")
    class TopicDeleteSimpleFeesPositiveTestCases {

        @HapiTest
        @DisplayName("TopicDelete - base fees (payer + admin sig)")
        final Stream<DynamicTest> topicDeleteBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).payingWith(PAYER).signedBy(PAYER, ADMIN_KEY),
                    deleteTopic(TOPIC)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(topicDeleteTxn),
                    validateChargedUsdWithinWithTxnSize(
                            topicDeleteTxn,
                            txnSize -> expectedTopicDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(topicDeleteTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TopicDelete with threshold admin key - extra signatures")
        final Stream<DynamicTest> topicDeleteWithThresholdAdminKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY).shape(keyShape),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .sigControl(forKey(ADMIN_KEY, validSig)),
                    deleteTopic(TOPIC)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .sigControl(forKey(ADMIN_KEY, validSig))
                            .via(topicDeleteTxn),
                    validateChargedUsdWithinWithTxnSize(
                            topicDeleteTxn,
                            txnSize -> expectedTopicDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(topicDeleteTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TopicDelete - payer is admin (no extra sig)")
        final Stream<DynamicTest> topicDeletePayerIsAdmin() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC).adminKeyName(PAYER).payingWith(PAYER).signedBy(PAYER),
                    deleteTopic(TOPIC).payingWith(PAYER).signedBy(PAYER).via(topicDeleteTxn),
                    validateChargedUsdWithinWithTxnSize(
                            topicDeleteTxn,
                            txnSize -> expectedTopicDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(topicDeleteTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TopicDelete with large payer key - extra processing bytes fee")
        final Stream<DynamicTest> topicDeleteLargePayerKeyExtraProcessingBytesFee() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, ADMIN_KEY),
                    deleteTopic(TOPIC)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(topicDeleteTxn),
                    validateChargedUsdWithinWithTxnSize(
                            topicDeleteTxn,
                            txnSize -> expectedTopicDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 21L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName("TopicDelete with very large payer key below oversize - extra processing bytes fee")
        final Stream<DynamicTest> topicDeleteVeryLargePayerKeyBelowOversizeFee() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(sigs(
                    ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON,
                    ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, ADMIN_KEY),
                    deleteTopic(TOPIC)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(topicDeleteTxn),
                    validateChargedUsdWithinWithTxnSize(
                            topicDeleteTxn,
                            txnSize -> expectedTopicDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 42L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }
    }

    @Nested
    @DisplayName("TopicDelete Simple Fees Negative Test Cases")
    class TopicDeleteSimpleFeesNegativeTestCases {

        @Nested
        @DisplayName("TopicDelete Failures on Ingest")
        class TopicDeleteFailuresOnIngest {

            @HapiTest
            @DisplayName("TopicDelete - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> topicDeleteInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY),
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(1L) // Fee too low
                                .via(topicDeleteTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(topicDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicDelete - missing payer signature fails on ingest - no fee charged")
            final Stream<DynamicTest> topicDeleteMissingPayerSignatureFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY),
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .signedBy(ADMIN_KEY) // Missing payer signature
                                .via(topicDeleteTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(topicDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicDelete - threshold payer key with invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> topicDeleteThresholdKeyInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(PAYER, ADMIN_KEY),
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, ADMIN_KEY)
                                .via(topicDeleteTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(topicDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicDelete - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> topicDeleteInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HBAR / 100_000L),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC).adminKeyName(ADMIN_KEY),
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .via(topicDeleteTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(topicDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicDelete - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> topicDeleteMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY),
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .memo(LONG_MEMO)
                                .via(topicDeleteTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(topicDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicDelete - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> topicDeleteExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredDeleteTxn";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .txnId(expiredTxnId)
                                .via(topicDeleteTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(topicDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicDelete - too far start time fails on ingest - no fee charged")
            final Stream<DynamicTest> topicDeleteTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureDeleteTxn";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(3_600L).payerId(PAYER),
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .txnId(futureTxnId)
                                .via(topicDeleteTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(topicDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicDelete - invalid transaction duration fails on ingest - no fee charged")
            final Stream<DynamicTest> topicDeleteInvalidTransactionDurationFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY),
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .validDurationSecs(0)
                                .via(topicDeleteTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(topicDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicDelete - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> topicDeleteDuplicateTxnFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY),
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .via("deleteFirst"),
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .txnId("deleteFirst")
                                .via(topicDeleteTxn)
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }
        }

        @Nested
        @DisplayName("TopicDelete Failures on Handle")
        class TopicDeleteFailuresOnHandle {

            @HapiTest
            @DisplayName("TopicDelete - invalid topic fails on handle - full fee charged")
            final Stream<DynamicTest> topicDeleteInvalidTopicFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        deleteTopic("0.0.99999999") // Invalid topic
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(topicDeleteTxn)
                                .hasKnownStatus(INVALID_TOPIC_ID),
                        validateChargedUsdWithinWithTxnSize(
                                topicDeleteTxn,
                                txnSize -> expectedTopicDeleteFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(topicDeleteTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TopicDelete - missing admin key signature fails at handle - full fee charged")
            final Stream<DynamicTest> topicDeleteMissingAdminKeySignatureFailsAtHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY),
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing admin key signature
                                .via(topicDeleteTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                topicDeleteTxn,
                                txnSize -> expectedTopicDeleteFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(topicDeleteTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TopicDelete - immutable topic (no admin key) fails on handle - full fee charged")
            final Stream<DynamicTest> topicDeleteImmutableTopicFails() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC)
                                // No admin key - topic is immutable
                                .payingWith(PAYER)
                                .signedBy(PAYER),
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(topicDeleteTxn)
                                .hasKnownStatus(UNAUTHORIZED),
                        validateChargedUsdWithinWithTxnSize(
                                topicDeleteTxn,
                                txnSize -> expectedTopicDeleteFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(topicDeleteTxn, PAYER));
            }

            @Tag(ONLY_SUBPROCESS)
            @HapiTest
            @DisplayName("TopicDelete - duplicate transaction fails on handle - payer charged for first only")
            final Stream<DynamicTest> topicDeleteDuplicateFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(topicDeleteTxn),
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .setNode(3)
                                .txnId(DUPLICATE_TXN_ID)
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithinWithTxnSize(
                                topicDeleteTxn,
                                txnSize -> expectedTopicDeleteFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(topicDeleteTxn, PAYER));
            }
        }
    }
}
