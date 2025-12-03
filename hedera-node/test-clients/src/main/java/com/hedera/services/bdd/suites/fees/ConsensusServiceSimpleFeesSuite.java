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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForQueries;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class ConsensusServiceSimpleFeesSuite {
    private static final long OFFERED_QUERY_FEE = 83333;
    private static final double GET_TOPIC_INFO_BASE_FEE = 0.0001;
    private static final double EXPECTED_CRYPTO_TRANSFER_FEE = 0.0001;
    private static final double TOPIC_CREATE_BASE_FEE = 0.01;
    private static final double TOPIC_UPDATE_BASE_FEE = 0.00022;
    private static final double SUBMIT_MESSAGE_BASE_FEE = 0.0008;
    private static final double DELETE_TOPIC_BASE_FEE = 0.005;
    private static final double SINGLE_KEY_FEE = 0.01;
    private static final double SINGLE_SIGNATURE_FEE = 0.001;
    private static final double SINGLE_BYTE_FEE = 0.000011;
    private static final String PAYER = "payer";
    private static final String ADMIN = "admin";

    @HapiTest
    @DisplayName("compare create topic")
    final Stream<DynamicTest> createTopicPlainComparison() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                createTopic("testTopic")
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("create-topic-txn"),
                validateChargedUsd("create-topic-txn", TOPIC_CREATE_BASE_FEE));
    }

    @HapiTest
    @DisplayName("compare create topic with admin key")
    final Stream<DynamicTest> createTopicWithAdminComparison() {
        return hapiTest(
                newKeyNamed(ADMIN),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                createTopic("testTopic")
                        .blankMemo()
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN)
                        .adminKeyName(ADMIN)
                        .fee(ONE_HBAR)
                        .via("create-topic-admin-txn"),
                validateChargedUsd(
                        "create-topic-admin-txn", TOPIC_CREATE_BASE_FEE + SINGLE_KEY_FEE + SINGLE_SIGNATURE_FEE));
    }

    @HapiTest
    @DisplayName("compare create topic with payer as admin key")
    final Stream<DynamicTest> createTopicWithPayerAdminComparison() {
        return hapiTest(
                newKeyNamed(ADMIN),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                createTopic("testTopic")
                        .blankMemo()
                        .payingWith(PAYER)
                        .adminKeyName(PAYER)
                        .fee(ONE_HBAR)
                        .via("create-topic-admin-txn"),
                validateChargedUsd("create-topic-admin-txn", TOPIC_CREATE_BASE_FEE + SINGLE_KEY_FEE));
    }

    @HapiTest
    @DisplayName("compare update topic with admin key")
    final Stream<DynamicTest> updateTopicComparisonWithPayerAdmin() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                createTopic("testTopic").payingWith(PAYER).adminKeyName(PAYER).fee(ONE_HBAR),
                updateTopic("testTopic")
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("update-topic-txn"),
                validateChargedUsd("update-topic-txn", TOPIC_UPDATE_BASE_FEE));
    }

    @HapiTest
    @DisplayName("compare update topic with admin key")
    final Stream<DynamicTest> updateTopicComparisonWithAdmin() {
        final String ADMIN = "admin";
        return hapiTest(
                newKeyNamed(ADMIN),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                createTopic("testTopic").payingWith(PAYER).adminKeyName(ADMIN).fee(ONE_HBAR),
                updateTopic("testTopic")
                        .adminKey(ADMIN)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN)
                        .fee(ONE_HBAR)
                        .via("update-topic-txn"),
                validateChargedUsd("update-topic-txn", TOPIC_UPDATE_BASE_FEE + SINGLE_SIGNATURE_FEE));
    }

    @HapiTest
    @DisplayName("compare submit message with included bytes")
    final Stream<DynamicTest> submitMessageFeeWithIncludedBytesComparison() {
        // 100 is less than the free size, so there's no per byte charge
        final var byte_size = 100;
        final byte[] messageBytes = new byte[byte_size]; // up to 1k
        Arrays.fill(messageBytes, (byte) 0b1);
        return hapiTest(
                newKeyNamed(PAYER),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                createTopic("testTopic").payingWith(PAYER).fee(ONE_HBAR),
                submitMessageTo("testTopic")
                        .payingWith(PAYER)
                        .message(new String(messageBytes))
                        .fee(ONE_HBAR)
                        .via("submit-message-txn"),
                validateChargedUsd("submit-message-txn", SUBMIT_MESSAGE_BASE_FEE));
    }

    @HapiTest
    @DisplayName("compare submit message with extra bytes")
    final Stream<DynamicTest> submitBiggerMessageFeeComparison() {
        // 100 is less than the free size, so there's no per byte charge
        final var byte_size = 1023;
        final byte[] messageBytes = new byte[byte_size]; // up to 1k
        Arrays.fill(messageBytes, (byte) 0b1);
        return hapiTest(
                newKeyNamed(PAYER),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                createTopic("testTopic").payingWith(PAYER).fee(ONE_HBAR),
                submitMessageTo("testTopic")
                        .blankMemo()
                        .payingWith(PAYER)
                        .message(new String(messageBytes))
                        .fee(ONE_HBAR)
                        .via("submit-message-txn"),
                validateChargedUsd(
                        "submit-message-txn", SUBMIT_MESSAGE_BASE_FEE + (byte_size - 100) * SINGLE_BYTE_FEE));
    }

    @HapiTest
    @DisplayName("compare get topic info")
    final Stream<DynamicTest> getTopicInfoComparison() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                createTopic("testTopic"),
                getTopicInfo("testTopic").payingWith(PAYER).via("getInfo").nodePayment(OFFERED_QUERY_FEE),
                validateChargedUsdForQueries("getInfo", GET_TOPIC_INFO_BASE_FEE + EXPECTED_CRYPTO_TRANSFER_FEE, 1));
    }

    @HapiTest
    @DisplayName("compare delete topic with admin key")
    final Stream<DynamicTest> deleteTopicPlainComparison() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                createTopic("testTopic").payingWith(PAYER).adminKeyName(PAYER).fee(ONE_HBAR),
                deleteTopic("testTopic")
                        .signedBy(PAYER)
                        .payingWith(PAYER)
                        .fee(ONE_HBAR)
                        .via("delete-topic-txn"),
                validateChargedUsd("delete-topic-txn", DELETE_TOPIC_BASE_FEE));
    }
}
