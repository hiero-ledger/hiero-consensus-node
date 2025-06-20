// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.manager;

import com.swirlds.base.state.Lifecycle;
import com.swirlds.base.state.LifecycleException;
import com.swirlds.base.state.MutabilityException;
import java.util.concurrent.ThreadFactory;

/**
 * Responsible for managing threading resources.
 */
public interface ThreadManager extends Lifecycle {

    /**
     * Create a new thread. Thread is not automatically started.
     *
     * @param threadGroup the thread group into which the thread is placed
     * @param runnable    the runnable that will be executed on the thread
     * @return a new Thread
     * @throws LifecycleException if called before the thread manager has been started
     */
    Thread createThread(ThreadGroup threadGroup, Runnable runnable);

    /**
     * Create a new thread factory. Thread factory will throw {@link MutabilityException MutabilityException} if it is
     * used to create a thread before the thread manager is started.
     *
     * @param component  the component name
     * @param threadName the thread name (each thread created by the factory will also have a unique number appended to
     *                   the thread name)
     * @return a thread factory
     */
    ThreadFactory createThreadFactory(final String component, final String threadName);
}
