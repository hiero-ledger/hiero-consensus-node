// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.test.fixtures;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;

/**
 * Provides a functional interface for lambda methods that operate on a slice of a given array.
 *
 * @param <T>
 * 		the array type
 * @param <U>
 * 		the offset type
 * @param <V>
 * 		the length type
 */
@FunctionalInterface
public interface SliceConsumer<T, U, V> {

    /**
     * Processes a given slice of the array, starting with the element at offset and ending with the element at ((offset
     * + length) - 1).
     *
     * @param items
     * 		the array of elements
     * @param offset
     * 		the beginning offset where processing should begin
     * @param length
     * 		the total number of elements starting from the offset that should be processed
     */
    void accept(final T[] items, final U offset, final V length)
            throws NoSuchProviderException, NoSuchAlgorithmException;
}
