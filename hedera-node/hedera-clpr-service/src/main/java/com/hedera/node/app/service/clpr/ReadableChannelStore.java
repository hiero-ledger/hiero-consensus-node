// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Provides read-only methods for interacting with CLPR channels in state.
 */
public interface ReadableChannelStore {

    /**
     * Gets a channel by its channel identifier.
     *
     * @param channelId the 32-byte channel identifier
     * @return the channel, or null if not found
     */
    @Nullable
    ClprChannel getChannel(@NonNull Bytes channelId);

    /**
     * Gets the number of channels in the state.
     *
     * @return the number of channels
     */
    long sizeOfState();

    /**
     * Returns the last index number issued to a syncable channel, or 0 if none. Index numbers are issued in increasing
     * order and never reused, so a reader that remembers it can later read only the channels indexed since.
     *
     * @return the last index number issued
     */
    long lastIndexNum();

    /**
     * Gets the ids of the channels that are neither {@code PENDING} nor {@code CLOSED} and were given an index number
     * greater than {@code afterIndexNum}, in the order they became syncable. Unlike the channels themselves, which
     * can only be looked up by id, these can be read in full; e.g., by a node that starts and must find the channels
     * to sync. Reading them takes one lookup per index number issued after {@code afterIndexNum}, including those of
     * channels that are no longer syncable.
     *
     * @param afterIndexNum the index number to read after; 0 to read every syncable channel
     * @return the syncable channel ids
     */
    @NonNull
    List<Bytes> syncableChannelIds(long afterIndexNum);
}
