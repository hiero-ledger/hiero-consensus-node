// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.function.LongToIntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BinarySearch Tests")
class BinarySearchTests {

    /**
     * Generate a sorted list of unique integers.
     */
    private List<Integer> generateUniqueValues(final Random random, final int size) {
        final Set<Integer> valueSet = new HashSet<>();

        while (valueSet.size() < size) {
            valueSet.add(random.nextInt());
        }

        final List<Integer> valueList = new ArrayList<>(valueSet);
        Collections.sort(valueList);

        return valueList;
    }

    /**
     * Perform a binary search and assert that the result is valid.
     */
    private void assertSearchIsValid(
            final List<Integer> values,
            final int startingIndex,
            final int endingIndex,
            final int expectedIndex,
            int desiredValue) {

        final LongToIntFunction comparisonFunction =
                (final long index) -> Long.compare(values.get((int) index), desiredValue);

        final long computedIndex = BinarySearch.search(startingIndex, endingIndex, comparisonFunction);

        assertEquals(expectedIndex, computedIndex, "unexpected position reported");
    }

    /**
     * Perform a binary search and assert that the result is not present.
     */
    private void assertSearchIsInvalid(
            final List<Integer> values, final int startingIndex, final int endingIndex, int desiredValue) {

        final LongToIntFunction comparisonFunction = (final long index) -> values.get((int) index) - desiredValue;

        assertThrows(
                NoSuchElementException.class,
                () -> BinarySearch.search(startingIndex, endingIndex, comparisonFunction));
    }

    /**
     * Test binary search in many ways using a list of a given size.
     */
    private void testSearch(final Random random, final int size) {
        final List<Integer> data = generateUniqueValues(random, size);

        // Search for each value in the data
        for (int expectedIndex = 0; expectedIndex < data.size(); expectedIndex++) {

            // Choose random start/end positions that contain the requested data
            final int start = expectedIndex == 0 ? 0 : random.nextInt(expectedIndex);
            final int end =
                    expectedIndex + 1 == data.size() ? data.size() : random.nextInt(expectedIndex + 1, data.size());

            final int desiredValue = data.get(expectedIndex);
            assertSearchIsValid(data, start, end, expectedIndex, desiredValue);
        }

        // Search for values not in the data by choosing in-between values
        for (int expectedIndex = 0; expectedIndex < data.size(); expectedIndex++) {

            // Choose random start/end positions that contain the requested data
            final int start = expectedIndex == 0 ? 0 : random.nextInt(expectedIndex);
            final int end =
                    expectedIndex + 1 == data.size() ? data.size() : random.nextInt(expectedIndex + 1, data.size());

            final int desiredValue = data.get(expectedIndex) + 1;
            if (expectedIndex + 1 < data.size() && data.get(expectedIndex + 1) == desiredValue) {
                // Edge case: two adjacent integers have no in-between value. Skip.
                continue;
            }

            assertSearchIsValid(data, start, end, expectedIndex, desiredValue);
        }

        // Check a variety of error scenarios.
        if (size > 0) {
            // Do a search for a value that is not present (i.e. a value less than all values in the list)
            assertSearchIsInvalid(data, 0, size, data.get(0) - 1);

            // Check invalid indices behavior
            if (size >= 2) {
                final int minimumIndex = random.nextInt(size - 1) + 1;
                final int maximumIndex = random.nextInt(minimumIndex);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> BinarySearch.search(minimumIndex, maximumIndex, i -> {
                            fail("should have failed before this method was called");
                            return 1;
                        }));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> BinarySearch.search(minimumIndex, maximumIndex, i -> {
                            fail("should have failed before this method was called");
                            return 1;
                        }));

                // Null search function.
                assertThrows(
                        NullPointerException.class,
                        () -> BinarySearch.search(0, 1, null),
                        "null comparison function should not be permitted");

                // If the comparison function throws something, it should be thrown all the way up to the top context
                assertThrows(
                        UncheckedIOException.class,
                        () -> BinarySearch.search(0, 1, x -> {
                            throw new UncheckedIOException(new IOException("asdf"));
                        }),
                        "exception not rethrown");
            }
        }
    }

    @Test
    @DisplayName("Binary Search Test")
    void binarySearchTest() {
        final Random random = getRandomPrintSeed();
        for (int size = 0; size < 100; size++) {
            testSearch(random, size);
        }
        testSearch(random, 1023);
        testSearch(random, 1024);
        testSearch(random, 1025);
    }

    @Test
    @DisplayName("Negative Index Test")
    void negativeIndexTest() {
        final int size = 10;

        final Random random = getRandomPrintSeed();
        final List<Integer> data = generateUniqueValues(random, size);

        // Shift the index by a relative offset to the left. Causes some index values to be negative.
        for (int offset = 0; offset < size * 2; offset++) {
            final int finalOffset = offset;

            final int desiredIndex = random.nextInt(size);
            final int desiredShiftedIndex = desiredIndex - offset;
            final int desiredValue = data.get(desiredIndex);

            assertEquals(
                    desiredShiftedIndex,
                    BinarySearch.search(
                            -offset,
                            size - offset,
                            x -> Integer.compare(data.get((int) x + finalOffset), desiredValue)));
        }
    }
}
