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
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicUpdateNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;
import static org.hiero.hapi.support.fees.Extra.KEYS;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

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
 * Tests for TopicUpdate simple fees.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 * - Number of keys (extras beyond included)
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TopicUpdateSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String ADMIN_KEY = "adminKey";
    private static final String NEW_ADMIN_KEY = "newAdminKey";
    private static final String SUBMIT_KEY = "submitKey";
    private static final String NEW_SUBMIT_KEY = "newSubmitKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOPIC = "testTopic";
    private static final String topicUpdateTxn = "topicUpdateTxn";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("TopicUpdate Simple Fees Positive Test Cases")
    class TopicUpdateSimpleFeesPositiveTestCases {

        @HapiTest
        @DisplayName("TopicUpdate - base fees (payer + admin sig, no key change)")
        final Stream<DynamicTest> topicUpdateBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).payingWith(PAYER).signedBy(PAYER, ADMIN_KEY),
                    updateTopic(TOPIC)
                            .memo("updated memo")
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(topicUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            topicUpdateTxn,
                            txnSize -> expectedTopicUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            1.0),
                    validateChargedAccount(topicUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TopicUpdate - with new admin key (extra key + sig)")
        final Stream<DynamicTest> topicUpdateWithNewAdminKey() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(NEW_ADMIN_KEY),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).payingWith(PAYER).signedBy(PAYER, ADMIN_KEY),
                    updateTopic(TOPIC)
                            .adminKey(NEW_ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY, NEW_ADMIN_KEY)
                            .via(topicUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            topicUpdateTxn,
                            txnSize -> expectedTopicUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            1.0),
                    validateChargedAccount(topicUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TopicUpdate - with new submit key (extra key only)")
        final Stream<DynamicTest> topicUpdateWithNewSubmitKey() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(NEW_SUBMIT_KEY),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).payingWith(PAYER).signedBy(PAYER, ADMIN_KEY),
                    updateTopic(TOPIC)
                            .submitKey(NEW_SUBMIT_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(topicUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            topicUpdateTxn,
                            txnSize -> expectedTopicUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            1.0),
                    validateChargedAccount(topicUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TopicUpdate - with threshold admin key (multiple sigs)")
        final Stream<DynamicTest> topicUpdateWithThresholdAdminKey() {
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
                    updateTopic(TOPIC)
                            .memo("updated memo")
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .sigControl(forKey(ADMIN_KEY, validSig))
                            .via(topicUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            topicUpdateTxn,
                            txnSize -> expectedTopicUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            1.0),
                    validateChargedAccount(topicUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TopicUpdate - payer is admin (no extra sigs)")
        final Stream<DynamicTest> topicUpdatePayerIsAdmin() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC).adminKeyName(PAYER).payingWith(PAYER).signedBy(PAYER),
                    updateTopic(TOPIC)
                            .memo("updated memo")
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via(topicUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            topicUpdateTxn,
                            txnSize -> expectedTopicUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            1.0),
                    validateChargedAccount(topicUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TopicUpdate - with both new admin and submit keys")
        final Stream<DynamicTest> topicUpdateWithBothNewKeys() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(NEW_ADMIN_KEY),
                    newKeyNamed(NEW_SUBMIT_KEY),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).payingWith(PAYER).signedBy(PAYER, ADMIN_KEY),
                    updateTopic(TOPIC)
                            .adminKey(NEW_ADMIN_KEY)
                            .submitKey(NEW_SUBMIT_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY, NEW_ADMIN_KEY)
                            .via(topicUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            topicUpdateTxn,
                            txnSize -> expectedTopicUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            1.0),
                    validateChargedAccount(topicUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TopicUpdate - large payer key charges extra signatures and processing bytes")
        final Stream<DynamicTest> topicUpdateLargePayerKeyExtraFee() {
            KeyShape largeKeyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigning = largeKeyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(largeKeyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).payingWith(DEFAULT_PAYER),
                    updateTopic(TOPIC)
                            .memo("updated memo")
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigning))
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(topicUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            topicUpdateTxn,
                            txnSize -> expectedTopicUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 21L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            1.0));
        }

        @HapiTest
        @DisplayName("TopicUpdate - very large payer key below oversize limit")
        final Stream<DynamicTest> topicUpdateVeryLargePayerKeyBelowOversizeFee() {
            KeyShape veryLargeKeyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigning = veryLargeKeyShape.signedWith(sigs(
                    ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON,
                    ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(veryLargeKeyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).payingWith(DEFAULT_PAYER),
                    updateTopic(TOPIC)
                            .memo("updated memo")
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigning))
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(topicUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            topicUpdateTxn,
                            txnSize -> expectedTopicUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 42L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            1.0));
        }
    }

    @Nested
    @DisplayName("TopicUpdate Simple Fees Negative Test Cases")
    class TopicUpdateSimpleFeesNegativeTestCases {

        @Nested
        @DisplayName("TopicUpdate Failures on Ingest")
        class TopicUpdateFailuresOnIngest {

            @HapiTest
            @DisplayName("TopicUpdate - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> topicUpdateInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY),
                        updateTopic(TOPIC)
                                .memo("updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(1L) // Fee too low
                                .via(topicUpdateTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(topicUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicUpdate - missing payer signature fails on ingest - no fee charged")
            final Stream<DynamicTest> topicUpdateMissingPayerSignatureFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY),
                        updateTopic(TOPIC)
                                .memo("updated memo")
                                .payingWith(PAYER)
                                .signedBy(ADMIN_KEY) // Missing payer signature
                                .via(topicUpdateTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(topicUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicUpdate - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> topicUpdateMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY),
                        updateTopic(TOPIC)
                                .memo(LONG_MEMO)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .via(topicUpdateTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(topicUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicUpdate - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> topicUpdateInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC).adminKeyName(ADMIN_KEY).payingWith(DEFAULT_PAYER),
                        updateTopic(TOPIC)
                                .memo("updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .via(topicUpdateTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(topicUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicUpdate - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> topicUpdateExpiredFailsOnIngest() {
                final var expiredTxnId = "expiredTopicUpdateTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC).adminKeyName(ADMIN_KEY).payingWith(DEFAULT_PAYER),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        updateTopic(TOPIC)
                                .memo("updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .txnId(expiredTxnId)
                                .via(topicUpdateTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(topicUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicUpdate - too far in future fails on ingest - no fee charged")
            final Stream<DynamicTest> topicUpdateTooFarInFutureFailsOnIngest() {
                final var futureTxnId = "futureTopicUpdateTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC).adminKeyName(ADMIN_KEY).payingWith(DEFAULT_PAYER),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(3_600L).payerId(PAYER),
                        updateTopic(TOPIC)
                                .memo("updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .txnId(futureTxnId)
                                .via(topicUpdateTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(topicUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicUpdate - invalid transaction duration fails on ingest - no fee charged")
            final Stream<DynamicTest> topicUpdateInvalidDurationFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC).adminKeyName(ADMIN_KEY).payingWith(DEFAULT_PAYER),
                        updateTopic(TOPIC)
                                .memo("updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .validDurationSecs(0L)
                                .via(topicUpdateTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(topicUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicUpdate - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> topicUpdateDuplicateFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC).adminKeyName(ADMIN_KEY).payingWith(DEFAULT_PAYER),
                        updateTopic(TOPIC)
                                .memo("first update")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .via(topicUpdateTxn),
                        updateTopic(TOPIC)
                                .memo("duplicate update")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .txnId(topicUpdateTxn)
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }
        }

        @Nested
        @DisplayName("TopicUpdate Failures on Pre-Handle")
        class TopicUpdateFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TopicUpdate - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> topicUpdateInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "topic-update-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC).adminKeyName(ADMIN_KEY).signedBy(DEFAULT_PAYER, ADMIN_KEY),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "4")),
                        updateTopic(TOPIC)
                                .memo("updated memo")
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, ADMIN_KEY)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedTopicUpdateNetworkFeeOnlyUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                1.0),
                        validateChargedAccount(INNER_ID, "4"));
            }
        }

        @Nested
        @DisplayName("TopicUpdate Failures on Handle")
        class TopicUpdateFailuresOnHandle {

            @HapiTest
            @DisplayName("TopicUpdate - invalid topic fails on handle - full fee charged")
            final Stream<DynamicTest> topicUpdateInvalidTopicFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        updateTopic("0.0.99999999") // Invalid topic
                                .memo("updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(topicUpdateTxn)
                                .hasKnownStatus(INVALID_TOPIC_ID),
                        validateChargedUsdWithinWithTxnSize(
                                topicUpdateTxn,
                                txnSize -> expectedTopicUpdateFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        KEYS, 0L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1.0),
                        validateChargedAccount(topicUpdateTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TopicUpdate - deleted topic fails on handle - full fee charged")
            final Stream<DynamicTest> topicUpdateDeletedTopicFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY),
                        deleteTopic(TOPIC).payingWith(PAYER).signedBy(PAYER, ADMIN_KEY),
                        updateTopic(TOPIC)
                                .memo("updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .via(topicUpdateTxn)
                                .hasKnownStatus(INVALID_TOPIC_ID),
                        validateChargedUsdWithinWithTxnSize(
                                topicUpdateTxn,
                                txnSize -> expectedTopicUpdateFullFeeUsd(Map.of(
                                        SIGNATURES, 2L,
                                        KEYS, 0L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1.0),
                        validateChargedAccount(topicUpdateTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TopicUpdate - immutable topic (no admin key) submit key update fails - fee charged")
            final Stream<DynamicTest> topicUpdateImmutableTopicFails() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUBMIT_KEY),
                        createTopic(TOPIC)
                                // No admin key - topic is immutable
                                .payingWith(PAYER)
                                .signedBy(PAYER),
                        updateTopic(TOPIC)
                                .submitKey(SUBMIT_KEY) // Trying to add submit key to immutable topic
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(topicUpdateTxn)
                                .hasKnownStatus(UNAUTHORIZED),
                        validateChargedUsdWithinWithTxnSize(
                                topicUpdateTxn,
                                txnSize -> expectedTopicUpdateFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        KEYS, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1.0),
                        validateChargedAccount(topicUpdateTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TopicUpdate - new admin key not signed fails at handle - fee charged")
            final Stream<DynamicTest> topicUpdateNewAdminKeyNotSignedFailsAtHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed(NEW_ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY),
                        updateTopic(TOPIC)
                                .adminKey(NEW_ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY) // Missing new admin key signature
                                .via(topicUpdateTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                topicUpdateTxn,
                                txnSize -> expectedTopicUpdateFullFeeUsd(Map.of(
                                        SIGNATURES, 2L,
                                        KEYS, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1.0),
                        validateChargedAccount(topicUpdateTxn, PAYER));
            }

            @Nested
            @DisplayName("TopicUpdate Duplicate on Handle")
            class TopicUpdateDuplicateOnHandle {
                private static final String DUPLICATE_TXN_ID = "duplicateTopicUpdateTxnId";

                @Tag(ONLY_SUBPROCESS)
                @HapiTest
                @DisplayName("TopicUpdate - duplicate on handle charges full fee to payer")
                final Stream<DynamicTest> topicUpdateDuplicateFailsOnHandle() {
                    return hapiTest(
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            newKeyNamed(ADMIN_KEY),
                            createTopic(TOPIC).adminKeyName(ADMIN_KEY).payingWith(DEFAULT_PAYER),
                            cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "4")),
                            usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                            updateTopic(TOPIC)
                                    .memo("updated memo")
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, ADMIN_KEY)
                                    .txnId(DUPLICATE_TXN_ID)
                                    .setNode("4")
                                    .via(topicUpdateTxn),
                            updateTopic(TOPIC)
                                    .memo("updated memo")
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, ADMIN_KEY)
                                    .txnId(DUPLICATE_TXN_ID)
                                    .setNode("3")
                                    .hasPrecheck(DUPLICATE_TRANSACTION),
                            validateChargedUsdWithinWithTxnSize(
                                    topicUpdateTxn,
                                    txnSize -> expectedTopicUpdateFullFeeUsd(Map.of(
                                            SIGNATURES, 2L,
                                            KEYS, 0L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    1.0),
                            validateChargedAccount(topicUpdateTxn, PAYER));
                }
            }
        }
    }
}
