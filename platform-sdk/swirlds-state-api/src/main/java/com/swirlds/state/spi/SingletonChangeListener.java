// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A listener that is notified when a value is written to a singleton.
 * @param <V> The type of the value
 */
public interface SingletonChangeListener<V> {
    /** Whether to capture the prior value before committing an update. */
    default boolean requiresPreviousValue() {
        return false;
    }

    /** Receives the previous committed value, or null for an insertion. */
    default void singletonUpdateChange(@Nullable V previousValue, @NonNull V value) {
        singletonUpdateChange(value);
    }

    /** Invalidates any baseline retained for a removed singleton. */
    default void singletonDeleteChange() {}

    /**
     * Called when the value of a singleton is written.
     *
     * @param value The value of the singleton
     */
    void singletonUpdateChange(@NonNull V value);
}
