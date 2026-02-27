// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicDeleteFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.signedTxnSizeFor;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsd;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
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
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    deleteTopic(TOPIC)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("topicDeleteTxn"),
                    validateChargedUsdWithin(
                            "topicDeleteTxn",
                            expectedTopicDeleteFullFeeUsd(2L), // 2 sigs (payer + admin)
                            1.0));
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
                            .sigControl(forKey(ADMIN_KEY, validSig))
                            .fee(ONE_HUNDRED_HBARS),
                    deleteTopic(TOPIC)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .sigControl(forKey(ADMIN_KEY, validSig))
                            .fee(ONE_HUNDRED_HBARS)
                            .via("topicDeleteTxn"),
                    validateChargedUsdWithin(
                            "topicDeleteTxn",
                            expectedTopicDeleteFullFeeUsd(3L), // 3 sigs (1 payer + 2 admin threshold)
                            1.0));
        }

        @HapiTest
        @DisplayName("TopicDelete - payer is admin (no extra sig)")
        final Stream<DynamicTest> topicDeletePayerIsAdmin() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC)
                            .adminKeyName(PAYER)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    deleteTopic(TOPIC)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("topicDeleteTxn"),
                    validateChargedUsdWithin(
                            "topicDeleteTxn",
                            expectedTopicDeleteFullFeeUsd(1L), // 1 sig (payer is admin)
                            1.0));
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
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(1L) // Fee too low
                                .via("topicDeleteTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("topicDeleteTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TopicDelete - missing payer signature fails on ingest - no fee charged")
            final Stream<DynamicTest> topicDeleteMissingPayerSignatureFailsOnIngest() {
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
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .signedBy(ADMIN_KEY) // Missing payer signature
                                .fee(ONE_HUNDRED_HBARS)
                                .via("topicDeleteTxn")
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord("topicDeleteTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }
        }

        @Nested
        @DisplayName("TopicDelete Failures on Handle")
        class TopicDeleteFailuresOnHandle {

            @HapiTest
            @DisplayName("TopicDelete - invalid topic fails - fee charged")
            final Stream<DynamicTest> topicDeleteInvalidTopicFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        deleteTopic("0.0.99999999") // Invalid topic
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("topicDeleteTxn")
                                .hasKnownStatus(INVALID_TOPIC_ID),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "topicDeleteTxn",
                                initialBalance,
                                afterBalance,
                                expectedTopicDeleteFullFeeUsd(1L),
                                1.0));
            }

            @HapiTest
            @DisplayName("TopicDelete - missing admin key signature fails at handle - fee charged")
            final Stream<DynamicTest> topicDeleteMissingAdminKeySignatureFailsAtHandle() {
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
                                .signedBy(PAYER) // Missing admin key signature
                                .fee(ONE_HUNDRED_HBARS)
                                .via("topicDeleteTxn")
                                .hasKnownStatus(INVALID_SIGNATURE),
                        withOpContext((spec, log) -> allRunFor(
                                spec,
                                validateChargedUsd(
                                        "topicDeleteTxn",
                                        expectedTopicDeleteFullFeeUsd(1L, signedTxnSizeFor(spec, "topicDeleteTxn"))))));
            }

            @HapiTest
            @DisplayName("TopicDelete - immutable topic (no admin key) fails - fee charged")
            final Stream<DynamicTest> topicDeleteImmutableTopicFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic(TOPIC)
                                // No admin key - topic is immutable
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        deleteTopic(TOPIC)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("topicDeleteTxn")
                                .hasKnownStatus(UNAUTHORIZED),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "topicDeleteTxn",
                                initialBalance,
                                afterBalance,
                                expectedTopicDeleteFullFeeUsd(1L),
                                1.0));
            }
        }
    }
}
