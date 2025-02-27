// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.utility;

/**
 * Functional interface that represents a function that accepts one argument and produces a result. This variant is
 * capable of throwing a checked exception.
 *
 * @param <T> the type of the input to the function.
 * @param <R> the type of the result of the function.
 */
@FunctionalInterface
public interface ThrowableFunction<T, R> {
    /**
     * Applies this function to the given argument.
     *
     * @param t the function argument.
     * @return the function result.
     * @throws Throwable if an error occurs.
     */
    R apply(T t) throws Throwable;
}
