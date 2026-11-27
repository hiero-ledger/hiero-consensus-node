// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.node.app.service.clpr.impl.schemas.V0800ClprSchema.CHANNEL_INDEX_NUMS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0800ClprSchema.CHANNEL_INDEX_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0800ClprSchema.INDEXED_CHANNELS_STATE_ID;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.state.clpr.ClprChannelIndex;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stubs the CLPR channel index states on mocked writable states, which a {@code WritableChannelStore} updates when a
 * channel becomes, or stops being, syncable.
 */
final class ChannelIndexStubs {
    private ChannelIndexStubs() {}

    /**
     * Makes the given mocked writable states return an empty channel index singleton, indexed channels and channel index numbers.
     *
     * @param writableStates the mocked CLPR writable states
     */
    static void stubChannelIndex(@NonNull final WritableStates writableStates) {
        final var channelIndexRef = new AtomicReference<ClprChannelIndex>();
        lenient()
                .when(writableStates.<ClprChannelIndex>getSingleton(CHANNEL_INDEX_STATE_ID))
                .thenReturn(new FunctionWritableSingletonState<>(
                        CHANNEL_INDEX_STATE_ID,
                        "ClprService:CHANNEL_INDEX",
                        channelIndexRef::get,
                        channelIndexRef::set));
        lenient()
                .when(writableStates.<ProtoLong, ProtoBytes>get(INDEXED_CHANNELS_STATE_ID))
                .thenReturn(MapWritableKVState.<ProtoLong, ProtoBytes>builder(
                                INDEXED_CHANNELS_STATE_ID, "ClprService:INDEXED_CHANNELS")
                        .build());
        lenient()
                .when(writableStates.<ProtoBytes, ProtoLong>get(CHANNEL_INDEX_NUMS_STATE_ID))
                .thenReturn(MapWritableKVState.<ProtoBytes, ProtoLong>builder(
                                CHANNEL_INDEX_NUMS_STATE_ID, "ClprService:CHANNEL_INDEX_NUMS")
                        .build());
    }
}
