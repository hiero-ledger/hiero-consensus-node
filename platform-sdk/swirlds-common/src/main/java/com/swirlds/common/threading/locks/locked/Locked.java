// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.locks.locked;

import com.swirlds.common.threading.locks.AutoClosableLock;

/**
 * Returned by an {@link AutoClosableLock} when the lock has been acquired.
 */
@FunctionalInterface
public interface Locked extends AutoCloseable {
    /**
     * Unlocks the previously acquired lock
     */
    @Override
    void close();
}
