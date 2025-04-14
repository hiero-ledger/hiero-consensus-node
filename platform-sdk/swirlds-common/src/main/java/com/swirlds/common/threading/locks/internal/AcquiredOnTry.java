// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.locks.internal;

import com.swirlds.base.utility.AutoCloseableNonThrowing;
import com.swirlds.common.threading.locks.locked.MaybeLocked;
import edu.umd.cs.findbugs.annotations.NonNull;

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
