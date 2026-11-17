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
 * {@code CLOSED} is issued the next sequence number; an index entry maps that number to its id, and a sequence
 * number entry maps its id back to the number. Both are removed when the channel becomes either or is removed. A write
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

    @Override
    protected WritableKVState<ProtoBytes, ProtoLong> channelIndexSequenceNumbersState() {
        return super.channelIndexSequenceNumbersState();
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
        final var key = new ProtoBytes(channel.channelId());
        final var sequenceNumber = channelIndexSequenceNumbersState().get(key);
        if (isSyncable(channel.status())) {
            // Also indexes a syncable channel that predates the index, on its first write
            if (sequenceNumber == null) {
                addIndexEntries(key);
            }
        } else if (sequenceNumber != null) {
            removeIndexEntries(key, sequenceNumber);
        }
        channelState().put(key, channel);
    }

    /**
     * Removes a channel from state.
     *
     * @param channelId the channel identifier to remove
     */
    public void remove(@NonNull final Bytes channelId) {
        requireNonNull(channelId);
        final var key = new ProtoBytes(channelId);
        final var sequenceNumber = channelIndexSequenceNumbersState().get(key);
        if (sequenceNumber != null) {
            removeIndexEntries(key, sequenceNumber);
        }
        channelState().remove(key);
    }

    /**
     * Issues the next sequence number to the given channel id, and maps each to the other.
     *
     * @param channelId the channel id
     */
    private void addIndexEntries(@NonNull final ProtoBytes channelId) {
        final var sequenceNumber = new ProtoLong(lastSequenceNumber() + 1);
        channelIndexState()
                .put(ClprChannelIndex.newBuilder()
                        .lastSequenceNumber(sequenceNumber.value())
                        .build());
        channelIndexEntriesState().put(sequenceNumber, channelId);
        channelIndexSequenceNumbersState().put(channelId, sequenceNumber);
    }

    /**
     * Removes the index entries of the given channel id and its sequence number.
     *
     * @param channelId the channel id
     * @param sequenceNumber the channel's sequence number
     */
    private void removeIndexEntries(@NonNull final ProtoBytes channelId, @NonNull final ProtoLong sequenceNumber) {
        channelIndexEntriesState().remove(sequenceNumber);
        channelIndexSequenceNumbersState().remove(channelId);
    }

    private static boolean isSyncable(@NonNull final ClprChannelStatus status) {
        return status != ClprChannelStatus.PENDING && status != ClprChannelStatus.CLOSED;
    }
}
