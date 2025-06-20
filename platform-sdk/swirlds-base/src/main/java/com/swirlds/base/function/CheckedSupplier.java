// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.function;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Similar to {@link java.util.function.Supplier} but throws an exception.
 *
 * @param <V> the value type
 * @param <E> the type thrown by the supplier
 */
@FunctionalInterface
public interface CheckedSupplier<V, E extends Exception> {

    /**
     * Runs the runnable.
     *
     * @throws E the exception type thrown by the runnable
     */
    V get() throws E;

    /**
     * Convert a {@link Supplier} to a {@link CheckedSupplier}.
     *
     * @param supplier the supplier
     * @param <E>      the type thrown by the supplier
     * @return the {@link CheckedSupplier}
     */
    @NonNull
    static <V, E extends Exception> CheckedSupplier<V, E> of(@NonNull final Supplier<V> supplier) {
        Objects.requireNonNull(supplier, "supplier must not be null");
        return supplier::get;
    }
}
