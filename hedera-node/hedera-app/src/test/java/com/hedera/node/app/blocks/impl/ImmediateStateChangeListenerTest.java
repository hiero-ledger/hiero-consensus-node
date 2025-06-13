// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.block.stream.output.StateChange.ChangeOperationOneOfType.MAP_DELETE;
import static com.hedera.hapi.block.stream.output.StateChange.ChangeOperationOneOfType.MAP_UPDATE;
import static com.hedera.hapi.block.stream.output.StateChange.ChangeOperationOneOfType.QUEUE_POP;
import static com.hedera.hapi.block.stream.output.StateChange.ChangeOperationOneOfType.QUEUE_PUSH;
import static com.swirlds.state.StateChangeListener.StateType.MAP;
import static com.swirlds.state.StateChangeListener.StateType.QUEUE;
import static com.swirlds.state.merkle.StateUtils.stateIdFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImmediateStateChangeListenerTest {
    private static final int STATE_ID = 1;
    private static final AccountID KEY = AccountID.newBuilder().accountNum(1234).build();
    private static final Account VALUE = Account.newBuilder().accountId(KEY).build();
    public static final ProtoBytes PROTO_BYTES = new ProtoBytes(Bytes.wrap(new byte[] {1, 2, 3}));
    private ImmediateStateChangeListener listener;

    @BeforeEach
    void setUp() {
        listener = new ImmediateStateChangeListener();
    }

    @Test
    void testGetStateChanges() {
        listener.mapUpdateChange(STATE_ID, KEY, VALUE);

        List<StateChange> stateChanges = listener.getStateChanges();
        assertEquals(1, stateChanges.size());
    }

    @Test
    void testResetStateChanges() {
        listener.mapUpdateChange(STATE_ID, KEY, VALUE);
        listener.reset();

        List<StateChange> stateChanges = listener.getStateChanges();
        assertTrue(stateChanges.isEmpty());
    }

    @Test
    void testMapUpdateChange() {
        listener.mapUpdateChange(STATE_ID, KEY, VALUE);

        StateChange stateChange = listener.getStateChanges().getFirst();
        assertEquals(MAP_UPDATE, stateChange.changeOperation().kind());
        assertEquals(STATE_ID, stateChange.stateId());
        assertEquals(KEY, stateChange.mapUpdate().key().accountIdKey());
        assertEquals(VALUE, stateChange.mapUpdate().value().accountValue());
    }

    @Test
    void testMapDeleteChange() {
        listener.mapDeleteChange(STATE_ID, KEY);

        StateChange stateChange = listener.getStateChanges().getFirst();
        assertEquals(MAP_DELETE, stateChange.changeOperation().kind());
        assertEquals(STATE_ID, stateChange.stateId());
        assertEquals(KEY, stateChange.mapDelete().key().accountIdKey());
    }

    @Test
    void targetTypeIsMapAndQueue() {
        assertEquals(Set.of(MAP, QUEUE), listener.stateTypes());
    }

    @Test
    void understandsStateIds() {
        final var service = BlockStreamService.NAME;
        final var stateKey = V0560BlockStreamSchema.BLOCK_STREAM_INFO_KEY;
        assertEquals(stateIdFor(service, stateKey), listener.stateIdFor(service, stateKey));
    }

    @Test
    void testQueuePushChange() {
        listener.queuePushChange(STATE_ID, PROTO_BYTES);

        StateChange stateChange = listener.getStateChanges().getFirst();
        assertEquals(QUEUE_PUSH, stateChange.changeOperation().kind());
        assertEquals(STATE_ID, stateChange.stateId());
        assertEquals(PROTO_BYTES.value(), stateChange.queuePush().protoBytesElement());
    }

    @Test
    void testQueuePopChange() {
        listener.queuePopChange(STATE_ID);

        StateChange stateChange = listener.getStateChanges().getFirst();
        assertEquals(QUEUE_POP, stateChange.changeOperation().kind());
        assertEquals(STATE_ID, stateChange.stateId());
    }
}
