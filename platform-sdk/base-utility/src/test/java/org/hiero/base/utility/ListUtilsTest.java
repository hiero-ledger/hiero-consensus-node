// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.RandomAccess;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ListUtils}.
 *
 * <p>Test names are prefixed with the variant under test:
 * <ul>
 *   <li>{@code array} - {@link ListUtils#mapToCopy(Object[], Function)}</li>
 *   <li>{@code randomAccessList} - {@link ListUtils#mapToCopy(List, Function)} with a
 *       {@link RandomAccess} list (index based access)</li>
 *   <li>{@code linkedList} - {@link ListUtils#mapToCopy(List, Function)} with a
 *       non-{@link RandomAccess} list (iterator based access)</li>
 * </ul>
 */
class ListUtilsTest {

    private static List<String> randomAccessList(final String... values) {
        final List<String> list = new ArrayList<>(Arrays.asList(values));
        assertTrue(list instanceof RandomAccess, "test setup: list must be RandomAccess");
        return list;
    }

    private static List<String> linkedList(final String... values) {
        final List<String> list = new LinkedList<>(Arrays.asList(values));
        assertFalse(list instanceof RandomAccess, "test setup: list must not be RandomAccess");
        return list;
    }

    // ------------------------------------------------------------------
    // Array variant
    // ------------------------------------------------------------------

    @Test
    void arrayMapsAllElementsInOrder() {
        final List<Integer> result = ListUtils.mapToCopy(new String[] {"a", "bb", "ccc"}, String::length);

        assertEquals(Arrays.asList(1, 2, 3), result);
    }

    @Test
    void arrayEmptyArrayReturnsEmptyList() {
        final List<String> result = ListUtils.mapToCopy(new Integer[0], String::valueOf);

        assertTrue(result.isEmpty());
    }

    @Test
    void arrayInvokesMapperOncePerElement() {
        final AtomicInteger calls = new AtomicInteger();

        ListUtils.mapToCopy(new String[] {"x", "y", "z"}, s -> calls.incrementAndGet());

        assertEquals(3, calls.get());
    }

    @Test
    void arrayPassesNullElementsToMapper() {
        final List<String> result =
                ListUtils.mapToCopy(new String[] {"a", null, "c"}, s -> s == null ? "NULL" : s.toUpperCase());

        assertEquals(Arrays.asList("A", "NULL", "C"), result);
    }

    @Test
    void arrayAllowsMapperToReturnNull() {
        final List<String> result = ListUtils.mapToCopy(new String[] {"a", "b"}, s -> null);

        assertEquals(2, result.size());
        assertNull(result.get(0));
        assertNull(result.get(1));
    }

    @Test
    void arrayKeepsDuplicates() {
        final List<Integer> result = ListUtils.mapToCopy(new String[] {"a", "a", "b"}, String::length);

        assertEquals(Arrays.asList(1, 1, 1), result);
    }

    @Test
    void arrayAcceptsMapperWithWiderParameterType() {
        final Function<Object, String> mapper = Object::toString;

        final List<String> result = ListUtils.mapToCopy(new Integer[] {1, 2}, mapper);

        assertEquals(Arrays.asList("1", "2"), result);
    }

    @Test
    void arrayAcceptsMapperWithNarrowerReturnType() {
        final Function<String, Integer> mapper = String::length;

        final List<Number> result = ListUtils.mapToCopy(new String[] {"ab", "c"}, mapper);

        assertEquals(Arrays.<Number>asList(2, 1), result);
    }

    @Test
    void arrayResultIsMutable() {
        final List<String> result = ListUtils.mapToCopy(new String[] {"a"}, s -> s);

        result.add("b");
        result.remove(0);

        assertEquals(Collections.singletonList("b"), result);
    }

    @Test
    void arrayResultIsIndependentOfSource() {
        final String[] input = {"a", "b"};

        final List<String> result = ListUtils.mapToCopy(input, s -> s);
        input[0] = "changed";

        assertEquals(Arrays.asList("a", "b"), result);
    }

    @Test
    void arrayDoesNotModifySource() {
        final String[] input = {"a", "b"};

        ListUtils.mapToCopy(input, String::toUpperCase);

        assertEquals("a", input[0]);
        assertEquals("b", input[1]);
    }

    @Test
    void arrayNullArrayThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> ListUtils.mapToCopy((String[]) null, s -> s));
    }

    @Test
    void arrayNullMapperThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> ListUtils.mapToCopy(new String[] {"a"}, null));
    }

    @Test
    void arrayMapperExceptionIsPropagated() {
        final IllegalStateException boom = new IllegalStateException("boom");

        final IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> ListUtils.mapToCopy(new String[] {"a"}, s -> {
                    throw boom;
                }));

        assertSame(boom, thrown);
    }

    // ------------------------------------------------------------------
    // List variant - RandomAccess list (ArrayList)
    // ------------------------------------------------------------------

    @Test
    void randomAccessListMapsAllElementsInOrder() {
        final List<Integer> result = ListUtils.mapToCopy(randomAccessList("a", "bb", "ccc"), String::length);

        assertEquals(Arrays.asList(1, 2, 3), result);
    }

    @Test
    void randomAccessListEmptyListReturnsEmptyList() {
        final List<String> result = ListUtils.mapToCopy(randomAccessList(), String::toUpperCase);

        assertTrue(result.isEmpty());
    }

    @Test
    void randomAccessListInvokesMapperOncePerElement() {
        final AtomicInteger calls = new AtomicInteger();

        ListUtils.mapToCopy(randomAccessList("x", "y", "z"), s -> calls.incrementAndGet());

        assertEquals(3, calls.get());
    }

    @Test
    void randomAccessListPassesNullElementsToMapper() {
        final List<String> result =
                ListUtils.mapToCopy(randomAccessList("a", null, "c"), s -> s == null ? "NULL" : s.toUpperCase());

        assertEquals(Arrays.asList("A", "NULL", "C"), result);
    }

    @Test
    void randomAccessListAllowsMapperToReturnNull() {
        final List<String> result = ListUtils.mapToCopy(randomAccessList("a", "b"), s -> null);

        assertEquals(2, result.size());
        assertNull(result.get(0));
        assertNull(result.get(1));
    }

    @Test
    void randomAccessListKeepsDuplicates() {
        final List<Integer> result = ListUtils.mapToCopy(randomAccessList("a", "a", "b"), String::length);

        assertEquals(Arrays.asList(1, 1, 1), result);
    }

    @Test
    void randomAccessListAcceptsMapperWithWiderParameterType() {
        final Function<Object, String> mapper = Object::toString;

        final List<String> result = ListUtils.mapToCopy(Arrays.asList(1, 2), mapper);

        assertEquals(Arrays.asList("1", "2"), result);
    }

    @Test
    void randomAccessListAcceptsMapperWithNarrowerReturnType() {
        final Function<String, Integer> mapper = String::length;

        final List<Number> result = ListUtils.mapToCopy(randomAccessList("ab", "c"), mapper);

        assertEquals(Arrays.<Number>asList(2, 1), result);
    }

    @Test
    void randomAccessListAcceptsUnmodifiableList() {
        final List<String> input = Collections.unmodifiableList(randomAccessList("a", "b"));
        assertTrue(input instanceof RandomAccess, "test setup: list must be RandomAccess");

        final List<String> result = ListUtils.mapToCopy(input, String::toUpperCase);

        assertEquals(Arrays.asList("A", "B"), result);
    }

    @Test
    void randomAccessListReturnsNewInstance() {
        final List<String> input = randomAccessList("a", "b");

        final List<String> result = ListUtils.mapToCopy(input, s -> s);

        assertNotSame(input, result);
        assertEquals(input, result);
    }

    @Test
    void randomAccessListResultIsMutable() {
        final List<String> result = ListUtils.mapToCopy(randomAccessList("a"), s -> s);

        result.add("b");
        result.remove(0);

        assertEquals(Collections.singletonList("b"), result);
    }

    @Test
    void randomAccessListResultIsIndependentOfSource() {
        final List<String> input = randomAccessList("a", "b");

        final List<String> result = ListUtils.mapToCopy(input, s -> s);
        input.set(0, "changed");
        input.add("extra");

        assertEquals(Arrays.asList("a", "b"), result);
    }

    @Test
    void randomAccessListDoesNotModifySource() {
        final List<String> input = randomAccessList("a", "b");

        ListUtils.mapToCopy(input, String::toUpperCase);

        assertEquals(Arrays.asList("a", "b"), input);
    }

    @Test
    void randomAccessListNullListThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> ListUtils.mapToCopy((List<String>) null, s -> s));
    }

    @Test
    void randomAccessListNullMapperThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> ListUtils.mapToCopy(randomAccessList("a"), null));
    }

    @Test
    void randomAccessListMapperExceptionIsPropagated() {
        final IllegalStateException boom = new IllegalStateException("boom");

        final IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> ListUtils.mapToCopy(randomAccessList("a"), s -> {
                    throw boom;
                }));

        assertSame(boom, thrown);
    }

    // ------------------------------------------------------------------
    // List variant - non-RandomAccess list (LinkedList)
    // ------------------------------------------------------------------

    @Test
    void linkedListMapsAllElementsInOrder() {
        final List<Integer> result = ListUtils.mapToCopy(linkedList("a", "bb", "ccc"), String::length);

        assertEquals(Arrays.asList(1, 2, 3), result);
    }

    @Test
    void linkedListEmptyListReturnsEmptyList() {
        final List<String> result = ListUtils.mapToCopy(linkedList(), String::toUpperCase);

        assertTrue(result.isEmpty());
    }

    @Test
    void linkedListInvokesMapperOncePerElement() {
        final AtomicInteger calls = new AtomicInteger();

        ListUtils.mapToCopy(linkedList("x", "y", "z"), s -> calls.incrementAndGet());

        assertEquals(3, calls.get());
    }

    @Test
    void linkedListPassesNullElementsToMapper() {
        final List<String> result =
                ListUtils.mapToCopy(linkedList("a", null, "c"), s -> s == null ? "NULL" : s.toUpperCase());

        assertEquals(Arrays.asList("A", "NULL", "C"), result);
    }

    @Test
    void linkedListAllowsMapperToReturnNull() {
        final List<String> result = ListUtils.mapToCopy(linkedList("a", "b"), s -> null);

        assertEquals(2, result.size());
        assertNull(result.get(0));
        assertNull(result.get(1));
    }

    @Test
    void linkedListKeepsDuplicates() {
        final List<Integer> result = ListUtils.mapToCopy(linkedList("a", "a", "b"), String::length);

        assertEquals(Arrays.asList(1, 1, 1), result);
    }

    @Test
    void linkedListAcceptsMapperWithWiderParameterType() {
        final Function<Object, String> mapper = Object::toString;
        final List<Integer> input = new LinkedList<>(Arrays.asList(1, 2));

        final List<String> result = ListUtils.mapToCopy(input, mapper);

        assertEquals(Arrays.asList("1", "2"), result);
    }

    @Test
    void linkedListAcceptsMapperWithNarrowerReturnType() {
        final Function<String, Integer> mapper = String::length;

        final List<Number> result = ListUtils.mapToCopy(linkedList("ab", "c"), mapper);

        assertEquals(Arrays.<Number>asList(2, 1), result);
    }

    @Test
    void linkedListAcceptsUnmodifiableList() {
        final List<String> input = Collections.unmodifiableList(linkedList("a", "b"));
        assertFalse(input instanceof RandomAccess, "test setup: list must not be RandomAccess");

        final List<String> result = ListUtils.mapToCopy(input, String::toUpperCase);

        assertEquals(Arrays.asList("A", "B"), result);
    }

    @Test
    void linkedListReturnsNewInstance() {
        final List<String> input = linkedList("a", "b");

        final List<String> result = ListUtils.mapToCopy(input, s -> s);

        assertNotSame(input, result);
        assertEquals(input, result);
    }

    @Test
    void linkedListResultIsMutable() {
        final List<String> result = ListUtils.mapToCopy(linkedList("a"), s -> s);

        result.add("b");
        result.remove(0);

        assertEquals(Collections.singletonList("b"), result);
    }

    @Test
    void linkedListResultIsIndependentOfSource() {
        final List<String> input = linkedList("a", "b");

        final List<String> result = ListUtils.mapToCopy(input, s -> s);
        input.set(0, "changed");
        input.add("extra");

        assertEquals(Arrays.asList("a", "b"), result);
    }

    @Test
    void linkedListDoesNotModifySource() {
        final List<String> input = linkedList("a", "b");

        ListUtils.mapToCopy(input, String::toUpperCase);

        assertEquals(Arrays.asList("a", "b"), input);
    }

    @Test
    void linkedListNullMapperThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> ListUtils.mapToCopy(linkedList("a"), null));
    }

    @Test
    void linkedListMapperExceptionIsPropagated() {
        final IllegalStateException boom = new IllegalStateException("boom");

        final IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> ListUtils.mapToCopy(linkedList("a"), s -> {
                    throw boom;
                }));

        assertSame(boom, thrown);
    }
}
