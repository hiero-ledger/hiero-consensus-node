// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers.internal;

import com.swirlds.component.framework.counters.ObjectCounter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import org.hiero.base.concurrent.AbstractTask;

/**
 * A task in a {@link ConcurrentTaskScheduler}.
 */
class ConcurrentTask extends AbstractTask {

    private final Consumer<Object> handler;
    private final Object data;
    private final ObjectCounter offRamp;
    private final UncaughtExceptionHandler uncaughtExceptionHandler;

    /**
     * Constructor. The task is created with zero dependencies, but not started automatically. It's
     * the caller responsibility to start the task using {@link #send()} method.
     *
     * @param pool                     the fork join pool that will execute this task
     * @param offRamp                  an object counter that is decremented when this task is executed
     * @param uncaughtExceptionHandler the handler for uncaught exceptions
     * @param handler                  the method that will be called when this task is executed
     * @param data                     the data to be passed to the consumer for this task
     */
    ConcurrentTask(
            @NonNull final ForkJoinPool pool,
            @NonNull final ObjectCounter offRamp,
            @NonNull final UncaughtExceptionHandler uncaughtExceptionHandler,
            @NonNull final Consumer<Object> handler,
            @Nullable final Object data) {
        super(pool, 0);
        this.handler = handler;
        this.data = data;
        this.offRamp = offRamp;
        this.uncaughtExceptionHandler = uncaughtExceptionHandler;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean onExecute() {
        try {
            handler.accept(data);
            return true;
        } finally {
            offRamp.offRamp();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onException(final Throwable t) {
        uncaughtExceptionHandler.uncaughtException(Thread.currentThread(), t);
    }
}
