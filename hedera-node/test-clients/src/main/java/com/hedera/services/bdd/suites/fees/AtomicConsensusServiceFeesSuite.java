// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;

@HapiTestLifecycle
public class AtomicConsensusServiceFeesSuite {

    private static final double BASE_FEE_TOPIC_CREATE = 0.01;
    private static final double BASE_FEE_TOPIC_CREATE_WITH_CUSTOM_FEE = 2.00;
    private static final double TOPIC_CREATE_WITH_FIVE_CUSTOM_FEES = 2.10;
    private static final double BASE_FEE_TOPIC_UPDATE = 0.00022;
    private static final double BASE_FEE_TOPIC_DELETE = 0.005;
    private static final double BASE_FEE_TOPIC_SUBMIT_MESSAGE = 0.0001;

    private static final String PAYER = "payer";
    private static final String TOPIC_NAME = "testTopic";


    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
            Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @HapiTest
    @DisplayName("Topic create base USD fee as expected")
    final Stream<DynamicTest> topicCreateBaseUSDFee() {
        final var batchOperator = "batchOperator";

        return hapiTest(
            cryptoCreate(batchOperator),
            newKeyNamed("adminKey"),
            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
            cryptoCreate("collector"),
            cryptoCreate("treasury"),
            cryptoCreate("autoRenewAccount"),
            atomicBatch(
                createTopic(TOPIC_NAME).blankMemo().payingWith(PAYER).via("topicCreate").batchKey(batchOperator),
                createTopic("TopicWithCustomFee")
                    .blankMemo()
                    .payingWith(PAYER)
                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector"))
                    .via("topicCreateWithCustomFee").batchKey(batchOperator),
                createTopic("TopicWithMultipleCustomFees")
                    .blankMemo()
                    .payingWith(PAYER)
                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector"))
                    .withConsensusCustomFee(fixedConsensusHbarFee(2, "collector"))
                    .withConsensusCustomFee(fixedConsensusHbarFee(3, "collector"))
                    .withConsensusCustomFee(fixedConsensusHbarFee(4, "collector"))
                    .withConsensusCustomFee(fixedConsensusHbarFee(5, "collector"))
                    .via("topicCreateWithMultipleCustomFees")
                    .batchKey(batchOperator)
            ).via("atomicBatch").signedByPayerAnd(batchOperator),
            validateInnerTxnChargedUsd("topicCreate", "atomicBatch", BASE_FEE_TOPIC_CREATE, 6),
            validateInnerTxnChargedUsd("topicCreateWithCustomFee", "atomicBatch", BASE_FEE_TOPIC_CREATE_WITH_CUSTOM_FEE,
                5),
            validateInnerTxnChargedUsd("topicCreateWithMultipleCustomFees", "atomicBatch",
                TOPIC_CREATE_WITH_FIVE_CUSTOM_FEES, 5)
        );
    }

    @HapiTest
    @DisplayName("Topic update base USD fee as expected")
    final Stream<DynamicTest> topicUpdateBaseUSDFee() {
        final var batchOperator = "batchOperator";

        return hapiTest(
            cryptoCreate(batchOperator),
            cryptoCreate("autoRenewAccount"),
            cryptoCreate(PAYER),
            createTopic(TOPIC_NAME)
                .autoRenewAccountId("autoRenewAccount")
                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS - 1)
                .adminKeyName(PAYER),
            atomicBatch(
                updateTopic(TOPIC_NAME)
                    .payingWith(PAYER)
                    .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                    .via("updateTopic").batchKey(batchOperator)
            ).via("atomicBatch").signedByPayerAnd(batchOperator),
            validateInnerTxnChargedUsd("updateTopic", "atomicBatch", BASE_FEE_TOPIC_UPDATE, 10)
        );
    }

    @HapiTest
    @DisplayName("Topic delete base USD fee as expected")
    final Stream<DynamicTest> topicDeleteBaseUSDFee() {
        final var batchOperator = "batchOperator";
        return hapiTest(
            cryptoCreate(batchOperator),
            cryptoCreate(PAYER),
            createTopic(TOPIC_NAME).adminKeyName(PAYER),
            atomicBatch(
                deleteTopic(TOPIC_NAME).blankMemo().payingWith(PAYER).via("topicDelete").batchKey(batchOperator)
            ).via("atomicBatch").signedByPayerAnd(batchOperator),
            validateInnerTxnChargedUsd("topicDelete", "atomicBatch", BASE_FEE_TOPIC_DELETE, 10)
        );
    }

    @HapiTest
    @DisplayName("Topic submit message base USD fee as expected")
    final Stream<DynamicTest> topicSubmitMessageBaseUSDFee() {
        final var batchOperator = "batchOperator";
        final byte[] messageBytes = new byte[100]; // 4k
        Arrays.fill(messageBytes, (byte) 0b1);
        return hapiTest(
            cryptoCreate(batchOperator),
            cryptoCreate(PAYER).hasRetryPrecheckFrom(BUSY),
            createTopic(TOPIC_NAME).submitKeyName(PAYER).hasRetryPrecheckFrom(BUSY),

            atomicBatch(
                submitMessageTo(TOPIC_NAME)
                    .blankMemo()
                    .payingWith(PAYER)
                    .message(new String(messageBytes))
                    .hasRetryPrecheckFrom(BUSY)
                    .via("submitMessage")
                    .batchKey(batchOperator)
            ).via("atomicBatch").signedByPayerAnd(batchOperator),
            sleepFor(1000),
            validateInnerTxnChargedUsd("submitMessage", "atomicBatch", BASE_FEE_TOPIC_SUBMIT_MESSAGE, 6)
        );
    }

}
