// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides write methods for modifying CLPR channels in state.
 */
public class WritableChannelStore extends ReadableChannelStoreImpl {

    /**
     * Create a new {@link WritableChannelStore} instance.
     *
     * @param states the state to use
     */
    public WritableChannelStore(@NonNull final WritableStates states) {
        super(states);
    }

    @Override
    protected WritableKVState<ProtoBytes, ClprChannel> channelState() {
        return super.channelState();
    }

    /**
     * Persists a {@link ClprChannel} into state. If a channel with the same
     * channel_id already exists, it will be overwritten.
     *
     * @param channel the channel to persist
     */
    public void put(@NonNull final ClprChannel channel) {
        requireNonNull(channel);
        requireNonNull(channel.channelId());
        channelState().put(new ProtoBytes(channel.channelId()), channel);
    }

    /**
     * Removes a channel from state.
     *
     * @param channelId the channel identifier to remove
     */
    public void remove(@NonNull final Bytes channelId) {
        requireNonNull(channelId);
        channelState().remove(new ProtoBytes(channelId));
    }
}
