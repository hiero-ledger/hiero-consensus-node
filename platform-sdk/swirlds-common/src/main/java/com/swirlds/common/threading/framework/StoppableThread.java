// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework;

/**
 * A thread or a class with a thread that can be stopped.
 */
public interface StoppableThread extends Stoppable {

    /**
     * The current desired status for this thread.
     */
    enum Status {
        NOT_STARTED,
        ALIVE,
        PAUSED,
        DYING,
        DEAD
    }

    /**
     * The name of this thread.
     *
     * @return the name
     */
    String getName();

    /**
     * <p>
     * Build a "seed" that can be planted in a thread. When the runnable is executed, it takes over the calling thread
     * and configures that thread the way it would configure a newly created thread. When work
     * is finished, the calling thread is restored back to its original configuration.
     * </p>
     *
     * <p>
     * Note that this seed will be unable to change the thread group or daemon status of the calling thread,
     * regardless of configuration.
     * </p>
     *
     * <p>
     * Should only be called once. Should not be called if {@link #start()} has been called.
     * </p>
     *
     * @return a seed that can be used to inject this thread configuration onto an existing thread.
     */
    ThreadSeed buildSeed();

    /**
     * <p>
     * Interrupt this thread.
     * </p>
     *
     * <p>
     * If called before the thread/seed is started, then this method blocks until the thread/seed is started.
     * </p>
     */
    boolean interrupt();

    /**
     * Check if this thread is currently alive.
     *
     * @return true if this thread is alive
     */
    boolean isAlive();

    /**
     * Get the current status of the thread.
     *
     * @return the current status
     */
    Status getStatus();

    /**
     * Check if this thread is currently in a hanging state.
     *
     * @return true if this thread is currently in a hanging state
     */
    boolean isHanging();
}
