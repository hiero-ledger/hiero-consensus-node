// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.locks.internal;

import com.swirlds.base.utility.AutoCloseableNonThrowing;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.concurrent.locks.locked.MaybeLocked;

/**
 * Returned when a lock has been acquired on a try
 */
public final class AcquiredOnTry implements MaybeLocked {
    private final AutoCloseableNonThrowing close;

    public AcquiredOnTry(@NonNull final AutoCloseableNonThrowing close) {
        this.close = close;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        close.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isLockAcquired() {
        return true;
    }
}
