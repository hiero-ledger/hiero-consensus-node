// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("RandomAccessDeque Tests")
class RandomAccessDequeTests {

    /**
     * Make sure two iterators contain the same values.
     */
    private <T> void assertIteratorValidity(final Iterator<T> expectedIterator, final Iterator<T> iterator) {
        while (expectedIterator.hasNext() && iterator.hasNext()) {
            assertEquals(expectedIterator.next(), iterator.next());
        }
        assertFalse(expectedIterator.hasNext());
        assertFalse(iterator.hasNext());
    }

    /**
     * Validate that the contents of the buffer matches the expected contents.
     */
    private <T> void validateContents(
            final Random random, final List<T> expectedContents, final RandomAccessDeque<T> queue) {

        assertEquals(expectedContents.size(), queue.size());

        // Get each value by index
        for (int index = 0; index < expectedContents.size(); index++) {
            assertEquals(expectedContents.get(index), queue.get(index));
        }

        // Special queries
        if (expectedContents.size() > 0) {
            assertEquals(expectedContents.get(0), queue.getFirst());
            assertEquals(expectedContents.get(expectedContents.size() - 1), queue.getLast());
        }

        // Iterator validity
        assertIteratorValidity(expectedContents.iterator(), queue.iterator());

        // Iterator validity starting at non-zero index
        if (!expectedContents.isEmpty()) {
            final int startingIndex = random.nextInt(0, expectedContents.size());
            final Iterator<T> partialExpectedIterator = expectedContents.iterator();
            for (int i = 0; i < startingIndex; i++) {
                partialExpectedIterator.next();
            }
            assertIteratorValidity(partialExpectedIterator, queue.iterator(startingIndex));
        }
    }

    /**
     * Random operations. Weighted so that there a lot more additions to the queue than removals.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 10, 100, 1_000, 10_000})
    @DisplayName("Lots Of Additions Test")
    void lotsOfAdditionsTest(int initialCapacity) {
        final Random random = getRandomPrintSeed();

        final int iterations = 10_000;

        final List<Integer> expectedValues = new ArrayList<>();

        final RandomAccessDeque<Integer> queue;
        if (initialCapacity == 0) {
            // use default capacity
            queue = new RandomAccessDeque<>();
        } else {
            queue = new RandomAccessDeque<>(initialCapacity);
        }

        validateContents(random, expectedValues, queue);

        for (int iteration = 0; iteration < iterations; iteration++) {
            final double choice = random.nextDouble();

            if (choice < 0.2) {
                final int value = random.nextInt();
                expectedValues.add(0, value);
                queue.addFirst(value);
            } else if (choice < 0.4) {
                final int value = random.nextInt();
                expectedValues.add(value);
                queue.addLast(value);
            } else if (choice < 0.5) {
                if (!expectedValues.isEmpty()) {
                    assertEquals(expectedValues.remove(0), queue.removeFirst());
                }
            } else if (choice < 0.6) {
                if (!expectedValues.isEmpty()) {
                    assertEquals(expectedValues.remove(expectedValues.size() - 1), queue.removeLast());
                }
            } else {
                if (!expectedValues.isEmpty()) {
                    final int index = random.nextInt(expectedValues.size());
                    final int value = random.nextInt();
                    assertEquals(expectedValues.set(index, value), queue.set(index, value));
                }
            }
            validateContents(random, expectedValues, queue);
        }
    }

    /**
     * Random operations. Weighted so that there a lot more removals than additions.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 10, 100, 1_000, 10_000})
    @DisplayName("Lots Of Removals Test")
    void lotsOfRemovalsTest(int initialCapacity) {
        final Random random = getRandomPrintSeed();

        final int iterations = 10_000;

        final List<Integer> expectedValues = new ArrayList<>();

        final RandomAccessDeque<Integer> queue;
        if (initialCapacity == 0) {
            // use default capacity
            queue = new RandomAccessDeque<>();
        } else {
            queue = new RandomAccessDeque<>(initialCapacity);
        }

        validateContents(random, expectedValues, queue);

        for (int iteration = 0; iteration < iterations; iteration++) {
            final double choice = random.nextDouble();

            if (choice < 0.1) {
                final int value = random.nextInt();
                expectedValues.add(0, value);
                queue.addFirst(value);
            } else if (choice < 0.2) {
                final int value = random.nextInt();
                expectedValues.add(value);
                queue.addLast(value);
            } else if (choice < 0.4) {
                if (!expectedValues.isEmpty()) {
                    assertEquals(expectedValues.remove(0), queue.removeFirst());
                }
            } else if (choice < 0.6) {
                if (!expectedValues.isEmpty()) {
                    assertEquals(expectedValues.remove(expectedValues.size() - 1), queue.removeLast());
                }
            } else {
                if (!expectedValues.isEmpty()) {
                    final int index = random.nextInt(expectedValues.size());
                    final int value = random.nextInt();
                    assertEquals(expectedValues.set(index, value), queue.set(index, value));
                }
            }
            validateContents(random, expectedValues, queue);
        }
    }

    /**
     * Random operations. Weighted so that there about the same number of additions as there are removals.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 10, 100, 1_000, 10_000})
    @DisplayName("Balanced Operations Test")
    void balancedOperationsTest(int initialCapacity) {
        final Random random = getRandomPrintSeed();

        final int iterations = 10_000;

        final List<Integer> expectedValues = new ArrayList<>();

        final RandomAccessDeque<Integer> queue;
        if (initialCapacity == 0) {
            // use default capacity
            queue = new RandomAccessDeque<>();
        } else {
            queue = new RandomAccessDeque<>(initialCapacity);
        }

        validateContents(random, expectedValues, queue);

        for (int iteration = 0; iteration < iterations; iteration++) {
            final double choice = random.nextDouble();

            if (choice < 0.2) {
                final int value = random.nextInt();
                expectedValues.add(0, value);
                queue.addFirst(value);
            } else if (choice < 0.4) {
                final int value = random.nextInt();
                expectedValues.add(value);
                queue.addLast(value);
            } else if (choice < 0.6) {
                if (!expectedValues.isEmpty()) {
                    assertEquals(expectedValues.remove(0), queue.removeFirst());
                }
            } else if (choice < 0.8) {
                if (!expectedValues.isEmpty()) {
                    assertEquals(expectedValues.remove(expectedValues.size() - 1), queue.removeLast());
                }
            } else {
                if (!expectedValues.isEmpty()) {
                    final int index = random.nextInt(expectedValues.size());
                    final int value = random.nextInt();
                    assertEquals(expectedValues.set(index, value), queue.set(index, value));
                }
            }
            validateContents(random, expectedValues, queue);
        }
    }

    /**
     * In this test, a bunch of elements are inserted at the onset. Then, whenever an element is added we also remove
     * one as well. This causes the data to move in circles around the queue like a caterpillar. This test is
     * so that the caterpillar is more likely to move forwards through the data queue.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 10, 100, 1_000, 10_000})
    @DisplayName("Caterpillar Test")
    void caterpillarTest(int initialCapacity) {
        final Random random = getRandomPrintSeed();

        final int iterations = 10_000;
        final int initialInsertions = random.nextInt(100, 500);

        final List<Integer> expectedValues = new ArrayList<>();

        final RandomAccessDeque<Integer> queue;
        if (initialCapacity == 0) {
            // use default capacity
            queue = new RandomAccessDeque<>();
        } else {
            queue = new RandomAccessDeque<>(initialCapacity);
        }

        validateContents(random, expectedValues, queue);

        for (int initialInsertion = 0; initialInsertion < initialInsertions; initialInsertion++) {
            final int value = random.nextInt();
            expectedValues.add(value);
            queue.addLast(value);
        }

        validateContents(random, expectedValues, queue);

        for (int iteration = 0; iteration < iterations; iteration++) {
            final double choice = random.nextDouble();

            if (choice < 0.5) {
                final int value = random.nextInt();
                expectedValues.add(0, value);
                queue.addFirst(value);
                assertEquals(expectedValues.remove(expectedValues.size() - 1), queue.removeLast());
            } else if (choice < 0.6) {
                final int value = random.nextInt();
                expectedValues.add(value);
                queue.addLast(value);
                assertEquals(expectedValues.remove(0), queue.removeFirst());
            } else {
                final int index = random.nextInt(expectedValues.size());
                final int value = random.nextInt();
                assertEquals(expectedValues.set(index, value), queue.set(index, value));
            }
            validateContents(random, expectedValues, queue);
        }
    }

    /**
     * In this test, a bunch of elements are inserted at the onset. Then, whenever an element is added we also remove
     * one as well. This causes the data to move in circles around the queue like a caterpillar. This test is
     * so that the caterpillar is more likely to move backwards through the data queue.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 10, 100, 1_000, 10_000})
    @DisplayName("Reverse Caterpillar Test")
    void reverseCaterpillarTest(int initialCapacity) {
        final Random random = getRandomPrintSeed();

        final int iterations = 10_000;
        final int initialInsertions = random.nextInt(100, 500);

        final List<Integer> expectedValues = new ArrayList<>();

        final RandomAccessDeque<Integer> queue;
        if (initialCapacity == 0) {
            // use default capacity
            queue = new RandomAccessDeque<>();
        } else {
            queue = new RandomAccessDeque<>(initialCapacity);
        }

        validateContents(random, expectedValues, queue);

        for (int initialInsertion = 0; initialInsertion < initialInsertions; initialInsertion++) {
            final int value = random.nextInt();
            expectedValues.add(value);
            queue.addLast(value);
        }

        validateContents(random, expectedValues, queue);

        for (int iteration = 0; iteration < iterations; iteration++) {
            final double choice = random.nextDouble();

            if (choice < 0.1) {
                final int value = random.nextInt();
                expectedValues.add(0, value);
                queue.addFirst(value);
                assertEquals(expectedValues.remove(expectedValues.size() - 1), queue.removeLast());
            } else if (choice < 0.6) {
                final int value = random.nextInt();
                expectedValues.add(value);
                queue.addLast(value);
                assertEquals(expectedValues.remove(0), queue.removeFirst());
            } else {
                final int index = random.nextInt(expectedValues.size());
                final int value = random.nextInt();
                assertEquals(expectedValues.set(index, value), queue.set(index, value));
            }
            validateContents(random, expectedValues, queue);
        }
    }

    /**
     * In this test, a bunch of elements are inserted at the onset. Then, whenever an element is added we also remove
     * one as well. This causes the data to move in circles around the queue like a caterpillar. This test is
     * so that the caterpillar is equally likely to move forwards as it is to move backwards.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 10, 100, 1_000, 10_000})
    @DisplayName("Balanced Caterpillar Test")
    void balancedCaterpillarTest(int initialCapacity) {
        final Random random = getRandomPrintSeed();

        final int iterations = 10_000;
        final int initialInsertions = random.nextInt(100, 500);

        final List<Integer> expectedValues = new ArrayList<>();

        final RandomAccessDeque<Integer> queue;
        if (initialCapacity == 0) {
            // use default capacity
            queue = new RandomAccessDeque<>();
        } else {
            queue = new RandomAccessDeque<>(initialCapacity);
        }

        validateContents(random, expectedValues, queue);

        for (int initialInsertion = 0; initialInsertion < initialInsertions; initialInsertion++) {
            final int value = random.nextInt();
            expectedValues.add(value);
            queue.addLast(value);
        }

        validateContents(random, expectedValues, queue);

        for (int iteration = 0; iteration < iterations; iteration++) {
            final double choice = random.nextDouble();

            if (choice < 0.3) {
                final int value = random.nextInt();
                expectedValues.add(0, value);
                queue.addFirst(value);
                assertEquals(expectedValues.remove(expectedValues.size() - 1), queue.removeLast());
            } else if (choice < 0.6) {
                final int value = random.nextInt();
                expectedValues.add(value);
                queue.addLast(value);
                assertEquals(expectedValues.remove(0), queue.removeFirst());
            } else {
                final int index = random.nextInt(expectedValues.size());
                final int value = random.nextInt();
                assertEquals(expectedValues.set(index, value), queue.set(index, value));
            }
            validateContents(random, expectedValues, queue);
        }
    }

    @Test
    @DisplayName("Illegal Operations Test")
    void illegalOperationsTest() {

        assertThrows(IllegalArgumentException.class, () -> new RandomAccessDeque<>(0));
        assertThrows(IllegalArgumentException.class, () -> new RandomAccessDeque<>(-1));

        final RandomAccessDeque<Integer> deque = new RandomAccessDeque<>();
        assertThrows(NoSuchElementException.class, () -> deque.get(0));
        assertThrows(IllegalStateException.class, () -> deque.set(0, 0));
        assertThrows(IllegalArgumentException.class, () -> deque.get(-1));
        assertThrows(IllegalArgumentException.class, () -> deque.set(-1, 0));

        final Iterator<Integer> it1 = deque.iterator();
        assertThrows(NoSuchElementException.class, it1::next);

        deque.addLast(1);
        assertThrows(NoSuchElementException.class, () -> deque.get(1));
        assertThrows(IllegalStateException.class, () -> deque.set(1, 0));

        final Iterator<Integer> it2 = deque.iterator();
        it2.next();
        assertThrows(NoSuchElementException.class, it2::next);
    }

    @Test
    @DisplayName("Simple Expansion Test")
    void simpleExpansionTest() {

        final RandomAccessDeque<Integer> deque = new RandomAccessDeque<>(10);

        for (int i = 0; i < 100; i++) {
            deque.addLast(i);
        }

        final Iterator<Integer> iterator = deque.iterator();
        for (int i = 0; i < 100; i++) {
            assertEquals(i, iterator.next());
        }
    }

    @Test
    @DisplayName("clear() method Test")
    void clearTest() {
        final RandomAccessDeque<Integer> deque = new RandomAccessDeque<>(100);
        for (int i = 0; i < 10; i++) {
            deque.addLast(i);
        }
        for (int i = 10; i < 20; i++) {
            deque.addFirst(i);
        }
        deque.removeFirst();
        deque.removeLast();

        deque.clear();

        assertEquals(0, deque.size());
        assertFalse(deque.iterator().hasNext());
    }
}
