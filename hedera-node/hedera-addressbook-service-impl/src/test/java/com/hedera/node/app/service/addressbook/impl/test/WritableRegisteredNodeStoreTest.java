// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test;

import static com.hedera.node.app.service.addressbook.impl.schemas.V073AddressBookSchema.REGISTERED_NODES_KEY;
import static com.hedera.node.app.service.addressbook.impl.schemas.V073AddressBookSchema.REGISTERED_NODES_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.node.app.service.addressbook.impl.WritableRegisteredNodeStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableRegisteredNodeStoreTest {

    private static final Key ADMIN_KEY = Key.newBuilder()
            .ed25519(Bytes.wrap("01234567890123456789012345678901"))
            .build();
    private static final long NODE_ID = 42L;
    private static final EntityNumber NODE_ENTITY_NUM =
            EntityNumber.newBuilder().number(NODE_ID).build();
    private static final RegisteredNode REGISTERED_NODE = RegisteredNode.newBuilder()
            .registeredNodeId(NODE_ID)
            .adminKey(ADMIN_KEY)
            .description("test node")
            .build();

    @Mock
    private WritableStates writableStates;

    private MapWritableKVState<EntityNumber, RegisteredNode> writableState;
    private WritableRegisteredNodeStore subject;

    @BeforeEach
    void setUp() {
        writableState = MapWritableKVState.<EntityNumber, RegisteredNode>builder(
                        REGISTERED_NODES_STATE_ID, REGISTERED_NODES_KEY)
                .build();
        given(writableStates.<EntityNumber, RegisteredNode>get(REGISTERED_NODES_STATE_ID))
                .willReturn(writableState);
        subject = new WritableRegisteredNodeStore(writableStates);
    }

    @Test
    void nullArgsFail() {
        assertThrows(NullPointerException.class, () -> new WritableRegisteredNodeStore(null));
        assertThrows(NullPointerException.class, () -> subject.put(null));
    }

    @Test
    void constructorCreatesStore() {
        assertNotNull(subject);
    }

    @Test
    void putStoresNode() {
        assertFalse(writableState.contains(NODE_ENTITY_NUM));

        subject.put(REGISTERED_NODE);

        assertTrue(writableState.contains(NODE_ENTITY_NUM));
        assertEquals(REGISTERED_NODE, writableState.get(NODE_ENTITY_NUM));
    }

    @Test
    void getReturnsStoredNode() {
        subject.put(REGISTERED_NODE);

        final var result = subject.get(NODE_ID);
        assertNotNull(result);
        assertEquals(REGISTERED_NODE, result);
    }

    @Test
    void getReturnsNullForMissingNode() {
        assertNull(subject.get(999L));
    }

    @Test
    void removeDeletesNode() {
        subject.put(REGISTERED_NODE);
        assertTrue(writableState.contains(NODE_ENTITY_NUM));

        subject.remove(NODE_ID);

        assertFalse(writableState.contains(NODE_ENTITY_NUM));
    }

    @Test
    void removeNonExistentNodeDoesNotThrow() {
        subject.remove(999L);
    }

    @Test
    void putOverwritesExistingNode() {
        subject.put(REGISTERED_NODE);

        final var updated = RegisteredNode.newBuilder()
                .registeredNodeId(NODE_ID)
                .adminKey(ADMIN_KEY)
                .description("updated description")
                .build();
        subject.put(updated);

        final var result = subject.get(NODE_ID);
        assertNotNull(result);
        assertThat(result.description()).isEqualTo("updated description");
    }
}
