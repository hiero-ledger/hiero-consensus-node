// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework.config;

import com.swirlds.common.threading.framework.QueueThreadPool;
import com.swirlds.common.threading.framework.internal.AbstractQueueThreadPoolConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import org.hiero.consensus.model.utility.interrupt.InterruptableConsumer;

/**
 * Implements a thread pool that continuously takes elements from a queue and handles them.
 *
 * @param <T>
 * 		the type of the item in the queue
 */
public class QueueThreadPoolConfiguration<T>
        extends AbstractQueueThreadPoolConfiguration<QueueThreadPoolConfiguration<T>, T> {

    /**
     * Create a new QueueThreadPool configuration.
     *
     * @param threadManager
     * 		responsible for creating and managing threads
     */
    public QueueThreadPoolConfiguration(final ThreadManager threadManager) {
        super(threadManager);
    }

    /**
     * Copy constructor.
     *
     * @param that
     * 		the configuration to copy.
     */
    private QueueThreadPoolConfiguration(final QueueThreadPoolConfiguration<T> that) {
        super(that);
    }

    /**
     * Get a copy of this configuration. New copy is always mutable,
     * and the mutability status of the original is unchanged.
     *
     * @return a copy of this configuration
     */
    @Override
    public QueueThreadPoolConfiguration<T> copy() {
        return new QueueThreadPoolConfiguration<>(this);
    }

    /**
     * <p>
     * Build a new queue thread pool. Does not start the pool.
     * </p>
     *
     * <p>
     * After calling this method, this configuration object should not be modified or used to construct other
     * pools.
     * </p>
     *
     * @return a queue thread built using this configuration
     */
    public QueueThreadPool<T> build() {
        return build(false);
    }

    /**
     * <p>
     * Build a new queue thread pool.
     * </p>
     *
     * <p>
     * After calling this method, this configuration object should not be modified or used to construct other
     * pools.
     * </p>
     *
     * @param start
     * 		if true then start the pool
     * @return a queue thread pool built using this configuration
     */
    public QueueThreadPool<T> build(final boolean start) {
        final QueueThreadPool<T> pool = buildQueueThreadPool(start);
        becomeImmutable();
        return pool;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * This method must be thread safe, as it will be invoked on multiple threads in parallel.
     * </p>
     */
    @Override
    public QueueThreadPoolConfiguration<T> setHandler(final InterruptableConsumer<T> handler) {
        return super.setHandler(handler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InterruptableConsumer<T> getHandler() {
        return super.getHandler();
    }
}
