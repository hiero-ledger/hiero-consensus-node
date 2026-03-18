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
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicUpdateNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsd;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;
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
 * Tests for TopicUpdate simple fees.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 * - Number of keys (extras beyond included)
 */
@Tag(MATS)
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
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    updateTopic(TOPIC)
                            .memo("updated memo")
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("topicUpdateTxn"),
                    validateChargedUsdWithin(
                            "topicUpdateTxn",
                            expectedTopicUpdateFullFeeUsd(2L, 0L), // 2 sigs, 0 extra keys
                            1.0));
        }

        @HapiTest
        @DisplayName("TopicUpdate - with new admin key (extra key + sig)")
        final Stream<DynamicTest> topicUpdateWithNewAdminKey() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(NEW_ADMIN_KEY),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    updateTopic(TOPIC)
                            .adminKey(NEW_ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY, NEW_ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("topicUpdateTxn"),
                    validateChargedUsdWithin(
                            "topicUpdateTxn",
                            expectedTopicUpdateFullFeeUsd(
                                    3L, 1L), // 3 sigs (payer + old admin + new admin), 1 extra key
                            1.0));
        }

        @HapiTest
        @DisplayName("TopicUpdate - with new submit key (extra key only)")
        final Stream<DynamicTest> topicUpdateWithNewSubmitKey() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(NEW_SUBMIT_KEY),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    updateTopic(TOPIC)
                            .submitKey(NEW_SUBMIT_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("topicUpdateTxn"),
                    validateChargedUsdWithin(
                            "topicUpdateTxn",
                            expectedTopicUpdateFullFeeUsd(2L, 1L), // 2 sigs, 1 extra key (new submit key)
                            1.0));
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
                            .sigControl(forKey(ADMIN_KEY, validSig))
                            .fee(ONE_HUNDRED_HBARS),
                    updateTopic(TOPIC)
                            .memo("updated memo")
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .sigControl(forKey(ADMIN_KEY, validSig))
                            .fee(ONE_HUNDRED_HBARS)
                            .via("topicUpdateTxn"),
                    validateChargedUsdWithin(
                            "topicUpdateTxn",
                            expectedTopicUpdateFullFeeUsd(3L, 0L), // 3 sigs (1 payer + 2 admin threshold), 0 extra keys
                            1.0));
        }

        @HapiTest
        @DisplayName("TopicUpdate - payer is admin (no extra sigs)")
        final Stream<DynamicTest> topicUpdatePayerIsAdmin() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC)
                            .adminKeyName(PAYER)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    updateTopic(TOPIC)
                            .memo("updated memo")
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("topicUpdateTxn"),
                    validateChargedUsdWithin(
                            "topicUpdateTxn",
                            expectedTopicUpdateFullFeeUsd(1L, 0L), // 1 sig (payer is admin), 0 extra keys
                            1.0));
        }

        @HapiTest
        @DisplayName("TopicUpdate - with both new admin and submit keys")
        final Stream<DynamicTest> topicUpdateWithBothNewKeys() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(NEW_ADMIN_KEY),
                    newKeyNamed(NEW_SUBMIT_KEY),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    updateTopic(TOPIC)
                            .adminKey(NEW_ADMIN_KEY)
                            .submitKey(NEW_SUBMIT_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY, NEW_ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("topicUpdateTxn"),
                    validateChargedUsdWithin(
                            "topicUpdateTxn",
                            expectedTopicUpdateFullFeeUsd(
                                    3L, 2L), // 3 sigs (payer + old admin + new admin), 2 extra keys (new admin + new
                            // submit)
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
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        updateTopic(TOPIC)
                                .memo("updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(1L) // Fee too low
                                .via("topicUpdateTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("topicUpdateTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TopicUpdate - missing payer signature fails on ingest - no fee charged")
            final Stream<DynamicTest> topicUpdateMissingPayerSignatureFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        updateTopic(TOPIC)
                                .memo("updated memo")
                                .payingWith(PAYER)
                                .signedBy(ADMIN_KEY) // Missing payer signature
                                .fee(ONE_HUNDRED_HBARS)
                                .via("topicUpdateTxn")
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord("topicUpdateTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TopicUpdate - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> topicUpdateMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        updateTopic(TOPIC)
                                .memo(LONG_MEMO)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("topicUpdateTxn")
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord("topicUpdateTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }
        }

        @Nested
        @DisplayName("TopicUpdate Failures on Pre-Handle")
        class TopicUpdateFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TopicUpdate - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> topicUpdateInvalidPayerSigFailsOnPreHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "topic-update-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "0.0.4"))
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance("0.0.4").exposingBalanceTo(initialNodeBalance::set),
                        updateTopic(TOPIC)
                                .memo("updated memo")
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, ADMIN_KEY)
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
                                expectedTopicUpdateNetworkFeeOnlyUsd(2L),
                                1.0));
            }
        }

        @Nested
        @DisplayName("TopicUpdate Failures on Handle")
        class TopicUpdateFailuresOnHandle {

            @HapiTest
            @DisplayName("TopicUpdate - invalid topic fails - fee charged")
            final Stream<DynamicTest> topicUpdateInvalidTopicFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        updateTopic("0.0.99999999") // Invalid topic
                                .memo("updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("topicUpdateTxn")
                                .hasKnownStatus(INVALID_TOPIC_ID),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "topicUpdateTxn",
                                initialBalance,
                                afterBalance,
                                expectedTopicUpdateFullFeeUsd(1L),
                                1.0));
            }

            @HapiTest
            @DisplayName("TopicUpdate - deleted topic fails - fee charged")
            final Stream<DynamicTest> topicUpdateDeletedTopicFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

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
                        updateTopic(TOPIC)
                                .memo("updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("topicUpdateTxn")
                                .hasKnownStatus(INVALID_TOPIC_ID),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "topicUpdateTxn",
                                initialBalance,
                                afterBalance,
                                expectedTopicUpdateFullFeeUsd(2L),
                                1.0));
            }

            @HapiTest
            @DisplayName("TopicUpdate - immutable topic (no admin key) submit key update fails - fee charged")
            final Stream<DynamicTest> topicUpdateImmutableTopicFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUBMIT_KEY),
                        createTopic(TOPIC)
                                // No admin key - topic is immutable
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        updateTopic(TOPIC)
                                .submitKey(SUBMIT_KEY) // Trying to add submit key to immutable topic
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("topicUpdateTxn")
                                .hasKnownStatus(UNAUTHORIZED),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "topicUpdateTxn",
                                initialBalance,
                                afterBalance,
                                expectedTopicUpdateFullFeeUsd(1L, 1L),
                                1.0));
            }

            @HapiTest
            @DisplayName("TopicUpdate - new admin key not signed fails at handle - fee charged")
            final Stream<DynamicTest> topicUpdateNewAdminKeyNotSignedFailsAtHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed(NEW_ADMIN_KEY),
                        createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        updateTopic(TOPIC)
                                .adminKey(NEW_ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY) // Missing new admin key signature
                                .fee(ONE_HUNDRED_HBARS)
                                .via("topicUpdateTxn")
                                .hasKnownStatus(INVALID_SIGNATURE),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "topicUpdateTxn",
                                initialBalance,
                                afterBalance,
                                expectedTopicUpdateFullFeeUsd(2L, 1L), // 2 sigs, 1 extra key (new admin key)
                                1.0));
            }
        }
    }
}
