// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework.config;

import com.swirlds.common.threading.framework.QueueThread;
import com.swirlds.common.threading.framework.internal.AbstractQueueThreadConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import org.hiero.consensus.model.utility.interrupt.InterruptableConsumer;

/**
 * An object used to configure and build {@link QueueThread}s.
 *
 * @param <T>
 * 		the type held by the queue
 */
public class QueueThreadConfiguration<T> extends AbstractQueueThreadConfiguration<QueueThreadConfiguration<T>, T> {

    /**
     * Build a new queue thread configuration with default values.
     *
     * @param threadManager
     * 		responsible for the creation and management of the thread used by this object
     */
    public QueueThreadConfiguration(final ThreadManager threadManager) {
        super(threadManager);
    }

    /**
     * Copy constructor.
     *
     * @param that
     * 		the configuration to copy.
     */
    public QueueThreadConfiguration(final QueueThreadConfiguration<T> that) {
        super(that);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public QueueThreadConfiguration<T> copy() {
        return new QueueThreadConfiguration<>(this);
    }

    /**
     * <p>
     * Build a new queue thread. Does not start the thread.
     * </p>
     *
     * <p>
     * After calling this method, this configuration object should not be modified or used to construct other
     * threads.
     * </p>
     *
     * @return a queue thread built using this configuration
     */
    public QueueThread<T> build() {
        return build(false);
    }

    /**
     * <p>
     * Build a new queue thread.
     * </p>
     *
     * <p>
     * After calling this method, this configuration object should not be modified or used to construct other
     * threads.
     * </p>
     *
     * @param start
     * 		if true then start the thread
     * @return a queue thread built using this configuration
     */
    public QueueThread<T> build(final boolean start) {
        final QueueThread<T> queueThread = buildQueueThread(start);
        becomeImmutable();
        return queueThread;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public QueueThreadConfiguration<T> setHandler(final InterruptableConsumer<T> handler) {
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
