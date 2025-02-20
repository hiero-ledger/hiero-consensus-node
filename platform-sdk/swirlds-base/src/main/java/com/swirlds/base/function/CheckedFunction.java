// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.function;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.function.Function;

/**
 * Similar to {@link java.util.function.Function} but throws an exception.
 *
 * @param <T> the type accepted by the function
 * @param <R> the return type of the function
 * @param <E> the type thrown by the function
 */
@FunctionalInterface
public interface CheckedFunction<T, R, E extends Exception> {

    /**
     * Apply the function.
     *
     * @param value the input to the function
     * @return the value returned by the function
     * @throws E the exception type thrown by the function
     */
    @Nullable
    R apply(@Nullable T value) throws E;

    /**
     * Convert a {@link Function} to a {@link CheckedFunction}.
     *
     * @param function the function
     * @param <T>      the type accepted by the function
     * @param <R>      the return type of the function
     * @param <E>      the type thrown by the function
     * @return the {@link CheckedFunction}
     */
    @NonNull
    static <T, R, E extends Exception> CheckedFunction<T, R, E> of(@NonNull final Function<T, R> function) {
        Objects.requireNonNull(function, "function must not be null");
        return function::apply;
    }
}
