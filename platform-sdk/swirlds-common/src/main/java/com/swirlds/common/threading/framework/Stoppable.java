// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework;

import com.swirlds.base.state.Startable;

/**
 * Describes a thread or a collection of threads that can be started, stopped, paused, resumed, and joined.
 */
public interface Stoppable extends Startable {
    /**
     * The behavior of this thread when it is stopped
     */
    enum StopBehavior {
        /**
         * The thread will block indefinitely, until it is done stopping
         */
        BLOCKING,
        /**
         * The thread will be interrupted if stopping takes too long
         */
        INTERRUPTABLE
    }

    /**
     * Start the thread(s).
     */
    @Override
    void start();

    /**
     * <p>Calls {@link #stop(StopBehavior)} with the default {@link StopBehavior StopBehavior} defined on the object</p>
     *
     * @return true if operation was successful, or false if the thread is in the incorrect state to be stopped
     */
    boolean stop();

    /**
     * <p>
     * Attempt to gracefully stop the thread.
     * </p>
     *
     * <p>
     * Should not be called before the thread has been started, or in the case of a seed before
     * the seed has been built.
     * </p>
     *
     * @param behavior
     * 		the type of {@link StopBehavior} that should be used
     * @return true if operation was successful, or false if the thread is in the incorrect state to be stopped
     */
    boolean stop(StopBehavior behavior);

    /**
     * <p>
     * Causes the thread to finish its current work and to then block. Thread remains blocked
     * until {@link #resume()} is called.
     * </p>
     *
     * <p>
     * This method must not be called unless the thread is alive and {@link #resume()} has already
     * been called and has returned.
     * </p>
     *
     * @return true if operation was successful, or false if the thread is in the incorrect state to be paused
     */
    boolean pause();

    /**
     * <p>
     * This method can be called to resume work on the thread after a {@link #pause()} call.
     * </p>
     *
     * <p>
     * This method should only be called if the thread is paused.
     * </p>
     *
     * @return true if operation was successful, or false if the thread is in the incorrect state to be resumed
     */
    boolean resume();

    /**
     * Joins the thread. Equivalent to {@link Thread#join()}. If this thread was injected, join
     * waits for the stoppable thread to be finished, but does not wait for the thread itself to die.
     *
     * @throws InterruptedException
     * 		if interrupted before join is complete
     */
    void join() throws InterruptedException;

    /**
     * Join the thread. Equivalent to {@link Thread#join(long)}.  If this thread was injected, join
     * waits for the stoppable thread to be finished, but does not wait for the thread itself to die.
     *
     * @throws InterruptedException
     * 		if interrupted before join is complete
     */
    void join(long millis) throws InterruptedException;

    /**
     * Join the thread. Equivalent to {@link Thread#join(long, int)}. If this thread was injected, join
     * waits for the stoppable thread to be finished, but does not wait for the thread itself to die.
     *
     * @throws InterruptedException
     * 		if interrupted before join is complete
     */
    void join(long millis, int nanos) throws InterruptedException;
}
