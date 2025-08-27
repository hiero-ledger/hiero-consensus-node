// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.locks;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.TimeUnit;
import org.hiero.base.concurrent.locks.locked.LockedResource;
import org.hiero.base.concurrent.locks.locked.MaybeLockedResource;

/**
 * A {@link AutoClosableLock} that can lock a resource.
 *
 * @param <T>
 * 		type of the resource
 */
public interface AutoClosableResourceLock<T> extends AutoClosableLock {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    LockedResource<T> lock();

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    LockedResource<T> lockInterruptibly() throws InterruptedException;

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    MaybeLockedResource<T> tryLock();

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    MaybeLockedResource<T> tryLock(final long time, @NonNull final TimeUnit unit) throws InterruptedException;
}
