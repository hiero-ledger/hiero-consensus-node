// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOPIC_MESSAGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.consensus.impl.handlers.ConsensusSubmitMessageHandler.RUNNING_HASH_VERSION;
import static com.hedera.node.app.service.consensus.impl.handlers.ConsensusSubmitMessageHandler.noThrowSha384HashOf;
import static com.hedera.node.app.service.consensus.impl.schemas.V0490ConsensusSchema.TOPICS_STATE_ID;
import static com.hedera.node.app.service.consensus.impl.test.handlers.ConsensusTestUtils.SIMPLE_KEY_A;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.consensus.ConsensusMessageChunkInfo;
import com.hedera.hapi.node.consensus.ConsensusSubmitMessageTransactionBody;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.transaction.CustomFeeLimit;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.service.consensus.impl.ReadableTopicStoreImpl;
import com.hedera.node.app.service.consensus.impl.WritableTopicStore;
import com.hedera.node.app.service.consensus.impl.handlers.ConsensusSubmitMessageHandler;
import com.hedera.node.app.service.consensus.impl.handlers.customfee.ConsensusCustomFeeAssessor;
import com.hedera.node.app.service.consensus.impl.records.ConsensusSubmitMessageStreamBuilder;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.records.CryptoTransferStreamBuilder;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.key.KeyVerifier;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsensusSubmitMessageHandlerTest extends ConsensusTestBase {
    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock(answer = RETURNS_SELF)
    private ConsensusSubmitMessageStreamBuilder recordBuilder;

    @Mock(strictness = LENIENT)
    private HandleContext.SavepointStack stack;

    @Mock
    private WritableEntityCounters entityCounters;

    @Mock
    private KeyVerifier keyVerifier;

    @Mock
    private SignatureVerification signatureVerification;

    @Mock
    private CryptoTransferStreamBuilder streamBuilder;

    private ConsensusCustomFeeAssessor customFeeAssessor;

    private ConsensusSubmitMessageHandler subject;

    private static final Key ED25519KEY = Key.newBuilder()
            .ed25519(Bytes.fromHex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
            .build();

    private static final Key ECDSAKEY = Key.newBuilder()
            .ecdsaSecp256k1((Bytes.fromHex("0101010101010101010101010101010101010101010101010101010101010101")))
            .build();

    @BeforeEach
    void setUp() {
        commonSetUp();
        customFeeAssessor = spy(new ConsensusCustomFeeAssessor());
        subject = new ConsensusSubmitMessageHandler(customFeeAssessor);

        final var config = HederaTestConfigBuilder.create()
                .withValue("consensus.message.maxBytesAllowed", 100)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        writableTopicState = writableTopicStateWithOneKey();
        given(readableStates.<TopicID, Topic>get(TOPICS_STATE_ID)).willReturn(readableTopicState);
        given(writableStates.<TopicID, Topic>get(TOPICS_STATE_ID)).willReturn(writableTopicState);
        readableStore = new ReadableTopicStoreImpl(readableStates, readableEntityCounters);
        given(storeFactory.readableStore(ReadableTopicStore.class)).willReturn(readableStore);
        writableStore = new WritableTopicStore(writableStates, entityCounters);
        given(storeFactory.writableStore(WritableTopicStore.class)).willReturn(writableStore);

        given(handleContext.configuration()).willReturn(config);
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(ConsensusSubmitMessageStreamBuilder.class)).willReturn(recordBuilder);
    }

    @Test
    @DisplayName("pureChecks fails if submit message missing topic ID")
    void topicWithoutIdNotFound() {
        given(pureChecksContext.body()).willReturn(newDefaultSubmitMessageTxn());
        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_TOPIC_ID);
    }

    @Test
    @DisplayName("pureChecks fails if submit message is empty")
    void failsIfMessageIsEmpty() {
        givenValidTopic();
        final var txn = newSubmitMessageTxn(topicEntityNum, "");
        given(pureChecksContext.body()).willReturn(txn);
        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_TOPIC_MESSAGE);
    }

    @Test
    @DisplayName("pureChecks works as expected")
    void pureCheckWorksAsExpexcted() {
        givenValidTopic();
        final var txn = newDefaultSubmitMessageTxn(topicEntityNum);
        given(pureChecksContext.body()).willReturn(txn);
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    @DisplayName("Topic submission key sig required")
    void submissionKeySigRequired() throws PreCheckException {
        readableStore = mock(ReadableTopicStore.class);
        // given:
        final var payerKey = mockPayerLookup();
        mockTopicLookup(SIMPLE_KEY_A);
        final var context = new FakePreHandleContext(accountStore, newDefaultSubmitMessageTxn(topicEntityNum));
        context.registerStore(ReadableTopicStore.class, readableStore);

        // when:
        subject.preHandle(context);

        // then:
        assertThat(context.payerKey()).isEqualTo(payerKey);
        assertThat(context.requiredNonPayerKeys()).containsExactlyInAnyOrder(SIMPLE_KEY_A);
    }

    @Test
    @DisplayName("Topic not found returns error")
    void topicIdNotFound() throws PreCheckException {
        mockPayerLookup();
        readableTopicState = emptyReadableTopicState();
        given(readableStates.<TopicID, Topic>get(TOPICS_STATE_ID)).willReturn(readableTopicState);
        readableStore = new ReadableTopicStoreImpl(readableStates, readableEntityCounters);
        final var context = new FakePreHandleContext(accountStore, newDefaultSubmitMessageTxn(topicEntityNum));
        context.registerStore(ReadableTopicStore.class, readableStore);

        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_TOPIC_ID);
    }

    @Test
    @DisplayName("Topic without submit key does not error")
    void noTopicSubmitKey() throws PreCheckException {
        readableStore = mock(ReadableTopicStore.class);
        mockPayerLookup();
        mockTopicLookup(null);
        final var context = new FakePreHandleContext(accountStore, newDefaultSubmitMessageTxn(topicEntityNum));
        context.registerStore(ReadableTopicStore.class, readableStore);

        // when:
        assertDoesNotThrow(() -> subject.preHandle(context));
    }

    @Test
    @DisplayName("Handle works as expected")
    void handleWorksAsExpected() {
        givenValidTopic();
        final var txn = newDefaultSubmitMessageTxn(topicEntityNum);
        given(handleContext.body()).willReturn(txn);

        given(handleContext.consensusNow()).willReturn(consensusTimestamp);

        mockPayerKeyIsFeeExempt();

        final var initialTopic = writableTopicState.get(topicId);
        subject.handle(handleContext);

        final var expectedTopic = writableTopicState.get(topicId);
        assertNotEquals(initialTopic, expectedTopic);
        assertEquals(initialTopic.sequenceNumber() + 1, expectedTopic.sequenceNumber());
        assertNotEquals(
                initialTopic.runningHash().toString(),
                expectedTopic.runningHash().toString());
    }

    @Test
    @DisplayName("Handle throws IOException")
    void handleThrowsIOException() {
        givenValidTopic();
        subject = new ConsensusSubmitMessageHandler(new ConsensusCustomFeeAssessor()) {
            @Override
            public Topic updateRunningHashAndSequenceNumber(
                    @NonNull final TransactionBody txn, @NonNull final Topic topic, @Nullable Instant consensusNow)
                    throws IOException {
                throw new IOException();
            }
        };
        mockPayerKeyIsFeeExempt();
        final var txn = newSubmitMessageTxn(topicEntityNum, "");
        given(handleContext.body()).willReturn(txn);

        given(handleContext.consensusNow()).willReturn(consensusTimestamp);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertThat(msg.getStatus()).isEqualTo(ResponseCodeEnum.INVALID_TRANSACTION);
    }

    @Test
    @DisplayName("Handle works as expected if Consensus time is null")
    void handleWorksAsExpectedIfConsensusTimeIsNull() {
        givenValidTopic();
        final var txn = newDefaultSubmitMessageTxn(topicEntityNum);
        given(handleContext.body()).willReturn(txn);

        given(handleContext.consensusNow()).willReturn(null);

        mockPayerKeyIsFeeExempt();

        final var initialTopic = writableTopicState.get(topicId);
        subject.handle(handleContext);

        final var expectedTopic = writableTopicState.get(topicId);
        assertNotEquals(initialTopic, expectedTopic);
        assertEquals(initialTopic.sequenceNumber() + 1, expectedTopic.sequenceNumber());
        assertNotEquals(
                initialTopic.runningHash().toString(),
                expectedTopic.runningHash().toString());
        verify(recordBuilder).topicRunningHash(expectedTopic.runningHash());
        verify(recordBuilder).topicSequenceNumber(expectedTopic.sequenceNumber());
        verify(recordBuilder).topicRunningHashVersion(RUNNING_HASH_VERSION);
    }

    @Test
    @DisplayName("Handle fails if submit message is too large")
    void failsIfMessageIsTooLarge() {
        givenValidTopic();
        final var txn = newSubmitMessageTxn(1L, Arrays.toString(new byte[2000]));
        given(handleContext.body()).willReturn(txn);

        final var config = HederaTestConfigBuilder.create()
                .withValue("consensus.message.maxBytesAllowed", 5)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.MESSAGE_SIZE_TOO_LARGE, msg.getStatus());
    }

    @Test
    @DisplayName("Handle fails if topic for which message is being submitted is not found")
    void failsIfTopicIDInvalid() {
        givenValidTopic();
        final var txn = newDefaultSubmitMessageTxn(0L);
        given(handleContext.body()).willReturn(txn);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(INVALID_TOPIC_ID, msg.getStatus());
    }

    @Test
    void failsOnUnavailableDigest() {
        final var raw = NONSENSE.toByteArray();
        assertDoesNotThrow(() -> noThrowSha384HashOf(raw));
    }

    @Test
    @DisplayName("Handle fails if submit message chunk number is invalid")
    void failsIfChunkNumberIsInvalid() {
        givenValidTopic();
        final var txn = newSubmitMessageTxnWithChunks(topicEntityNum, 2, 1);
        given(handleContext.body()).willReturn(txn);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_CHUNK_NUMBER, msg.getStatus());
    }

    @Test
    @DisplayName("Handle fails if submit message chunk number is less than 1")
    void failsIfChunkNumberInvalid() {
        givenValidTopic();
        final var txn = newSubmitMessageTxnWithChunks(topicEntityNum, 0, 1);
        given(handleContext.body()).willReturn(txn);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_CHUNK_NUMBER, msg.getStatus());
    }

    @Test
    @DisplayName("Handle fails if submit message chunk txn payer is not same as initial txn payer")
    void failsIfChunkTxnPayerIsNotInitialPayer() {
        givenValidTopic();
        final var chunkTxnId =
                TransactionID.newBuilder().accountID(anotherPayer).build();
        final var txn = newSubmitMessageTxnWithChunksAndPayer(topicEntityNum, 2, 2, chunkTxnId);
        given(handleContext.body()).willReturn(txn);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_CHUNK_TRANSACTION_ID, msg.getStatus());
    }

    @Test
    @DisplayName("Handle fails if submit message chunk txn id is not same as initial txn id")
    void failsIfChunkTxnPayerIsNotInitialID() {
        givenValidTopic();
        final var chunkTxnId =
                TransactionID.newBuilder().accountID(anotherPayer).build();
        final var txn = newSubmitMessageTxnWithChunksAndPayer(topicEntityNum, 1, 2, chunkTxnId);
        given(handleContext.body()).willReturn(txn);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_CHUNK_TRANSACTION_ID, msg.getStatus());
    }

    @Test
    void calculateFeesHappyPath() {
        givenValidTopic();
        final var chunkTxnId =
                TransactionID.newBuilder().accountID(anotherPayer).build();
        final var txn = newSubmitMessageTxnWithChunksAndPayer(topicEntityNum, 1, 2, chunkTxnId);
        final var feeCtx = mock(FeeContext.class);
        readableStore = mock(ReadableTopicStore.class);
        given(feeCtx.body()).willReturn(txn);
        final var config = HederaTestConfigBuilder.create()
                .withValue("fees.simpleFeesEnabled", false)
                .getOrCreateConfig();
        given(feeCtx.configuration()).willReturn(config);

        final var feeCalcFactory = mock(FeeCalculatorFactory.class);
        final var feeCalc = mock(FeeCalculator.class);
        given(feeCtx.feeCalculatorFactory()).willReturn(feeCalcFactory);
        given(feeCalcFactory.feeCalculator(notNull())).willReturn(feeCalc);
        given(feeCalc.addBytesPerTransaction(anyLong())).willReturn(feeCalc);
        given(feeCalc.addNetworkRamByteSeconds(anyLong())).willReturn(feeCalc);
        // The fees wouldn't be free in this scenario, but we don't care about the actual return
        // value here since we're using a mock calculator
        given(feeCalc.calculate()).willReturn(Fees.FREE);
        readableStore = mock(ReadableTopicStore.class);
        given(storeFactory.readableStore(ReadableTopicStore.class)).willReturn(readableStore);
        given(feeCtx.readableStore(ReadableTopicStore.class)).willReturn(readableStore);
        given(readableStore.getTopic(topicId))
                .willReturn(Topic.newBuilder()
                        .runningHash(Bytes.wrap(new byte[48]))
                        .sequenceNumber(1L)
                        .build());
        subject.calculateFees(feeCtx);

        verify(feeCalc).addBytesPerTransaction(28);
        verify(feeCalc).addNetworkRamByteSeconds(10080);
    }

    @Test
    @DisplayName("Handle submit to topic with custom fee works as expected")
    void handleWorksAsExpectedWithCustomFee() {
        givenValidTopic();

        final var txn = newSubmitMessageTxnWithMaxFee();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.consensusNow()).willReturn(consensusTimestamp);

        mockPayerKeyIsNotFeeExempt();

        final var initialTopic = writableTopicState.get(topicId);
        subject.handle(handleContext);

        final var expectedTopic = writableTopicState.get(topicId);
        assertNotEquals(initialTopic, expectedTopic);
        assertEquals(initialTopic.sequenceNumber() + 1, expectedTopic.sequenceNumber());
        assertNotEquals(
                initialTopic.runningHash().toString(),
                expectedTopic.runningHash().toString());
    }

    @Test
    void testSimpleKeyVerifierFromWithEd25519Key() {
        List<Key> signatories = List.of(ED25519KEY);

        Predicate<Key> verifier = ConsensusSubmitMessageHandler.simpleKeyVerifierFrom(signatories);

        assertTrue(verifier.test(ED25519KEY));
    }

    @Test
    void testSimpleKeyVerifierFromWithEcdsaSecp256k1Key() {

        List<Key> signatories = List.of(ECDSAKEY);

        Predicate<Key> verifier = ConsensusSubmitMessageHandler.simpleKeyVerifierFrom(signatories);

        assertTrue(verifier.test(ECDSAKEY));
    }

    @Test
    void testSimpleKeyVerifierFromWithNonSignatoryKey() {
        List<Key> signatories = List.of(ED25519KEY);

        Predicate<Key> verifier = ConsensusSubmitMessageHandler.simpleKeyVerifierFrom(signatories);

        assertFalse(verifier.test(ECDSAKEY));
    }

    @Test
    void testSimpleKeyVerifierFromWithEmptySignatories() {
        List<Key> signatories = List.of();

        Predicate<Key> verifier = ConsensusSubmitMessageHandler.simpleKeyVerifierFrom(signatories);

        assertFalse(verifier.test(ED25519KEY));
    }

    /* ----------------- Helper Methods ------------------- */

    private Key mockPayerLookup() {
        return ConsensusTestUtils.mockPayerLookup(A_COMPLEX_KEY, payerId, accountStore);
    }

    private void mockTopicLookup(Key submitKey) {
        ConsensusTestUtils.mockTopicLookup(null, submitKey, readableStore);
    }

    private TransactionBody newDefaultSubmitMessageTxn(final long topicEntityNum) {
        return newSubmitMessageTxn(
                topicEntityNum,
                "Message for test-" + Instant.now() + "." + Instant.now().getNano());
    }

    private TransactionBody newSubmitMessageTxn(final long topicEntityNum, final String message) {
        final var txnId = TransactionID.newBuilder().accountID(payerId).build();
        final var submitMessageBuilder = ConsensusSubmitMessageTransactionBody.newBuilder()
                .topicID(TopicID.newBuilder().topicNum(topicEntityNum).build())
                .message(Bytes.wrap(message));
        return TransactionBody.newBuilder()
                .transactionID(txnId)
                .consensusSubmitMessage(submitMessageBuilder.build())
                .build();
    }

    private TransactionBody newSubmitMessageTxnWithMaxFee() {
        final var txnId = TransactionID.newBuilder().accountID(payerId).build();
        final var maxCustomFees = List.of(
                // fungible token limit
                CustomFeeLimit.newBuilder()
                        .accountId(payerId)
                        .fees(List.of(FixedFee.newBuilder()
                                .denominatingTokenId(fungibleTokenId)
                                .amount(1)
                                .build()))
                        .build(),
                // hbar limit
                CustomFeeLimit.newBuilder()
                        .accountId(payerId)
                        .fees(List.of(FixedFee.newBuilder().amount(1).build()))
                        .build());
        final var submitMessageBuilder = ConsensusSubmitMessageTransactionBody.newBuilder()
                .topicID(TopicID.newBuilder().topicNum(topicEntityNum).build())
                .message(Bytes.wrap("Message for test-" + Instant.now() + "."
                        + Instant.now().getNano()));
        return TransactionBody.newBuilder()
                .transactionID(txnId)
                .consensusSubmitMessage(submitMessageBuilder.build())
                .maxCustomFees(maxCustomFees)
                .build();
    }

    private TransactionBody newDefaultSubmitMessageTxn() {
        return newSubmitMessageTxnWithoutId(
                "Message for test-" + Instant.now() + "." + Instant.now().getNano());
    }

    private TransactionBody newSubmitMessageTxnWithoutId(final String message) {
        final var txnId = TransactionID.newBuilder().accountID(payerId).build();
        final var submitMessageBuilder =
                ConsensusSubmitMessageTransactionBody.newBuilder().message(Bytes.wrap(message));
        return TransactionBody.newBuilder()
                .transactionID(txnId)
                .consensusSubmitMessage(submitMessageBuilder.build())
                .build();
    }

    private TransactionBody newSubmitMessageTxnWithChunks(
            final long topicEntityNum, final int currentChunk, final int totalChunk) {
        return newSubmitMessageTxnWithChunksAndPayer(topicEntityNum, currentChunk, totalChunk, null);
    }

    private TransactionBody newSubmitMessageTxnWithChunksAndPayer(
            final long topicEntityNum, final int currentChunk, final int totalChunk, final TransactionID initialTxnId) {
        final var txnId = TransactionID.newBuilder().accountID(payerId).build();
        final var submitMessageBuilder = ConsensusSubmitMessageTransactionBody.newBuilder()
                .topicID(TopicID.newBuilder().topicNum(topicEntityNum).build())
                .chunkInfo(ConsensusMessageChunkInfo.newBuilder()
                        .initialTransactionID(initialTxnId != null ? initialTxnId : txnId)
                        .number(currentChunk)
                        .total(totalChunk)
                        .build())
                .message(Bytes.wrap("test"));
        return TransactionBody.newBuilder()
                .transactionID(txnId)
                .consensusSubmitMessage(submitMessageBuilder.build())
                .build();
    }

    private final ByteString NONSENSE = ByteString.copyFromUtf8("NONSENSE");

    private void mockPayerKeyIsFeeExempt() {
        // mock signature is in FEKL
        given(handleContext.keyVerifier()).willReturn(keyVerifier);
        given(keyVerifier.verificationFor(any(Key.class), any())).willReturn(signatureVerification);
        given(signatureVerification.passed()).willReturn(true);
    }

    private void mockPayerKeyIsNotFeeExempt() {
        // mock payer and token processing
        doReturn(anotherPayer).when(customFeeAssessor).getTokenTreasury(any(), any());
        given(handleContext.payer()).willReturn(payerId);
        // mock crypto transfer dispatch results
        given(handleContext.dispatch(any(DispatchOptions.class))).willReturn(streamBuilder);
        given(streamBuilder.status()).willReturn(SUCCESS);
        // mock signature is not in FEKL
        given(handleContext.keyVerifier()).willReturn(keyVerifier);
        given(keyVerifier.verificationFor(any(Key.class), any())).willReturn(signatureVerification);
        given(signatureVerification.passed()).willReturn(false);
    }
}
