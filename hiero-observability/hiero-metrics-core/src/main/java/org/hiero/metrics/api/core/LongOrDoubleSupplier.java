// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * A wrapper class that holds either a {@link DoubleSupplier} or a {@link LongSupplier}.
 */
public final class LongOrDoubleSupplier {

    private final DoubleSupplier doubleValueSupplier;
    private final LongSupplier longValueSupplier;

    /**
     * Create an instance that holds a {@link DoubleSupplier}.
     *
     * @param valueSupplier the {@code double} value supplier
     */
    public LongOrDoubleSupplier(@NonNull DoubleSupplier valueSupplier) {
        this.doubleValueSupplier = Objects.requireNonNull(valueSupplier, "valueSupplier cannot be null");
        this.longValueSupplier = null;
    }

    /**
     * Create an instance that holds a {@link LongSupplier}.
     *
     * @param valueSupplier the {@code long} value supplier
     */
    public LongOrDoubleSupplier(@NonNull LongSupplier valueSupplier) {
        this.doubleValueSupplier = null;
        this.longValueSupplier = Objects.requireNonNull(valueSupplier, "valueSupplier cannot be null");
    }

    /**
     * @return {@code true} if this instance holds a {@link LongSupplier}, {@code false} if it holds a {@link DoubleSupplier}
     */
    public boolean isDoubleSupplier() {
        return doubleValueSupplier != null;
    }

    /**
     * Get the {@link DoubleSupplier} held by this instance.
     *
     * @return the {@code double} value supplier
     * @throws NullPointerException if this instance holds a {@link LongSupplier}
     */
    public DoubleSupplier getDoubleValueSupplier() {
        return Objects.requireNonNull(this.doubleValueSupplier, "Double value supplier is not set");
    }

    /**
     * Get the {@link LongSupplier} held by this instance.
     *
     * @return the {@code long} value supplier
     * @throws NullPointerException if this instance holds a {@link DoubleSupplier}
     */
    public LongSupplier getLongValueSupplier() {
        return Objects.requireNonNull(this.longValueSupplier, "Long value supplier is not set");
    }
}
