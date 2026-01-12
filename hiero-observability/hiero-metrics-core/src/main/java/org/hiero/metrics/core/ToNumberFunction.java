// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import java.util.Objects;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

/**
 * A wrapper class that holds either a {@link ToDoubleFunction} or a {@link ToLongFunction}.
 * <p>
 * When converting value is {@code null}, the {@link ToDoubleFunction} will return {@link Double#NaN}
 * and the {@link ToLongFunction} will return {@code 0L}.
 *
 * @param <T> the type of the input to the function
 */
public final class ToNumberFunction<T> {

    private final ToDoubleFunction<T> toDoubleFunction;
    private final ToLongFunction<T> toLongFunction;

    /**
     * Create an instance that holds a {@link ToDoubleFunction}.
     *
     * @param valueConverter the {@code double} value converter, must not be {@code null}
     * @throws NullPointerException if {@code valueConverter} is {@code null}
     */
    public ToNumberFunction(ToDoubleFunction<T> valueConverter) {
        Objects.requireNonNull(valueConverter, "valueConverter cannot be null");
        this.toDoubleFunction = d -> d == null ? Double.NaN : valueConverter.applyAsDouble(d);
        this.toLongFunction = null;
    }

    /**
     * Create an instance that holds a {@link ToLongFunction}.
     *
     * @param valueConverter the {@code long} value converter, must not be {@code null}
     * @throws NullPointerException if {@code valueConverter} is {@code null}
     */
    public ToNumberFunction(ToLongFunction<T> valueConverter) {
        Objects.requireNonNull(valueConverter, "valueConverter cannot be null");
        this.toDoubleFunction = null;
        this.toLongFunction = val -> val == null ? 0L : valueConverter.applyAsLong(val);
    }

    /**
     * @return {@code true} if this instance holds a {@link ToDoubleFunction}, {@code false} if it holds a {@link ToLongFunction}
     */
    public boolean isFloatingPointFunction() {
        return toDoubleFunction != null;
    }

    /**
     * Get the {@link ToDoubleFunction} held by this instance.
     *
     * @return the {@code double} value converter
     * @throws NullPointerException if this instance holds a {@link ToLongFunction}
     */
    public ToDoubleFunction<T> getToDoubleFunction() {
        return Objects.requireNonNull(this.toDoubleFunction, "Double value converter is not set");
    }

    /**
     * Get the {@link ToLongFunction} held by this instance.
     *
     * @return the {@code long} value converter
     * @throws NullPointerException if this instance holds a {@link ToDoubleFunction}
     */
    public ToLongFunction<T> getToLongFunction() {
        return Objects.requireNonNull(this.toLongFunction, "Long value converter is not set");
    }
}
