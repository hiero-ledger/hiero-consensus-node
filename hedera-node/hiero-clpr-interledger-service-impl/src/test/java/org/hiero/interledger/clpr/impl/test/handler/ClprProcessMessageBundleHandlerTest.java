// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test.handler;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_BUNDLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_RUNNING_HASH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_STATE_PROOF;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_QUEUE_NOT_AVAILABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.interledger.clpr.ClprStateProofUtils.extractMessageKey;
import static org.hiero.interledger.clpr.ClprStateProofUtils.extractMessageValue;
import static org.hiero.interledger.clpr.ClprStateProofUtils.validateStateProof;
import static org.hiero.interledger.clpr.impl.ClprMessageUtils.nextRunningHash;
import static org.hiero.interledger.clpr.impl.ClprServiceImpl.RUNNING_HASH_SIZE;
import static org.hiero.interledger.clpr.impl.test.handler.ClprTestConstants.MOCK_LEDGER_ID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.hiero.hapi.interledger.clpr.ClprHandleMessagePayloadTransactionBody;
import org.hiero.hapi.interledger.clpr.ClprProcessMessageBundleTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprMessage;
import org.hiero.hapi.interledger.state.clpr.ClprMessageBundle;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessagePayload;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;
import org.hiero.interledger.clpr.ClprStateProofUtils;
import org.hiero.interledger.clpr.WritableClprMessageQueueMetadataStore;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;
import org.hiero.interledger.clpr.impl.handlers.ClprProcessMessageBundleHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprProcessMessageBundleHandlerTest extends ClprHandlerTestBase {
    @Mock
    private ClprStateProofManager stateProofManager;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private PreHandleContext preHandleContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private TransactionBody transactionBody;

    @Mock
    private ClprProcessMessageBundleTransactionBody clprProcessMessageBundleTransactionBody;

    @Mock
    private ClprMessageBundle clprMessageBundle;

    @Mock
    private StateProof clprStateProof;

    @Mock
    private StoreFactory storeFactory;

    private ClprProcessMessageBundleHandler subject;

    @BeforeEach
    void setUp() {
        setupStates();
        subject = new ClprProcessMessageBundleHandler(stateProofManager);
    }

    @Test
    @DisplayName("Constructor throws NullPointerException when stateProofManager is null")
    void constructorThrowsForNullStateProofManager() {
        assertThatThrownBy(() -> new ClprProcessMessageBundleHandler(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("pureChecks throws NOT_SUPPORTED if CLPR is disabled")
    void pureChecksThrowsIfClprIsDisabled() {
        given(stateProofManager.clprEnabled()).willReturn(false);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", NOT_SUPPORTED);
        verifyNoInteractions(pureChecksContext);
    }

    @Test
    @DisplayName("pureChecks throws INVALID_TRANSACTION_BODY if message bundle is missing")
    void pureChecksThrowsIfMessageBundleIsMissing() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprProcessMessageBundleOrThrow()).willReturn(clprProcessMessageBundleTransactionBody);
        given(clprProcessMessageBundleTransactionBody.hasMessageBundle()).willReturn(false);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", INVALID_TRANSACTION_BODY);
    }

    @Test
    @DisplayName("pureChecks throws CLPR_INVALID_STATE_PROOF if state proof is invalid")
    void pureChecksThrowsIfStateProofValidationFails() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprProcessMessageBundleOrThrow()).willReturn(clprProcessMessageBundleTransactionBody);
        given(clprProcessMessageBundleTransactionBody.hasMessageBundle()).willReturn(true);
        given(clprProcessMessageBundleTransactionBody.messageBundleOrThrow()).willReturn(clprMessageBundle);
        given(clprMessageBundle.hasStateProof()).willReturn(true);
        given(clprMessageBundle.stateProofOrThrow()).willReturn(clprStateProof);

        try (MockedStatic<ClprStateProofUtils> stateProofUtils = mockStatic(ClprStateProofUtils.class)) {
            stateProofUtils.when(() -> validateStateProof(clprStateProof)).thenReturn(false);

            assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", CLPR_INVALID_STATE_PROOF);
        }
    }

    @Test
    @DisplayName("pureChecks throws CLPR_INVALID_BUNDLE when bundle is already fully received")
    void pureChecksThrowsIfBundleAlreadyReceived() {
        final var messageQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(MOCK_LEDGER_ID)
                .receivedMessageId(4L)
                .receivedRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .build();
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(stateProofManager.getLocalMessageQueueMetadata(MOCK_LEDGER_ID)).willReturn(messageQueue);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprProcessMessageBundleOrThrow()).willReturn(clprProcessMessageBundleTransactionBody);
        given(clprProcessMessageBundleTransactionBody.hasMessageBundle()).willReturn(true);
        given(clprProcessMessageBundleTransactionBody.messageBundleOrThrow()).willReturn(clprMessageBundle);
        given(clprMessageBundle.ledgerIdOrThrow()).willReturn(MOCK_LEDGER_ID);
        given(clprMessageBundle.hasStateProof()).willReturn(true);
        given(clprMessageBundle.stateProofOrThrow()).willReturn(clprStateProof);
        given(clprMessageBundle.messages()).willReturn(List.of(payload("msg1"), payload("msg2"), payload("msg3")));

        final var lastPayload = payload("msg4");
        final var runningHash4 = nextRunningHash(lastPayload, Bytes.wrap(new byte[RUNNING_HASH_SIZE]));
        try (MockedStatic<ClprStateProofUtils> stateProofUtils = mockStatic(ClprStateProofUtils.class)) {
            stateProofUtils.when(() -> validateStateProof(clprStateProof)).thenReturn(true);
            stateProofUtils
                    .when(() -> extractMessageKey(clprStateProof))
                    .thenReturn(ClprMessageKey.newBuilder().messageId(4L).build());
            stateProofUtils
                    .when(() -> extractMessageValue(clprStateProof))
                    .thenReturn(ClprMessageValue.newBuilder()
                            .payload(lastPayload)
                            .runningHashAfterProcessing(runningHash4)
                            .build());

            assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", CLPR_INVALID_BUNDLE);
        }
    }

    @Test
    @DisplayName("pureChecks throws CLPR_INVALID_RUNNING_HASH when running hash does not match")
    void pureChecksThrowsIfRunningHashMismatch() {
        final var messageQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(MOCK_LEDGER_ID)
                .receivedMessageId(5L)
                .receivedRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .build();
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(stateProofManager.getLocalMessageQueueMetadata(MOCK_LEDGER_ID)).willReturn(messageQueue);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprProcessMessageBundleOrThrow()).willReturn(clprProcessMessageBundleTransactionBody);
        given(clprProcessMessageBundleTransactionBody.hasMessageBundle()).willReturn(true);
        given(clprProcessMessageBundleTransactionBody.messageBundleOrThrow()).willReturn(clprMessageBundle);
        given(clprMessageBundle.ledgerIdOrThrow()).willReturn(MOCK_LEDGER_ID);
        given(clprMessageBundle.hasStateProof()).willReturn(true);
        given(clprMessageBundle.stateProofOrThrow()).willReturn(clprStateProof);
        given(clprMessageBundle.messages()).willReturn(List.of(payload("msg1")));

        try (MockedStatic<ClprStateProofUtils> stateProofUtils = mockStatic(ClprStateProofUtils.class)) {
            stateProofUtils.when(() -> validateStateProof(clprStateProof)).thenReturn(true);
            stateProofUtils
                    .when(() -> extractMessageKey(clprStateProof))
                    .thenReturn(ClprMessageKey.newBuilder().messageId(7L).build());
            stateProofUtils
                    .when(() -> extractMessageValue(clprStateProof))
                    .thenReturn(ClprMessageValue.newBuilder()
                            .payload(payload("msg2"))
                            .runningHashAfterProcessing(Bytes.wrap("bad-hash".getBytes()))
                            .build());

            assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", CLPR_INVALID_RUNNING_HASH);
        }
    }

    @Test
    @DisplayName("pureChecks succeeds with valid bundle and state proof")
    void pureChecksSucceedsWithValidBundleAndStateProof() throws PreCheckException {
        final var receivedRunningHash = Bytes.wrap(new byte[RUNNING_HASH_SIZE]);
        final var messageQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(MOCK_LEDGER_ID)
                .receivedMessageId(5L)
                .receivedRunningHash(receivedRunningHash)
                .build();
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(stateProofManager.getLocalMessageQueueMetadata(MOCK_LEDGER_ID)).willReturn(messageQueue);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprProcessMessageBundleOrThrow()).willReturn(clprProcessMessageBundleTransactionBody);
        given(clprProcessMessageBundleTransactionBody.hasMessageBundle()).willReturn(true);
        given(clprProcessMessageBundleTransactionBody.messageBundleOrThrow()).willReturn(clprMessageBundle);
        given(clprMessageBundle.ledgerIdOrThrow()).willReturn(MOCK_LEDGER_ID);
        given(clprMessageBundle.hasStateProof()).willReturn(true);
        given(clprMessageBundle.stateProofOrThrow()).willReturn(clprStateProof);

        final var payload1 = payload("msg1");
        final var payload2 = payload("msg2");
        given(clprMessageBundle.messages()).willReturn(List.of(payload1));

        final var runningHash1 = nextRunningHash(payload1, receivedRunningHash);
        final var runningHash2 = nextRunningHash(payload2, runningHash1);

        try (MockedStatic<ClprStateProofUtils> stateProofUtils = mockStatic(ClprStateProofUtils.class)) {
            stateProofUtils.when(() -> validateStateProof(clprStateProof)).thenReturn(true);
            stateProofUtils
                    .when(() -> extractMessageKey(clprStateProof))
                    .thenReturn(ClprMessageKey.newBuilder().messageId(7L).build());
            stateProofUtils
                    .when(() -> extractMessageValue(clprStateProof))
                    .thenReturn(ClprMessageValue.newBuilder()
                            .payload(payload2)
                            .runningHashAfterProcessing(runningHash2)
                            .build());

            subject.pureChecks(pureChecksContext);
        }
    }

    @Test
    @DisplayName("preHandle throws NOT_SUPPORTED if CLPR is disabled")
    void preHandleThrowsIfClprIsDisabled() {
        given(stateProofManager.clprEnabled()).willReturn(false);

        assertThatThrownBy(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", NOT_SUPPORTED);
        verifyNoInteractions(preHandleContext);
    }

    @Test
    @DisplayName("preHandle succeeds if CLPR is enabled")
    void preHandleSucceedsIfClprIsEnabled() throws PreCheckException {
        given(stateProofManager.clprEnabled()).willReturn(true);
        subject.preHandle(preHandleContext);
    }

    @Test
    @DisplayName("handle throws NullPointerException when context is null")
    void handleThrowsForNullContext() {
        assertThatThrownBy(() -> subject.handle(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("handle throws CLPR_MESSAGE_QUEUE_NOT_AVAILABLE when source queue metadata missing")
    void handleThrowsIfQueueMissing() {
        final var payload1 = payload("msg1");
        final var payload2 = payload("msg2");
        final var runningHash2 =
                nextRunningHash(payload2, nextRunningHash(payload1, Bytes.wrap(new byte[RUNNING_HASH_SIZE])));
        final var stateProof = ClprStateProofUtils.buildLocalClprStateProofWrapper(
                ClprMessageKey.newBuilder().messageId(2L).build(),
                ClprMessageValue.newBuilder()
                        .payload(payload2)
                        .runningHashAfterProcessing(runningHash2)
                        .build());

        final var txBody = TransactionBody.newBuilder()
                .clprProcessMessageBundle(ClprProcessMessageBundleTransactionBody.newBuilder()
                        .messageBundle(ClprMessageBundle.newBuilder()
                                .ledgerId(MOCK_LEDGER_ID)
                                .messages(List.of(payload1))
                                .stateProof(stateProof)
                                .build())
                        .build())
                .build();

        given(handleContext.body()).willReturn(txBody);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprMessageQueueMetadataStore.class))
                .willReturn(writableClprMessageQueueMetadataStore);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .extracting("status")
                .isEqualTo(CLPR_MESSAGE_QUEUE_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("handle dispatches CLPR_HANDLE_MESSAGE_PAYLOAD transactions and updates queue metadata")
    void handleDispatchesPayloadTransactionsAndUpdatesQueue() throws HandleException {
        final var receivedRunningHash = Bytes.wrap(new byte[RUNNING_HASH_SIZE]);
        final var initialQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(MOCK_LEDGER_ID)
                .receivedMessageId(5L)
                .receivedRunningHash(receivedRunningHash)
                .nextMessageId(6L)
                .sentMessageId(0L)
                .sentRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .build();
        writableClprMessageQueueMetadataStore.put(MOCK_LEDGER_ID, initialQueue);

        final var payload1 = payload("msg1");
        final var payload2 = payload("msg2");
        final var payload3 = payload("msg3");

        final var runningHash1 = nextRunningHash(payload1, receivedRunningHash);
        final var runningHash2 = nextRunningHash(payload2, runningHash1);
        final var runningHash3 = nextRunningHash(payload3, runningHash2);

        final var stateProof = ClprStateProofUtils.buildLocalClprStateProofWrapper(
                ClprMessageKey.newBuilder()
                        .ledgerId(MOCK_LEDGER_ID)
                        .messageId(8L)
                        .build(),
                ClprMessageValue.newBuilder()
                        .payload(payload3)
                        .runningHashAfterProcessing(runningHash3)
                        .build());

        final var txBody = TransactionBody.newBuilder()
                .clprProcessMessageBundle(ClprProcessMessageBundleTransactionBody.newBuilder()
                        .messageBundle(ClprMessageBundle.newBuilder()
                                .ledgerId(MOCK_LEDGER_ID)
                                .messages(List.of(payload1, payload2))
                                .stateProof(stateProof)
                                .build())
                        .build())
                .build();

        given(handleContext.body()).willReturn(txBody);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprMessageQueueMetadataStore.class))
                .willReturn(writableClprMessageQueueMetadataStore);
        given(handleContext.payer())
                .willReturn(AccountID.newBuilder().accountNum(1001L).build());
        final var callbackBuilder = org.mockito.Mockito.mock(StreamBuilder.class);
        given(callbackBuilder.status()).willReturn(SUCCESS);
        given(handleContext.dispatch(any())).willReturn(callbackBuilder);

        subject.handle(handleContext);

        final var updatedQueueMetadata = writableClprMessageQueueMetadataStore.get(MOCK_LEDGER_ID);
        assertThat(updatedQueueMetadata).isNotNull();
        assertThat(updatedQueueMetadata.receivedMessageId()).isEqualTo(8L);
        assertThat(updatedQueueMetadata.nextMessageId()).isEqualTo(6L);
        assertThat(updatedQueueMetadata.receivedRunningHash()).isEqualTo(runningHash3);

        final var dispatchCaptor = ArgumentCaptor.forClass(DispatchOptions.class);
        verify(handleContext, times(3)).dispatch(dispatchCaptor.capture());
        final var dispatches = dispatchCaptor.getAllValues();

        assertPayloadDispatch(dispatches.get(0), 6L, payload1);
        assertPayloadDispatch(dispatches.get(1), 7L, payload2);
        assertPayloadDispatch(dispatches.get(2), 8L, payload3);
    }

    @Test
    @DisplayName("handle skips already-received bundle messages and dispatches only new payloads")
    void handleSkipsAlreadyReceivedPayloads() throws HandleException {
        final var payload1 = payload("msg1");
        final var payload2 = payload("msg2");
        final var payload3 = payload("msg3");
        final var payload4 = payload("msg4");

        final var initHash = Bytes.wrap(new byte[RUNNING_HASH_SIZE]);
        final var runningHash1 = nextRunningHash(payload1, initHash);
        final var runningHash2 = nextRunningHash(payload2, runningHash1);
        final var runningHash3 = nextRunningHash(payload3, runningHash2);
        final var runningHash4 = nextRunningHash(payload4, runningHash3);

        final var initialQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(MOCK_LEDGER_ID)
                .receivedMessageId(2L)
                .receivedRunningHash(runningHash2)
                .nextMessageId(3L)
                .sentMessageId(0L)
                .build();
        writableClprMessageQueueMetadataStore.put(MOCK_LEDGER_ID, initialQueue);

        final var stateProof = ClprStateProofUtils.buildLocalClprStateProofWrapper(
                ClprMessageKey.newBuilder().messageId(4L).build(),
                ClprMessageValue.newBuilder()
                        .payload(payload4)
                        .runningHashAfterProcessing(runningHash4)
                        .build());
        final var txBody = TransactionBody.newBuilder()
                .clprProcessMessageBundle(ClprProcessMessageBundleTransactionBody.newBuilder()
                        .messageBundle(ClprMessageBundle.newBuilder()
                                .ledgerId(MOCK_LEDGER_ID)
                                .messages(List.of(payload1, payload2, payload3))
                                .stateProof(stateProof)
                                .build())
                        .build())
                .build();

        given(handleContext.body()).willReturn(txBody);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprMessageQueueMetadataStore.class))
                .willReturn(writableClprMessageQueueMetadataStore);
        given(handleContext.payer())
                .willReturn(AccountID.newBuilder().accountNum(1001L).build());
        final var callbackBuilder = org.mockito.Mockito.mock(StreamBuilder.class);
        given(callbackBuilder.status()).willReturn(SUCCESS);
        given(handleContext.dispatch(any())).willReturn(callbackBuilder);

        subject.handle(handleContext);

        final var updatedQueueMetadata = writableClprMessageQueueMetadataStore.get(MOCK_LEDGER_ID);
        assertThat(updatedQueueMetadata).isNotNull();
        assertThat(updatedQueueMetadata.receivedMessageId()).isEqualTo(4L);
        assertThat(updatedQueueMetadata.nextMessageId()).isEqualTo(3L);
        assertThat(updatedQueueMetadata.receivedRunningHash()).isEqualTo(runningHash4);

        final var dispatchCaptor = ArgumentCaptor.forClass(DispatchOptions.class);
        verify(handleContext, times(2)).dispatch(dispatchCaptor.capture());
        final var dispatches = dispatchCaptor.getAllValues();

        assertPayloadDispatch(dispatches.get(0), 3L, payload3);
        assertPayloadDispatch(dispatches.get(1), 4L, payload4);
    }

    @Test
    @DisplayName("handle propagates failed child dispatch status")
    void handlePropagatesFailedChildDispatchStatus() {
        final var initialQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(MOCK_LEDGER_ID)
                .receivedMessageId(0L)
                .receivedRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .nextMessageId(1L)
                .sentMessageId(0L)
                .sentRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .build();
        writableClprMessageQueueMetadataStore.put(MOCK_LEDGER_ID, initialQueue);

        final var payload1 = payload("msg1");
        final var stateProof = ClprStateProofUtils.buildLocalClprStateProofWrapper(
                ClprMessageKey.newBuilder()
                        .ledgerId(MOCK_LEDGER_ID)
                        .messageId(1L)
                        .build(),
                ClprMessageValue.newBuilder()
                        .payload(payload1)
                        .runningHashAfterProcessing(nextRunningHash(payload1, initialQueue.receivedRunningHash()))
                        .build());
        final var txBody = TransactionBody.newBuilder()
                .clprProcessMessageBundle(ClprProcessMessageBundleTransactionBody.newBuilder()
                        .messageBundle(ClprMessageBundle.newBuilder()
                                .ledgerId(MOCK_LEDGER_ID)
                                .messages(List.of())
                                .stateProof(stateProof)
                                .build())
                        .build())
                .build();

        given(handleContext.body()).willReturn(txBody);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprMessageQueueMetadataStore.class))
                .willReturn(writableClprMessageQueueMetadataStore);
        given(handleContext.payer())
                .willReturn(AccountID.newBuilder().accountNum(1001L).build());
        final var callbackBuilder = org.mockito.Mockito.mock(StreamBuilder.class);
        given(callbackBuilder.status()).willReturn(CONTRACT_REVERT_EXECUTED);
        given(handleContext.dispatch(any())).willReturn(callbackBuilder);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .extracting("status")
                .isEqualTo(CONTRACT_REVERT_EXECUTED);

        final var queueAfterFailure = writableClprMessageQueueMetadataStore.get(MOCK_LEDGER_ID);
        assertThat(queueAfterFailure).isEqualTo(initialQueue);
    }

    @Test
    @DisplayName("handle throws CLPR_INVALID_BUNDLE when bundle message IDs are misaligned")
    void handleThrowsIfBundleMisalignedByMessageId() {
        final var initialQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(MOCK_LEDGER_ID)
                .receivedMessageId(5L)
                .receivedRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .nextMessageId(6L)
                .sentMessageId(0L)
                .sentRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .build();
        writableClprMessageQueueMetadataStore.put(MOCK_LEDGER_ID, initialQueue);

        final var payload1 = payload("msg1");
        final var payload2 = payload("msg2");
        final var payload3 = payload("msg3");

        final var runningHash1 = nextRunningHash(payload1, initialQueue.receivedRunningHash());
        final var runningHash2 = nextRunningHash(payload2, runningHash1);
        final var runningHash3 = nextRunningHash(payload3, runningHash2);
        final var stateProof = ClprStateProofUtils.buildLocalClprStateProofWrapper(
                ClprMessageKey.newBuilder()
                        .ledgerId(MOCK_LEDGER_ID)
                        .messageId(9L)
                        .build(),
                ClprMessageValue.newBuilder()
                        .payload(payload3)
                        .runningHashAfterProcessing(runningHash3)
                        .build());

        final var txBody = TransactionBody.newBuilder()
                .clprProcessMessageBundle(ClprProcessMessageBundleTransactionBody.newBuilder()
                        .messageBundle(ClprMessageBundle.newBuilder()
                                .ledgerId(MOCK_LEDGER_ID)
                                .messages(List.of(payload1, payload2))
                                .stateProof(stateProof)
                                .build())
                        .build())
                .build();

        given(handleContext.body()).willReturn(txBody);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprMessageQueueMetadataStore.class))
                .willReturn(writableClprMessageQueueMetadataStore);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .extracting("status")
                .isEqualTo(CLPR_INVALID_BUNDLE);
    }

    private ClprMessagePayload payload(final String msg) {
        return ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .messageData(Bytes.wrap(msg.getBytes()))
                        .build())
                .build();
    }

    private void assertPayloadDispatch(
            final DispatchOptions<?> dispatchOptions,
            final long expectedInboundMessageId,
            final ClprMessagePayload expectedPayload) {
        final var dispatchBody = dispatchOptions.body();
        assertThat(dispatchBody.hasClprHandleMessagePayload()).isTrue();

        final ClprHandleMessagePayloadTransactionBody op = dispatchBody.clprHandleMessagePayloadOrThrow();
        assertThat(op.sourceLedgerIdOrThrow()).isEqualTo(MOCK_LEDGER_ID);
        assertThat(op.inboundMessageId()).isEqualTo(expectedInboundMessageId);
        assertThat(op.payloadOrThrow()).isEqualTo(expectedPayload);
    }
}
