// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.MESSAGE_QUEUE_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.node.app.service.clpr.ReadableMessageQueueStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Provides read-only methods for interacting with the CLPR message queue in state.
 */
public class ReadableMessageQueueStoreImpl implements ReadableMessageQueueStore {
    private static final Logger log = LogManager.getLogger(ReadableMessageQueueStoreImpl.class);

    /** The underlying data storage class that holds message queue data. */
    private final ReadableKVState<ClprMessageKey, ClprMessageValue> messageQueueState;

    /**
     * Create a new {@link ReadableMessageQueueStoreImpl} instance.
     *
     * @param states the state to use
     */
    public ReadableMessageQueueStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        this.messageQueueState = states.get(MESSAGE_QUEUE_STATE_ID);
    }

    @Override
    @Nullable
    public ClprMessageValue getMessage(@NonNull final Bytes channelId, final long messageId) {
        requireNonNull(channelId);
        final var key = ClprMessageKey.newBuilder()
                .channelId(channelId)
                .messageId(messageId)
                .build();
        final var value = messageQueueState.get(key);
        if (log.isDebugEnabled()) {
            final var payload = value != null && value.hasPayload() ? value.payload() : null;
            log.debug(
                    "[CLPR-QUEUE-READ] get conn={} messageId={} present={} kind={} runningHash={}",
                    channelId,
                    messageId,
                    value != null,
                    payloadKind(payload),
                    value == null ? "<none>" : shortHex(value.runningHashAfterProcessing()));
        }
        return value;
    }

    @Override
    public long sizeOfState() {
        return messageQueueState.size();
    }

    protected <T extends ReadableKVState<ClprMessageKey, ClprMessageValue>> T messageQueueState() {
        return (T) messageQueueState;
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
