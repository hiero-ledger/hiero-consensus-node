// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr;

import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with the CLPR message queue in state.
 */
public interface ReadableMessageQueueStore {

    /**
     * Gets a message from the queue by channel ID and message ID.
     *
     * @param channelId the 32-byte channel identifier
     * @param messageId the message sequence number
     * @return the message value, or null if not found
     */
    @Nullable
    ClprMessageValue getMessage(@NonNull Bytes channelId, long messageId);

    /**
     * Gets the number of messages in the queue across all channels.
     *
     * @return the total number of queued messages
     */
    long sizeOfState();
}
