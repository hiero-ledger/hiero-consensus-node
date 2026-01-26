// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicCreateWithCustomFeeFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsd;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
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
 * Tests for TopicCreate with custom fees simple fees.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 * - Number of keys (extras beyond included)
 * - Custom fee surcharge
 */
@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TopicCreateCustomFeeSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String ADMIN_KEY = "adminKey";
    private static final String SUBMIT_KEY = "submitKey";
    private static final String FEE_SCHEDULE_KEY = "feeScheduleKey";
    private static final String COLLECTOR = "collector";
    private static final String TOPIC = "testTopic";

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
                            .fee(ONE_HUNDRED_HBARS)
                            .via("createTopicTxn"),
                    validateChargedUsdWithin(
                            "createTopicTxn",
                            expectedTopicCreateWithCustomFeeFullFeeUsd(1L, 0L), // 1 sig, 0 extra keys, with custom fee
                            1.0));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .via("createTopicTxn"),
                    validateChargedUsdWithin(
                            "createTopicTxn",
                            expectedTopicCreateWithCustomFeeFullFeeUsd(
                                    2L, 1L), // 2 sigs (payer + admin), 1 extra key, with custom fee
                            1.0));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .via("createTopicTxn"),
                    validateChargedUsdWithin(
                            "createTopicTxn",
                            expectedTopicCreateWithCustomFeeFullFeeUsd(
                                    2L, 2L), // 2 sigs, 2 extra keys (admin + submit), with custom fee
                            1.0));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .via("createTopicTxn"),
                    validateChargedUsdWithin(
                            "createTopicTxn",
                            expectedTopicCreateWithCustomFeeFullFeeUsd(
                                    3L, 2L), // 3 sigs (1 payer + 2 admin threshold), 2 extra keys (threshold key)
                            1.0));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .via("createTopicTxn"),
                    validateChargedUsdWithin(
                            "createTopicTxn",
                            expectedTopicCreateWithCustomFeeFullFeeUsd(1L, 0L), // Single surcharge regardless of count
                            1.0));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .via("createTopicTxn"),
                    validateChargedUsdWithin(
                            "createTopicTxn",
                            expectedTopicCreateFullFeeUsd(1L, 0L), // Standard fee without custom fee surcharge
                            1.0));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .via("createTopicTxn"),
                    validateChargedUsdWithin(
                            "createTopicTxn",
                            expectedTopicCreateWithCustomFeeFullFeeUsd(
                                    1L, 1L), // 1 sig, 1 extra key (fee schedule key), with custom fee
                            1.0));
        }
    }

    @Nested
    @DisplayName("TopicCreate with Custom Fee Simple Fees Negative Test Cases")
    class TopicCreateCustomFeeNegativeTestCases {

        @HapiTest
        @DisplayName("TopicCreate with invalid collector - fee charged")
        final Stream<DynamicTest> topicCreateWithInvalidCollectorFails() {
            final AtomicLong initialBalance = new AtomicLong();
            final AtomicLong afterBalance = new AtomicLong();

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    createTopic(TOPIC)
                            .withConsensusCustomFee(
                                    fixedConsensusHbarFee(ONE_HBAR, "0.0.99999999")) // Invalid collector
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("createTopicTxn")
                            .hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR),
                    getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    withOpContext((spec, log) -> {
                        assertTrue(initialBalance.get() > afterBalance.get());
                    }),
                    validateChargedFeeToUsd(
                            "createTopicTxn",
                            initialBalance,
                            afterBalance,
                            expectedTopicCreateWithCustomFeeFullFeeUsd(1L, 0L),
                            1.0));
        }

        @HapiTest
        @DisplayName("TopicCreate with deleted collector - fee charged")
        final Stream<DynamicTest> topicCreateWithDeletedCollectorFails() {
            final AtomicLong initialBalance = new AtomicLong();
            final AtomicLong afterBalance = new AtomicLong();

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(COLLECTOR).balance(0L),
                    cryptoDelete(COLLECTOR).transfer(PAYER),
                    getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    createTopic(TOPIC)
                            .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR)) // Deleted collector
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("createTopicTxn")
                            .hasKnownStatus(ACCOUNT_DELETED),
                    getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    withOpContext((spec, log) -> {
                        assertTrue(initialBalance.get() > afterBalance.get());
                    }),
                    validateChargedFeeToUsd(
                            "createTopicTxn",
                            initialBalance,
                            afterBalance,
                            expectedTopicCreateWithCustomFeeFullFeeUsd(1L, 0L),
                            1.0));
        }
    }
}
