// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

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
public final class ToLongOrDoubleFunction<T> {

    private final ToDoubleFunction<T> doubleValueConverter;
    private final ToLongFunction<T> longValueConverter;

    /**
     * Create an instance that holds a {@link ToDoubleFunction}.
     *
     * @param valueConverter the {@code double} value converter
     */
    public ToLongOrDoubleFunction(ToDoubleFunction<T> valueConverter) {
        Objects.requireNonNull(valueConverter, "valueConverter cannot be null");
        this.doubleValueConverter = d -> d == null ? Double.NaN : valueConverter.applyAsDouble(d);
        this.longValueConverter = null;
    }

    /**
     * Create an instance that holds a {@link ToLongFunction}.
     *
     * @param valueConverter the {@code long} value converter
     */
    public ToLongOrDoubleFunction(ToLongFunction<T> valueConverter) {
        Objects.requireNonNull(valueConverter, "valueConverter cannot be null");
        this.doubleValueConverter = null;
        this.longValueConverter = val -> val == null ? 0L : valueConverter.applyAsLong(val);
    }

    /**
     * @return {@code true} if this instance holds a {@link ToLongFunction}, {@code false} if it holds a {@link ToDoubleFunction}
     */
    public boolean isToDoubleFunction() {
        return doubleValueConverter != null;
    }

    /**
     * Get the {@link ToDoubleFunction} held by this instance.
     *
     * @return the {@code double} value converter
     * @throws NullPointerException if this instance holds a {@link ToLongFunction}
     */
    public ToDoubleFunction<T> getDoubleValueConverter() {
        return Objects.requireNonNull(this.doubleValueConverter, "Double value converter is not set");
    }

    /**
     * Get the {@link ToLongFunction} held by this instance.
     *
     * @return the {@code long} value converter
     * @throws NullPointerException if this instance holds a {@link ToDoubleFunction}
     */
    public ToLongFunction<T> getLongValueConverter() {
        return Objects.requireNonNull(this.longValueConverter, "Long value converter is not set");
    }
}
