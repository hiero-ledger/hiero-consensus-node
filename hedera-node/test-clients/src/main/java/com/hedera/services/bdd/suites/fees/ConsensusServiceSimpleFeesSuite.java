// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedSimpleFees;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForQueries;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFeeFromBytesFor;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicCreateWithCustomFeeFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicDeleteFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.signedTxnSizeFor;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SUBMIT_MESSAGE_FULL_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SUBMIT_MESSAGE_WITH_CUSTOM_FEE_BASE_USD;
import static org.hiero.hapi.support.fees.Extra.KEYS;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class ConsensusServiceSimpleFeesSuite {
    private static final double EXPECTED_CRYPTO_TRANSFER_FEE = 0.0001;

    @Nested
    class TopicFeesComparison {
        private static final String PAYER = "payer";
        private static final String ADMIN = "admin";

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare create topic")
        final Stream<DynamicTest> createTopicPlainComparison() {
            // Signatures: payer only; Keys: none.
            final var sigs = 1L;
            final var keys = 0L;
            return hapiTest(
                    overriding("fees.simpleFeesEnabled", "true"),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    withOpContext((spec, log) -> {
                        final var signedTxnSize = signedTxnSizeFor(spec, "create-topic-txn");
                        final var expectedFee = expectedTopicCreateFullFeeUsd(Map.of(
                                SIGNATURES, sigs,
                                KEYS, keys,
                                PROCESSING_BYTES, (long) signedTxnSize));
                        allRunFor(spec, validateChargedSimpleFees("Simple Fees", "create-topic-txn", expectedFee, 1));
                    }),
                    overriding("fees.simpleFeesEnabled", "false"));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare create topic with admin key")
        final Stream<DynamicTest> createTopicWithAdminComparison() {
            // Signatures: payer + admin key; Keys: 1 admin key
            final var sigs = 2L;
            final var keys = 1L;
            return hapiTest(
                    overriding("fees.simpleFeesEnabled", "true"),
                    newKeyNamed(ADMIN),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(ADMIN)
                            .fee(ONE_HBAR)
                            .via("create-topic-admin-txn"),
                    withOpContext((spec, log) -> {
                        final var signedTxnSize = signedTxnSizeFor(spec, "create-topic-admin-txn");
                        final var expectedFee = expectedTopicCreateFullFeeUsd(Map.of(
                                SIGNATURES, sigs,
                                KEYS, keys,
                                PROCESSING_BYTES, (long) signedTxnSize));
                        allRunFor(
                                spec,
                                validateChargedSimpleFees("Simple Fees", "create-topic-admin-txn", expectedFee, 1));
                    }),
                    overriding("fees.simpleFeesEnabled", "false"));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare create topic with payer as admin key")
        final Stream<DynamicTest> createTopicWithPayerAdminComparison() {
            // Signatures: payer only (admin key is payer); Keys: 1 admin key.
            final var sigs = 1L;
            final var keys = 1L;
            return hapiTest(
                    overriding("fees.simpleFeesEnabled", "true"),
                    newKeyNamed(ADMIN),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-admin-txn"),
                    withOpContext((spec, log) -> {
                        final var signedTxnSize = signedTxnSizeFor(spec, "create-topic-admin-txn");
                        final var expectedFee = expectedTopicCreateFullFeeUsd(Map.of(
                                SIGNATURES, sigs,
                                KEYS, keys,
                                PROCESSING_BYTES, (long) signedTxnSize));
                        allRunFor(
                                spec,
                                validateChargedSimpleFees("Simple Fees", "create-topic-admin-txn", expectedFee, 1));
                    }),
                    overriding("fees.simpleFeesEnabled", "false"));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare create topic with custom fee")
        final Stream<DynamicTest> createTopicCustomFeeComparison() {
            // Signatures: payer only; Keys: none.
            final var sigs = 1L;
            final var keys = 0L;
            return hapiTest(
                    overriding("fees.simpleFeesEnabled", "true"),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("collector"),
                    createTopic("testTopic")
                            .blankMemo()
                            .withConsensusCustomFee(fixedConsensusHbarFee(88, "collector"))
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("create-topic-txn"),
                    withOpContext((spec, log) -> {
                        final var signedTxnSize = signedTxnSizeFor(spec, "create-topic-txn");
                        final var expectedFee = expectedTopicCreateWithCustomFeeFullFeeUsd(Map.of(
                                SIGNATURES, sigs,
                                KEYS, keys,
                                PROCESSING_BYTES, (long) signedTxnSize));
                        allRunFor(spec, validateChargedSimpleFees("Simple Fees", "create-topic-txn", expectedFee, 1));
                    }),
                    overriding("fees.simpleFeesEnabled", "false"));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare update topic with admin key")
        final Stream<DynamicTest> updateTopicComparisonWithPayerAdmin() {
            // Signatures: payer only; Keys: none (no key change).
            final var sigs = 1L;
            final var keys = 0L;
            return hapiTest(
                    overriding("fees.simpleFeesEnabled", "true"),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-admin-txn"),
                    updateTopic("testTopic").payingWith(PAYER).fee(ONE_HBAR).via("update-topic-txn"),
                    withOpContext((spec, log) -> {
                        final var signedTxnSize = signedTxnSizeFor(spec, "update-topic-txn");
                        final var expectedFee = expectedTopicUpdateFullFeeUsd(Map.of(
                                SIGNATURES, sigs,
                                KEYS, keys,
                                PROCESSING_BYTES, (long) signedTxnSize));
                        allRunFor(spec, validateChargedSimpleFees("Simple Fees", "update-topic-txn", expectedFee, 1));
                    }),
                    overriding("fees.simpleFeesEnabled", "false"));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare update topic with admin key")
        final Stream<DynamicTest> updateTopicComparisonWithAdmin() {
            final String ADMIN = "admin";
            // Signatures: payer + admin key; Keys: 1 admin key
            final var sigs = 2L;
            final var keys = 1L;
            return hapiTest(
                    overriding("fees.simpleFeesEnabled", "true"),
                    newKeyNamed(ADMIN),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(ADMIN)
                            .fee(ONE_HBAR)
                            .via("create-topic-admin-txn"),
                    updateTopic("testTopic")
                            .adminKey(ADMIN)
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("update-topic-txn"),
                    withOpContext((spec, log) -> {
                        final var signedTxnSize = signedTxnSizeFor(spec, "update-topic-txn");
                        final var expectedFee = expectedTopicUpdateFullFeeUsd(Map.of(
                                SIGNATURES, sigs,
                                KEYS, keys,
                                PROCESSING_BYTES, (long) signedTxnSize));
                        allRunFor(spec, validateChargedSimpleFees("Simple Fees", "update-topic-txn", expectedFee, 1));
                    }),
                    overriding("fees.simpleFeesEnabled", "false"));
        }

        @HapiTest
        @DisplayName("compare get topic info")
        final Stream<DynamicTest> getTopicInfoComparison() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic"),
                    getTopicInfo("testTopic").payingWith(PAYER).via("getInfo"),
                    // we are paying with the crypto transfer fee
                    validateChargedUsdForQueries("getInfo", EXPECTED_CRYPTO_TRANSFER_FEE, 1));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare delete topic with admin key")
        final Stream<DynamicTest> deleteTopicPlainComparison() {
            // Signatures: payer only; Keys: none.
            final var sigs = 1L;
            return hapiTest(
                    overriding("fees.simpleFeesEnabled", "true"),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-admin-txn"),
                    deleteTopic("testTopic")
                            .signedBy(PAYER)
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("delete-topic-txn"),
                    withOpContext((spec, log) -> {
                        final var signedTxnSize = signedTxnSizeFor(spec, "delete-topic-txn");
                        final var expectedFee = expectedTopicDeleteFullFeeUsd(
                                Map.of(SIGNATURES, sigs, PROCESSING_BYTES, (long) signedTxnSize));
                        allRunFor(spec, validateChargedSimpleFees("Simple Fees", "delete-topic-txn", expectedFee, 1));
                    }),
                    overriding("fees.simpleFeesEnabled", "false"));
        }
    }

    @HapiTest
    final Stream<DynamicTest> submitMessageBaseFee() {
        return hapiTest(
                cryptoCreate("payer"),
                createTopic("topic1"),
                submitMessageTo("topic1")
                        .message("asdf")
                        .payingWith("payer")
                        .fee(ONE_HBAR)
                        .via("submitTxn"),
                validateChargedUsd("submitTxn", SUBMIT_MESSAGE_FULL_FEE_USD));
    }

    @HapiTest
    final Stream<DynamicTest> submitMessageWithCustomBaseFee() {
        return hapiTest(
                cryptoCreate("collector"),
                cryptoCreate("payer"),
                createTopic("customTopic").withConsensusCustomFee(fixedConsensusHbarFee(333, "collector")),
                submitMessageTo("customTopic")
                        .message("asdf")
                        .payingWith("payer")
                        .fee(ONE_HBAR)
                        .via("submitTxn"),
                validateChargedUsd("submitTxn", SUBMIT_MESSAGE_WITH_CUSTOM_FEE_BASE_USD));
    }

    @HapiTest
    final Stream<DynamicTest> maxAllowedBytesChargesAdditionalNodeFee() {
        final var payload = "a".repeat(1024);
        return hapiTest(
                cryptoCreate("payer"),
                createTopic("customTopic"),
                submitMessageTo("customTopic")
                        .message(payload)
                        .payingWith("payer")
                        .fee(ONE_HBAR)
                        .via("submitTxn"),
                withOpContext((spec, opLog) -> validateChargedUsd(
                        "submitTxn", SUBMIT_MESSAGE_FULL_FEE_USD + expectedFeeFromBytesFor(spec, opLog, "submitTxn"))));
    }
}
