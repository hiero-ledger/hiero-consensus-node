// SPDX-License-Identifier: Apache-2.0
/*
 * Copyright (C) 2024-2026 Hedera Hashgraph, LLC
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

package com.swirlds.virtualmap;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.createMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueCodec;
import com.swirlds.virtualmap.test.fixtures.VirtualTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VirtualMapIteratorTest extends VirtualTestBase {

    @Test
    @DisplayName("Iterate over empty map")
    void iterateOverEmptyMap() {
        final VirtualMap map = createMap();
        final VirtualMapIterator it = new VirtualMapIterator(map);
        assertFalse(it.hasNext(), "Empty map should have no leaves to iterate");
        assertThrows(
                NoSuchElementException.class, it::next, "next() should throw NoSuchElementException for empty map");
    }

    @Test
    @DisplayName("Iterate over all leaves")
    void iterateOverAllLeaves() {
        final VirtualMap map = createMap();
        for (int i = 0; i < 10; i++) {
            map.put(TestKey.longToKey(i), new TestValue("v" + i), TestValueCodec.INSTANCE);
        }

        final List<VirtualLeafBytes<?>> leaves = new ArrayList<>();
        final VirtualMapIterator it = new VirtualMapIterator(map);
        while (it.hasNext()) {
            leaves.add(it.next());
        }

        assertEquals(10, leaves.size(), "Should have iterated over all 10 leaves");
        for (int i = 0; i < 10; i++) {
            final long finalI = i;
            assertTrue(
                    leaves.stream().anyMatch(l -> l.keyBytes().equals(TestKey.longToKey(finalI))),
                    "Leaf with key " + i + " should be present");
        }
    }

    @Test
    @DisplayName("Iterate with filter")
    void iterateWithFilter() {
        final VirtualMap map = createMap();
        for (int i = 0; i < 10; i++) {
            map.put(TestKey.longToKey(i), new TestValue("v" + i), TestValueCodec.INSTANCE);
        }

        final VirtualMapIterator it = new VirtualMapIterator(map);
        // Filter only even keys
        it.setFilter(leaf -> {
            final Bytes keyBytes = leaf.keyBytes();
            // TestKey.longToKey puts a long (8 bytes)
            final long keyLong = keyBytes.toReadableSequentialData().readLong();
            return keyLong % 2 == 0;
        });

        final List<VirtualLeafBytes<?>> leaves = new ArrayList<>();
        while (it.hasNext()) {
            leaves.add(it.next());
        }

        assertEquals(5, leaves.size(), "Should have iterated over 5 leaves (even keys)");
        for (int i = 0; i < 10; i += 2) {
            final long finalI = i;
            assertTrue(
                    leaves.stream().anyMatch(l -> l.keyBytes().equals(TestKey.longToKey(finalI))),
                    "Leaf with even key " + i + " should be present");
        }
    }

    @Test
    @DisplayName("Set filter after iteration started")
    void setFilterAfterStarted() {
        final VirtualMap map = createMap();
        map.put(TestKey.longToKey(1), new TestValue("v1"), TestValueCodec.INSTANCE);

        final VirtualMapIterator it = new VirtualMapIterator(map);
        assertTrue(it.hasNext());

        assertThrows(
                IllegalStateException.class,
                () -> it.setFilter(leaf -> true),
                "Should not be able to set filter after hasNext() called and found a node");
    }

    @Test
    @DisplayName("Iterate with BiPredicate filter")
    void iterateWithBiPredicateFilter() {
        final VirtualMap map = createMap();
        for (int i = 0; i < 10; i++) {
            map.put(TestKey.longToKey(i), new TestValue("v" + i), TestValueCodec.INSTANCE);
        }

        final long firstLeafPath = map.getMetadata().getFirstLeafPath();

        final VirtualMapIterator it = new VirtualMapIterator(map);
        // Filter only odd paths
        it.setFilter((leaf) -> leaf.path() % 2 != 0);

        final List<VirtualLeafBytes<?>> leaves = new ArrayList<>();
        while (it.hasNext()) {
            leaves.add(it.next());
        }

        assertFalse(leaves.isEmpty(), "Should have some leaves with odd paths");
        for (final VirtualLeafBytes<?> leaf : leaves) {
            assertTrue(leaf.path() % 2 != 0, "Path " + leaf.path() + " should be odd");
        }
    }

    @Test
    @DisplayName("Get path from iterator")
    void getPath() {
        final VirtualMap map = createMap();
        map.put(TestKey.longToKey(1), new TestValue("v1"), TestValueCodec.INSTANCE);

        final VirtualMapIterator it = new VirtualMapIterator(map);
        assertNull(it.getPath(), "Path should be null before any next() call");

        assertTrue(it.hasNext());
        final VirtualLeafBytes<?> leaf = it.next();
        assertEquals(leaf.path(), it.getPath(), "Path should match the path of the returned leaf");
    }
}
