// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.consensus;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asTopicId;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.chunkAFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.asOpArray;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
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
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class SubmitMessageSuite {
    private static final int CHUNK_SIZE = 1024;

    @HapiTest
    final Stream<DynamicTest> pureCheckFails() {
        return hapiTest(
                cryptoCreate("nonTopicId"),
                submitMessageTo(spec -> asTopicId(spec.registry().getAccountID("nonTopicId")))
                        .hasPrecheck(INVALID_TOPIC_ID),
                submitMessageTo((String) null).hasPrecheck(INVALID_TOPIC_ID));
    }

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return hapiTest(createTopic("testTopic"), submitModified(withSuccessivelyVariedBodyIds(), () -> submitMessageTo(
                        "testTopic")
                .message("HI")));
    }

    @HapiTest
    final Stream<DynamicTest> topicIdIsValidated() {
        return hapiTest(
                cryptoCreate("nonTopicId"),
                submitMessageTo((String) null).hasRetryPrecheckFrom(BUSY).hasKnownStatus(INVALID_TOPIC_ID),
                submitMessageTo("1.2.3").hasRetryPrecheckFrom(BUSY).hasKnownStatus(INVALID_TOPIC_ID),
                submitMessageTo(spec -> asTopicId(spec.registry().getAccountID("nonTopicId")))
                        .hasRetryPrecheckFrom(BUSY)
                        .hasKnownStatus(INVALID_TOPIC_ID));
    }

    @HapiTest
    final Stream<DynamicTest> messageIsValidated() {
        return hapiTest(
                createTopic("testTopic"),
                submitMessageTo("testTopic")
                        .clearMessage()
                        .hasRetryPrecheckFrom(BUSY)
                        .hasPrecheck(INVALID_TOPIC_MESSAGE),
                submitMessageTo("testTopic")
                        .message("")
                        .hasRetryPrecheckFrom(BUSY)
                        .hasPrecheck(INVALID_TOPIC_MESSAGE));
    }

    @HapiTest
    final Stream<DynamicTest> messageSubmissionSimple() {
        return hapiTest(
                newKeyNamed("submitKey"),
                createTopic("testTopic").submitKeyName("submitKey").hasRetryPrecheckFrom(BUSY),
                cryptoCreate("civilian"),
                submitMessageTo("testTopic")
                        .message("testmessage")
                        .payingWith("civilian")
                        .hasRetryPrecheckFrom(BUSY)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    final Stream<DynamicTest> messageSubmissionIncreasesSeqNo() {
        KeyShape submitKeyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

        return hapiTest(
                createTopic("testTopic").submitKeyShape(submitKeyShape),
                getTopicInfo("testTopic").hasSeqNo(0),
                submitMessageTo("testTopic").message("Hello world!").hasRetryPrecheckFrom(BUSY),
                getTopicInfo("testTopic").hasSeqNo(1));
    }

    @HapiTest
    final Stream<DynamicTest> messageSubmissionWithSubmitKey() {
        KeyShape submitKeyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

        SigControl validSig = submitKeyShape.signedWith(sigs(ON, OFF, sigs(ON, ON)));
        SigControl invalidSig = submitKeyShape.signedWith(sigs(ON, OFF, sigs(ON, OFF)));

        return hapiTest(
                newKeyNamed("submitKey").shape(submitKeyShape),
                createTopic("testTopic").submitKeyName("submitKey"),
                submitMessageTo("testTopic")
                        .sigControl(forKey("testTopicSubmit", invalidSig))
                        .hasRetryPrecheckFrom(BUSY)
                        .hasKnownStatus(INVALID_SIGNATURE),
                submitMessageTo("testTopic")
                        .sigControl(forKey("testTopicSubmit", validSig))
                        .hasRetryPrecheckFrom(BUSY)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    final Stream<DynamicTest> messageSubmissionMultiple() {
        final int numMessages = 10;

        return hapiTest(
                createTopic("testTopic").hasRetryPrecheckFrom(BUSY),
                inParallel(asOpArray(
                        numMessages,
                        i -> submitMessageTo("testTopic").message("message").hasRetryPrecheckFrom(BUSY))),
                sleepFor(1000),
                getTopicInfo("testTopic").hasSeqNo(numMessages));
    }

    @HapiTest
    final Stream<DynamicTest> messageSubmissionOverSize() {
        final byte[] messageBytes = new byte[4096]; // 4k
        Arrays.fill(messageBytes, (byte) 0b1);

        return hapiTest(
                newKeyNamed("submitKey"),
                createTopic("testTopic").submitKeyName("submitKey").hasRetryPrecheckFrom(BUSY),
                submitMessageTo("testTopic")
                        .message(new String(messageBytes))
                        // In hedera-app we don't enforce such prechecks
                        .hasPrecheckFrom(TRANSACTION_OVERSIZE, BUSY, OK)
                        .hasKnownStatus(MESSAGE_SIZE_TOO_LARGE));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> messageSubmissionCorrectlyUpdatesRunningHash() {
        String topic = "testTopic";
        String message1 = "Hello world!";
        String message2 = "Hello world again!";
        String nonsense = "Nonsense";
        String message3 = "Goodbye!";

        return hapiTest(
                createTopic(topic),
                getTopicInfo(topic).hasSeqNo(0).hasRunningHash(new byte[48]).saveRunningHash(),
                submitMessageTo(topic)
                        .message(message1)
                        .hasRetryPrecheckFrom(BUSY)
                        .via("submitMessage1"),
                getTxnRecord("submitMessage1").hasCorrectRunningHash(topic, message1),
                submitMessageTo(topic)
                        .message(message2)
                        .hasRetryPrecheckFrom(BUSY)
                        .via("submitMessage2"),
                getTxnRecord("submitMessage2").hasCorrectRunningHash(topic, message2),
                submitMessageTo(topic)
                        .message(nonsense)
                        .via("nonsense")
                        .chunkInfo(3, 4)
                        .hasRetryPrecheckFrom(BUSY)
                        .hasKnownStatus(INVALID_CHUNK_NUMBER),
                getTxnRecord("nonsense").hasCorrectRunningHash(topic, message2).logged(),
                submitMessageTo(topic)
                        .message(message3)
                        .hasRetryPrecheckFrom(BUSY)
                        .via("submitMessage3"),
                getTxnRecord("submitMessage3").hasCorrectRunningHash(topic, message3));
    }

    @HapiTest
    final Stream<DynamicTest> chunkNumberIsValidated() {
        return hapiTest(
                createTopic("testTopic"),
                submitMessageTo("testTopic")
                        .message("failsForChunkNumberGreaterThanTotalChunks")
                        .chunkInfo(2, 3)
                        .hasRetryPrecheckFrom(BUSY)
                        .hasKnownStatus(INVALID_CHUNK_NUMBER),
                submitMessageTo("testTopic")
                        .message("acceptsChunkNumberLessThanTotalChunks")
                        .chunkInfo(3, 2)
                        .hasRetryPrecheckFrom(BUSY)
                        .hasKnownStatus(SUCCESS),
                submitMessageTo("testTopic")
                        .message("acceptsChunkNumberEqualTotalChunks")
                        .chunkInfo(5, 5)
                        .hasRetryPrecheckFrom(BUSY)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    final Stream<DynamicTest> chunkTransactionIDIsValidated() {
        return hapiTest(
                cryptoCreate("initialTransactionPayer"),
                createTopic("testTopic"),
                submitMessageTo("testTopic")
                        .message("failsForDifferentPayers")
                        .chunkInfo(3, 2, "initialTransactionPayer")
                        .hasRetryPrecheckFrom(BUSY)
                        .hasKnownStatus(INVALID_CHUNK_TRANSACTION_ID),
                // Add delay to make sure the valid start of the transaction will
                // not match
                // that of the initialTransactionID
                sleepFor(1000),
                /* AcceptsChunkNumberDifferentThan1HavingTheSamePayerEvenWhenNotMatchingValidStart */
                submitMessageTo("testTopic")
                        .message("A")
                        .chunkInfo(3, 3, "initialTransactionPayer")
                        .payingWith("initialTransactionPayer")
                        .hasRetryPrecheckFrom(BUSY)
                        .hasKnownStatus(SUCCESS),
                /* FailsForTransactionIDOfChunkNumber1NotMatchingTheEntireInitialTransactionID */
                sleepFor(1000),
                submitMessageTo("testTopic")
                        .message("B")
                        .chunkInfo(2, 1)
                        // Also add delay here
                        .hasRetryPrecheckFrom(BUSY)
                        .hasKnownStatus(INVALID_CHUNK_TRANSACTION_ID),
                /* AcceptsChunkNumber1WhenItsTransactionIDMatchesTheEntireInitialTransactionID */
                submitMessageTo("testTopic")
                        .message("C")
                        .chunkInfo(4, 1)
                        .via("firstChunk")
                        .payingWith("initialTransactionPayer")
                        .usePresetTimestamp()
                        .hasRetryPrecheckFrom(BUSY)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> longMessageIsFragmentedIntoChunks() {
        String fileForLongMessage = "src/main/resources/RandomLargeBinary.bin";
        return hapiTest(flattened(
                cryptoCreate("payer"),
                createTopic("testTopic"),
                chunkAFile(fileForLongMessage, CHUNK_SIZE, "payer", "testTopic")));
    }
}
