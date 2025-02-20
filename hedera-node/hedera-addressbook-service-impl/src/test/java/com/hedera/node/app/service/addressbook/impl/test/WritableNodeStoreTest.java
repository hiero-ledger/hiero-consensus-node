// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.node.app.service.addressbook.impl.WritableNodeStore;
import com.hedera.node.app.service.addressbook.impl.test.handlers.AddressBookTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableNodeStoreTest extends AddressBookTestBase {
    private Node node;

    @Test
    void throwsIfNullValuesAsArgs() {
        assertThrows(NullPointerException.class, () -> new WritableNodeStore(null, writableEntityCounters));
        assertThrows(NullPointerException.class, () -> new WritableNodeStore(writableStates, null));
        assertThrows(NullPointerException.class, () -> writableStore.put(null));
    }

    @Test
    void constructorCreatesNodeState() {
        final var store = new WritableNodeStore(writableStates, writableEntityCounters);
        assertNotNull(store);
    }

    @Test
    void commitsNodeChanges() {
        node = createNode();
        assertFalse(writableNodeState.contains(nodeId));

        writableStore.put(node);

        assertTrue(writableNodeState.contains(nodeId));
        final var writtenNode = writableNodeState.get(nodeId);
        assertEquals(node, writtenNode);
    }

    @Test
    void getReturnsNode() {
        node = createNode();
        writableStore.put(node);

        final var maybeReadNode = writableStore.get(nodeId.number());

        assertNotNull(maybeReadNode);
        assertEquals(node, maybeReadNode);
    }
}
