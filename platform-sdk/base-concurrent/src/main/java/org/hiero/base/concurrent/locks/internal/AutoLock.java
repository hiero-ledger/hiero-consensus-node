// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.locks.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.hiero.base.concurrent.locks.AutoClosableLock;
import org.hiero.base.concurrent.locks.locked.Locked;
import org.hiero.base.concurrent.locks.locked.MaybeLocked;

/**
 * A standard lock that provides the {@link AutoCloseable} semantics. Lock is reentrant.
 */
public final class AutoLock implements AutoClosableLock {

    private final Lock lock = new ReentrantLock();

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Locked lock() {
        lock.lock();
        return lock::unlock;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Locked lockInterruptibly() throws InterruptedException {
        lock.lockInterruptibly();
        return lock::unlock;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MaybeLocked tryLock() {
        final boolean locked = lock.tryLock();
        return new MaybeLocked() {
            @Override
            public boolean isLockAcquired() {
                return locked;
            }

            @Override
            public void close() {
                if (locked) {
                    lock.unlock();
                }
            }
        };
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MaybeLocked tryLock(final long time, final TimeUnit unit) throws InterruptedException {
        final boolean locked = lock.tryLock(time, unit);
        return new MaybeLocked() {
            @Override
            public boolean isLockAcquired() {
                return locked;
            }

            @Override
            public void close() {
                if (locked) {
                    lock.unlock();
                }
            }
        };
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Condition newCondition() {
        return lock.newCondition();
    }
}
