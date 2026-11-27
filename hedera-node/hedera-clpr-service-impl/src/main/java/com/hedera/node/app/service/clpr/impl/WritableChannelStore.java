// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides write methods for modifying CLPR channels in state.
 */
public class WritableChannelStore extends ReadableChannelStoreImpl {

    /** The entity counters, which hold the number of channels. */
    private final WritableEntityCounters entityCounters;

    /**
     * Create a new {@link WritableChannelStore} instance.
     *
     * @param states the state to use
     * @param entityCounters the entity counters
     */
    public WritableChannelStore(
            @NonNull final WritableStates states, @NonNull final WritableEntityCounters entityCounters) {
        super(states, entityCounters);
        this.entityCounters = entityCounters;
    }

    @Override
    protected WritableKVState<ProtoBytes, ClprChannel> channelState() {
        return super.channelState();
    }

    /**
     * Persists a {@link ClprChannel} into state. If a channel with the same
     * channel_id already exists, it will be overwritten; otherwise the channel count is incremented.
     *
     * @param channel the channel to persist
     */
    public void put(@NonNull final ClprChannel channel) {
        requireNonNull(channel);
        requireNonNull(channel.channelId());
        final var key = new ProtoBytes(channel.channelId());
        if (channelState().get(key) == null) {
            entityCounters.incrementEntityTypeCount(EntityType.CLPR_CHANNEL);
        }
        channelState().put(key, channel);
    }

    /**
     * Removes a channel from state, decrementing the channel count if it exists.
     *
     * @param channelId the channel identifier to remove
     */
    public void remove(@NonNull final Bytes channelId) {
        requireNonNull(channelId);
        final var key = new ProtoBytes(channelId);
        if (channelState().get(key) != null) {
            entityCounters.decrementEntityTypeCounter(EntityType.CLPR_CHANNEL);
            channelState().remove(key);
        }
    }
}
