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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForQueries;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;

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
    private static final String PAYER = "payer";
    private static final String ADMIN = "admin";

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

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare submit message with included bytes")
        final Stream<DynamicTest> submitMessageFeeWithIncludedBytesComparison() {
            // 100 is less than the free size, so there's no per byte charge
            final var byte_size = 100;
            final byte[] messageBytes = new byte[byte_size]; // up to 1k
            Arrays.fill(messageBytes, (byte) 0b1);
            return compareSimpleToOld(
                    () -> Arrays.asList(
                            newKeyNamed(PAYER),
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            // create topic, provide up to 1 hbar to pay for it
                            createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .fee(ONE_HBAR)
                                    .via("create-topic-txn"),
                            // submit message, provide up to 1 hbar to pay for it
                            submitMessageTo("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .message(new String(messageBytes))
                                    .fee(ONE_HBAR)
                                    .via("submit-message-txn")),
                    "submit-message-txn",
                    0.0001000,
                    1,
                    0.0001000,
                    1);
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare submit message with extra bytes")
        final Stream<DynamicTest> submitBiggerMessageFeeComparison() {
            // 100 is less than the free size, so there's no per byte charge
            final var byte_size = 1023;
            final byte[] messageBytes = new byte[byte_size]; // up to 1k
            Arrays.fill(messageBytes, (byte) 0b1);
            return compareSimpleToOld(
                    () -> Arrays.asList(
                            newKeyNamed(PAYER),
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            // create topic, provide up to 1 hbar to pay for it
                            createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .fee(ONE_HBAR)
                                    .via("create-topic-txn"),
                            // submit message, provide up to 1 hbar to pay for it
                            submitMessageTo("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .message(new String(messageBytes))
                                    .fee(ONE_HBAR)
                                    .via("submit-message-txn")),
                    "submit-message-txn",
                    // base == 0
                    // network + node = 1000000
                    // extra bytes = 1023-100= 923
                    // byte cost = 110000
                    // total = 102530000
                    0.01025,
                    1,
                    0.01025,
                    1);
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare submit message with custom fee and included bytes")
        final Stream<DynamicTest> submitCustomFeeMessageWithIncludedBytesComparison() {
            // 100 is less than the free size, so there's no per byte charge
            final var byte_size = 100;
            final byte[] messageBytes = new byte[byte_size]; // up to 1k
            Arrays.fill(messageBytes, (byte) 0b1);
            return compareSimpleToOld(
                    () -> Arrays.asList(
                            cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                            cryptoCreate("collector"),
                            createTopic("testTopic")
                                    .blankMemo()
                                    .withConsensusCustomFee(fixedConsensusHbarFee(88, "collector"))
                                    .payingWith(PAYER)
                                    .fee(ONE_HUNDRED_HBARS)
                                    .via("create-topic-txn"),
                            // submit message, provide up to 1 hbar to pay for it
                            submitMessageTo("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .message(new String(messageBytes))
                                    .fee(ONE_HUNDRED_HBARS)
                                    .via("submit-message-txn")),
                    "submit-message-txn",
                    0.05,
                    1,
                    0.05,
                    1);
        }
        // TODO: support queries
        //        @HapiTest()
        //        @DisplayName("compare get topic info")
        //        final Stream<DynamicTest> getTopicInfoComparison() {
        //            return compare(() -> Arrays.asList(
        //                    newKeyNamed(PAYER),
        //                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
        //                    // create topic. provide up to 1 hbar to pay for it
        //                    createTopic("testTopic")
        //                            .blankMemo()
        //                            .payingWith(PAYER)
        //                            .adminKeyName(PAYER)
        //                            .fee(ONE_HBAR)
        //                            .via("create-topic-txn"),
        //                    // the extra 10 is for the admin key
        //                    validateChargedUsd("create-topic-txn", 0.01022),
        //                    // get topic info, provide up to 1 hbar to pay for it
        //                    getTopicInfo("testTopic")
        //                            .payingWith(PAYER)
        //                            .fee(ONE_HBAR)
        //                            .via("get-topic-txn")
        //                            .logged(),
        //                    validateChargedUsd("get-topic-txn", 0.000101)));
        //        }

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
}
