// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.locks.locked;

import org.hiero.base.concurrent.locks.AutoClosableLock;

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
