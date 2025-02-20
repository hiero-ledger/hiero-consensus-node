// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.function;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Similar to {@link java.util.function.Consumer} but throws an exception.
 *
 * @param <T> the type accepted by the consumer
 * @param <E> the type thrown by the consumer
 */
@FunctionalInterface
public interface CheckedConsumer<T, E extends Exception> {

    /**
     * Accept the value.
     *
     * @param value the value to accept
     * @throws E the exception type thrown by the consumer
     */
    void accept(@Nullable T value) throws E;

    /**
     * Convert a {@link Consumer} to a {@link CheckedConsumer}.
     *
     * @param consumer the consumer
     * @param <T>      the type accepted by the consumer
     * @param <E>      the type thrown by the consumer
     * @return the {@link CheckedConsumer}
     */
    @NonNull
    static <T, E extends Exception> CheckedConsumer<T, E> of(@NonNull final Consumer<T> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        return consumer::accept;
    }
}
