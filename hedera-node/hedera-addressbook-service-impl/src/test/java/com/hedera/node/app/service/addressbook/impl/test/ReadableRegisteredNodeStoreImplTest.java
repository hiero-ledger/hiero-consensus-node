// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test;

import static com.hedera.node.app.service.addressbook.impl.schemas.V073AddressBookSchema.REGISTERED_NODES_KEY;
import static com.hedera.node.app.service.addressbook.impl.schemas.V073AddressBookSchema.REGISTERED_NODES_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.node.app.service.addressbook.impl.ReadableRegisteredNodeStoreImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableRegisteredNodeStoreImplTest {

    private static final Key ADMIN_KEY = Key.newBuilder()
            .ed25519(Bytes.wrap("01234567890123456789012345678901"))
            .build();
    private static final long NODE_ID = 42L;
    private static final RegisteredNode REGISTERED_NODE = RegisteredNode.newBuilder()
            .registeredNodeId(NODE_ID)
            .adminKey(ADMIN_KEY)
            .description("test node")
            .build();

    @Mock
    private ReadableStates readableStates;

    private ReadableRegisteredNodeStoreImpl subject;

    @BeforeEach
    void setUp() {
        final var state = MapReadableKVState.<EntityNumber, RegisteredNode>builder(
                        REGISTERED_NODES_STATE_ID, REGISTERED_NODES_KEY)
                .value(EntityNumber.newBuilder().number(NODE_ID).build(), REGISTERED_NODE)
                .build();
        given(readableStates.<EntityNumber, RegisteredNode>get(REGISTERED_NODES_STATE_ID))
                .willReturn(state);
        subject = new ReadableRegisteredNodeStoreImpl(readableStates);
    }

    @Test
    void constructorCreatesStore() {
        assertNotNull(subject);
    }

    @Test
    void nullStatesFails() {
        assertThrows(NullPointerException.class, () -> new ReadableRegisteredNodeStoreImpl(null));
    }

    @Test
    void getsNodeIfExists() {
        final var node = subject.get(NODE_ID);
        assertNotNull(node);
        assertThat(node.registeredNodeId()).isEqualTo(NODE_ID);
        assertThat(node.adminKey()).isEqualTo(ADMIN_KEY);
        assertThat(node.description()).isEqualTo("test node");
    }

    @Test
    void returnsNullForMissingNode() {
        assertThat(subject.get(999L)).isNull();
    }
}
