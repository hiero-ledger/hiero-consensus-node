// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.clpr.ReadableChannelStore;
import com.hedera.node.app.service.entityid.ReadableEntityCounters;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with CLPR channels in state.
 */
public class ReadableChannelStoreImpl implements ReadableChannelStore {

    /** The underlying data storage class that holds channel data. */
    private final ReadableKVState<ProtoBytes, ClprChannel> channelState;

    /** The entity counters, which hold the number of channels. */
    private final ReadableEntityCounters entityCounters;

    /**
     * Create a new {@link ReadableChannelStoreImpl} instance.
     *
     * @param states the state to use
     * @param entityCounters the entity counters
     */
    public ReadableChannelStoreImpl(
            @NonNull final ReadableStates states, @NonNull final ReadableEntityCounters entityCounters) {
        requireNonNull(states);
        this.channelState = states.get(CHANNELS_STATE_ID);
        this.entityCounters = requireNonNull(entityCounters);
    }

    @Override
    @Nullable
    public ClprChannel getChannel(@NonNull final Bytes channelId) {
        requireNonNull(channelId);
        return channelState.get(new ProtoBytes(channelId));
    }

    @Override
    public long sizeOfState() {
        return entityCounters.getCounterFor(EntityType.CLPR_CHANNEL);
    }

    protected <T extends ReadableKVState<ProtoBytes, ClprChannel>> T channelState() {
        return (T) channelState;
    }
}
