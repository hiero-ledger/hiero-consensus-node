// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.compareSimpleToOld;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForQueries;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.BYTES_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SUBMIT_MESSAGE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SUBMIT_MESSAGE_WITH_CUSTOM_FEE_BASE_USD;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@Tag(MATS)
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
            return compareSimpleToOld(
                    () -> Arrays.asList(
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .fee(ONE_HBAR)
                                    .via("create-topic-txn")),
                    "create-topic-txn",
                    0.01000,
                    0.1,
                    0.01003,
                    1);
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare create topic with admin key")
        final Stream<DynamicTest> createTopicWithAdminComparison() {
            return compareSimpleToOld(
                    () -> Arrays.asList(
                            newKeyNamed(ADMIN),
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .adminKeyName(ADMIN)
                                    .fee(ONE_HBAR)
                                    .via("create-topic-admin-txn")),
                    "create-topic-admin-txn",
                    0.02109,
                    1,
                    0.02109,
                    1);
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare create topic with payer as admin key")
        final Stream<DynamicTest> createTopicWithPayerAdminComparison() {
            return compareSimpleToOld(
                    () -> Arrays.asList(
                            newKeyNamed(ADMIN),
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .adminKeyName(PAYER)
                                    .fee(ONE_HBAR)
                                    .via("create-topic-admin-txn")),
                    "create-topic-admin-txn",
                    0.02009,
                    1,
                    0.02009,
                    1);
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare create topic with custom fee")
        final Stream<DynamicTest> createTopicCustomFeeComparison() {
            return compareSimpleToOld(
                    () -> Arrays.asList(
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            cryptoCreate("collector"),
                            createTopic("testTopic")
                                    .blankMemo()
                                    .withConsensusCustomFee(fixedConsensusHbarFee(88, "collector"))
                                    .payingWith(PAYER)
                                    .fee(ONE_HUNDRED_HBARS)
                                    .via("create-topic-txn")),
                    "create-topic-txn",
                    2.0001,
                    1,
                    2,
                    5);
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare update topic with admin key")
        final Stream<DynamicTest> updateTopicComparisonWithPayerAdmin() {
            return compareSimpleToOld(
                    () -> Arrays.asList(
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .adminKeyName(PAYER)
                                    .fee(ONE_HBAR)
                                    .via("create-topic-admin-txn"),
                            updateTopic("testTopic")
                                    .payingWith(PAYER)
                                    .fee(ONE_HBAR)
                                    .via("update-topic-txn")),
                    "update-topic-txn",
                    0.00022,
                    1,
                    0.000218,
                    1);
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare update topic with admin key")
        final Stream<DynamicTest> updateTopicComparisonWithAdmin() {
            final String ADMIN = "admin";
            return compareSimpleToOld(
                    () -> Arrays.asList(
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
                                    .via("update-topic-txn")),
                    "update-topic-txn",
                    // base fee = 1200000
                    // node fee = base (100000) + 1 extra sig (1000000)
                    // network fee = node fee * 9
                    // total = 12200000
                    0.00122,
                    1,
                    0.00122,
                    1);
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
            return compareSimpleToOld(
                    () -> Arrays.asList(
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
                                    .via("delete-topic-txn")),
                    "delete-topic-txn",
                    0.005,
                    1,
                    0.005,
                    5);
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
                validateChargedUsd("submitTxn", SUBMIT_MESSAGE_BASE_FEE_USD));
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
        // 1153 is total transaction size - 1024 included bytes
        final var feeFromNodeBytes = (1153 - 1024) * (10 * BYTES_FEE_USD);
        return hapiTest(
                cryptoCreate("payer"),
                createTopic("customTopic"),
                submitMessageTo("customTopic")
                        .message(payload)
                        .payingWith("payer")
                        .memo("test")
                        .fee(ONE_HBAR)
                        .via("submitTxn"),
                validateChargedUsd("submitTxn", SUBMIT_MESSAGE_BASE_FEE_USD + feeFromNodeBytes));
    }
}
