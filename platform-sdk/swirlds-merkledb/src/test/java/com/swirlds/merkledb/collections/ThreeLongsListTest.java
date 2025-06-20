// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import static com.swirlds.merkledb.collections.ThreeLongsList.SMALL_MAX_TRIPLES;
import static com.swirlds.merkledb.collections.ThreeLongsList.SMALL_TRIPLES_PER_CHUNK;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ThreeLongsListTest {

    private static final int NON_DEFAULT_MAX_TRIPLES = 100_000;

    private static final ThreeLongsList largeLongList = new ThreeLongsList(NON_DEFAULT_MAX_TRIPLES);

    @Test
    @Order(1)
    void autoExpansionWorks() {
        assertDoesNotThrow(
                () -> IntStream.range(0, NON_DEFAULT_MAX_TRIPLES).forEach(i -> largeLongList.add(i, i * 2L, i * 3L)),
                "Addition within max range should automatically expand the list");
    }

    @Test
    @Order(2)
    void checkGet() {
        assertEquals(100_000, largeLongList.size(), "Size should reflect all additions");
        // check all data
        for (int i = 0; i < 100_000; i++) {
            final long[] readValue = largeLongList.get(i);
            assertEquals(3, readValue.length, "Gotten arrays should be triples");
            assertEquals(i, readValue[0], "First triple index should match added value");
            assertEquals(i * 2L, readValue[1], "Second triple index should match added value");
            assertEquals(i * 3L, readValue[2], "Third triple index should match added value");
        }
    }

    @Test
    @Order(3)
    void checkForEach() {
        // check all data
        final AtomicInteger count = new AtomicInteger(0);
        largeLongList.forEach((l1, l2, l3) -> {
            final int expected = count.get();
            assertEquals(expected, l1, "First triple index should match added value");
            assertEquals(expected * 2L, l2, "Second triple index should match added value");
            assertEquals(expected * 3L, l3, "Third triple index should match added value");
            count.getAndIncrement();
        });
    }

    @Test
    void boundCheckingWorks() {
        final ThreeLongsList subject = new ThreeLongsList(1);

        assertThrows(IndexOutOfBoundsException.class, () -> subject.get(-1), "Negative indices should not be allowed");
        assertThrows(
                IndexOutOfBoundsException.class, () -> subject.get(0), "Indices not yet added should not be allowed");

        subject.add(1, 2, 3);

        assertArrayEquals(new long[] {1, 2, 3}, subject.get(0), "Added triples should be returned intact");
        assertThrows(IndexOutOfBoundsException.class, () -> subject.get(1), "Size should not be a valid index");
        assertEquals(1, subject.size(), "Size should reflect number of added triples");
        assertEquals(1, subject.capacity(), "Capacity should reflect constructor args");

        assertThrows(
                IllegalStateException.class, () -> subject.add(3, 2, 1), "Should not be able to exceed max capacity");
    }

    @Test
    void constructorsWork() {
        final ThreeLongsList defaultSub = new ThreeLongsList();
        final ThreeLongsList nonDefaultMaxCapSub = new ThreeLongsList(1);
        final ThreeLongsList nonDefaultLongsPerChunkSub = new ThreeLongsList(SMALL_MAX_TRIPLES, 10);

        assertEquals(SMALL_MAX_TRIPLES, defaultSub.capacity(), "Implicit max capacity should be default");
        assertEquals(
                SMALL_TRIPLES_PER_CHUNK,
                defaultSub.getTriplesPerChunk(),
                "Implicit triples-per-chunk should be default");
        assertEquals(1, nonDefaultMaxCapSub.capacity(), "Capacity should reflect constructor arg");
        assertEquals(1, nonDefaultMaxCapSub.getTriplesPerChunk(), "Triples-per-chunk should be capped at max capacity");
        assertEquals(
                SMALL_MAX_TRIPLES, nonDefaultLongsPerChunkSub.capacity(), "Capacity should reflect constructor arg");
        assertEquals(
                10,
                nonDefaultLongsPerChunkSub.getTriplesPerChunk(),
                "Triples-per-chunk should reflect constructor arg");

        assertThrows(
                IllegalArgumentException.class,
                () -> new ThreeLongsList(1, -1),
                "Negative triples per chunk should be illegal");
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThreeLongsList(1, 2),
                "Triples-per-chunk should not be allowed to exceed the max capacity");
    }

    @Test
    void clearingWorks() {
        final int smolCap = 4;
        final ThreeLongsList smolSub = new ThreeLongsList(smolCap, 2);

        for (int i = 0; i < smolCap; i++) {
            smolSub.add(i, i * 2, i * 3);
        }

        assertThrows(
                IllegalStateException.class, () -> smolSub.add(0, 0, 0), "Should not be able to add when at-capacity");

        smolSub.clear();

        assertThrows(
                IndexOutOfBoundsException.class,
                () -> smolSub.get(0),
                "No index should be filled after clearing the list");

        for (int i = 0; i < smolCap; i++) {
            smolSub.add(i, i * 2, i * 3);
        }

        assertEquals(smolCap, smolSub.size(), "Size should re-increment from zero after clearing");
    }

    @Test
    void traversalStopsAtLastIndex() throws Exception {
        final ThreeLongsList.ThreeLongFunction<Exception> consumer = mock(ThreeLongsList.ThreeLongFunction.class);

        final ThreeLongsList sub = new ThreeLongsList(10);

        sub.add(1, 2, 3);
        sub.add(4, 5, 6);
        sub.forEach(consumer);

        verify(consumer).process(1L, 2L, 3L);
        verify(consumer).process(4L, 5L, 6L);
    }
}
