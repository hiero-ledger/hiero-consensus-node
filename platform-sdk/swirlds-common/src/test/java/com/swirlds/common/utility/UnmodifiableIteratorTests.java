// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UnmodifiableIterator Tests")
class UnmodifiableIteratorTests {

    @Test
    @DisplayName("Null Base Iterator Test")
    void nullBaseIteratorTest() {
        assertThrows(NullPointerException.class, () -> new UnmodifiableIterator<>(null));
    }

    @Test
    @DisplayName("Iteration Test")
    void iterationTest() {
        final List<Integer> data = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            data.add(i);
        }

        final List<Integer> resultingData = new ArrayList<>();
        final Iterator<Integer> it = new UnmodifiableIterator<>(data.iterator());
        while (it.hasNext()) {
            resultingData.add(it.next());
            assertThrows(UnsupportedOperationException.class, it::remove);
        }
        assertThrows(NoSuchElementException.class, it::next);

        assertEquals(data, resultingData);
        for (int i = 0; i < 100; i++) {
            assertEquals(i, resultingData.get(i));
        }
    }

    @Test
    @DisplayName("forEachRemaining() Test")
    void forEachRemainingTest() {
        final List<Integer> data = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            data.add(i);
        }

        final List<Integer> resultingData = new ArrayList<>();
        final Iterator<Integer> it = new UnmodifiableIterator<>(data.iterator());
        it.forEachRemaining(x -> {
            resultingData.add(x);
            assertThrows(UnsupportedOperationException.class, it::remove);
        });
        assertThrows(NoSuchElementException.class, it::next);

        assertEquals(data, resultingData);
        for (int i = 0; i < 100; i++) {
            assertEquals(i, resultingData.get(i));
        }
    }
}
