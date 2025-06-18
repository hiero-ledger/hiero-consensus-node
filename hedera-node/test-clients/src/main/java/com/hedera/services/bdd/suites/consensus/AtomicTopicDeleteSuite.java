// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.consensus;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asTopicId;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

// This test cases are direct copies of TopicDeleteSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
public class AtomicTopicDeleteSuite {

    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String ATOMIC_BATCH = "atomicBatch";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed("adminKey"),
                createTopic("topic").adminKeyName("adminKey"),
                atomicBatch(deleteTopic("topic").batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> pureCheckNonTopicIdFails() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate("nonTopicId"),
                atomicBatch(deleteTopic(spec -> asTopicId(spec.registry().getAccountID("nonTopicId")))
                                .hasPrecheck(INVALID_TOPIC_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> pureCheckNullTopicIdFails() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate("nonTopicId"),
                atomicBatch(deleteTopic((String) null)
                                .hasPrecheck(INVALID_TOPIC_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotDeleteAccountAsTopic() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate("nonTopicId"),
                atomicBatch(deleteTopic(spec -> asTopicId(spec.registry().getAccountID("nonTopicId")))
                                .hasKnownStatus(INVALID_TOPIC_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> topicIdIsValidated() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                deleteTopic((String) null).hasKnownStatus(INVALID_TOPIC_ID),
                atomicBatch(deleteTopic("100.232.4534") // non-existent id
                                .hasKnownStatus(INVALID_TOPIC_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> noAdminKeyCannotDelete() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                createTopic("testTopic"),
                atomicBatch(deleteTopic("testTopic")
                                .hasKnownStatus(UNAUTHORIZED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> deleteWithAdminKey() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed("adminKey"),
                createTopic("testTopic").adminKeyName("adminKey"),
                atomicBatch(deleteTopic("testTopic")
                                .hasPrecheck(ResponseCodeEnum.OK)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTopicInfo("testTopic").hasCostAnswerPrecheck(INVALID_TOPIC_ID));
    }

    @HapiTest
    final Stream<DynamicTest> deleteFailedWithWrongKey() {
        long PAYER_BALANCE = 1_999_999_999L;
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed("adminKey"),
                newKeyNamed("wrongKey"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                createTopic("testTopic").adminKeyName("adminKey"),
                atomicBatch(deleteTopic("testTopic")
                                .payingWith("payer")
                                .signedBy("payer", "wrongKey")
                                .hasKnownStatus(ResponseCodeEnum.INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> feeAsExpected() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate("payer"),
                createTopic("testTopic").adminKeyName("payer"),
                atomicBatch(deleteTopic("testTopic")
                                .blankMemo()
                                .payingWith("payer")
                                .via("topicDelete")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .via(ATOMIC_BATCH),
                validateInnerTxnChargedUsd("topicDelete", ATOMIC_BATCH, 0.005, 6));
    }
}
