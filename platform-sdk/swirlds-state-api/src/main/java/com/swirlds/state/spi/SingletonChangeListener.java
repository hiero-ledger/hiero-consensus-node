// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A listener that is notified when a value is written to a singleton.
 * @param <V> The type of the value
 */
public interface SingletonChangeListener<V> {
    /**
     * Called when the value of a singleton is written.
     *
     * @param value The value of the singleton
     */
    void singletonUpdateChange(@NonNull V value);
}
