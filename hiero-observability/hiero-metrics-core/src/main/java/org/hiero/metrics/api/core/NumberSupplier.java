// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * A wrapper class that holds either a {@link DoubleSupplier} or a {@link LongSupplier}.
 */
public final class NumberSupplier {

    private final DoubleSupplier doubleSupplier;
    private final LongSupplier longSupplier;

    /**
     * Create an instance that holds a {@link DoubleSupplier}.
     *
     * @param valueSupplier the {@code double} value supplier, must not be {@code null}
     * @throws NullPointerException if {@code valueSupplier} is {@code null}
     */
    public NumberSupplier(@NonNull DoubleSupplier valueSupplier) {
        this.doubleSupplier = Objects.requireNonNull(valueSupplier, "value supplier cannot be null");
        this.longSupplier = null;
    }

    /**
     * Create an instance that holds a {@link LongSupplier}.
     *
     * @param valueSupplier the {@code long} value supplier, must not be {@code null}
     * @throws NullPointerException if {@code valueSupplier} is {@code null}
     */
    public NumberSupplier(@NonNull LongSupplier valueSupplier) {
        this.doubleSupplier = null;
        this.longSupplier = Objects.requireNonNull(valueSupplier, "value supplier cannot be null");
    }

    /**
     * @return {@code true} if this instance holds a {@link DoubleSupplier}, {@code false} if it holds a {@link LongSupplier}
     */
    public boolean isFloatingSupplier() {
        return doubleSupplier != null;
    }

    /**
     * Get the {@link DoubleSupplier} held by this instance.
     *
     * @return the {@code double} value supplier
     * @throws NullPointerException if this instance holds a {@link LongSupplier}
     */
    public DoubleSupplier getDoubleSupplier() {
        return Objects.requireNonNull(this.doubleSupplier, "Double value supplier is not set");
    }

    /**
     * Get the {@link LongSupplier} held by this instance.
     *
     * @return the {@code long} value supplier
     * @throws NullPointerException if this instance holds a {@link DoubleSupplier}
     */
    public LongSupplier getLongSupplier() {
        return Objects.requireNonNull(this.longSupplier, "Long value supplier is not set");
    }
}
