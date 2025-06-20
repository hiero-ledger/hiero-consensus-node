// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework.internal;

import com.swirlds.common.threading.framework.QueueThreadPool;
import com.swirlds.common.threading.framework.config.QueueThreadPoolConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;

/**
 * Boilerplate getters, setters, and configuration for queue thread pool configuration.
 *
 * @param <C>
 * 		the type of the class extending this class
 * @param <T>
 * 		the type of the objects in the queue
 */
public abstract class AbstractQueueThreadPoolConfiguration<C extends AbstractQueueThreadConfiguration<C, T>, T>
        extends AbstractQueueThreadConfiguration<QueueThreadPoolConfiguration<T>, T> {

    private static final int DEFAULT_THREAD_COUNT = Runtime.getRuntime().availableProcessors();

    private int threadCount = DEFAULT_THREAD_COUNT;

    protected AbstractQueueThreadPoolConfiguration(final ThreadManager threadManager) {
        super(threadManager);
    }

    /**
     * Copy constructor.
     *
     * @param that
     * 		the configuration to copy
     */
    protected AbstractQueueThreadPoolConfiguration(final AbstractQueueThreadPoolConfiguration<C, T> that) {
        super(that);

        this.threadCount = that.threadCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public abstract AbstractQueueThreadPoolConfiguration<C, T> copy();

    /**
     * Build a new QueueThreadPool from this configuration.
     *
     * @param start
     * 		if true then automatically start the threads in the pool
     * @return a QueueThreadPool
     */
    protected QueueThreadPool<T> buildQueueThreadPool(final boolean start) {
        final QueueThreadPool<T> pool = new QueueThreadPoolImpl<>(this);

        if (start) {
            pool.start();
        }

        return pool;
    }

    /**
     * Get the number of threads in the pool.
     *
     * @return the number of threads in the pool
     */
    public int getThreadCount() {
        return threadCount;
    }

    /**
     * Set the number of threads in the pool.
     *
     * @param threadCount
     * 		the number of threads in the pool
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setThreadCount(final int threadCount) {
        throwIfImmutable();
        this.threadCount = threadCount;
        return (C) this;
    }
}
