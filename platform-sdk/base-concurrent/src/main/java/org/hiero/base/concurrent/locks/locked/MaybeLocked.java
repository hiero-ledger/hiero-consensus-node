// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.locks.locked;

import org.hiero.base.concurrent.locks.AutoClosableLock;

/**
 * Returned by the {@link AutoClosableLock} when the caller is not sure if the lock has been acquired or not.
 */
public interface MaybeLocked extends Locked {
    /** A convenience singleton to return when the lock has not been acquired */
    MaybeLocked NOT_ACQUIRED = new MaybeLocked() {
        @Override
        public boolean isLockAcquired() {
            return false;
        }

        @Override
        public void close() {
            // do nothing
        }
    };

    /**
     * @return true if the lock has been acquired, false otherwise
     */
    boolean isLockAcquired();
}
