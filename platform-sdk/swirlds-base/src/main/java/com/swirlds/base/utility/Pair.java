// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.utility;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A simple class to hold a pair of values.
 *
 * @param left value for the left/key side of the pair
 * @param right value for the right/value side of the pair
 * @param <L> type of the left/key side of the pair
 * @param <R> type of the right/value side of the pair
 */
public record Pair<L, R>(L left, R right) {
    /**
     * Create a new pair of values.
     *
     * @param left value for the left/key side of the pair
     * @param right value for the right/value side of the pair
     * @param <L> type of the left/key side of the pair
     * @param <R> type of the right/value side of the pair
     *
     * @return a new pair of key and value
     */
    @NonNull
    public static <L, R> Pair<L, R> of(@Nullable final L left, @Nullable final R right) {
        return new Pair<>(left, right);
    }

    /**
     * Convenience method to reference the left site as key.
     *
     * @return the left side of the pair
     */
    public L key() {
        return left;
    }

    /**
     * Convenience method to reference the right site as value.
     *
     * @return the right side of the pair
     */
    public R value() {
        return right;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "Pair{" + "left=" + left + ", right=" + right + '}';
    }
}
