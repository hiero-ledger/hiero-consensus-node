// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test;

import static org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema.CLPR_MESSAGE_QUEUE_METADATA_STATE_ID;
import static org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema.CLPR_MESSAGE_QUEUE_METADATA_STATE_LABEL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.platform.state.StateKey;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.MapReadableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.HashMap;
import java.util.Map;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;
import org.hiero.interledger.clpr.impl.ReadableClprMessageQueueMetadataStoreImpl;
import org.hiero.interledger.clpr.impl.WritableClprMessageQueueMetadataStoreImpl;
import org.junit.jupiter.api.Test;

class ClprMessageQueueMetadataStoreTest {

    @Test
    void persistsAndReadsMetadata() {
        final var backing = new HashMap<ClprMessageKey, ClprMessageValue>();
        final Map<Integer, Object> mapOfStates = new HashMap<>();
        final var state = new MapWritableKVState<>(
                CLPR_MESSAGE_QUEUE_METADATA_STATE_ID, CLPR_MESSAGE_QUEUE_METADATA_STATE_LABEL, backing);
        mapOfStates.put(StateKey.KeyOneOfType.CLPRSERVICE_I_MESSAGE_QUEUE_METADATA.protoOrdinal(), state);

        final var writableStore = new WritableClprMessageQueueMetadataStoreImpl(new MapWritableStates(mapOfStates));
        final var readableStore = new ReadableClprMessageQueueMetadataStoreImpl(new MapReadableStates(mapOfStates));

        final var ledgerId = ClprLedgerId.newBuilder()
                .ledgerId(Bytes.wrap("Hello CLPR".getBytes()))
                .build();

        final var messageQueue = ClprMessageQueueMetadata.newBuilder()
                .ledgerId(ClprLedgerId.DEFAULT)
                .receivedMessageId(0)
                .sentMessageId(1)
                .nextMessageId(2)
                .build();

        writableStore.put(ledgerId, messageQueue);
        assertEquals(messageQueue, readableStore.get(ledgerId));
    }
}
