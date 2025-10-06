// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files.hashmap;

import static com.swirlds.merkledb.files.hashmap.HalfDiskHashMap.INVALID_VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.merkledb.test.fixtures.ExampleLongKey;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BucketMutationTest {

    private static void put(final BucketMutation m, final Bytes key, final long value) {
        m.put(key, key.hashCode(), value);
    }

    private static void putIfEqual(final BucketMutation m, final Bytes key, final long oldValue, final long value) {
        m.putIfEqual(key, key.hashCode(), oldValue, value);
    }

    @Test
    void nullKeyThrows() {
        assertThrows(NullPointerException.class, () -> new BucketMutation(null, 0, 1), "Null is not allowed");
    }

    @Test
    void createList() {
        final Bytes rootKey = ExampleLongKey.longToKey(1);
        final var root = new BucketMutation(rootKey, rootKey.hashCode(), 10);
        for (int i = 2; i < 100; i++) {
            put(root, ExampleLongKey.longToKey(i), 10 * i);
        }

        final AtomicLong index = new AtomicLong(1);
        root.forEachKeyValue((k, khc, ov, v) -> {
            final long i = index.getAndIncrement();
            assertEquals(ExampleLongKey.longToKey(i), k, "Unexpected key " + k + " for iteration " + i);
            assertEquals(i * 10, v, "Unexpected value " + v + " for iteration " + i);
        });
    }

    @Test
    void updateList() {
        // Test adding the keys out of order, and also updating only the first, middle and last.
        final Bytes rootKey = ExampleLongKey.longToKey(1);
        final var root = new BucketMutation(rootKey, rootKey.hashCode(), 10);
        put(root, ExampleLongKey.longToKey(3), 30);
        put(root, ExampleLongKey.longToKey(2), 20);
        put(root, ExampleLongKey.longToKey(5), 50);
        put(root, ExampleLongKey.longToKey(4), 40);

        put(root, ExampleLongKey.longToKey(1), 100);
        put(root, ExampleLongKey.longToKey(2), 200);
        put(root, ExampleLongKey.longToKey(4), 400);

        final var expectedKeys = new LinkedList<>(List.of(
                ExampleLongKey.longToKey(1),
                ExampleLongKey.longToKey(3),
                ExampleLongKey.longToKey(2),
                ExampleLongKey.longToKey(5),
                ExampleLongKey.longToKey(4)));

        final var expectedValues = new LinkedList<>(List.of(100, 30, 200, 50, 400));

        root.forEachKeyValue((k, khc, ov, v) -> {
            assertEquals(expectedKeys.removeFirst(), k, "Unexpected key");
            assertEquals((long) expectedValues.removeFirst(), v, "Unexpected value");
        });

        assertTrue(expectedKeys.isEmpty(), "Shouldn't have any expected keys left");
        assertTrue(expectedValues.isEmpty(), "Shouldn't have any expected values left");
    }

    @Test
    void updateWithoutOldValue() {
        final Bytes key = ExampleLongKey.longToKey(1);
        final var mutation = new BucketMutation(key, key.hashCode(), 2);
        put(mutation, ExampleLongKey.longToKey(1), 3);
        assertEquals(3, mutation.getValue());
        assertEquals(INVALID_VALUE, mutation.getOldValue());
        assertNull(mutation.getNext());
        putIfEqual(mutation, ExampleLongKey.longToKey(1), 3, 4);
        assertEquals(4, mutation.getValue());
        assertEquals(INVALID_VALUE, mutation.getOldValue());
        assertNull(mutation.getNext());
        putIfEqual(mutation, ExampleLongKey.longToKey(1), 3, 5);
        assertEquals(4, mutation.getValue());
        assertEquals(INVALID_VALUE, mutation.getOldValue());
        assertNull(mutation.getNext());
        put(mutation, ExampleLongKey.longToKey(1), 6);
        assertEquals(6, mutation.getValue());
        assertEquals(INVALID_VALUE, mutation.getOldValue());
        assertNull(mutation.getNext());
    }

    @Test
    void updateWithOldValue() {
        final Bytes key = ExampleLongKey.longToKey(1);
        final var mutation = new BucketMutation(key, key.hashCode(), -1, 2);
        putIfEqual(mutation, ExampleLongKey.longToKey(1), 3, 4);
        assertEquals(2, mutation.getValue());
        assertNotEquals(INVALID_VALUE, mutation.getOldValue());
        assertNull(mutation.getNext());
        putIfEqual(mutation, ExampleLongKey.longToKey(1), 2, 5);
        assertEquals(5, mutation.getValue());
        assertNotEquals(INVALID_VALUE, mutation.getOldValue());
        assertNull(mutation.getNext());
        put(mutation, ExampleLongKey.longToKey(1), 6);
        assertEquals(6, mutation.getValue());
        assertEquals(INVALID_VALUE, mutation.getOldValue());
        assertNull(mutation.getNext());
    }

    @Test
    void size() {
        final Bytes key = ExampleLongKey.longToKey(1);
        final var root = new BucketMutation(key, key.hashCode(), 10);
        assertEquals(1, root.size(), "Unexpected size");
        put(root, ExampleLongKey.longToKey(3), 30);
        assertEquals(2, root.size(), "Unexpected size");
        put(root, ExampleLongKey.longToKey(2), 20);
        assertEquals(3, root.size(), "Unexpected size");
        put(root, ExampleLongKey.longToKey(5), 50);
        assertEquals(4, root.size(), "Unexpected size");
        put(root, ExampleLongKey.longToKey(4), 40);
        assertEquals(5, root.size(), "Unexpected size");
    }
}
