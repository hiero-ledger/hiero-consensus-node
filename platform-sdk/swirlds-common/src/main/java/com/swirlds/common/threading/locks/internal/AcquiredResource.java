// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.locks.internal;

import com.swirlds.base.utility.AutoCloseableNonThrowing;
import com.swirlds.common.threading.locks.locked.MaybeLockedResource;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * An instance which is returned by the {@link ResourceLock} when the lock is acquired. Provides access to the locked
 * resource.
 *
 * @param <T>
 * 		the type of resource
 */
public final class AcquiredResource<T> implements MaybeLockedResource<T> {
    private final AutoCloseableNonThrowing unlock;
    private T resource;

    public AcquiredResource(@NonNull final AutoCloseableNonThrowing unlock, @Nullable final T resource) {
        this.unlock = unlock;
        this.resource = resource;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public T getResource() {
        return resource;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setResource(@Nullable final T resource) {
        this.resource = resource;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isLockAcquired() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        unlock.close();
    }
}
