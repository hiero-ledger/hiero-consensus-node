// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.consensus;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asTopicId;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.asOpArray;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CHUNK_NUMBER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CHUNK_TRANSACTION_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_MESSAGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MESSAGE_SIZE_TOO_LARGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

// This test cases are direct copies of SubmitMessageSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
public class AtomicSubmitMessageSuite {

    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @HapiTest
    final Stream<DynamicTest> pureCheckInvalidTopicIdFails() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate("nonTopicId"),
                atomicBatch(submitMessageTo(spec -> asTopicId(spec.registry().getAccountID("nonTopicId")))
                                .hasKnownStatus(INVALID_TOPIC_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> pureCheckNullTopicIdFails() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate("nonTopicId"),
                atomicBatch(submitMessageTo((String) null)
                                .hasKnownStatus(INVALID_TOPIC_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> topicIdIsValidated() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate("nonTopicId"),
                atomicBatch(submitMessageTo(spec -> asTopicId(spec.registry().getAccountID("nonTopicId")))
                                .hasRetryPrecheckFrom(BUSY)
                                .hasKnownStatus(INVALID_TOPIC_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> nullMessageFails() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                createTopic("testTopic"),
                atomicBatch(submitMessageTo("testTopic")
                                .clearMessage()
                                .hasRetryPrecheckFrom(BUSY)
                                .hasPrecheck(INVALID_TOPIC_MESSAGE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_TOPIC_MESSAGE));
    }

    @HapiTest
    final Stream<DynamicTest> emptyStringMessageFails() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                createTopic("testTopic"),
                atomicBatch(submitMessageTo("testTopic")
                                .message("")
                                .hasRetryPrecheckFrom(BUSY)
                                .hasPrecheck(INVALID_TOPIC_MESSAGE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_TOPIC_MESSAGE));
    }

    @HapiTest
    final Stream<DynamicTest> messageSubmissionSimple() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed("submitKey"),
                createTopic("testTopic").submitKeyName("submitKey").hasRetryPrecheckFrom(BUSY),
                cryptoCreate("civilian"),
                atomicBatch(submitMessageTo("testTopic")
                                .message("testmessage")
                                .payingWith("civilian")
                                .hasRetryPrecheckFrom(BUSY)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> messageSubmissionIncreasesSeqNo() {
        KeyShape submitKeyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                createTopic("testTopic").submitKeyShape(submitKeyShape),
                getTopicInfo("testTopic").hasSeqNo(0),
                atomicBatch(submitMessageTo("testTopic")
                                .message("Hello world!")
                                .hasRetryPrecheckFrom(BUSY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTopicInfo("testTopic").hasSeqNo(1));
    }

    @HapiTest
    final Stream<DynamicTest> messageSubmissionWithSubmitKey() {
        KeyShape submitKeyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

        SigControl validSig = submitKeyShape.signedWith(sigs(ON, OFF, sigs(ON, ON)));
        SigControl invalidSig = submitKeyShape.signedWith(sigs(ON, OFF, sigs(ON, OFF)));

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed("submitKey").shape(submitKeyShape),
                createTopic("testTopic").submitKeyName("submitKey"),
                atomicBatch(submitMessageTo("testTopic")
                                .sigControl(forKey("testTopicSubmit", invalidSig))
                                .hasRetryPrecheckFrom(BUSY)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(submitMessageTo("testTopic")
                                .sigControl(forKey("testTopicSubmit", validSig))
                                .hasRetryPrecheckFrom(BUSY)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> messageSubmissionMultiple() {
        final int numMessages = 10;

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                createTopic("testTopic").hasRetryPrecheckFrom(BUSY),
                inParallel(asOpArray(numMessages, i -> atomicBatch(submitMessageTo("testTopic")
                                .message("message")
                                .hasRetryPrecheckFrom(BUSY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR))),
                sleepFor(1000),
                getTopicInfo("testTopic").hasSeqNo(numMessages));
    }

    @HapiTest
    final Stream<DynamicTest> messageSubmissionOverSize() {
        final byte[] messageBytes = new byte[4096]; // 4k
        Arrays.fill(messageBytes, (byte) 0b1);

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed("submitKey"),
                createTopic("testTopic").submitKeyName("submitKey").hasRetryPrecheckFrom(BUSY),
                atomicBatch(submitMessageTo("testTopic")
                                .message(new String(messageBytes))
                                // In hedera-app we don't enforce such prechecks
                                .hasPrecheckFrom(TRANSACTION_OVERSIZE, BUSY, OK)
                                .hasKnownStatus(MESSAGE_SIZE_TOO_LARGE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> failsForChunkNumberGreaterThanTotalChunks() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                createTopic("testTopic"),
                atomicBatch(submitMessageTo("testTopic")
                                .message("failsForChunkNumberGreaterThanTotalChunks")
                                .chunkInfo(2, 3)
                                .hasRetryPrecheckFrom(BUSY)
                                .hasKnownStatus(INVALID_CHUNK_NUMBER)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> acceptsChunkNumberLessThanTotalChunks() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                createTopic("testTopic"),
                atomicBatch(submitMessageTo("testTopic")
                                .message("acceptsChunkNumberLessThanTotalChunks")
                                .chunkInfo(3, 2)
                                .hasRetryPrecheckFrom(BUSY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> acceptsChunkNumberEqualTotalChunks() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                createTopic("testTopic"),
                atomicBatch(submitMessageTo("testTopic")
                                .message("acceptsChunkNumberEqualTotalChunks")
                                .chunkInfo(5, 5)
                                .hasRetryPrecheckFrom(BUSY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> chunkTransactionIDIsValidated() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate("initialTransactionPayer"),
                createTopic("testTopic"),
                atomicBatch(submitMessageTo("testTopic")
                                .message("failsForDifferentPayers")
                                .chunkInfo(3, 2, "initialTransactionPayer")
                                .hasRetryPrecheckFrom(BUSY)
                                .hasKnownStatus(INVALID_CHUNK_TRANSACTION_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // Add delay to make sure the valid start of the transaction will
                // not match
                // that of the initialTransactionID
                sleepFor(1000),
                /* AcceptsChunkNumberDifferentThan1HavingTheSamePayerEvenWhenNotMatchingValidStart */
                atomicBatch(submitMessageTo("testTopic")
                                .message("A")
                                .chunkInfo(3, 3, "initialTransactionPayer")
                                .payingWith("initialTransactionPayer")
                                .hasRetryPrecheckFrom(BUSY)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                /* FailsForTransactionIDOfChunkNumber1NotMatchingTheEntireInitialTransactionID */
                sleepFor(1000),
                atomicBatch(submitMessageTo("testTopic")
                                .message("B")
                                .chunkInfo(2, 1)
                                // Also add delay here
                                .hasRetryPrecheckFrom(BUSY)
                                .hasKnownStatus(INVALID_CHUNK_TRANSACTION_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                /* AcceptsChunkNumber1WhenItsTransactionIDMatchesTheEntireInitialTransactionID */
                atomicBatch(submitMessageTo("testTopic")
                                .message("C")
                                .chunkInfo(4, 1)
                                .via("firstChunk")
                                .payingWith("initialTransactionPayer")
                                .usePresetTimestamp()
                                .hasRetryPrecheckFrom(BUSY)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }
}
