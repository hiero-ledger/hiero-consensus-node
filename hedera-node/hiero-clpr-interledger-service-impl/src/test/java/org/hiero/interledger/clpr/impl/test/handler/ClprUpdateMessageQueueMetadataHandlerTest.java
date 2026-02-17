// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test.handler;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_LEDGER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_STATE_PROOF;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_QUEUE_NOT_AVAILABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hiero.interledger.clpr.impl.test.handler.ClprTestConstants.LOCAL_LEDGER_ID;
import static org.hiero.interledger.clpr.impl.test.handler.ClprTestConstants.REMOTE_LEDGER_ID;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hiero.hapi.interledger.clpr.ClprUpdateMessageQueueMetadataTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;
import org.hiero.interledger.clpr.ClprStateProofUtils;
import org.hiero.interledger.clpr.WritableClprMessageQueueMetadataStore;
import org.hiero.interledger.clpr.WritableClprMessageStore;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;
import org.hiero.interledger.clpr.impl.handlers.ClprUpdateMessageQueueMetadataHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprUpdateMessageQueueMetadataHandlerTest extends ClprHandlerTestBase {

    @Mock
    private ClprStateProofManager stateProofManager;

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private PreHandleContext preHandleContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private TransactionBody transactionBody;

    @Mock
    private ClprUpdateMessageQueueMetadataTransactionBody clprUpdateMessageQueueMetadataTransactionBody;

    @Mock
    private StateProof clprStateProof;

    @Mock
    private StoreFactory storeFactory;

    private ClprUpdateMessageQueueMetadataHandler subject;

    @BeforeEach
    void setUp() {
        setupStates();
        subject = new ClprUpdateMessageQueueMetadataHandler(stateProofManager, networkInfo, configProvider);
    }

    @Test
    @DisplayName("Constructor throws NullPointerException when stateProofManager is null")
    void constructorThrowsForNullStateProofManager() {
        assertThatThrownBy(() -> new ClprUpdateMessageQueueMetadataHandler(null, networkInfo, configProvider))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Constructor throws NullPointerException when networkInfo is null")
    void constructorThrowsForNullNetworkInfo() {
        assertThatThrownBy(() -> new ClprUpdateMessageQueueMetadataHandler(stateProofManager, null, configProvider))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Constructor throws NullPointerException when configProvider is null")
    void constructorThrowsForNullConfigProvider() {
        assertThatThrownBy(() -> new ClprUpdateMessageQueueMetadataHandler(stateProofManager, networkInfo, null))
                .isInstanceOf(NullPointerException.class);
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
    @DisplayName("pureChecks throws INVALID_TRANSACTION_BODY if ledger ID is missing")
    void pureChecksThrowsIfLedgerIdIsMissing() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprUpdateMessageQueueMetadataOrThrow())
                .willReturn(clprUpdateMessageQueueMetadataTransactionBody);
        given(clprUpdateMessageQueueMetadataTransactionBody.hasLedgerId()).willReturn(false);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", INVALID_TRANSACTION_BODY);
    }

    @Test
    @DisplayName("pureChecks throws CLPR_INVALID_LEDGER_ID if ledger ID is empty")
    void pureChecksThrowsIfLedgerIdIsEmpty() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprUpdateMessageQueueMetadataOrThrow())
                .willReturn(clprUpdateMessageQueueMetadataTransactionBody);
        given(clprUpdateMessageQueueMetadataTransactionBody.hasLedgerId()).willReturn(true);
        given(clprUpdateMessageQueueMetadataTransactionBody.ledgerIdOrThrow())
                .willReturn(ClprLedgerId.newBuilder().ledgerId(Bytes.EMPTY).build());

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", CLPR_INVALID_LEDGER_ID);
    }

    @Test
    @DisplayName("pureChecks throws CLPR_INVALID_LEDGER_ID if remoteLedgerId equals localLedgerId")
    void pureChecksThrowsIfRemoteLedgerIdEqualsLocalLedgerId() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(stateProofManager.getLocalLedgerId()).willReturn(LOCAL_LEDGER_ID);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprUpdateMessageQueueMetadataOrThrow())
                .willReturn(clprUpdateMessageQueueMetadataTransactionBody);
        given(clprUpdateMessageQueueMetadataTransactionBody.hasLedgerId()).willReturn(true);
        given(clprUpdateMessageQueueMetadataTransactionBody.ledgerIdOrThrow()).willReturn(LOCAL_LEDGER_ID);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", CLPR_INVALID_LEDGER_ID);
    }

    @Test
    @DisplayName("pureChecks throws CLPR_MESSAGE_QUEUE_NOT_AVAILABLE if messageQueueMetadataProof is missing")
    void pureChecksThrowsIfMessageQueueMetadataProofIsMissing() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(stateProofManager.getLocalLedgerId()).willReturn(LOCAL_LEDGER_ID);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprUpdateMessageQueueMetadataOrThrow())
                .willReturn(clprUpdateMessageQueueMetadataTransactionBody);
        given(clprUpdateMessageQueueMetadataTransactionBody.hasLedgerId()).willReturn(true);
        given(clprUpdateMessageQueueMetadataTransactionBody.ledgerIdOrThrow()).willReturn(REMOTE_LEDGER_ID);
        given(clprUpdateMessageQueueMetadataTransactionBody.hasMessageQueueMetadataProof())
                .willReturn(false);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", CLPR_MESSAGE_QUEUE_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("pureChecks throws CLPR_INVALID_STATE_PROOF if state proof validation fails")
    void pureChecksThrowsIfStateProofValidationFails() {
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(stateProofManager.getLocalLedgerId()).willReturn(LOCAL_LEDGER_ID);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprUpdateMessageQueueMetadataOrThrow())
                .willReturn(clprUpdateMessageQueueMetadataTransactionBody);
        given(clprUpdateMessageQueueMetadataTransactionBody.hasLedgerId()).willReturn(true);
        given(clprUpdateMessageQueueMetadataTransactionBody.ledgerIdOrThrow()).willReturn(REMOTE_LEDGER_ID);
        given(clprUpdateMessageQueueMetadataTransactionBody.hasMessageQueueMetadataProof())
                .willReturn(true);
        given(clprUpdateMessageQueueMetadataTransactionBody.messageQueueMetadataProofOrThrow())
                .willReturn(clprStateProof);
        try (MockedStatic<ClprStateProofUtils> stateProofUtils = mockStatic(ClprStateProofUtils.class)) {
            stateProofUtils
                    .when(() -> ClprStateProofUtils.validateStateProof(clprStateProof))
                    .thenReturn(false);

            assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", CLPR_INVALID_STATE_PROOF);
        }
    }

    @Test
    @DisplayName("pureChecks succeeds with valid inputs")
    void pureChecksSucceedsWithValidInputs() throws PreCheckException {
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(stateProofManager.getLocalLedgerId()).willReturn(LOCAL_LEDGER_ID);
        given(pureChecksContext.body()).willReturn(transactionBody);
        given(transactionBody.clprUpdateMessageQueueMetadataOrThrow())
                .willReturn(clprUpdateMessageQueueMetadataTransactionBody);
        given(clprUpdateMessageQueueMetadataTransactionBody.hasLedgerId()).willReturn(true);
        given(clprUpdateMessageQueueMetadataTransactionBody.ledgerIdOrThrow()).willReturn(REMOTE_LEDGER_ID);
        given(clprUpdateMessageQueueMetadataTransactionBody.hasMessageQueueMetadataProof())
                .willReturn(true);
        given(clprUpdateMessageQueueMetadataTransactionBody.messageQueueMetadataProofOrThrow())
                .willReturn(clprStateProof);

        try (MockedStatic<ClprStateProofUtils> mockedStatic = mockStatic(ClprStateProofUtils.class)) {
            mockedStatic
                    .when(() -> ClprStateProofUtils.validateStateProof(clprStateProof))
                    .thenReturn(true);
            subject.pureChecks(pureChecksContext);
        }
        // No exception means success
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
        verifyNoMoreInteractions(preHandleContext);
    }

    @Test
    @DisplayName("handle throws NullPointerException when context is null")
    void handleThrowsForNullContext() {
        assertThatThrownBy(() -> subject.handle(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("context");
    }

    @Test
    @DisplayName("handle creates a new queue if queue metadata is null")
    void handleCreatesNewQueueIfMetadataIsNull() throws HandleException {
        final var remoteQueueMetadataFromProof = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .receivedMessageId(10L)
                .receivedRunningHash(Bytes.wrap("some-hash".getBytes()))
                .build();

        given(handleContext.body()).willReturn(transactionBody);
        given(transactionBody.clprUpdateMessageQueueMetadataOrThrow())
                .willReturn(clprUpdateMessageQueueMetadataTransactionBody);
        given(clprUpdateMessageQueueMetadataTransactionBody.ledgerIdOrThrow()).willReturn(REMOTE_LEDGER_ID);
        given(clprUpdateMessageQueueMetadataTransactionBody.messageQueueMetadataProofOrThrow())
                .willReturn(clprStateProof);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprMessageQueueMetadataStore.class))
                .willReturn(writableClprMessageQueueMetadataStore);
        given(storeFactory.writableStore(WritableClprMessageStore.class))
                .willReturn(writableClprMessageStore); // For initQueue

        try (MockedStatic<ClprStateProofUtils> stateProofUtils = mockStatic(ClprStateProofUtils.class)) {
            stateProofUtils
                    .when(() -> ClprStateProofUtils.extractMessageQueueMetadata(clprStateProof))
                    .thenReturn(remoteQueueMetadataFromProof);

            // Call the handler
            subject.handle(handleContext);
        }
    }

    @Test
    @DisplayName("handle updates existing queue if localLedgerId is different from remoteLedgerId")
    void handleUpdatesExistingQueueIfLedgerIdsDiffer() throws HandleException {
        final var initialQueueMetadata = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .nextMessageId(6L)
                .sentMessageId(2L)
                .sentRunningHash(Bytes.wrap("old-sent-hash".getBytes()))
                .receivedMessageId(2L)
                .receivedRunningHash(Bytes.wrap("old-received-hash".getBytes()))
                .build();
        final var remoteQueueMetadataFromProof = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .receivedMessageId(4L) // New received ID from remote
                .receivedRunningHash(Bytes.wrap("new-received-hash".getBytes())) // New received hash from remote
                .build();

        given(handleContext.body()).willReturn(transactionBody);
        given(transactionBody.clprUpdateMessageQueueMetadataOrThrow())
                .willReturn(clprUpdateMessageQueueMetadataTransactionBody);
        given(clprUpdateMessageQueueMetadataTransactionBody.ledgerIdOrThrow()).willReturn(REMOTE_LEDGER_ID);
        given(clprUpdateMessageQueueMetadataTransactionBody.messageQueueMetadataProofOrThrow())
                .willReturn(clprStateProof);

        given(stateProofManager.getLocalLedgerId()).willReturn(LOCAL_LEDGER_ID); // Different from REMOTE_LEDGER_ID

        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprMessageQueueMetadataStore.class))
                .willReturn(writableClprMessageQueueMetadataStore);
        given(storeFactory.writableStore(WritableClprMessageStore.class)).willReturn(writableClprMessageStore);

        try (MockedStatic<ClprStateProofUtils> stateProofUtils = mockStatic(ClprStateProofUtils.class)) {
            stateProofUtils
                    .when(() -> ClprStateProofUtils.extractMessageQueueMetadata(clprStateProof))
                    .thenReturn(remoteQueueMetadataFromProof);

            // set up initial queue
            writableClprMessageQueueMetadataStore.put(REMOTE_LEDGER_ID, initialQueueMetadata);
            writableClprMessageStore.put(
                    ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(3L)
                            .build(),
                    ClprMessageValue.newBuilder().build());
            writableClprMessageStore.put(
                    ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(4L)
                            .build(),
                    ClprMessageValue.newBuilder().build());
            writableClprMessageStore.put(
                    ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(5L)
                            .build(),
                    ClprMessageValue.newBuilder().build());

            // Call the handler
            subject.handle(handleContext);

            // Verify the queue was updated
            final var updatedQueue = writableClprMessageQueueMetadataStore.get(REMOTE_LEDGER_ID);
            assertThat(updatedQueue).isNotNull();
            assertThat(updatedQueue.sentMessageId()).isEqualTo(remoteQueueMetadataFromProof.receivedMessageId());

            assertThat(updatedQueue.sentRunningHash()).isEqualTo(remoteQueueMetadataFromProof.receivedRunningHash());
            // Other fields should remain the same
            assertThat(updatedQueue.nextMessageId()).isEqualTo(initialQueueMetadata.nextMessageId());
            assertThat(updatedQueue.receivedMessageId()).isEqualTo(initialQueueMetadata.receivedMessageId());
            assertThat(updatedQueue.receivedRunningHash()).isEqualTo(initialQueueMetadata.receivedRunningHash());

            assertThat(writableClprMessageStore.get(ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(3L)
                            .build()))
                    .isNull();
            assertThat(writableClprMessageStore.get(ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(4L)
                            .build()))
                    .isNull();
            assertThat(writableClprMessageStore.get(ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(5L)
                            .build()))
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("handle clamps remote received id to local max and uses local running hash")
    void handleClampsRemoteReceivedIdToLocalMax() throws HandleException {
        final var initialQueueMetadata = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .nextMessageId(6L)
                .sentMessageId(2L)
                .sentRunningHash(Bytes.wrap("old-sent-hash".getBytes()))
                .receivedMessageId(2L)
                .receivedRunningHash(Bytes.wrap("old-received-hash".getBytes()))
                .build();
        final var remoteQueueMetadataFromProof = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .receivedMessageId(10L)
                .receivedRunningHash(Bytes.wrap("remote-received-hash".getBytes()))
                .build();

        given(handleContext.body()).willReturn(transactionBody);
        given(transactionBody.clprUpdateMessageQueueMetadataOrThrow())
                .willReturn(clprUpdateMessageQueueMetadataTransactionBody);
        given(clprUpdateMessageQueueMetadataTransactionBody.ledgerIdOrThrow()).willReturn(REMOTE_LEDGER_ID);
        given(clprUpdateMessageQueueMetadataTransactionBody.messageQueueMetadataProofOrThrow())
                .willReturn(clprStateProof);
        given(stateProofManager.getLocalLedgerId()).willReturn(LOCAL_LEDGER_ID);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprMessageQueueMetadataStore.class))
                .willReturn(writableClprMessageQueueMetadataStore);
        given(storeFactory.writableStore(WritableClprMessageStore.class)).willReturn(writableClprMessageStore);

        try (MockedStatic<ClprStateProofUtils> stateProofUtils = mockStatic(ClprStateProofUtils.class)) {
            stateProofUtils
                    .when(() -> ClprStateProofUtils.extractMessageQueueMetadata(clprStateProof))
                    .thenReturn(remoteQueueMetadataFromProof);

            writableClprMessageQueueMetadataStore.put(REMOTE_LEDGER_ID, initialQueueMetadata);
            writableClprMessageStore.put(
                    ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(3L)
                            .build(),
                    ClprMessageValue.newBuilder().build());
            writableClprMessageStore.put(
                    ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(4L)
                            .build(),
                    ClprMessageValue.newBuilder().build());
            final var lastRunningHash = Bytes.wrap("local-hash-5".getBytes());
            writableClprMessageStore.put(
                    ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(5L)
                            .build(),
                    ClprMessageValue.newBuilder()
                            .runningHashAfterProcessing(lastRunningHash)
                            .build());

            subject.handle(handleContext);

            final var updatedQueue = writableClprMessageQueueMetadataStore.get(REMOTE_LEDGER_ID);
            assertThat(updatedQueue).isNotNull();
            assertThat(updatedQueue.sentMessageId()).isEqualTo(5L);
            assertThat(updatedQueue.sentRunningHash()).isEqualTo(lastRunningHash);

            assertThat(writableClprMessageStore.get(ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(3L)
                            .build()))
                    .isNull();
            assertThat(writableClprMessageStore.get(ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(4L)
                            .build()))
                    .isNull();
            assertThat(writableClprMessageStore.get(ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(5L)
                            .build()))
                    .isNull();
        }
    }

    @Test
    @DisplayName("handle does not update queue when remote received id does not advance")
    void handleDoesNotUpdateQueueWhenRemoteReceivedIdDoesNotAdvance() throws HandleException {
        final var initialQueueMetadata = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .nextMessageId(6L)
                .sentMessageId(4L)
                .sentRunningHash(Bytes.wrap("old-sent-hash".getBytes()))
                .receivedMessageId(2L)
                .receivedRunningHash(Bytes.wrap("old-received-hash".getBytes()))
                .build();
        final var remoteQueueMetadataFromProof = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .receivedMessageId(3L)
                .receivedRunningHash(Bytes.wrap("new-received-hash".getBytes()))
                .build();

        given(handleContext.body()).willReturn(transactionBody);
        given(transactionBody.clprUpdateMessageQueueMetadataOrThrow())
                .willReturn(clprUpdateMessageQueueMetadataTransactionBody);
        given(clprUpdateMessageQueueMetadataTransactionBody.ledgerIdOrThrow()).willReturn(REMOTE_LEDGER_ID);
        given(clprUpdateMessageQueueMetadataTransactionBody.messageQueueMetadataProofOrThrow())
                .willReturn(clprStateProof);
        given(stateProofManager.getLocalLedgerId()).willReturn(LOCAL_LEDGER_ID);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprMessageQueueMetadataStore.class))
                .willReturn(writableClprMessageQueueMetadataStore);

        try (MockedStatic<ClprStateProofUtils> stateProofUtils = mockStatic(ClprStateProofUtils.class)) {
            stateProofUtils
                    .when(() -> ClprStateProofUtils.extractMessageQueueMetadata(clprStateProof))
                    .thenReturn(remoteQueueMetadataFromProof);

            writableClprMessageQueueMetadataStore.put(REMOTE_LEDGER_ID, initialQueueMetadata);

            subject.handle(handleContext);

            final var currentQueue = writableClprMessageQueueMetadataStore.get(REMOTE_LEDGER_ID);
            assertThat(currentQueue).isNotNull();
            assertThat(currentQueue).isEqualTo(initialQueueMetadata);
        }
    }

    @Test
    @DisplayName("handle removes all messages when remote ack catches up")
    void handleRemovesAllMessagesWhenRemoteAckCatchesUp() throws HandleException {
        final var initialQueueMetadata = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .nextMessageId(4L)
                .sentMessageId(0L)
                .sentRunningHash(Bytes.wrap("old-sent-hash".getBytes()))
                .receivedMessageId(0L)
                .receivedRunningHash(Bytes.wrap("old-received-hash".getBytes()))
                .build();
        final var remoteQueueMetadataFromProof = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .receivedMessageId(3L)
                .receivedRunningHash(Bytes.wrap("new-received-hash".getBytes()))
                .build();

        given(handleContext.body()).willReturn(transactionBody);
        given(transactionBody.clprUpdateMessageQueueMetadataOrThrow())
                .willReturn(clprUpdateMessageQueueMetadataTransactionBody);
        given(clprUpdateMessageQueueMetadataTransactionBody.ledgerIdOrThrow()).willReturn(REMOTE_LEDGER_ID);
        given(clprUpdateMessageQueueMetadataTransactionBody.messageQueueMetadataProofOrThrow())
                .willReturn(clprStateProof);
        given(stateProofManager.getLocalLedgerId()).willReturn(LOCAL_LEDGER_ID);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprMessageQueueMetadataStore.class))
                .willReturn(writableClprMessageQueueMetadataStore);
        given(storeFactory.writableStore(WritableClprMessageStore.class)).willReturn(writableClprMessageStore);

        try (MockedStatic<ClprStateProofUtils> stateProofUtils = mockStatic(ClprStateProofUtils.class)) {
            stateProofUtils
                    .when(() -> ClprStateProofUtils.extractMessageQueueMetadata(clprStateProof))
                    .thenReturn(remoteQueueMetadataFromProof);

            writableClprMessageQueueMetadataStore.put(REMOTE_LEDGER_ID, initialQueueMetadata);
            writableClprMessageStore.put(
                    ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(1L)
                            .build(),
                    ClprMessageValue.newBuilder().build());
            writableClprMessageStore.put(
                    ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(2L)
                            .build(),
                    ClprMessageValue.newBuilder().build());
            writableClprMessageStore.put(
                    ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(3L)
                            .build(),
                    ClprMessageValue.newBuilder().build());

            subject.handle(handleContext);

            final var updatedQueue = writableClprMessageQueueMetadataStore.get(REMOTE_LEDGER_ID);
            assertThat(updatedQueue).isNotNull();
            assertThat(updatedQueue.sentMessageId()).isEqualTo(updatedQueue.nextMessageId() - 1);

            assertThat(writableClprMessageStore.get(ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(1L)
                            .build()))
                    .isNull();
            assertThat(writableClprMessageStore.get(ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(2L)
                            .build()))
                    .isNull();
            assertThat(writableClprMessageStore.get(ClprMessageKey.newBuilder()
                            .ledgerId(REMOTE_LEDGER_ID)
                            .messageId(3L)
                            .build()))
                    .isNull();
        }
    }

    @Test
    @DisplayName("handle does not update queue if localLedgerId is null or equals remoteLedgerId")
    void handleDoesNotUpdateQueueIfLedgerIdsAreSameOrLocalIsNull() throws HandleException {
        final var initialQueueMetadata = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .nextMessageId(5L)
                .sentMessageId(2L)
                .sentRunningHash(Bytes.wrap("old-sent-hash".getBytes()))
                .receivedMessageId(2L)
                .receivedRunningHash(Bytes.wrap("old-received-hash".getBytes()))
                .build();
        final var remoteQueueMetadataFromProof = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(REMOTE_LEDGER_ID)
                .receivedMessageId(15L)
                .receivedRunningHash(Bytes.wrap("new-received-hash".getBytes()))
                .build();

        given(handleContext.body()).willReturn(transactionBody);
        given(transactionBody.clprUpdateMessageQueueMetadataOrThrow())
                .willReturn(clprUpdateMessageQueueMetadataTransactionBody);
        given(clprUpdateMessageQueueMetadataTransactionBody.ledgerIdOrThrow()).willReturn(REMOTE_LEDGER_ID);
        given(clprUpdateMessageQueueMetadataTransactionBody.messageQueueMetadataProofOrThrow())
                .willReturn(clprStateProof);
        given(stateProofManager.getLocalLedgerId()).willReturn(REMOTE_LEDGER_ID); // Same as remoteLedgerId

        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprMessageQueueMetadataStore.class))
                .willReturn(writableClprMessageQueueMetadataStore);

        // set initial queue
        writableClprMessageQueueMetadataStore.put(REMOTE_LEDGER_ID, initialQueueMetadata);

        try (MockedStatic<ClprStateProofUtils> stateProofUtils = mockStatic(ClprStateProofUtils.class)) {
            stateProofUtils
                    .when(() -> ClprStateProofUtils.extractMessageQueueMetadata(clprStateProof))
                    .thenReturn(remoteQueueMetadataFromProof);

            // Call the handler
            subject.handle(handleContext);

            // Verify the queue was NOT updated (should remain initial state)
            final var currentQueue = writableClprMessageQueueMetadataStore.get(REMOTE_LEDGER_ID);
            assertThat(currentQueue).isNotNull();
            assertThat(currentQueue).isEqualTo(initialQueueMetadata);
        }
    }
}
