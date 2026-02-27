// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
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
        // clear the state
        rebuildState(0);
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

    @Test
    void getReturnsNullForMissingNode() {
        rebuildState(0);
        assertNull(writableStore.get(999L));
    }

    @Test
    void putAndIncrementCountStoresNode() {
        rebuildState(0);
        node = createNode();
        assertFalse(writableNodeState.contains(nodeId));

        writableStore.putAndIncrementCount(node);

        assertTrue(writableNodeState.contains(nodeId));
        final var writtenNode = writableNodeState.get(nodeId);
        assertEquals(node, writtenNode);
    }

    @Test
    void removeDeletesNodeFromState() {
        node = createNode();
        writableStore.put(node);
        assertTrue(writableNodeState.contains(nodeId));

        writableStore.remove(nodeId.number());

        assertFalse(writableNodeState.contains(nodeId));
    }

    @Test
    void removeNonExistentNodeDoesNotThrow() {
        rebuildState(0);
        // should not throw when removing a node that doesn't exist
        writableStore.remove(999L);
    }

    @Test
    void modifiedNodesIsEmptyInitially() {
        rebuildState(0);
        assertTrue(writableStore.modifiedNodes().isEmpty());
    }

    @Test
    void modifiedNodesTracksChanges() {
        rebuildState(0);
        node = createNode();

        writableStore.put(node);

        final var modified = writableStore.modifiedNodes();
        assertFalse(modified.isEmpty());
        assertTrue(modified.contains(nodeId));
    }

    @Test
    void modifiedNodesTracksMultipleChanges() {
        rebuildState(0);
        final var node1 = createNode();
        final var node2 = Node.newBuilder()
                .nodeId(2L)
                .accountId(accountId)
                .description("node2")
                .build();

        writableStore.put(node1);
        writableStore.put(node2);

        final var modified = writableStore.modifiedNodes();
        assertEquals(2, modified.size());
        assertTrue(modified.contains(nodeId));
        assertTrue(modified.contains(EntityNumber.newBuilder().number(2L).build()));
    }

    @Test
    void sizeOfStateReturnsConfiguredCount() {
        rebuildState(3);
        assertEquals(3, writableStore.sizeOfState());
    }

    @Test
    void sizeOfStateReturnsZeroWhenEmpty() {
        rebuildState(0);
        assertEquals(0, writableStore.sizeOfState());
    }

    @Test
    void keysReturnsNodeEntityNumbers() {
        rebuildState(3);
        final var keys = writableStore.keys();
        assertNotNull(keys);
        assertEquals(3, keys.size());
    }

    @Test
    void keysReturnsEmptyListWhenNoNodes() {
        rebuildState(0);
        final var keys = writableStore.keys();
        assertNotNull(keys);
        assertTrue(keys.isEmpty());
    }

    @Test
    void putOverwritesExistingNode() {
        node = createNode();
        writableStore.put(node);

        final var updatedNode = Node.newBuilder()
                .nodeId(nodeId.number())
                .accountId(accountId)
                .description("updated description")
                .build();
        writableStore.put(updatedNode);

        final var readNode = writableStore.get(nodeId.number());
        assertNotNull(readNode);
        assertEquals("updated description", readNode.description());
    }
}
