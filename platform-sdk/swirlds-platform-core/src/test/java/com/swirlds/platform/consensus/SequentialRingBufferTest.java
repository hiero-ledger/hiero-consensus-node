// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class SequentialRingBufferTest {
    @Test
    void basic() {
        final SequentialRingBuffer<Long> data = new SequentialRingBuffer<>(1);
        // no indexes should exist in a new instance
        assertEmpty(data, -10, 20);
        // create a single round
        data.add(data.nextIndex(), data.nextIndex());
        assertIndexes(data, -10, 1, 1, 20);
        // create a few more
        LongStream.range(2, 10).forEach(i -> data.add(i, i));
        // remove some from the front and back
        data.removeOlderThan(4);
        data.removeNewerThan(7);
        // indexes 4-7 should exist
        assertIndexes(data, -10, 4, 7, 20);
    }

    @Test
    void illegalCreate() {
        final SequentialRingBuffer<Long> data = new SequentialRingBuffer<>(100);
        assertEmpty(data, 90, 110);
        // requesting to create an element which is not the next one, this is illegal but the class
        // will comply
        data.add(105, 105L);
        assertIndexes(data, 90, 100, 105, 110, true);
        data.add(103, 103L);
        assertNotNull(
                data.get(103),
                "if we try to add an element that already exists, it should replace the existing" + " one");
        assertIndexes(data, 90, 100, 105, 110, true);
    }

    @Test
    void removeNewerThan() {
        final SequentialRingBuffer<Long> data = new SequentialRingBuffer<>(100);
        assertDoesNotThrow(() -> data.removeNewerThan(90));
        assertDoesNotThrow(() -> data.removeNewerThan(100));
        assertDoesNotThrow(() -> data.removeNewerThan(110));
        LongStream.range(100, 111).forEach(i -> data.add(i, i));
        assertIndexes(data, 90, 100, 110, 120);
        data.removeNewerThan(105);
        assertIndexes(data, 90, 100, 105, 120);
        data.removeNewerThan(95);
        assertEmpty(data, 90, 120);
    }

    @Test
    void removeOlderThan() {
        final SequentialRingBuffer<Long> data = new SequentialRingBuffer<>(100);
        assertDoesNotThrow(() -> data.removeOlderThan(110));
        assertDoesNotThrow(() -> data.removeOlderThan(100));
        assertDoesNotThrow(() -> data.removeOlderThan(90));
        LongStream.range(100, 111).forEach(i -> data.add(i, i));
        assertIndexes(data, 90, 100, 110, 120);
        data.removeOlderThan(105);
        assertIndexes(data, 90, 105, 110, 120);
        data.removeOlderThan(115);
        assertEmpty(data, 90, 120);
    }

    @Test
    void intOverflow() {
        final SequentialRingBuffer<Long> data = new SequentialRingBuffer<>(100);
        assertDoesNotThrow(() -> data.get(Long.MAX_VALUE / 2));
    }

    private void assertEmpty(final SequentialRingBuffer<Long> rounds, final long startCheck, final long endCheck) {
        assertIndexes(rounds, startCheck, 0, -1, endCheck);
    }

    private void assertIndexes(
            final SequentialRingBuffer<Long> data,
            final long startCheck,
            final long startExists,
            final long endExists,
            final long endCheck) {
        assertIndexes(data, startCheck, startExists, endExists, endCheck, false);
    }

    private void assertIndexes(
            final SequentialRingBuffer<Long> data,
            final long startCheck,
            final long startExists,
            final long endExists,
            final long endCheck,
            final boolean allowNull) {
        LongStream.range(startCheck, endCheck)
                .filter(l -> l < startExists || l > endExists)
                .forEach(i -> {
                    final String shouldExist = startExists <= endExists
                            ? String.format(
                                    "index %d should not exist, only indexes %d-%d" + " should exist",
                                    i, startExists, endExists)
                            : String.format("index %d should not exist, no data should" + " exist", i);
                    assertFalse(data.exists(i), shouldExist);

                    assertNull(
                            data.get(i),
                            String.format(
                                    "index %d should be null because it doesn't exist, "
                                            + "only indexes %d-%d should exist",
                                    i, startExists, endExists));
                });
        LongStream.range(startExists, endExists + 1).forEach(i -> {
            assertTrue(
                    data.exists(i),
                    String.format("indexes %d-%d should exist, but %d doesn't", startExists, endExists, i));
            if (!allowNull) {
                assertNotNull(
                        data.get(i),
                        String.format("indexes %d-%d should not be null, but %d is null", startExists, endExists, i));
                assertEquals(
                        i,
                        data.get(i),
                        String.format("the object returned for index %d has a value of" + " %d", i, data.get(i)));
            }
        });
        if (startExists <= endExists) {
            assertNotNull(
                    data.getLatest(), String.format("the latest index should not be null, it should be %d", endExists));
            assertEquals(
                    endExists,
                    data.getLatest(),
                    String.format("the latest index should not be %d, it should be %d", data.getLatest(), endExists));
            assertEquals(
                    endExists + 1, data.nextIndex(), "the next index should be the one right after the latest one");
        } else {
            assertNull(data.getLatest(), "the latest index should be null, because there should be no data");
        }
    }
}
