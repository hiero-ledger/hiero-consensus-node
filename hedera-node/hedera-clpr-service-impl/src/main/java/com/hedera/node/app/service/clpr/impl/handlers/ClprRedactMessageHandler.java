// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_ALREADY_ACKNOWLEDGED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_ALREADY_REDACTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_NOT_REDACTABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprRedactedMessage;
import com.hedera.node.app.service.clpr.ReadableChannelStore;
import com.hedera.node.app.service.clpr.impl.ClprHashUtils;
import com.hedera.node.app.service.clpr.impl.WritableMessageQueueStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handles {@link HederaFunctionality#CLPR_REDACT_MESSAGE} transactions.
 * Replaces the slot's payload with a {@link ClprRedactedMessage} carrying
 * {@code SHA-256(serialized_original_payload)} (clpr-service-spec.md §4.4).
 */
@Singleton
public class ClprRedactMessageHandler extends AbstractClprHandler {

    @Inject
    public ClprRedactMessageHandler() {
        // Exists for Dagger injection
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        final var op = context.body().clprRedactMessageOrThrow();
        validateTruePreCheck(op.channelId().length() == CHANNEL_ID_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.messageId() != 0, INVALID_TRANSACTION_BODY);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        // Authorization enforced by PrivilegesVerifier.checkClprAdmin — only
        // treasury and system admin accounts are permitted.
    }

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        final var op = context.body().clprRedactMessageOrThrow();
        final var channelId = op.channelId();
        final var messageId = op.messageId();

        final var storeFactory = context.storeFactory();
        final var channelStore = storeFactory.readableStore(ReadableChannelStore.class);
        final var messageQueueStore = storeFactory.writableStore(WritableMessageQueueStore.class);

        final var channel = requireChannel(channelStore, channelId);

        // Validate message has not been acknowledged
        validateTrue(messageId > channel.ackedMessageId(), CLPR_MESSAGE_ALREADY_ACKNOWLEDGED);

        // Validate message exists in queue range
        validateTrue(messageId < channel.nextMessageId(), CLPR_MESSAGE_NOT_FOUND);

        // Look up the message
        final var message = messageQueueStore.getMessage(channelId, messageId);
        validateTrue(message != null, CLPR_MESSAGE_NOT_FOUND);

        // Check if already redacted
        // rbair23: I think we can remove the exception and let this be idempotent
        final var existingPayload = message.payload();
        if (existingPayload == null || existingPayload.hasRedactedMessage()) {
            throw new HandleException(CLPR_MESSAGE_ALREADY_REDACTED);
        }

        // Spec §4.4: only Data Messages may be redacted — Response Messages and Control Messages
        // may not. Reject any non-Data payload variant explicitly.
        if (!existingPayload.hasMessage()) {
            throw new HandleException(CLPR_MESSAGE_NOT_REDACTABLE);
        }

        final var messageHash = ClprHashUtils.sha256(
                ClprMessagePayload.PROTOBUF.toBytes(existingPayload).toByteArray());
        final var redactedPayload = ClprMessagePayload.newBuilder()
                .redactedMessage(ClprRedactedMessage.newBuilder()
                        .messageHash(Bytes.wrap(messageHash))
                        // Preserve the originating application address so the source-side onClprResponse
                        // dispatch can still deliver the eventual REDACTED reply to that application after
                        // the peer round-trips the slot.
                        .sender(existingPayload.messageOrThrow().sender())
                        .build())
                .build();
        messageQueueStore.put(
                channelId,
                messageId,
                message.copyBuilder().payload(redactedPayload).build());
    }
}
