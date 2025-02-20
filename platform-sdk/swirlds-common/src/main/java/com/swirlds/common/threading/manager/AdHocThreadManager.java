// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.manager;

import com.swirlds.base.state.LifecyclePhase;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import java.util.concurrent.ThreadFactory;

/**
 * A simple resource manager. The goal of this implementation is to create threads without complaining about lifecycle.
 * Eventually, this implementation should not be used in production code.
 */
public final class AdHocThreadManager implements ThreadManager {

    private static final AdHocThreadManager STATIC_THREAD_MANAGER = new AdHocThreadManager();

    /**
     * Get the static ad hoc thread manager. This manager does not complain about lifecycle violations.
     *
     * @return a thread manager that is always willing to create threads regardless of lifecycle
     */
    public static ThreadManager getStaticThreadManager() {
        return STATIC_THREAD_MANAGER;
    }

    private AdHocThreadManager() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        // intentional no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        // intentional no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Thread createThread(final ThreadGroup threadGroup, final Runnable runnable) {
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
    public LifecyclePhase getLifecyclePhase() {
        return LifecyclePhase.STARTED;
    }
}
