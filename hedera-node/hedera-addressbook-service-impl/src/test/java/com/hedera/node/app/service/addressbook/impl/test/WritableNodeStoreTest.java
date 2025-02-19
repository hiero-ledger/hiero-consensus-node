/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
