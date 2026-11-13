// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0800ClprSchema.CHANNEL_INDEX_ENTRIES_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0800ClprSchema.CHANNEL_INDEX_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelIndex;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.node.app.service.clpr.impl.ReadableChannelStoreImpl;
import com.hedera.node.app.service.clpr.impl.WritableChannelStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChannelStoreTest {
    private static final Bytes CHANNEL_ID_1 = Bytes.wrap(new byte[32]);
    private static final Bytes CHANNEL_ID_2 = Bytes.wrap(
            new byte[] {2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2
            });

    @Mock
    private WritableStates writableStates;

    @Mock
    private ReadableStates readableStates;

    private final AtomicReference<ClprChannelIndex> index = new AtomicReference<>();

    private FunctionWritableSingletonState<ClprChannelIndex> indexState;

    private MapWritableKVState<ProtoBytes, ClprChannel> channels;

    private MapWritableKVState<ProtoLong, ProtoBytes> entries;

    private WritableChannelStore subject;

    @BeforeEach
    void setUp() {
        channels = MapWritableKVState.<ProtoBytes, ClprChannel>builder(CHANNELS_STATE_ID, "ClprService:CHANNELS")
                .build();
        lenient()
                .when(writableStates.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID))
                .thenReturn(channels);
        indexState = new FunctionWritableSingletonState<>(
                CHANNEL_INDEX_STATE_ID, "ClprService:CHANNEL_INDEX", index::get, index::set);
        lenient()
                .when(writableStates.<ClprChannelIndex>getSingleton(CHANNEL_INDEX_STATE_ID))
                .thenReturn(indexState);
        entries = MapWritableKVState.<ProtoLong, ProtoBytes>builder(
                        CHANNEL_INDEX_ENTRIES_STATE_ID, "ClprService:CHANNEL_INDEX_ENTRIES")
                .build();
        lenient()
                .when(writableStates.<ProtoLong, ProtoBytes>get(CHANNEL_INDEX_ENTRIES_STATE_ID))
                .thenReturn(entries);
        subject = new WritableChannelStore(writableStates);
    }

    @Test
    @DisplayName("an unset index reads as empty")
    void unsetIndexIsEmpty() {
        assertThat(subject.syncableChannelIds()).isEmpty();
    }

    @Test
    @DisplayName("a PENDING channel is not indexed")
    void pendingChannelIsNotIndexed() {
        subject.put(channel(CHANNEL_ID_1, ClprChannelStatus.PENDING));

        assertThat(subject.syncableChannelIds()).isEmpty();
        assertThat(indexState.isModified()).isFalse();
        assertThat(sequenceNumberOf(CHANNEL_ID_1)).isZero();
    }

    @Test
    @DisplayName("channels are issued increasing sequence numbers when they become syncable, in that order")
    void channelsAreIndexedInActivationOrder() {
        subject.put(channel(CHANNEL_ID_1, ClprChannelStatus.PENDING));
        subject.put(channel(CHANNEL_ID_2, ClprChannelStatus.ACTIVE));
        subject.put(channel(CHANNEL_ID_1, ClprChannelStatus.ACTIVE));

        assertThat(subject.syncableChannelIds()).containsExactly(CHANNEL_ID_2, CHANNEL_ID_1);
        assertThat(sequenceNumberOf(CHANNEL_ID_2)).isEqualTo(1L);
        assertThat(sequenceNumberOf(CHANNEL_ID_1)).isEqualTo(2L);
        indexState.commit();
        assertThat(index.get().lastSequenceNumber()).isEqualTo(2L);
    }

    @ParameterizedTest
    @EnumSource(
            value = ClprChannelStatus.class,
            names = {"ACTIVE", "PAUSED", "CLOSING", "DRAINED"})
    @DisplayName("rewriting a syncable channel keeps its sequence number and leaves the index untouched")
    void syncableRewriteKeepsSequenceNumber(final ClprChannelStatus status) {
        subject.put(channel(CHANNEL_ID_1, ClprChannelStatus.ACTIVE));
        indexState.commit();
        entries.commit();

        // A handler may write a channel it built without the sequence number
        subject.put(channel(CHANNEL_ID_1, status));

        assertThat(sequenceNumberOf(CHANNEL_ID_1)).isEqualTo(1L);
        assertThat(subject.syncableChannelIds()).containsExactly(CHANNEL_ID_1);
        assertThat(indexState.isModified()).isFalse();
        assertThat(entries.modifiedKeys()).isEmpty();
    }

    @Test
    @DisplayName("a CLOSED channel loses its entry, and sequence numbers are not reused")
    void closedChannelLeavesIndex() {
        subject.put(channel(CHANNEL_ID_1, ClprChannelStatus.ACTIVE));
        subject.put(channel(CHANNEL_ID_2, ClprChannelStatus.ACTIVE));

        subject.put(channel(CHANNEL_ID_1, ClprChannelStatus.CLOSED));

        assertThat(entries.get(new ProtoLong(1L))).isNull();
        assertThat(sequenceNumberOf(CHANNEL_ID_1)).isZero();
        assertThat(subject.syncableChannelIds()).containsExactly(CHANNEL_ID_2);

        // A channel id cannot actually be reused, but the store must not reuse sequence numbers either way
        subject.put(channel(CHANNEL_ID_1, ClprChannelStatus.ACTIVE));

        assertThat(sequenceNumberOf(CHANNEL_ID_1)).isEqualTo(3L);
        assertThat(subject.syncableChannelIds()).containsExactly(CHANNEL_ID_2, CHANNEL_ID_1);
    }

    @Test
    @DisplayName("a removed channel loses its entry")
    void removedChannelLeavesIndex() {
        subject.put(channel(CHANNEL_ID_1, ClprChannelStatus.ACTIVE));

        subject.remove(CHANNEL_ID_1);

        assertThat(subject.syncableChannelIds()).isEmpty();
        assertThat(subject.getChannel(CHANNEL_ID_1)).isNull();
    }

    @Test
    @DisplayName("a syncable channel that predates the index is indexed on its next write")
    void preexistingSyncableChannelIsIndexedOnNextWrite() {
        // As written before the index existed: syncable, with no sequence number
        channels.put(new ProtoBytes(CHANNEL_ID_1), channel(CHANNEL_ID_1, ClprChannelStatus.ACTIVE));

        subject.put(channel(CHANNEL_ID_1, ClprChannelStatus.ACTIVE));

        assertThat(sequenceNumberOf(CHANNEL_ID_1)).isEqualTo(1L);
        assertThat(subject.syncableChannelIds()).containsExactly(CHANNEL_ID_1);
    }

    @Test
    @DisplayName("the readable store reads every entry up to the last sequence number, skipping gaps")
    void readableStoreReadsEntries() {
        given(readableStates.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID))
                .willReturn(
                        MapReadableKVState.<ProtoBytes, ClprChannel>builder(CHANNELS_STATE_ID, "ClprService:CHANNELS")
                                .build());
        given(readableStates.<ClprChannelIndex>getSingleton(CHANNEL_INDEX_STATE_ID))
                .willReturn(new FunctionReadableSingletonState<>(
                        CHANNEL_INDEX_STATE_ID, "ClprService:CHANNEL_INDEX", () -> ClprChannelIndex.newBuilder()
                                .lastSequenceNumber(3L)
                                .build()));
        given(readableStates.<ProtoLong, ProtoBytes>get(CHANNEL_INDEX_ENTRIES_STATE_ID))
                .willReturn(MapReadableKVState.<ProtoLong, ProtoBytes>builder(
                                CHANNEL_INDEX_ENTRIES_STATE_ID, "ClprService:CHANNEL_INDEX_ENTRIES")
                        .value(new ProtoLong(1L), new ProtoBytes(CHANNEL_ID_2))
                        .value(new ProtoLong(3L), new ProtoBytes(CHANNEL_ID_1))
                        .build());

        assertThat(new ReadableChannelStoreImpl(readableStates).syncableChannelIds())
                .containsExactly(CHANNEL_ID_2, CHANNEL_ID_1);
    }

    private long sequenceNumberOf(final Bytes channelId) {
        return subject.getChannel(channelId).indexSequenceNumber();
    }

    private static ClprChannel channel(final Bytes channelId, final ClprChannelStatus status) {
        return ClprChannel.newBuilder().channelId(channelId).status(status).build();
    }
}
