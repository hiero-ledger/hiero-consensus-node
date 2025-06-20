// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.benchmark;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.utility.AutoCloseableWrapper;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class MutableStateManager<S extends MerkleNode> implements StateManager<S> {

    private S state;
    private final Lock lock;

    public MutableStateManager(final S initialState) {
        this.state = initialState;
        lock = new ReentrantLock();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AutoCloseableWrapper<S> getState() {
        lock.lock();
        return new AutoCloseableWrapper<>(state, lock::unlock);
    }

    /**
     * Lock the mutex.
     */
    public void lock() {
        lock.lock();
    }

    /**
     * Unlock the mutex.
     */
    public void unlock() {
        lock.unlock();
    }

    /**
     * Set the current mutable state. It is expected that this be called after {@link #lock} and before the
     * corresponding {@link #unlock()}.
     */
    public void setState(final S state) {
        this.state = state;
    }
}
