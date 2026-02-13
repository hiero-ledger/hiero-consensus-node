// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test;

import static org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema.CLPR_MESSAGES_STATE_ID;
import static org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema.CLPR_MESSAGES_STATE_LABEL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.platform.state.StateKey;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.MapReadableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.HashMap;
import java.util.Map;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessage;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessagePayload;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;
import org.hiero.interledger.clpr.impl.ReadableClprMessageStoreImpl;
import org.hiero.interledger.clpr.impl.WritableClprMessageStoreImpl;
import org.junit.jupiter.api.Test;

class ClprMessageStoreTest {

    @Test
    void persistsAndReadsMessages() {
        final var backing = new HashMap<ClprMessageKey, ClprMessageValue>();
        final Map<Integer, Object> mapOfStates = new HashMap<>();
        final var state = new MapWritableKVState<>(CLPR_MESSAGES_STATE_ID, CLPR_MESSAGES_STATE_LABEL, backing);
        mapOfStates.put(StateKey.KeyOneOfType.CLPRSERVICE_I_MESSAGES.protoOrdinal(), state);

        final var writableStore = new WritableClprMessageStoreImpl(new MapWritableStates(mapOfStates));
        final var readableStore = new ReadableClprMessageStoreImpl(new MapReadableStates(mapOfStates));

        final var messageKey = ClprMessageKey.newBuilder()
                .messageId(11)
                .ledgerId(ClprLedgerId.DEFAULT)
                .build();
        Bytes msgData = Bytes.wrap("Hello CLPR".getBytes());
        ClprMessage msg = ClprMessage.newBuilder().messageData(msgData).build();
        final var payload = ClprMessagePayload.newBuilder().message(msg).build();
        final var messageValue = ClprMessageValue.newBuilder().payload(payload).build();

        writableStore.put(messageKey, messageValue);
        assertEquals(messageValue, readableStore.get(messageKey));
    }
}
