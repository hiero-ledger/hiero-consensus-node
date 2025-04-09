// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.locks;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import org.hiero.base.concurrent.locks.locked.Locked;
import org.hiero.base.concurrent.locks.locked.MaybeLocked;

/**
 * Similar to {@link java.util.concurrent.locks.Lock} but intended to be used by the try-with-resources statement.
 * Returns an object that will release the lock automatically at the end of the try block.
 */
public interface AutoClosableLock {

    /**
     * Acquires the lock, blocking until the lock becomes available.
     *
     * @return an instance used to release the lock
     */
    @NonNull
    Locked lock();

    /**
     * Same as {@link #lock()}, but can unblock if interrupted
     */
    @NonNull
    Locked lockInterruptibly() throws InterruptedException;

    /**
     * Tries to acquire the lock if it is available. Returns immediately
     *
     * @return an instance that tells the caller if the lock has been acquired and provides a method to unlock it if it
     * 		has
     */
    MaybeLocked tryLock();

    /**
     * {@link #tryLock()} but with a timeout
     */
    @NonNull
    MaybeLocked tryLock(long time, @NonNull TimeUnit unit) throws InterruptedException;

    /**
     * Returns a new {@link Condition} instance that is bound to this
     * {@code Lock} instance.
     *
     * <p>Before waiting on the condition the lock must be held by the
     * current thread.
     * A call to {@link Condition#await()} will atomically release the lock
     * before waiting and re-acquire the lock before the wait returns.
     *
     * @return A new {@link Condition} instance for this {@code Lock} instance
     */
    @NonNull
    Condition newCondition();
}
