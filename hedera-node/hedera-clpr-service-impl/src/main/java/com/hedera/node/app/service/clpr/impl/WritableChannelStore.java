// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelIndex;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides write methods for modifying CLPR channels in state. Keeps the channel index (see
 * {@link #syncableChannelIds()}) in step with the channels: a channel that is neither {@code PENDING} nor
 * {@code CLOSED} is issued the next sequence number, recorded in its {@code index_sequence_number}, and an index
 * entry maps that number to its id; the entry is removed when the channel becomes either or is removed. A write
 * that does not change whether a channel is syncable keeps its sequence number and leaves the index untouched.
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

    @Override
    protected WritableSingletonState<ClprChannelIndex> channelIndexState() {
        return super.channelIndexState();
    }

    @Override
    protected WritableKVState<ProtoLong, ProtoBytes> channelIndexEntriesState() {
        return super.channelIndexEntriesState();
    }

    /**
     * Persists a {@link ClprChannel} into state. If a channel with the same
     * channel_id already exists, it will be overwritten. The persisted channel's
     * {@code index_sequence_number} is set by this store, whatever the given channel has.
     *
     * @param channel the channel to persist
     */
    public void put(@NonNull final ClprChannel channel) {
        requireNonNull(channel);
        requireNonNull(channel.channelId());
        final var key = new ProtoBytes(channel.channelId());
        final var previous = channelState().get(key);
        long sequenceNumber = previous == null ? 0L : previous.indexSequenceNumber();
        if (isSyncable(channel.status())) {
            // Also indexes a syncable channel that predates the index, on its first write
            if (sequenceNumber == 0L) {
                sequenceNumber = addIndexEntry(channel.channelId());
            }
        } else if (sequenceNumber != 0L) {
            channelIndexEntriesState().remove(new ProtoLong(sequenceNumber));
            sequenceNumber = 0L;
        }
        final var toStore = channel.indexSequenceNumber() == sequenceNumber
                ? channel
                : channel.copyBuilder().indexSequenceNumber(sequenceNumber).build();
        channelState().put(key, toStore);
    }

    /**
     * Removes a channel from state.
     *
     * @param channelId the channel identifier to remove
     */
    public void remove(@NonNull final Bytes channelId) {
        requireNonNull(channelId);
        final var key = new ProtoBytes(channelId);
        final var previous = channelState().get(key);
        if (previous != null && previous.indexSequenceNumber() != 0L) {
            channelIndexEntriesState().remove(new ProtoLong(previous.indexSequenceNumber()));
        }
        channelState().remove(key);
    }

    /**
     * Issues the next sequence number and maps it to the given channel id in the index entries.
     *
     * @param channelId the channel id
     * @return the issued sequence number
     */
    private long addIndexEntry(@NonNull final Bytes channelId) {
        final long sequenceNumber = lastSequenceNumber() + 1;
        channelIndexState()
                .put(ClprChannelIndex.newBuilder()
                        .lastSequenceNumber(sequenceNumber)
                        .build());
        channelIndexEntriesState().put(new ProtoLong(sequenceNumber), new ProtoBytes(channelId));
        return sequenceNumber;
    }

    private static boolean isSyncable(@NonNull final ClprChannelStatus status) {
        return status != ClprChannelStatus.PENDING && status != ClprChannelStatus.CLOSED;
    }
}
