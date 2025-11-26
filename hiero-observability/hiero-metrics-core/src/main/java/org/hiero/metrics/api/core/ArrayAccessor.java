// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;

/**
 * Interface for accessing array-like structures. <br>
 * It extends {@link Iterable} for easy iteration, but it is recommended to use indexed access using loop:
 * <br>
 * <pre>
 *  {@code
 *      int size = arrayAccessor.size();
 *      for (int i = 0; i < size; i++) {
 *           T element = arrayAccessor.get(i);
 *           // process element
 *      }
 *  }
 * </pre>
 *
 * @param <T> the type of elements in the array
 */
public interface ArrayAccessor<T> extends Iterable<T> {

    /**
     * @return the number of elements in the array
     */
    int size();

    /**
     * @param index the index of the element to return
     * @return the element at the specified index
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    @NonNull
    T get(int index);

    @NonNull
    @Override
    default Iterator<T> iterator() {
        return new Iterator<>() {

            private final int size = size(); // fix the size at the start of iteration
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < size;
            }

            @Override
            public T next() {
                return get(index++);
            }
        };
    }
}
