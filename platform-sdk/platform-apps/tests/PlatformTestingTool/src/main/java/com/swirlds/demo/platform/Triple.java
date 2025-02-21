// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A simple triple of objects.
 *
 * @param left value for the left object
 * @param middle value for the middle object
 * @param right value for the right object
 * @param <L> type of the left object
 * @param <M> type of the middle object
 * @param <R> type of the right object
 * @deprecated Please consider using own data structure instead of this class.
 *             To have more meaningful names than left, middle and right
 */
@Deprecated(forRemoval = true)
public record Triple<L, M, R>(L left, M middle, R right) {

    /**
     * Creates a new triple of objects.
     *
     * @param left value for the left object
     * @param middle value for the middle object
     * @param right value for the right object
     * @param <L> type of the left object
     * @param <M> type of the middle object
     * @param <R> type of the right object
     *
     * @return a new triple of objects
     * @deprecated Please consider using own data structure instead of this class.
     */
    @NonNull
    @Deprecated(forRemoval = true)
    public static <L, M, R> Triple<L, M, R> of(@Nullable L left, @Nullable M middle, @Nullable R right) {
        return new Triple<>(left, middle, right);
    }
}
