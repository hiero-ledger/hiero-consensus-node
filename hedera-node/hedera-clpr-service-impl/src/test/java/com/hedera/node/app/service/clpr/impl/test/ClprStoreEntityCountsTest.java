// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CONNECTORS_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.clpr.impl.WritableChannelStore;
import com.hedera.node.app.service.clpr.impl.WritableConnectorStore;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that the CLPR channel and connector stores keep the channel and connector counts in
 * {@link WritableEntityCounters}.
 */
@ExtendWith(MockitoExtension.class)
class ClprStoreEntityCountsTest {
    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[32]);
    private static final Bytes CONNECTOR_ID = Bytes.wrap(new byte[] {1, 2, 3});

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableEntityCounters entityCounters;

    private WritableChannelStore channelStore;

    private WritableConnectorStore connectorStore;

    @BeforeEach
    void setUp() {
        lenient()
                .when(writableStates.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID))
                .thenReturn(
                        MapWritableKVState.<ProtoBytes, ClprChannel>builder(CHANNELS_STATE_ID, "ClprService:CHANNELS")
                                .build());
        lenient()
                .when(writableStates.<ClprConnectorKey, ClprConnector>get(CONNECTORS_STATE_ID))
                .thenReturn(MapWritableKVState.<ClprConnectorKey, ClprConnector>builder(
                                CONNECTORS_STATE_ID, "ClprService:CONNECTORS")
                        .build());
        channelStore = new WritableChannelStore(writableStates, entityCounters);
        connectorStore = new WritableConnectorStore(writableStates, entityCounters);
    }

    @Test
    @DisplayName("putting a new channel counts it once; overwriting it does not count it again")
    void newChannelIsCountedOnce() {
        channelStore.put(channel(ClprChannelStatus.PENDING));
        channelStore.put(channel(ClprChannelStatus.ACTIVE));

        verify(entityCounters).incrementEntityTypeCount(EntityType.CLPR_CHANNEL);
    }

    @Test
    @DisplayName("removing a channel uncounts it only if it exists")
    void removedChannelIsUncountedOnlyIfItExists() {
        channelStore.remove(CHANNEL_ID);
        verify(entityCounters, never()).decrementEntityTypeCounter(EntityType.CLPR_CHANNEL);

        channelStore.put(channel(ClprChannelStatus.ACTIVE));
        channelStore.remove(CHANNEL_ID);

        verify(entityCounters).decrementEntityTypeCounter(EntityType.CLPR_CHANNEL);
        assertThat(channelStore.getChannel(CHANNEL_ID)).isNull();
    }

    @Test
    @DisplayName("the channel store's size is the channel count")
    void channelStoreSizeIsTheChannelCount() {
        given(entityCounters.getCounterFor(EntityType.CLPR_CHANNEL)).willReturn(7L);

        assertThat(channelStore.sizeOfState()).isEqualTo(7L);
    }

    @Test
    @DisplayName("putting a new connector counts it once; overwriting it does not count it again")
    void newConnectorIsCountedOnce() {
        connectorStore.put(connector(1L));
        connectorStore.put(connector(2L));

        verify(entityCounters).incrementEntityTypeCount(EntityType.CLPR_CONNECTOR);
    }

    @Test
    @DisplayName("removing a connector uncounts it only if it exists")
    void removedConnectorIsUncountedOnlyIfItExists() {
        final var key = new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ID);
        connectorStore.remove(key);
        verify(entityCounters, never()).decrementEntityTypeCounter(EntityType.CLPR_CONNECTOR);

        connectorStore.put(connector(1L));
        connectorStore.remove(key);

        verify(entityCounters).decrementEntityTypeCounter(EntityType.CLPR_CONNECTOR);
        assertThat(connectorStore.getConnector(key)).isNull();
    }

    @Test
    @DisplayName("the connector store's size is the connector count")
    void connectorStoreSizeIsTheConnectorCount() {
        given(entityCounters.getCounterFor(EntityType.CLPR_CONNECTOR)).willReturn(5L);

        assertThat(connectorStore.sizeOfState()).isEqualTo(5L);
    }

    private static ClprChannel channel(final ClprChannelStatus status) {
        return ClprChannel.newBuilder().channelId(CHANNEL_ID).status(status).build();
    }

    private static ClprConnector connector(final long lockedStake) {
        return ClprConnector.newBuilder()
                .channelId(CHANNEL_ID)
                .connectorId(CONNECTOR_ID)
                .lockedStake(lockedStake)
                .build();
    }
}
