// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Provides write methods for modifying the CLPR message queue in state.
 */
public class WritableMessageQueueStore extends ReadableMessageQueueStoreImpl {
    private static final Logger log = LogManager.getLogger(WritableMessageQueueStore.class);

    /**
     * Create a new {@link WritableMessageQueueStore} instance.
     *
     * @param states the state to use
     */
    public WritableMessageQueueStore(@NonNull final WritableStates states) {
        super(states);
    }

    @Override
    protected WritableKVState<ClprMessageKey, ClprMessageValue> messageQueueState() {
        return super.messageQueueState();
    }

    /**
     * Persists a message into the queue. If a message with the same key
     * already exists, it will be overwritten.
     *
     * @param channelId the 32-byte channel identifier
     * @param messageId the message sequence number
     * @param value the message value to persist
     */
    public void put(@NonNull final Bytes channelId, final long messageId, @NonNull final ClprMessageValue value) {
        requireNonNull(channelId);
        requireNonNull(value);
        final var key = ClprMessageKey.newBuilder()
                .channelId(channelId)
                .messageId(messageId)
                .build();
        messageQueueState().put(key, value);
        final var payload = value.hasPayload() ? value.payload() : null;
        log.debug(
                "[CLPR-QUEUE-WRITE] put conn={} messageId={} kind={} runningHash={} hasPayload={}",
                channelId,
                messageId,
                payloadKind(payload),
                shortHex(value.runningHashAfterProcessing()),
                value.hasPayload());
    }

    /**
     * Removes a message from the queue.
     *
     * @param channelId the 32-byte channel identifier
     * @param messageId the message sequence number
     */
    public void remove(@NonNull final Bytes channelId, final long messageId) {
        requireNonNull(channelId);
        final var key = ClprMessageKey.newBuilder()
                .channelId(channelId)
                .messageId(messageId)
                .build();
        messageQueueState().remove(key);
        log.debug("[CLPR-QUEUE-WRITE] remove conn={} messageId={}", channelId, messageId);
    }

    private static String payloadKind(@Nullable final ClprMessagePayload payload) {
        if (payload == null) {
            return "<none>";
        } else if (payload.hasMessage()) {
            return "DATA";
        } else if (payload.hasMessageReply()) {
            return "MESSAGE_REPLY";
        } else if (payload.hasControl()) {
            return "CONTROL";
        } else if (payload.hasRedactedMessage()) {
            return "REDACTED";
        } else {
            return "EMPTY";
        }
    }

    private static String shortHex(@Nullable final Bytes bytes) {
        if (bytes == null) {
            return "<null>";
        }
        final var hex = bytes.toHex();
        return hex.length() <= 64 ? hex : hex.substring(0, 64) + "...";
    }
}
