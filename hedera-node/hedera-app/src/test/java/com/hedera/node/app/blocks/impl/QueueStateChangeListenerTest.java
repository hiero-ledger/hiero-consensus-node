// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.block.stream.output.StateChange.ChangeOperationOneOfType.QUEUE_POP;
import static com.hedera.hapi.block.stream.output.StateChange.ChangeOperationOneOfType.QUEUE_PUSH;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.stateIdFor;
import static com.swirlds.state.StateChangeListener.StateType.QUEUE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueStateChangeListenerTest {

    private static final int STATE_ID = 1;
    public static final ProtoBytes PROTO_BYTES = new ProtoBytes(Bytes.wrap(new byte[] {1, 2, 3}));

    private QueueStateChangeListener subject;

    @BeforeEach
    void setUp() {
        subject = new QueueStateChangeListener();
    }

    @Test
    void targetTypeIsQueue() {
        assertEquals(Set.of(QUEUE), subject.stateTypes());
    }

    @Test
    void understandsStateIds() {
        final var service = BlockStreamService.NAME;
        final var stateKey = V0560BlockStreamSchema.BLOCK_STREAM_INFO_KEY;
        assertEquals(stateIdFor(service, stateKey), subject.stateIdFor(service, stateKey));
    }

    @Test
    void testQueuePushChange() {
        subject.queuePushChange(STATE_ID, PROTO_BYTES);

        StateChange stateChange = subject.getStateChanges().getFirst();
        assertEquals(QUEUE_PUSH, stateChange.changeOperation().kind());
        assertEquals(STATE_ID, stateChange.stateId());
        assertEquals(PROTO_BYTES.value(), stateChange.queuePush().protoBytesElement());
    }

    @Test
    void testQueuePopChange() {
        subject.queuePopChange(STATE_ID);

        StateChange stateChange = subject.getStateChanges().getFirst();
        assertEquals(QUEUE_POP, stateChange.changeOperation().kind());
        assertEquals(STATE_ID, stateChange.stateId());
    }
}
