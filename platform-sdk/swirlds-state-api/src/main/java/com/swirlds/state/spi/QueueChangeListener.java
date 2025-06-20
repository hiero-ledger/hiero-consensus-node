// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A listener that is notified when a value is added to or removed from a queue.
 * @param <V> The type of the value
 */
public interface QueueChangeListener<V> {
    /**
     * Called when a value is added to a queue.
     *
     * @param value The value added to the queue
     */
    void queuePushChange(@NonNull V value);

    /**
     * Called when a value is removed from a queue.
     */
    void queuePopChange();
}
