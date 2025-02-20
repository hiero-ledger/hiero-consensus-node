// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.manager;

import com.swirlds.base.state.LifecyclePhase;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import java.util.concurrent.ThreadFactory;

/**
 * A standard implementation of a {@link ThreadManager}. Will not permit threads to be created
 * until {@link #start()} is called.
 */
public class StandardThreadManager implements ThreadManager {

    private LifecyclePhase phase = LifecyclePhase.NOT_STARTED;

    /**
     * {@inheritDoc}
     */
    @Override
    public Thread createThread(final ThreadGroup threadGroup, final Runnable runnable) {
        throwIfNotInPhase(LifecyclePhase.STARTED);
        return new Thread(threadGroup, runnable);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ThreadFactory createThreadFactory(final String component, final String threadName) {
        return new ThreadConfiguration(this)
                .setComponent(component)
                .setThreadName(threadName)
                .buildFactory();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        throwIfNotInPhase(LifecyclePhase.NOT_STARTED);
        phase = LifecyclePhase.STARTED;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        throwIfNotInPhase(LifecyclePhase.STARTED);
        phase = LifecyclePhase.STOPPED;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LifecyclePhase getLifecyclePhase() {
        return phase;
    }
}
