// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util.iterator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.hiero.consensus.model.utility.TypedIterator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

public class TypedIteratorTest {

    private static final String SHOULD_HAVE_NEXT = "Iterator should have a next element.";
    private static final String SHOULD_NOT_HAVE_NEXT = "Iterator should not have a next element.";
    private static final String NEXT_ELEMENT_MISMATCH = "The element returned by next() is not correct.";
    private static final String NEXT_SHOULD_THROW =
            "next() should throw an exception when no more elements are " + "available.";

    @Tag(TestComponentTags.PLATFORM)
    @Test
    @DisplayName("TypedListIterator - null list")
    void testNullArray() {
        assertThrows(NullPointerException.class, () -> new TypedIterator<>(null));
    }

    @Tag(TestComponentTags.PLATFORM)
    @Test
    @DisplayName("TypedListIterator - empty list")
    void testEmptyArray() {
        final Iterator<Integer> iter = new TypedIterator<>(Collections.emptyIterator());
        assertDoesNotHaveNext(iter);
        assertHasNextThrows(iter);
    }

    @Tag(TestComponentTags.PLATFORM)
    @Test
    @DisplayName("TypedListIterator - all values returned")
    void testAllValuesReturned() {

        final List<Integer> values = List.of(0, 1, 2, 3, 4, 5);

        final Iterator<Integer> iter = new TypedIterator<>(values.iterator());

        for (int i = 0; i < values.size(); i++) {
            assertHasNext(iter);
            assertNextElement(i, iter);
        }

        assertDoesNotHaveNext(iter);
        assertHasNextThrows(iter);
    }

    @Tag(TestComponentTags.PLATFORM)
    @Test
    @DisplayName("TypedListIterator - remove")
    void testRemove() {
        final List<Integer> immutableList = List.of(0, 1, 2, 3, 4, 5);

        Iterator<Integer> iter = new TypedIterator<>(immutableList.iterator());
        iter.next();
        assertThrows(
                UnsupportedOperationException.class,
                iter::remove,
                "Remove is not allowed on immutable list iterators.");

        final List<Integer> mutableList = new ArrayList<>(immutableList);
        iter = new TypedIterator<>(mutableList.iterator());

        assertEquals(6, mutableList.size(), "initial size should be 6");
        iter.next();

        assertDoesNotThrow(iter::remove, "Remove should not throw on mutable list iterator");
        assertEquals(5, mutableList.size(), "an element should have been removed");
        assertFalse(mutableList.contains(0), "Item '0' should have been removed");
    }

    private static void assertHasNext(final Iterator<Integer> iter) {
        assertTrue(iter.hasNext(), SHOULD_HAVE_NEXT);
    }

    private static void assertHasNextThrows(final Iterator<Integer> iter) {
        assertThrows(NoSuchElementException.class, iter::next, NEXT_SHOULD_THROW);
    }

    private static void assertDoesNotHaveNext(final Iterator<Integer> iter) {
        assertFalse(iter.hasNext(), SHOULD_NOT_HAVE_NEXT);
    }

    private static void assertNextElement(final Integer expected, final Iterator<Integer> iter) {
        assertEquals(expected, iter.next(), NEXT_ELEMENT_MISMATCH);
    }
}
