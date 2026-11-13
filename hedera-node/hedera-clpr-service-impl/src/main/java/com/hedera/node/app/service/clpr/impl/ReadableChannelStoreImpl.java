// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0800ClprSchema.CHANNEL_INDEX_ENTRIES_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0800ClprSchema.CHANNEL_INDEX_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelIndex;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.node.app.service.clpr.ReadableChannelStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides read-only methods for interacting with CLPR channels in state.
 */
public class ReadableChannelStoreImpl implements ReadableChannelStore {

    /** The underlying data storage class that holds channel data. */
    private final ReadableKVState<ProtoBytes, ClprChannel> channelState;

    /** The states holding the channel index and its entries, which are only looked up when they are used. */
    private final ReadableStates states;

    /**
     * Create a new {@link ReadableChannelStoreImpl} instance.
     *
     * @param states the state to use
     */
    public ReadableChannelStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        this.channelState = states.get(CHANNELS_STATE_ID);
        this.states = states;
    }

    @Override
    @Nullable
    public ClprChannel getChannel(@NonNull final Bytes channelId) {
        requireNonNull(channelId);
        return channelState.get(new ProtoBytes(channelId));
    }

    @Override
    public long sizeOfState() {
        return channelState.size();
    }

    @Override
    @NonNull
    public List<Bytes> syncableChannelIds() {
        // One lookup per sequence number ever issued; those of Channels that stopped being syncable are gaps
        final long lastSequenceNumber = lastSequenceNumber();
        final var channelIds = new ArrayList<Bytes>();
        final ReadableKVState<ProtoLong, ProtoBytes> entries = channelIndexEntriesState();
        for (long sequenceNumber = 1; sequenceNumber <= lastSequenceNumber; sequenceNumber++) {
            final var channelId = entries.get(new ProtoLong(sequenceNumber));
            if (channelId != null) {
                channelIds.add(channelId.value());
            }
        }
        return channelIds;
    }

    /**
     * Returns the last sequence number issued to a syncable channel, or 0 if none.
     *
     * @return the last sequence number issued
     */
    protected long lastSequenceNumber() {
        final var index = channelIndexState().get();
        return index == null ? 0L : index.lastSequenceNumber();
    }

    protected <T extends ReadableSingletonState<ClprChannelIndex>> T channelIndexState() {
        return (T) states.<ClprChannelIndex>getSingleton(CHANNEL_INDEX_STATE_ID);
    }

    protected <T extends ReadableKVState<ProtoLong, ProtoBytes>> T channelIndexEntriesState() {
        return (T) states.<ProtoLong, ProtoBytes>get(CHANNEL_INDEX_ENTRIES_STATE_ID);
    }

    protected <T extends ReadableKVState<ProtoBytes, ClprChannel>> T channelState() {
        return (T) channelState;
    }
}
