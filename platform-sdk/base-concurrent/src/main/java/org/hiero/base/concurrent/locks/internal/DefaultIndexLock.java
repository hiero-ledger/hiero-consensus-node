// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.locks.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.hiero.base.concurrent.locks.IndexLock;
import org.hiero.base.concurrent.locks.locked.Locked;

/**
 * Default implementation of {@link IndexLock}
 */
public final class DefaultIndexLock implements IndexLock {

    private final int parallelism;
    private final Lock[] locks;

    /**
     * Create a new lock for index values.
     *
     * @param parallelism
     * 		the number of unique locks. Higher parallelism reduces chances of collision for non-identical
     * 		indexes at the cost of additional memory overhead.
     */
    public DefaultIndexLock(final int parallelism) {

        this.parallelism = parallelism;

        this.locks = new Lock[parallelism];
        for (int lockIndex = 0; lockIndex < parallelism; lockIndex++) {
            locks[lockIndex] = new ReentrantLock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void lock(final long index) {
        locks[(int) (Math.abs(index) % parallelism)].lock();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void lock(@Nullable final Object object) {
        final int hash = object == null ? 0 : object.hashCode();
        lock(hash);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unlock(final long index) {
        locks[(int) (Math.abs(index) % parallelism)].unlock();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unlock(@Nullable final Object object) {
        final int hash = object == null ? 0 : object.hashCode();
        unlock(hash);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Locked autoLock(final long index) {
        lock(index);
        return () -> unlock(index);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Locked autoLock(@Nullable final Object object) {
        final int hash = object == null ? 0 : object.hashCode();
        return autoLock(hash);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void fullyLock() {
        for (int index = 0; index < parallelism; index++) {
            locks[index].lock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void fullyUnlock() {
        for (int index = 0; index < parallelism; index++) {
            locks[index].unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Locked autoFullLock() {
        fullyLock();
        return this::fullyUnlock;
    }
}
