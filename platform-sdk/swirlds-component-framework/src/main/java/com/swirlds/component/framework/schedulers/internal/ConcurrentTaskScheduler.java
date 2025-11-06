// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers.internal;

import com.swirlds.component.framework.counters.ObjectCounter;
import com.swirlds.component.framework.model.TraceableWiringModel;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

/**
 * A {@link TaskScheduler} that permits parallel execution of tasks.
 *
 * @param <OUT> the output type of the scheduler (use {@link Void} for a task scheduler with no output type)
 */
public class ConcurrentTaskScheduler<OUT> extends TaskScheduler<OUT> {

    private final ObjectCounter onRamp;
    private final ObjectCounter offRamp;
    private final ForkJoinPool pool;
    private final long capacity;

    /**
     * Constructor.
     *
     * @param model                    the wiring model containing this scheduler
     * @param name                     the name of the scheduler
     * @param pool                     the fork join pool that will execute tasks on this scheduler
     * @param uncaughtExceptionHandler the handler for uncaught exceptions
     * @param onRamp                   an object counter that is incremented when data is added to the scheduler
     * @param offRamp                  an object counter that is decremented when data is removed from the scheduler
     * @param capacity                 the maximum desired capacity for this scheduler
     * @param flushEnabled             if true, then {@link #flush()} will be enabled, otherwise it will throw.
     * @param squelchingEnabled        if true, then squelching will be enabled, otherwise trying to squelch will throw
     * @param insertionIsBlocking      when data is inserted into this scheduler, will it block until capacity is
     *                                 available?
     */
    public ConcurrentTaskScheduler(
            @NonNull final TraceableWiringModel model,
            @NonNull final String name,
            @NonNull final ForkJoinPool pool,
            @NonNull final UncaughtExceptionHandler uncaughtExceptionHandler,
            @NonNull final ObjectCounter onRamp,
            @NonNull final ObjectCounter offRamp,
            final long capacity,
            final boolean flushEnabled,
            final boolean squelchingEnabled,
            final boolean insertionIsBlocking) {

        super(
                model,
                name,
                TaskSchedulerType.CONCURRENT,
                uncaughtExceptionHandler,
                flushEnabled,
                squelchingEnabled,
                insertionIsBlocking);

        this.pool = Objects.requireNonNull(pool);
        this.onRamp = Objects.requireNonNull(onRamp);
        this.offRamp = Objects.requireNonNull(offRamp);
        this.capacity = capacity;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void put(@NonNull final Consumer<Object> handler, @NonNull final Object data) {
        onRamp.onRamp();
        new ConcurrentTask(pool, offRamp, getUncaughtExceptionHandler(), handler, data).send();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean offer(@NonNull final Consumer<Object> handler, @NonNull final Object data) {
        final boolean accepted = onRamp.attemptOnRamp();
        if (accepted) {
            new ConcurrentTask(pool, offRamp, getUncaughtExceptionHandler(), handler, data).send();
        }
        return accepted;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void inject(@NonNull final Consumer<Object> handler, @NonNull final Object data) {
        onRamp.forceOnRamp();
        new ConcurrentTask(pool, offRamp, getUncaughtExceptionHandler(), handler, data).send();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getUnprocessedTaskCount() {
        return onRamp.getCount();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getCapacity() {
        return capacity;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() {
        throwIfFlushDisabled();
        onRamp.waitUntilEmpty();
    }
}
