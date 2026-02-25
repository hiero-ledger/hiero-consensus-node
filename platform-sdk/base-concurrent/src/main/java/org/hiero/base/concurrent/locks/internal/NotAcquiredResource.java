// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.locks.internal;

import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.base.concurrent.locks.locked.MaybeLockedResource;

/**
 * Return an instance of this when a {@link ResourceLock} has not been acquired
 */
public final class NotAcquiredResource<T> implements MaybeLockedResource<T> {

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        // do nothing
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public T getResource() {
        throw new IllegalStateException("Cannot get resource if the lock is not obtained");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setResource(@Nullable T resource) {
        throw new IllegalStateException("Cannot set resource if the lock is not obtained");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isLockAcquired() {
        return false;
    }
}
