// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of ConsensusServiceFeesSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm the fees are the same
class AtomicConsensusServiceFeesSuite {

    private static final double BASE_FEE_TOPIC_CREATE = 0.01;
    private static final double BASE_FEE_TOPIC_CREATE_WITH_CUSTOM_FEE = 2.00;
    private static final double TOPIC_CREATE_WITH_FIVE_CUSTOM_FEES = 2.10;
    private static final double BASE_FEE_TOPIC_UPDATE = 0.00022;
    private static final double BASE_FEE_TOPIC_DELETE = 0.005;
    private static final double BASE_FEE_TOPIC_SUBMIT_MESSAGE = 0.0001;
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String ATOMIC_BATCH = "atomicBatch";

    private static final String PAYER = "payer";
    private static final String TOPIC_NAME = "testTopic";

    private HapiSpecOperation[] topicCreateSetup() {
        return new HapiSpecOperation[] {
            cryptoCreate(BATCH_OPERATOR),
            newKeyNamed("adminKey"),
            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
            cryptoCreate("collector"),
            cryptoCreate("treasury"),
            cryptoCreate("autoRenewAccount"),
        };
    }

    @HapiTest
    @DisplayName("Topic create base USD fee as expected")
    final Stream<DynamicTest> topicCreateBaseUSDFee() {
        return hapiTest(flattened(
                topicCreateSetup(),
                atomicBatch(createTopic(TOPIC_NAME)
                                .blankMemo()
                                .payingWith(PAYER)
                                .via("topicCreate")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("topicCreate", ATOMIC_BATCH, BASE_FEE_TOPIC_CREATE, 6)));
    }

    @HapiTest
    @DisplayName("Topic create with custom fee base USD fee as expected")
    final Stream<DynamicTest> topicCreateWithCustomFee() {
        return hapiTest(flattened(
                topicCreateSetup(),
                atomicBatch(createTopic("TopicWithCustomFee")
                                .blankMemo()
                                .payingWith(PAYER)
                                .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector"))
                                .via("topicCreateWithCustomFee")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(
                        "topicCreateWithCustomFee", ATOMIC_BATCH, BASE_FEE_TOPIC_CREATE_WITH_CUSTOM_FEE, 5)));
    }

    @HapiTest
    @DisplayName("Topic create with multiple custom fee base USD fee as expected")
    @Tag(MATS)
    final Stream<DynamicTest> topicCreateWithMultipleCustomFee() {
        return hapiTest(flattened(
                topicCreateSetup(),
                atomicBatch(createTopic("TopicWithMultipleCustomFees")
                                .blankMemo()
                                .payingWith(PAYER)
                                .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector"))
                                .withConsensusCustomFee(fixedConsensusHbarFee(2, "collector"))
                                .withConsensusCustomFee(fixedConsensusHbarFee(3, "collector"))
                                .withConsensusCustomFee(fixedConsensusHbarFee(4, "collector"))
                                .withConsensusCustomFee(fixedConsensusHbarFee(5, "collector"))
                                .via("topicCreateWithMultipleCustomFees")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(
                        "topicCreateWithMultipleCustomFees", ATOMIC_BATCH, TOPIC_CREATE_WITH_FIVE_CUSTOM_FEES, 5)));
    }

    @HapiTest
    @DisplayName("Topic update base USD fee as expected")
    @Tag(MATS)
    final Stream<DynamicTest> topicUpdateBaseUSDFee() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                cryptoCreate("autoRenewAccount"),
                cryptoCreate(PAYER),
                createTopic(TOPIC_NAME)
                        .autoRenewAccountId("autoRenewAccount")
                        .autoRenewPeriod(THREE_MONTHS_IN_SECONDS - 1)
                        .adminKeyName(PAYER),
                atomicBatch(updateTopic(TOPIC_NAME)
                                .payingWith(PAYER)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .via("updateTopic")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("updateTopic", ATOMIC_BATCH, BASE_FEE_TOPIC_UPDATE, 10));
    }

    @HapiTest
    @DisplayName("Topic delete base USD fee as expected")
    final Stream<DynamicTest> topicDeleteBaseUSDFee() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                cryptoCreate(PAYER),
                createTopic(TOPIC_NAME).adminKeyName(PAYER),
                atomicBatch(deleteTopic(TOPIC_NAME)
                                .blankMemo()
                                .payingWith(PAYER)
                                .via("topicDelete")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("topicDelete", ATOMIC_BATCH, BASE_FEE_TOPIC_DELETE, 10));
    }

    @HapiTest
    @DisplayName("Topic submit message base USD fee as expected")
    final Stream<DynamicTest> topicSubmitMessageBaseUSDFee() {
        final byte[] messageBytes = new byte[100]; // 4k
        Arrays.fill(messageBytes, (byte) 0b1);
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                cryptoCreate(PAYER).hasRetryPrecheckFrom(BUSY),
                createTopic(TOPIC_NAME).submitKeyName(PAYER).hasRetryPrecheckFrom(BUSY),
                atomicBatch(submitMessageTo(TOPIC_NAME)
                                .blankMemo()
                                .payingWith(PAYER)
                                .message(new String(messageBytes))
                                .hasRetryPrecheckFrom(BUSY)
                                .via("submitMessage")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                sleepFor(1000),
                validateInnerTxnChargedUsd("submitMessage", ATOMIC_BATCH, BASE_FEE_TOPIC_SUBMIT_MESSAGE, 6));
    }
}
