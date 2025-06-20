// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides mutable access to singleton state.
 *
 * @param <T> The type of the state
 */
public interface WritableSingletonState<T> extends ReadableSingletonState<T> {
    /**
     * Sets the given value on this state.
     *
     * @param value The value. May be null.
     */
    void put(@Nullable T value);

    /**
     * Gets whether the {@link #put(Object)} method has been called on this instance.
     *
     * @return True if the {@link #put(Object)} method has been called
     */
    boolean isModified();
}
