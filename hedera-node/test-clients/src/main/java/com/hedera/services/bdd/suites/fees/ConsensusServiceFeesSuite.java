// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class ConsensusServiceFeesSuite {
    private static final double BASE_FEE_TOPIC_CREATE = 0.01;
    private static final double BASE_FEE_TOPIC_CREATE_WITH_CUSTOM_FEE = 2.00;
    private static final double TOPIC_CREATE_WITH_FIVE_CUSTOM_FEES = 2.10;
    private static final double BASE_FEE_TOPIC_UPDATE = 0.00022;
    private static final double BASE_FEE_TOPIC_DELETE = 0.005;
    private static final double BASE_FEE_TOPIC_SUBMIT_MESSAGE = 0.0001;
    private static final double BASE_FEE_TOPIC_GET_INFO = 0.0001;

    private static final String PAYER = "payer";
    private static final String TOPIC_NAME = "testTopic";

    @HapiTest
    @DisplayName("Topic create base USD fee as expected")
    @Tag(MATS)
    final Stream<DynamicTest> topicCreateBaseUSDFee() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed("adminKey"),
                cryptoCreate("collector"),
                cryptoCreate("treasury"),
                cryptoCreate("autoRenewAccount"),
                createTopic(TOPIC_NAME).blankMemo().payingWith(PAYER).via("topicCreate"),
                createTopic("TopicWithCustomFee")
                        .blankMemo()
                        .payingWith(PAYER)
                        .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector"))
                        .via("topicCreateWithCustomFee"),
                createTopic("TopicWithMultipleCustomFees")
                        .blankMemo()
                        .payingWith(PAYER)
                        .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector"))
                        .withConsensusCustomFee(fixedConsensusHbarFee(2, "collector"))
                        .withConsensusCustomFee(fixedConsensusHbarFee(3, "collector"))
                        .withConsensusCustomFee(fixedConsensusHbarFee(4, "collector"))
                        .withConsensusCustomFee(fixedConsensusHbarFee(5, "collector"))
                        .via("topicCreateWithMultipleCustomFees"),
                validateChargedUsd("topicCreate", BASE_FEE_TOPIC_CREATE),
                validateChargedUsd("topicCreateWithCustomFee", BASE_FEE_TOPIC_CREATE_WITH_CUSTOM_FEE, 1.5),
                validateChargedUsd("topicCreateWithMultipleCustomFees", TOPIC_CREATE_WITH_FIVE_CUSTOM_FEES, 1.5));
    }

    @HapiTest
    @DisplayName("Topic update base USD fee as expected")
    final Stream<DynamicTest> topicUpdateBaseUSDFee() {
        return hapiTest(
                cryptoCreate("autoRenewAccount"),
                cryptoCreate(PAYER),
                createTopic(TOPIC_NAME)
                        .autoRenewAccountId("autoRenewAccount")
                        .autoRenewPeriod(THREE_MONTHS_IN_SECONDS - 1)
                        .adminKeyName(PAYER),
                updateTopic(TOPIC_NAME)
                        .payingWith(PAYER)
                        .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                        .via("updateTopic"),
                validateChargedUsdWithin("updateTopic", BASE_FEE_TOPIC_UPDATE, 3.0));
    }

    @HapiTest
    @DisplayName("Topic delete base USD fee as expected")
    final Stream<DynamicTest> topicDeleteBaseUSDFee() {
        return hapiTest(
                cryptoCreate(PAYER),
                createTopic(TOPIC_NAME).adminKeyName(PAYER),
                deleteTopic(TOPIC_NAME).blankMemo().payingWith(PAYER).via("topicDelete"),
                validateChargedUsd("topicDelete", BASE_FEE_TOPIC_DELETE));
    }

    @HapiTest
    @DisplayName("Topic submit message base USD fee as expected")
    @Tag(MATS)
    final Stream<DynamicTest> topicSubmitMessageBaseUSDFee() {
        final byte[] messageBytes = new byte[100]; // 4k
        Arrays.fill(messageBytes, (byte) 0b1);
        return hapiTest(
                cryptoCreate(PAYER).hasRetryPrecheckFrom(BUSY),
                createTopic(TOPIC_NAME).submitKeyName(PAYER).hasRetryPrecheckFrom(BUSY),
                submitMessageTo(TOPIC_NAME)
                        .blankMemo()
                        .payingWith(PAYER)
                        .message(new String(messageBytes))
                        .hasRetryPrecheckFrom(BUSY)
                        .via("submitMessage"),
                sleepFor(1000),
                validateChargedUsd("submitMessage", BASE_FEE_TOPIC_SUBMIT_MESSAGE));
    }

    @HapiTest
    @DisplayName("Topic get info base USD fee as expected")
    final Stream<DynamicTest> tokenGetTopicInfoBaseUSDFee() {
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                cryptoCreate(PAYER),
                createTopic(TOPIC_NAME).adminKeyName(PAYER),
                getTopicInfo(TOPIC_NAME).payingWith(PAYER).via("getTopic"),
                sleepFor(1000),
                validateChargedUsd("getTopic", BASE_FEE_TOPIC_GET_INFO));
    }
}
