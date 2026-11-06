// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_CHANNEL_STATUS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CONNECTORS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.MESSAGE_QUEUE_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.PENDING_COMMITMENTS_STATE_ID;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.lenient;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.clpr.ClprCloseChannelTransactionBody;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.clpr.ClprControlMessage;
import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageReply;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.service.clpr.impl.WritableChannelStore;
import com.hedera.node.app.service.clpr.impl.WritableConnectorStore;
import com.hedera.node.app.service.clpr.impl.WritableMessageQueueStore;
import com.hedera.node.app.service.clpr.impl.WritablePendingCommitmentStore;
import com.hedera.node.app.service.clpr.impl.handlers.ClprCloseChannelHandler;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprCloseChannelHandlerTest {

    private static final AccountID PAYER_ID =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(1001).build();
    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[32]);
    private static final Bytes COMMITMENT = Bytes.wrap(new byte[] {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
    });

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private WritableStates writableStates;

    @Mock
    private ClprChannelLifecycle channelLifecycle;

    private ClprCloseChannelHandler subject;
    private WritableChannelStore channelStore;
    private WritablePendingCommitmentStore commitmentStore;
    private WritableConnectorStore connectorStore;
    private WritableMessageQueueStore queueStore;
    private MapWritableKVState<ProtoBytes, ClprChannel> writableChannels;

    @BeforeEach
    void setUp() {
        subject = new ClprCloseChannelHandler(channelLifecycle);

        writableChannels = MapWritableKVState.<ProtoBytes, ClprChannel>builder(
                        CHANNELS_STATE_ID, "ClprService:CHANNELS")
                .build();
        lenient()
                .when(writableStates.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID))
                .thenReturn(writableChannels);
        channelStore = new WritableChannelStore(writableStates);

        final var writableCommitments = MapWritableKVState.<ProtoBytes, ProtoBytes>builder(
                        PENDING_COMMITMENTS_STATE_ID, "ClprService:PENDING_COMMITMENTS")
                .build();
        lenient()
                .when(writableStates.<ProtoBytes, ProtoBytes>get(PENDING_COMMITMENTS_STATE_ID))
                .thenReturn(writableCommitments);
        commitmentStore = new WritablePendingCommitmentStore(writableStates);

        final var writableConnectors = MapWritableKVState.<ClprConnectorKey, ClprConnector>builder(
                        CONNECTORS_STATE_ID, "ClprService:CONNECTORS")
                .build();
        lenient()
                .when(writableStates.<ClprConnectorKey, ClprConnector>get(CONNECTORS_STATE_ID))
                .thenReturn(writableConnectors);
        connectorStore = new WritableConnectorStore(writableStates);

        final var writableQueue = MapWritableKVState.<ClprMessageKey, ClprMessageValue>builder(
                        MESSAGE_QUEUE_STATE_ID, "ClprService:MESSAGE_QUEUE")
                .build();
        lenient()
                .when(writableStates.<ClprMessageKey, ClprMessageValue>get(MESSAGE_QUEUE_STATE_ID))
                .thenReturn(writableQueue);
        queueStore = new WritableMessageQueueStore(writableStates);
    }

    // ========== pureChecks tests ==========

    @Test
    @DisplayName("should reject when channel_id is not 32 bytes")
    void rejectsWrongChannelIdLength() {
        final var op = ClprCloseChannelTransactionBody.newBuilder()
                .channelId(Bytes.wrap(new byte[16]))
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should pass pureChecks with valid 32-byte channel_id")
    void passesWithValidChannelId() throws PreCheckException {
        given(pureChecksContext.body()).willReturn(validTxn());
        subject.pureChecks(pureChecksContext);
    }

    @Test
    void rejectsForceCloseWithAnOwnershipCommitment() {
        final var op = ClprCloseChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .ownershipCommitment(COMMITMENT)
                .forceClose(true)
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    // ========== handle tests ==========

    @Test
    @DisplayName("should reject when CLPR is not enabled")
    void rejectsWhenClprNotEnabled() {
        final var disabledConfig = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", false)
                .getOrCreateConfig();
        setupHandleContext(validTxn(), disabledConfig);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
    }

    @Test
    @DisplayName("should reject when channel not found")
    void rejectsWhenChannelNotFound() {
        setupHandleContext(validTxn());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CHANNEL_NOT_FOUND));
    }

    @Test
    @DisplayName("should transition ACTIVE channel (with unacked messages) to CLOSING")
    void transitionsActiveToClosing() {
        putChannelWithMessageIds(ClprChannelStatus.ACTIVE, 5L, 2L);
        setupHandleContext(validTxn());

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated).isNotNull();
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.CLOSING);
    }

    @Test
    @DisplayName("should transition PAUSED channel (with unacked messages) to CLOSING")
    void transitionsPausedToClosing() {
        putChannelWithMessageIds(ClprChannelStatus.PAUSED, 5L, 2L);
        setupHandleContext(validTxn());

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated).isNotNull();
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.CLOSING);
    }

    @Test
    @DisplayName("should transition ACTIVE channel (fully acked) to DRAINED")
    void transitionsActiveToDrainedWhenFullyAcked() {
        putChannelWithMessageIds(ClprChannelStatus.ACTIVE, 5L, 4L);
        setupHandleContext(validTxn());

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated).isNotNull();
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.DRAINED);
        then(channelLifecycle).should(never()).onChannelClosed(any());
    }

    @Test
    @DisplayName("should transition ACTIVE channel (unacked messages) to CLOSING")
    void transitionsActiveToClosingWhenUnacked() {
        putChannelWithMessageIds(ClprChannelStatus.ACTIVE, 5L, 2L);
        setupHandleContext(validTxn());

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated).isNotNull();
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.CLOSING);
        then(channelLifecycle).should(never()).onChannelClosed(any());
    }

    @Test
    @DisplayName("should transition PAUSED channel (fully acked) to DRAINED")
    void transitionsPausedToDrainedWhenFullyAcked() {
        putChannelWithMessageIds(ClprChannelStatus.PAUSED, 3L, 2L);
        setupHandleContext(validTxn());

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated).isNotNull();
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.DRAINED);
        then(channelLifecycle).should(never()).onChannelClosed(any());
    }

    @Test
    @DisplayName("should transition PAUSED channel (unacked messages) to CLOSING")
    void transitionsPausedToClosingWhenUnacked() {
        putChannelWithMessageIds(ClprChannelStatus.PAUSED, 5L, 1L);
        setupHandleContext(validTxn());

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated).isNotNull();
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.CLOSING);
        then(channelLifecycle).should(never()).onChannelClosed(any());
    }

    @Test
    @DisplayName("should transition DRAINED channel to CLOSED and call onChannelClosed")
    void transitionsDrainedToClosed() {
        putChannel(ClprChannelStatus.DRAINED);
        setupHandleContext(validTxn());

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated).isNotNull();
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.CLOSED);
        then(channelLifecycle).should().onChannelClosed(CHANNEL_ID);
    }

    @Test
    @DisplayName("should transition ACTIVE channel (nothing sent) to DRAINED via fast path")
    void transitionsActiveWithNothingSentToDrained() {
        putChannelWithMessageIds(ClprChannelStatus.ACTIVE, 0L, 0L);
        setupHandleContext(validTxn());

        subject.handle(handleContext);

        final var updated = channelStore.getChannel(CHANNEL_ID);
        assertThat(updated).isNotNull();
        assertThat(updated.status()).isEqualTo(ClprChannelStatus.DRAINED);
        then(channelLifecycle).should(never()).onChannelClosed(any());
    }

    @Test
    @DisplayName("should reject when channel is already CLOSING")
    void rejectsAlreadyClosing() {
        putChannel(ClprChannelStatus.CLOSING);
        setupHandleContext(validTxn());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_CHANNEL_STATUS));
    }

    @Test
    @DisplayName("should reject when channel is CLOSED")
    void rejectsClosed() {
        putChannel(ClprChannelStatus.CLOSED);
        setupHandleContext(validTxn());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_CHANNEL_STATUS));
    }

    @Test
    @DisplayName("should delete PENDING channel record and free its commitment")
    void deletesPendingChannelRecordAndCommitment() {
        final var channel = ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .status(ClprChannelStatus.PENDING)
                .ownershipCommitment(COMMITMENT)
                .build();
        channelStore.put(channel);
        commitmentStore.put(COMMITMENT);
        setupHandleContext(validTxn());

        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID)).isNull();
        assertThat(commitmentStore.contains(COMMITMENT)).isFalse();
        then(channelLifecycle).should(never()).onChannelClosed(any());
    }

    @Test
    @DisplayName("should delete abandoned pending commitment when no channel record exists")
    void adminClearsAbandonedCommitment() {
        // Only a pending commitment exists — registerChannel was submitted but
        // completeChannel was never called.
        commitmentStore.put(COMMITMENT);
        final var op = ClprCloseChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .ownershipCommitment(COMMITMENT)
                .build();
        setupHandleContext(txnWith(op));

        subject.handle(handleContext);

        assertThat(commitmentStore.contains(COMMITMENT)).isFalse();
        assertThat(channelStore.getChannel(CHANNEL_ID)).isNull();
        // No Channel ever existed — orchestrator was never tracking this id.
        then(channelLifecycle).should(never()).onChannelClosed(any());
    }

    @Test
    @DisplayName("should reject when no channel and no matching pending commitment")
    void rejectsWhenNeitherChannelNorCommitmentFound() {
        final var op = ClprCloseChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .ownershipCommitment(COMMITMENT)
                .build();
        setupHandleContext(txnWith(op));

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CHANNEL_NOT_FOUND));
    }

    @Test
    @DisplayName("should reject when no channel and ownership_commitment is absent")
    void rejectsWhenNoChannelAndNoCommitmentProvided() {
        setupHandleContext(validTxn());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CHANNEL_NOT_FOUND));
    }

    @Test
    void forceClosePurgesPendingSlotsInBoundedBatches() {
        final var connectorId = COMMITMENT;
        final var connectorKey = new ClprConnectorKey(CHANNEL_ID, connectorId);
        connectorStore.put(ClprConnector.newBuilder()
                .channelId(CHANNEL_ID)
                .connectorId(connectorId)
                .inFlightMessageCount(2)
                .build());
        final var initial = ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .status(ClprChannelStatus.CLOSING)
                .ownershipCommitment(COMMITMENT)
                .nextMessageId(20L)
                .ackedMessageId(1L)
                .sentRunningHash(COMMITMENT)
                .build();
        channelStore.put(initial);
        commitmentStore.put(COMMITMENT);
        final var data = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder().connectorId(connectorId))
                .build();
        final var control = ClprMessagePayload.newBuilder()
                .control(ClprControlMessage.DEFAULT)
                .build();
        final var largeReply = ClprMessagePayload.newBuilder()
                .messageReply(ClprMessageReply.newBuilder()
                        .messageId(1L)
                        .messageReplyData(Bytes.wrap(new byte[2 * 1024 * 1024])))
                .build();
        // Include a sparse range and another channel with the same sequence number.
        queueStore.put(CHANNEL_ID, 2L, queueValue(largeReply));
        queueStore.put(CHANNEL_ID, 3L, queueValue(control));
        queueStore.put(CHANNEL_ID, 17L, queueValue(data));
        queueStore.put(CHANNEL_ID, 18L, queueValue(data));
        final var anotherChannel = COMMITMENT;
        queueStore.put(anotherChannel, 2L, queueValue(largeReply));
        setupForceCloseContext();

        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID))
                .isEqualTo(initial.copyBuilder()
                        .status(ClprChannelStatus.CLOSED)
                        .queuePurgeCursor(18L)
                        .build());
        assertThat(queueStore.getMessage(CHANNEL_ID, 2L)).isNull();
        assertThat(queueStore.getMessage(CHANNEL_ID, 3L)).isNull();
        assertThat(queueStore.getMessage(CHANNEL_ID, 17L)).isNull();
        assertThat(queueStore.getMessage(CHANNEL_ID, 18L)).isNotNull();
        assertThat(connectorStore.getConnector(connectorKey).inFlightMessageCount())
                .isEqualTo(1L);

        subject.handle(handleContext);
        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID).queuePurgeCursor()).isEqualTo(20L);
        assertThat(queueStore.getMessage(CHANNEL_ID, 18L)).isNull();
        assertThat(connectorStore.getConnector(connectorKey).inFlightMessageCount())
                .isZero();
        assertThat(queueStore.getMessage(anotherChannel, 2L)).isEqualTo(queueValue(largeReply));
        assertThat(commitmentStore.contains(COMMITMENT)).isTrue();
        then(channelLifecycle).should().onChannelClosed(CHANNEL_ID);
    }

    @Test
    void forceCloseRecoversQueueForAnAlreadyClosedChannel() {
        putChannelWithMessageIds(ClprChannelStatus.CLOSED, 2L, 0L);
        final var reply = ClprMessagePayload.newBuilder()
                .messageReply(ClprMessageReply.newBuilder().messageId(1L))
                .build();
        queueStore.put(CHANNEL_ID, 1L, queueValue(reply));
        setupForceCloseContext();

        subject.handle(handleContext);

        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.CLOSED);
        assertThat(queueStore.getMessage(CHANNEL_ID, 1L)).isNull();
        then(channelLifecycle).shouldHaveNoInteractions();
    }

    @Test
    void forceCloseRejectsPendingChannel() {
        putChannel(ClprChannelStatus.PENDING);
        setupForceCloseContext();

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_CHANNEL_STATUS));
        assertThat(channelStore.getChannel(CHANNEL_ID).status()).isEqualTo(ClprChannelStatus.PENDING);
        then(channelLifecycle).shouldHaveNoInteractions();
    }

    // ========== Helper methods ==========

    private void setupForceCloseContext() {
        setupHandleContext(txnWith(ClprCloseChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .forceClose(true)
                .build()));
        lenient()
                .when(storeFactory.writableStore(WritableMessageQueueStore.class))
                .thenReturn(queueStore);
        lenient().when(storeFactory.writableStore(WritableConnectorStore.class)).thenReturn(connectorStore);
    }

    private static ClprMessageValue queueValue(final ClprMessagePayload payload) {
        return ClprMessageValue.newBuilder().payload(payload).build();
    }

    private void putChannel(final ClprChannelStatus status) {
        final var channel = ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .status(status)
                .ownershipCommitment(COMMITMENT)
                .build();
        channelStore.put(channel);
    }

    private void putChannelWithMessageIds(
            final ClprChannelStatus status, final long nextMessageId, final long ackedMessageId) {
        final var channel = ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .status(status)
                .ownershipCommitment(COMMITMENT)
                .nextMessageId(nextMessageId)
                .ackedMessageId(ackedMessageId)
                .build();
        channelStore.put(channel);
    }

    private void setupHandleContext(final TransactionBody txn) {
        final var enabledConfig =
                HederaTestConfigBuilder.create().withValue("clpr.enabled", true).getOrCreateConfig();
        setupHandleContext(txn, enabledConfig);
    }

    private void setupHandleContext(final TransactionBody txn, final Configuration configuration) {
        lenient().when(handleContext.body()).thenReturn(txn);
        lenient().when(handleContext.payer()).thenReturn(PAYER_ID);
        lenient().when(handleContext.configuration()).thenReturn(configuration);
        lenient().when(handleContext.storeFactory()).thenReturn(storeFactory);
        lenient().when(storeFactory.writableStore(WritableChannelStore.class)).thenReturn(channelStore);
        lenient()
                .when(storeFactory.writableStore(WritablePendingCommitmentStore.class))
                .thenReturn(commitmentStore);
    }

    private TransactionBody validTxn() {
        final var op = ClprCloseChannelTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .build();
        return txnWith(op);
    }

    private TransactionBody txnWith(final ClprCloseChannelTransactionBody op) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID))
                .clprCloseChannel(op)
                .build();
    }
}
