// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.locks.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import org.hiero.base.concurrent.locks.AutoClosableLock;
import org.hiero.base.concurrent.locks.locked.Locked;
import org.hiero.base.concurrent.locks.locked.MaybeLocked;

/**
 * Has similar semantics to {@link AutoLock}, except that it doesn't actually lock anything.
 */
public final class AutoNoOpLock implements AutoClosableLock {

    private static final Locked locked = () -> {
        // intentional no-op
    };

    private static final MaybeLocked maybeLocked = new MaybeLocked() {
        @Override
        public boolean isLockAcquired() {
            return true;
        }

        @Override
        public void close() {
            // intentional no-op
        }
    };
    private static final AutoClosableLock instance = new AutoNoOpLock();

    /**
     * Intentionally private. Use {@link #getInstance()} to get an instance.
     */
    private AutoNoOpLock() {}

    /**
     * Get an instance of a no-op auto lock. A no-op lock doesn't have any state, so we can reuse the
     * same one each time.
     *
     * @return an instance of a no-op auto-lock
     */
    @NonNull
    public static AutoClosableLock getInstance() {
        return instance;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Locked lock() {
        return locked;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Locked lockInterruptibly() {
        return locked;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MaybeLocked tryLock() {
        return maybeLocked;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MaybeLocked tryLock(final long time, @NonNull final TimeUnit unit) {
        return maybeLocked;
    }

    /**
     * Unsupported.
     *
     * @throws UnsupportedOperationException
     * 		if called
     */
    @Override
    @NonNull
    public Condition newCondition() {
        throw new UnsupportedOperationException();
    }
}
