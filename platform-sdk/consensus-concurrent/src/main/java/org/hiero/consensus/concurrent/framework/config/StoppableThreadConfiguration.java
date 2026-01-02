// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent.framework.config;

import org.hiero.base.concurrent.interrupt.InterruptableRunnable;
import org.hiero.consensus.concurrent.framework.StoppableThread;
import org.hiero.consensus.concurrent.framework.TypedStoppableThread;
import org.hiero.consensus.concurrent.framework.internal.AbstractStoppableThreadConfiguration;
import org.hiero.consensus.concurrent.manager.ThreadManager;

/**
 * An object responsible for configuring and constructing {@link StoppableThread}s.
 *
 * @param <T>
 * 		the type of instance that will do work
 */
public class StoppableThreadConfiguration<T extends InterruptableRunnable>
        extends AbstractStoppableThreadConfiguration<StoppableThreadConfiguration<T>, T> {

    /**
     * Build a new stoppable thread configuration with default values.
     *
     * @param threadManager
     * 		responsible for creating threads
     */
    public StoppableThreadConfiguration(final ThreadManager threadManager) {
        super(threadManager);
    }

    /**
     * Copy constructor.
     *
     * @param that
     * 		the configuration to copy.
     */
    private StoppableThreadConfiguration(final StoppableThreadConfiguration<T> that) {
        super(that);
    }

    /**
     * Get a copy of this configuration. New copy is always mutable,
     * and the mutability status of the original is unchanged.
     *
     * @return a copy of this configuration
     */
    public StoppableThreadConfiguration<T> copy() {
        return new StoppableThreadConfiguration<>(this);
    }

    /**
     * <p>
     * Build a new thread. Do not start it automatically.
     * </p>
     *
     * <p>
     * After calling this method, this configuration object should not be modified or used to construct other
     * threads.
     * </p>
     *
     * @return a stoppable thread built using this configuration
     */
    public TypedStoppableThread<T> build() {
        return build(false);
    }

    /**
     * <p>
     * Build a new thread.
     * </p>
     *
     * <p>
     * After calling this method, this configuration object should not be modified or used to construct other
     * threads.
     * </p>
     *
     * @param start
     * 		if true then start the thread before returning it
     * @return a stoppable thread built using this configuration
     */
    public TypedStoppableThread<T> build(final boolean start) {
        final TypedStoppableThread<T> thread = buildStoppableThread(start);
        becomeImmutable();
        return thread;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T getWork() {
        return super.getWork();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StoppableThreadConfiguration<T> setWork(final T work) {
        return super.setWork(work);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InterruptableRunnable getFinalCycleWork() {
        return super.getFinalCycleWork();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StoppableThreadConfiguration<T> setFinalCycleWork(final InterruptableRunnable finalCycleWork) {
        return super.setFinalCycleWork(finalCycleWork);
    }
}
