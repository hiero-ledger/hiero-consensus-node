// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_ALREADY_ACKNOWLEDGED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_ALREADY_REDACTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_NOT_REDACTABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.clpr.ClprRedactMessageTransactionBody;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.ReadableChannelStore;
import com.hedera.node.app.service.clpr.impl.ClprHashUtils;
import com.hedera.node.app.service.clpr.impl.WritableMessageQueueStore;
import com.hedera.node.app.service.clpr.impl.handlers.ClprRedactMessageHandler;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprRedactMessageHandlerTest {

    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[32]);
    private static final long MESSAGE_ID = 5L;
    private static final Bytes RUNNING_HASH = Bytes.wrap(new byte[32]);

    @Mock
    private HandleContext handleContext;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private ReadableChannelStore channelStore;

    @Mock
    private WritableMessageQueueStore messageQueueStore;

    private ClprRedactMessageHandler subject;

    @BeforeEach
    void setUp() {
        subject = new ClprRedactMessageHandler();
    }

    // ---- pureChecks tests ----

    @Test
    @DisplayName("pureChecks rejects invalid channel_id length")
    void pureChecksRejectsInvalidChannelIdLength() {
        final var body = txnBody(Bytes.wrap(new byte[16]), 1L);
        given(pureChecksContext.body()).willReturn(body);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("pureChecks rejects zero message_id")
    void pureChecksRejectsZeroMessageId() {
        final var body = txnBody(CHANNEL_ID, 0L);
        given(pureChecksContext.body()).willReturn(body);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("pureChecks accepts valid input")
    void pureChecksAcceptsValidInput() throws PreCheckException {
        final var body = txnBody(CHANNEL_ID, MESSAGE_ID);
        given(pureChecksContext.body()).willReturn(body);

        subject.pureChecks(pureChecksContext);
    }

    // ---- handle tests ----

    @Test
    @DisplayName("rejects when CLPR is disabled")
    void rejectsWhenDisabled() {
        setupHandleContext(false);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
    }

    @Test
    @DisplayName("rejects when channel is not found")
    void rejectsWhenChannelNotFound() {
        setupHandleContext(true);
        given(channelStore.getChannel(CHANNEL_ID)).willReturn(null);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CHANNEL_NOT_FOUND));
    }

    @Test
    @DisplayName("rejects when message is already acknowledged")
    void rejectsWhenMessageAlreadyAcknowledged() {
        setupHandleContext(true);
        // ackedMessageId = 10, so message_id 5 is already acknowledged
        given(channelStore.getChannel(CHANNEL_ID)).willReturn(channel(10, 20));

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_MESSAGE_ALREADY_ACKNOWLEDGED));
    }

    @Test
    @DisplayName("rejects when message_id equals acked_message_id")
    void rejectsWhenMessageIdEqualsAcked() {
        setupHandleContext(true);
        // ackedMessageId = 5, message_id = 5 → already acknowledged
        given(channelStore.getChannel(CHANNEL_ID)).willReturn(channel(5, 20));

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_MESSAGE_ALREADY_ACKNOWLEDGED));
    }

    @Test
    @DisplayName("rejects when message_id is beyond queue range")
    void rejectsWhenMessageIdBeyondQueue() {
        setupHandleContext(true);
        // nextMessageId = 3, so message_id 5 doesn't exist yet
        given(channelStore.getChannel(CHANNEL_ID)).willReturn(channel(0, 3));

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_MESSAGE_NOT_FOUND));
    }

    @Test
    @DisplayName("rejects when message is not found in store")
    void rejectsWhenMessageNotInStore() {
        setupHandleContext(true);
        given(channelStore.getChannel(CHANNEL_ID)).willReturn(channel(0, 10));
        given(messageQueueStore.getMessage(CHANNEL_ID, MESSAGE_ID)).willReturn(null);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_MESSAGE_NOT_FOUND));
    }

    @Test
    @DisplayName("rejects when message is already redacted")
    void rejectsWhenMessageAlreadyRedacted() {
        setupHandleContext(true);
        given(channelStore.getChannel(CHANNEL_ID)).willReturn(channel(0, 10));
        // Already redacted: payload is a ClprRedactedMessage variant
        final var redactedPayload = ClprMessagePayload.newBuilder()
                .redactedMessage(com.hedera.hapi.node.state.clpr.ClprRedactedMessage.newBuilder()
                        .messageHash(RUNNING_HASH)
                        .build())
                .build();
        final var redactedMessage = ClprMessageValue.newBuilder()
                .payload(redactedPayload)
                .runningHashAfterProcessing(RUNNING_HASH)
                .build();
        given(messageQueueStore.getMessage(CHANNEL_ID, MESSAGE_ID)).willReturn(redactedMessage);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_MESSAGE_ALREADY_REDACTED));
    }

    @Test
    @DisplayName("redacts Data slot: ClprRedactedMessage carries SHA-256(payload) and preserves sender")
    void successfullyRedactsDataMessage() {
        setupHandleContext(true);
        given(channelStore.getChannel(CHANNEL_ID)).willReturn(channel(0, 10));

        final var senderAddress = Bytes.wrap(HexFormat.of().parseHex("112233445566778899aabbccddeeff0102030405"));
        final var payload = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .sender(senderAddress)
                        .messageData(Bytes.wrap(new byte[] {1, 2, 3}))
                        .build())
                .build();
        final var originalMessage = ClprMessageValue.newBuilder()
                .payload(payload)
                .runningHashAfterProcessing(RUNNING_HASH)
                .build();
        given(messageQueueStore.getMessage(CHANNEL_ID, MESSAGE_ID)).willReturn(originalMessage);

        subject.handle(handleContext);

        final var valueCaptor = ArgumentCaptor.forClass(ClprMessageValue.class);
        verify(messageQueueStore)
                .put(
                        org.mockito.ArgumentMatchers.eq(CHANNEL_ID),
                        org.mockito.ArgumentMatchers.eq(MESSAGE_ID),
                        valueCaptor.capture());

        final var redacted = valueCaptor.getValue();
        // Slot running hash unchanged — chain is invariant under redaction (spec §4.1).
        assertThat(redacted.runningHashAfterProcessing()).isEqualTo(RUNNING_HASH);
        assertThat(redacted.payloadOrThrow().hasRedactedMessage()).isTrue();
        final var redactedMsg = redacted.payloadOrThrow().redactedMessageOrThrow();
        final var expectedMessageHash = ClprHashUtils.sha256(
                ClprMessagePayload.PROTOBUF.toBytes(payload).toByteArray());
        assertThat(redactedMsg.messageHash()).isEqualTo(Bytes.wrap(expectedMessageHash));
        // Sender preserved so ClprSubmitBundleHandler can still deliver the REDACTED
        // response callback to the originating application when the reply round-trips.
        assertThat(redactedMsg.sender()).isEqualTo(senderAddress);
    }

    @Test
    @DisplayName("rejects with CLPR_MESSAGE_NOT_REDACTABLE when the slot holds a Response Message")
    void rejectsRedactOfReplyMessage() {
        setupHandleContext(true);
        given(channelStore.getChannel(CHANNEL_ID)).willReturn(channel(0, 10));

        final var replyPayload = ClprMessagePayload.newBuilder()
                .messageReply(com.hedera.hapi.node.state.clpr.ClprMessageReply.newBuilder()
                        .messageId(7L)
                        .status(com.hedera.hapi.node.state.clpr.ClprMessageReplyStatus.SUCCESS)
                        .messageReplyData(Bytes.EMPTY)
                        .build())
                .build();
        given(messageQueueStore.getMessage(CHANNEL_ID, MESSAGE_ID))
                .willReturn(ClprMessageValue.newBuilder()
                        .payload(replyPayload)
                        .runningHashAfterProcessing(RUNNING_HASH)
                        .build());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_MESSAGE_NOT_REDACTABLE));
    }

    @Test
    @DisplayName("rejects with CLPR_MESSAGE_NOT_REDACTABLE when the slot holds a Control Message")
    void rejectsRedactOfControlMessage() {
        setupHandleContext(true);
        given(channelStore.getChannel(CHANNEL_ID)).willReturn(channel(0, 10));

        final var controlPayload = ClprMessagePayload.newBuilder()
                .control(com.hedera.hapi.node.state.clpr.ClprControlMessage.newBuilder()
                        .build())
                .build();
        given(messageQueueStore.getMessage(CHANNEL_ID, MESSAGE_ID))
                .willReturn(ClprMessageValue.newBuilder()
                        .payload(controlPayload)
                        .runningHashAfterProcessing(RUNNING_HASH)
                        .build());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_MESSAGE_NOT_REDACTABLE));
    }

    // ---- helpers ----

    private void setupHandleContext(final boolean clprEnabled) {
        final Configuration config = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", clprEnabled ? "true" : "false")
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        if (clprEnabled) {
            final var body = txnBody(CHANNEL_ID, MESSAGE_ID);
            given(handleContext.body()).willReturn(body);
            given(handleContext.storeFactory()).willReturn(storeFactory);
            given(storeFactory.readableStore(ReadableChannelStore.class)).willReturn(channelStore);
            given(storeFactory.writableStore(WritableMessageQueueStore.class)).willReturn(messageQueueStore);
        }
    }

    private static TransactionBody txnBody(final Bytes channelId, final long messageId) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.DEFAULT)
                .clprRedactMessage(ClprRedactMessageTransactionBody.newBuilder()
                        .channelId(channelId)
                        .messageId(messageId)
                        .build())
                .build();
    }

    private static ClprChannel channel(final long ackedMessageId, final long nextMessageId) {
        return ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .status(ClprChannelStatus.ACTIVE)
                .ackedMessageId(ackedMessageId)
                .nextMessageId(nextMessageId)
                .sentRunningHash(Bytes.wrap(new byte[32]))
                .build();
    }
}
