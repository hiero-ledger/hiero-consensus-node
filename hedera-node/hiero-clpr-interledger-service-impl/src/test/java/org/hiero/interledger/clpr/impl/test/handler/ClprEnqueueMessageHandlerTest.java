// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test.handler;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_LEDGER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_QUEUE_NOT_AVAILABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hiero.interledger.clpr.impl.ClprServiceImpl.RUNNING_HASH_SIZE;
import static org.hiero.interledger.clpr.impl.test.handler.ClprTestConstants.REMOTE_LEDGER_ID;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hiero.hapi.interledger.clpr.ClprEnqueueMessageTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprMessage;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessagePayload;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.hapi.interledger.state.clpr.ClprMessageReply;
import org.hiero.interledger.clpr.WritableClprMessageQueueMetadataStore;
import org.hiero.interledger.clpr.WritableClprMessageStore;
import org.hiero.interledger.clpr.impl.ClprMessageUtils;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;
import org.hiero.interledger.clpr.impl.handlers.ClprEnqueueMessageHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprEnqueueMessageHandlerTest extends ClprHandlerTestBase {
    @Mock
    private ClprStateProofManager stateProofManager;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private PreHandleContext preHandleContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private StoreFactory storeFactory;

    private ClprEnqueueMessageHandler subject;

    @BeforeEach
    void setUp() {
        setupStates();
        subject = new ClprEnqueueMessageHandler(stateProofManager);
    }

    @Test
    @DisplayName("Constructor throws NullPointerException when stateProofManager is null")
    void constructorThrowsForNullStateProofManager() {
        assertThatThrownBy(() -> new ClprEnqueueMessageHandler(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("pureChecks throws NOT_SUPPORTED if CLPR is disabled")
    void pureChecksThrowsIfClprDisabled() {
        given(stateProofManager.clprEnabled()).willReturn(false);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", NOT_SUPPORTED);
    }

    @Test
    @DisplayName("pureChecks throws INVALID_TRANSACTION_BODY if ledger id missing")
    void pureChecksThrowsIfLedgerIdMissing() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        final var op = ClprEnqueueMessageTransactionBody.newBuilder()
                .payload(ClprMessagePayload.newBuilder().build())
                .build();
        final var txBody = TransactionBody.newBuilder().clprEnqueueMessage(op).build();
        given(pureChecksContext.body()).willReturn(txBody);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", INVALID_TRANSACTION_BODY);
    }

    @Test
    @DisplayName("pureChecks throws CLPR_INVALID_LEDGER_ID if ledger id empty")
    void pureChecksThrowsIfLedgerIdEmpty() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        final var op = ClprEnqueueMessageTransactionBody.newBuilder()
                .ledgerId(org.hiero.hapi.interledger.state.clpr.ClprLedgerId.newBuilder()
                        .ledgerId(Bytes.EMPTY)
                        .build())
                .payload(ClprMessagePayload.newBuilder()
                        .message(ClprMessage.newBuilder().build())
                        .build())
                .build();
        final var txBody = TransactionBody.newBuilder().clprEnqueueMessage(op).build();
        given(pureChecksContext.body()).willReturn(txBody);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", CLPR_INVALID_LEDGER_ID);
    }

    @Test
    @DisplayName("pureChecks throws INVALID_TRANSACTION_BODY if payload missing")
    void pureChecksThrowsIfPayloadMissing() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        final var op = ClprEnqueueMessageTransactionBody.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .build();
        final var txBody = TransactionBody.newBuilder().clprEnqueueMessage(op).build();
        given(pureChecksContext.body()).willReturn(txBody);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", INVALID_TRANSACTION_BODY);
    }

    @Test
    @DisplayName("pureChecks throws INVALID_TRANSACTION_BODY if payload has no variant set")
    void pureChecksThrowsIfPayloadHasNoVariant() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        final var op = ClprEnqueueMessageTransactionBody.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .payload(ClprMessagePayload.newBuilder().build())
                .build();
        final var txBody = TransactionBody.newBuilder().clprEnqueueMessage(op).build();
        given(pureChecksContext.body()).willReturn(txBody);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", INVALID_TRANSACTION_BODY);
    }

    @Test
    @DisplayName("preHandle throws NOT_SUPPORTED if CLPR is disabled")
    void preHandleThrowsIfClprDisabled() {
        given(stateProofManager.clprEnabled()).willReturn(false);

        assertThatThrownBy(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", NOT_SUPPORTED);
    }

    @Test
    @DisplayName("preHandle throws NOT_SUPPORTED for user-submitted enqueue transaction")
    void preHandleThrowsForUserTransaction() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(preHandleContext.isUserTransaction()).willReturn(true);

        assertThatThrownBy(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", NOT_SUPPORTED);
    }

    @Test
    @DisplayName("preHandle succeeds for internal enqueue transaction")
    void preHandleAllowsInternalTransaction() throws PreCheckException {
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(preHandleContext.isUserTransaction()).willReturn(false);

        subject.preHandle(preHandleContext);
    }

    @Test
    @DisplayName("handle throws CLPR_MESSAGE_QUEUE_NOT_AVAILABLE if queue metadata missing")
    void handleThrowsIfQueueMissing() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        final var payload = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .messageData(Bytes.wrap("hello"))
                        .build())
                .build();
        final var op = ClprEnqueueMessageTransactionBody.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .payload(payload)
                .build();
        final var txBody = TransactionBody.newBuilder().clprEnqueueMessage(op).build();

        given(handleContext.body()).willReturn(txBody);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprMessageQueueMetadataStore.class))
                .willReturn(writableClprMessageQueueMetadataStore);
        given(storeFactory.writableStore(WritableClprMessageStore.class)).willReturn(writableClprMessageStore);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .extracting("status")
                .isEqualTo(CLPR_MESSAGE_QUEUE_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("handle enqueues a message and increments next_message_id")
    void handleEnqueuesMessage() throws HandleException {
        given(stateProofManager.clprEnabled()).willReturn(true);

        final var initialQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .nextMessageId(1L)
                .sentMessageId(0L)
                .sentRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .receivedMessageId(0L)
                .receivedRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .build();
        writableClprMessageQueueMetadataStore.put(REMOTE_LEDGER_ID, initialQueue);

        final var payload = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .messageData(Bytes.wrap("hello"))
                        .build())
                .build();
        final var op = ClprEnqueueMessageTransactionBody.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .payload(payload)
                .build();
        final var txBody = TransactionBody.newBuilder().clprEnqueueMessage(op).build();

        given(handleContext.body()).willReturn(txBody);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprMessageQueueMetadataStore.class))
                .willReturn(writableClprMessageQueueMetadataStore);
        given(storeFactory.writableStore(WritableClprMessageStore.class)).willReturn(writableClprMessageStore);

        subject.handle(handleContext);

        final var updatedQueue = writableClprMessageQueueMetadataStore.get(REMOTE_LEDGER_ID);
        assertThat(updatedQueue.nextMessageId()).isEqualTo(2L);

        final var messageKey = ClprMessageKey.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .messageId(1L)
                .build();
        final var storedValue = writableClprMessageStore.get(messageKey);
        assertThat(storedValue).isNotNull();
        assertThat(storedValue.payload()).isEqualTo(payload);

        final var expectedHash = ClprMessageUtils.nextRunningHash(payload, initialQueue.sentRunningHash());
        assertThat(storedValue.runningHashAfterProcessing()).isEqualTo(expectedHash);
    }

    @Test
    @DisplayName("handle validates expected_message_id when provided")
    void handleValidatesExpectedMessageId() {
        given(stateProofManager.clprEnabled()).willReturn(true);

        final var initialQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .nextMessageId(1L)
                .sentMessageId(0L)
                .sentRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .receivedMessageId(0L)
                .receivedRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .build();
        writableClprMessageQueueMetadataStore.put(REMOTE_LEDGER_ID, initialQueue);

        final var payload = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .messageData(Bytes.wrap("hello"))
                        .build())
                .build();
        final var op = ClprEnqueueMessageTransactionBody.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .payload(payload)
                .expectedMessageId(2L)
                .build();
        final var txBody = TransactionBody.newBuilder().clprEnqueueMessage(op).build();

        given(handleContext.body()).willReturn(txBody);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprMessageQueueMetadataStore.class))
                .willReturn(writableClprMessageQueueMetadataStore);
        given(storeFactory.writableStore(WritableClprMessageStore.class)).willReturn(writableClprMessageStore);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .extracting("status")
                .isEqualTo(INVALID_TRANSACTION_BODY);
    }

    @Test
    @DisplayName("handle enqueues sequential request/response payloads with running hash chaining")
    void handleEnqueuesSequentialPayloadsWithHashChaining() throws HandleException {
        given(stateProofManager.clprEnabled()).willReturn(true);

        final var initialQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .nextMessageId(1L)
                .sentMessageId(0L)
                .sentRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .receivedMessageId(0L)
                .receivedRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .build();
        writableClprMessageQueueMetadataStore.put(REMOTE_LEDGER_ID, initialQueue);

        final var requestPayload = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .messageData(Bytes.wrap("request-1"))
                        .build())
                .build();
        final var responsePayload = ClprMessagePayload.newBuilder()
                .messageReply(ClprMessageReply.newBuilder()
                        .messageId(1L)
                        .messageReplyData(Bytes.wrap("response-1"))
                        .build())
                .build();

        final var requestBody = TransactionBody.newBuilder()
                .clprEnqueueMessage(ClprEnqueueMessageTransactionBody.newBuilder()
                        .ledgerId(REMOTE_LEDGER_ID)
                        .payload(requestPayload)
                        .build())
                .build();
        final var responseBody = TransactionBody.newBuilder()
                .clprEnqueueMessage(ClprEnqueueMessageTransactionBody.newBuilder()
                        .ledgerId(REMOTE_LEDGER_ID)
                        .payload(responsePayload)
                        .build())
                .build();

        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprMessageQueueMetadataStore.class))
                .willReturn(writableClprMessageQueueMetadataStore);
        given(storeFactory.writableStore(WritableClprMessageStore.class)).willReturn(writableClprMessageStore);

        given(handleContext.body()).willReturn(requestBody, responseBody);
        subject.handle(handleContext);
        subject.handle(handleContext);

        final var firstMessageKey = ClprMessageKey.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .messageId(1L)
                .build();
        final var secondMessageKey = ClprMessageKey.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .messageId(2L)
                .build();
        final var firstMessage = writableClprMessageStore.get(firstMessageKey);
        final var secondMessage = writableClprMessageStore.get(secondMessageKey);

        final var expectedFirstHash = ClprMessageUtils.nextRunningHash(requestPayload, initialQueue.sentRunningHash());
        final var expectedSecondHash = ClprMessageUtils.nextRunningHash(responsePayload, expectedFirstHash);
        assertThat(firstMessage.runningHashAfterProcessing()).isEqualTo(expectedFirstHash);
        assertThat(secondMessage.runningHashAfterProcessing()).isEqualTo(expectedSecondHash);

        final var updatedQueue = writableClprMessageQueueMetadataStore.get(REMOTE_LEDGER_ID);
        assertThat(updatedQueue.nextMessageId()).isEqualTo(3L);
    }
}
