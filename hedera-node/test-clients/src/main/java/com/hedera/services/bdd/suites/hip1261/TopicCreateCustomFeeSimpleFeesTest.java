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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicCreateNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicCreateWithCustomFeeFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static org.hiero.hapi.support.fees.Extra.KEYS;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
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
 * Tests for TopicCreate with custom fees simple fees.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 * - Number of keys (extras beyond included)
 * - Custom fee surcharge
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TopicCreateCustomFeeSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String ADMIN_KEY = "adminKey";
    private static final String SUBMIT_KEY = "submitKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String FEE_SCHEDULE_KEY = "feeScheduleKey";
    private static final String COLLECTOR = "collector";
    private static final String AUTO_RENEW_ACCOUNT = "autoRenewAccount";
    private static final String TOPIC = "testTopic";
    private static final String createTopicTxn = "createTopicTxn";
    private static final String DUPLICATE_TXN_ID = "duplicateTopicDeleteTxnId";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("TopicCreate with Custom Fee Simple Fees Positive Test Cases")
    class TopicCreateCustomFeePositiveTestCases {

        @HapiTest
        @DisplayName("TopicCreate with custom fee - base fee + custom fee surcharge")
        final Stream<DynamicTest> topicCreateWithCustomFeeBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(COLLECTOR).balance(0L),
                    createTopic(TOPIC)
                            .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateWithCustomFeeFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            1.0),
                    validateChargedAccount(createTopicTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TopicCreate with custom fee + admin key")
        final Stream<DynamicTest> topicCreateWithCustomFeeAndAdminKey() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(COLLECTOR).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateWithCustomFeeFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            1.0),
                    validateChargedAccount(createTopicTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TopicCreate with custom fee + admin and submit keys")
        final Stream<DynamicTest> topicCreateWithCustomFeeAndBothKeys() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(COLLECTOR).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(SUBMIT_KEY),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .submitKeyName(SUBMIT_KEY)
                            .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateWithCustomFeeFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            1.0),
                    validateChargedAccount(createTopicTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TopicCreate with custom fee + threshold admin key")
        final Stream<DynamicTest> topicCreateWithCustomFeeAndThresholdAdminKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(COLLECTOR).balance(0L),
                    newKeyNamed(ADMIN_KEY).shape(keyShape),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .sigControl(forKey(ADMIN_KEY, validSig))
                            .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateWithCustomFeeFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            1.0),
                    validateChargedAccount(createTopicTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TopicCreate with multiple custom fees - single surcharge")
        final Stream<DynamicTest> topicCreateWithMultipleCustomFees() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(COLLECTOR).balance(0L),
                    cryptoCreate("collector2").balance(0L),
                    createTopic(TOPIC)
                            .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                            .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR / 2, "collector2"))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateWithCustomFeeFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            1.0),
                    validateChargedAccount(createTopicTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TopicCreate with custom fee + auto-renew account - extra sig, no extra keys")
        final Stream<DynamicTest> topicCreateWithCustomFeeAndAutoRenewAccount() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(COLLECTOR).balance(0L),
                    cryptoCreate(AUTO_RENEW_ACCOUNT).balance(ONE_HBAR),
                    createTopic(TOPIC)
                            .autoRenewAccountId(AUTO_RENEW_ACCOUNT)
                            .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, AUTO_RENEW_ACCOUNT)
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateWithCustomFeeFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            1.0),
                    validateChargedAccount(createTopicTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TopicCreate with custom fee + large payer key - extra processing bytes fee")
        final Stream<DynamicTest> topicCreateWithCustomFeeAndLargePayerKey() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(COLLECTOR).balance(0L),
                    createTopic(TOPIC)
                            .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER)
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateWithCustomFeeFullFeeUsd(Map.of(
                                    SIGNATURES, 20L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName("TopicCreate with custom fee + very large payer key below oversize - extra processing bytes fee")
        final Stream<DynamicTest> topicCreateWithCustomFeeAndVeryLargePayerKey() {
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
                    cryptoCreate(COLLECTOR).balance(0L),
                    createTopic(TOPIC)
                            .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER)
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateWithCustomFeeFullFeeUsd(Map.of(
                                    SIGNATURES, 41L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }
    }

    @Nested
    @DisplayName("TopicCreate with Custom Fee - Missing Extras (Partial Fees)")
    class TopicCreateCustomFeeMissingExtrasTestCases {

        @HapiTest
        @DisplayName("TopicCreate without custom fee - standard charge (no surcharge)")
        final Stream<DynamicTest> topicCreateWithoutCustomFeeStandardCharge() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC)
                            // No custom fee
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            1.0),
                    validateChargedAccount(createTopicTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TopicCreate with fee schedule key only")
        final Stream<DynamicTest> topicCreateWithFeeScheduleKeyOnly() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(COLLECTOR).balance(0L),
                    newKeyNamed(FEE_SCHEDULE_KEY),
                    createTopic(TOPIC)
                            .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                            .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateWithCustomFeeFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            1.0),
                    validateChargedAccount(createTopicTxn, PAYER));
        }
    }

    @Nested
    @DisplayName("TopicCreate with Custom Fee Simple Fees Negative Test Cases")
    class TopicCreateCustomFeeNegativeTestCases {

        @Nested
        @DisplayName("TopicCreate with Custom Fee Failures on Ingest")
        class TopicCreateCustomFeeFailuresOnIngest {

            @HapiTest
            @DisplayName("TopicCreate with custom fee - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> topicCreateCustomFeeInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(COLLECTOR).balance(0L),
                        createTopic(TOPIC)
                                .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(1L) // Fee too low
                                .via(createTopicTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(createTopicTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName(
                    "TopicCreate with custom fee - threshold payer key invalid sig fails on ingest - no fee charged")
            final Stream<DynamicTest> topicCreateCustomFeeThresholdKeyInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(COLLECTOR).balance(0L),
                        createTopic(TOPIC)
                                .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .via(createTopicTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(createTopicTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicCreate with custom fee - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> topicCreateCustomFeeInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HBAR / 100_000L),
                        cryptoCreate(COLLECTOR).balance(0L),
                        createTopic(TOPIC)
                                .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(createTopicTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(createTopicTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicCreate with custom fee - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> topicCreateCustomFeeMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(COLLECTOR).balance(0L),
                        createTopic(TOPIC)
                                .memo(LONG_MEMO)
                                .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(createTopicTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(createTopicTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicCreate with custom fee - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> topicCreateCustomFeeExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredCustomFeeCreateTxn";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(COLLECTOR).balance(0L),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        createTopic(TOPIC)
                                .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(expiredTxnId)
                                .via(createTopicTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(createTopicTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicCreate with custom fee - too far start time fails on ingest - no fee charged")
            final Stream<DynamicTest> topicCreateCustomFeeTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureCustomFeeCreateTxn";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(COLLECTOR).balance(0L),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(3_600L).payerId(PAYER),
                        createTopic(TOPIC)
                                .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(futureTxnId)
                                .via(createTopicTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(createTopicTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicCreate with custom fee - invalid transaction duration fails on ingest - no fee charged")
            final Stream<DynamicTest> topicCreateCustomFeeInvalidTransactionDurationFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(COLLECTOR).balance(0L),
                        createTopic(TOPIC)
                                .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .validDurationSecs(0)
                                .via(createTopicTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(createTopicTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TopicCreate with custom fee - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> topicCreateCustomFeeDuplicateTxnFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(COLLECTOR).balance(0L),
                        createTopic(TOPIC)
                                .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("createFirst"),
                        createTopic("anotherTopic")
                                .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId("createFirst")
                                .via(createTopicTxn)
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }
        }

        @Nested
        @DisplayName("TopicCreate with Custom Fee Failures on Pre-Handle")
        class TopicCreateCustomFeeFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName(
                    "TopicCreate with custom fee - invalid payer signature fails on pre-handle - network fee only, no custom fee surcharge")
            final Stream<DynamicTest> topicCreateCustomFeeInvalidPayerSigFailsOnPreHandle() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(COLLECTOR).balance(0L),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "4")),
                        createTopic(TOPIC)
                                .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .setNode("4")
                                .via(createTopicTxn)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                createTopicTxn,
                                txnSize -> expectedTopicCreateNetworkFeeOnlyUsd(Map.of(
                                        SIGNATURES, 1L,
                                        KEYS, 0L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1.0),
                        validateChargedAccount(createTopicTxn, "4"));
            }
        }

        @Nested
        @DisplayName("TopicCreate with Custom Fee Failures on Handle")
        class TopicCreateCustomFeeFailuresOnHandle {

            @HapiTest
            @DisplayName("TopicCreate with invalid collector fails on handle - full fee charged")
            final Stream<DynamicTest> topicCreateWithInvalidCollectorFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC)
                                .withConsensusCustomFee(
                                        fixedConsensusHbarFee(ONE_HBAR, "0.0.99999999")) // Invalid collector
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(createTopicTxn)
                                .hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR),
                        validateChargedUsdWithinWithTxnSize(
                                createTopicTxn,
                                txnSize -> expectedTopicCreateWithCustomFeeFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        KEYS, 0L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1.0),
                        validateChargedAccount(createTopicTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TopicCreate with deleted collector fails on handle - full fee charged")
            final Stream<DynamicTest> topicCreateWithDeletedCollectorFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(COLLECTOR).balance(0L),
                        cryptoDelete(COLLECTOR).transfer(PAYER),
                        createTopic(TOPIC)
                                .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR)) // Deleted collector
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(createTopicTxn)
                                .hasKnownStatus(ACCOUNT_DELETED),
                        validateChargedUsdWithinWithTxnSize(
                                createTopicTxn,
                                txnSize -> expectedTopicCreateWithCustomFeeFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        KEYS, 0L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1.0),
                        validateChargedAccount(createTopicTxn, PAYER));
            }

            @LeakyHapiTest
            @Tag(ONLY_SUBPROCESS)
            @DisplayName(
                    "TopicCreate with custom fee - duplicate transaction fails on handle - payer charged for first only")
            final Stream<DynamicTest> topicCreateCustomFeeDuplicateFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(COLLECTOR).balance(0L),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                        createTopic(TOPIC)
                                .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .fee(ONE_HUNDRED_HBARS)
                                .via(createTopicTxn),
                        createTopic("anotherTopic")
                                .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .setNode(3)
                                .txnId(DUPLICATE_TXN_ID)
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithinWithTxnSize(
                                createTopicTxn,
                                txnSize -> expectedTopicCreateWithCustomFeeFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        KEYS, 0L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1.0),
                        validateChargedAccount(createTopicTxn, PAYER));
            }
        }
    }
}
