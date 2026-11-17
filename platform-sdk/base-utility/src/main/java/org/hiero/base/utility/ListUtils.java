// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility;

import java.util.ArrayList;
import java.util.List;
import java.util.RandomAccess;
import java.util.function.Function;

/**
 * Utility methods for working with {@link List}s.
 */
public class ListUtils {

    /**
     * Applies a function to each element of an array and returns the results as a new list.
     * The result keeps the order of the source array and is independent of it. {@code null}
     * elements are passed to the mapper as-is.
     *
     * <pre>{@code
     * List<Integer> lengths = ListUtils.mapToCopy(new String[] {"a", "bb"}, String::length);
     * // [1, 2]
     * }</pre>
     *
     * @param original the array to map; must not be {@code null}
     * @param mapper   the function to apply to each element; must not be {@code null}
     * @param <T>      the element type of the source array
     * @param <R>      the element type of the resulting list
     * @return a new, mutable list containing the mapped elements in their original order
     * @throws NullPointerException if {@code original} or {@code mapper} is {@code null}
     */
    public static <R, T> List<R> mapToCopy(T[] original, Function<? super T, ? extends R> mapper) {
        final ArrayList<R> result = new ArrayList<>(original.length);
        for (final T t : original) {
            result.add(mapper.apply(t));
        }
        return result;
    }

    /**
     * Applies a function to each element of a list and returns the results as a new list.
     * The result keeps the order of the source list and is independent of it. {@code null}
     * elements are passed to the mapper as-is. The source list is not modified.
     *
     * <pre>{@code
     * List<String> upper = ListUtils.mapToCopy(List.of("a", "b"), String::toUpperCase);
     * // ["A", "B"]
     * }</pre>
     *
     * @param original the list to map; must not be {@code null}
     * @param mapper   the function to apply to each element; must not be {@code null}
     * @param <T>      the element type of the source list
     * @param <R>      the element type of the resulting list
     * @return a new, mutable list containing the mapped elements in their original order
     * @throws NullPointerException if {@code original} or {@code mapper} is {@code null}
     */
    public static <R, T> List<R> mapToCopy(List<T> original, Function<? super T, ? extends R> mapper) {
        final ArrayList<R> result = new ArrayList<>(original.size());
        if (original instanceof RandomAccess) {
            for (int i = 0; i < original.size(); i++) {
                result.add(mapper.apply(original.get(i)));
            }
        } else {
            for (final T t : original) {
                result.add(mapper.apply(t));
            }
        }
        return result;
    }
}
