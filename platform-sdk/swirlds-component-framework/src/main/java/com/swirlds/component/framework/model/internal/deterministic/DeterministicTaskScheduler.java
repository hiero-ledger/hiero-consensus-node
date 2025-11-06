// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.deterministic;

import com.swirlds.component.framework.counters.ObjectCounter;
import com.swirlds.component.framework.model.TraceableWiringModel;
import com.swirlds.component.framework.schedulers.ExceptionHandlers;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A deterministic implementation of a task scheduler.
 *
 * @param <OUT> the output type of the scheduler (use {@link Void} for a task scheduler with no output type)
 */
public class DeterministicTaskScheduler<OUT> extends TaskScheduler<OUT> {
    private final Consumer<Runnable> submitWork;

    private final ObjectCounter onRamp;
    private final ObjectCounter offRamp;
    private final long capacity;

    /**
     * Constructor.
     *
     * @param model               the wiring model containing this task scheduler
     * @param name                the name of the task scheduler
     * @param type                the type of task scheduler
     * @param onRamp              counts when things are added to this scheduler to be eventually handled
     * @param offRamp             counts when things are handled by this scheduler
     * @param capacity            the maximum desired capacity for this task scheduler
     * @param flushEnabled        if true, then {@link #flush()} will be enabled, otherwise it will throw.
     * @param squelchingEnabled   if true, then squelching will be enabled, otherwise trying to squelch will throw.
     * @param insertionIsBlocking when data is inserted into this task scheduler, will it block until capacity is
     *                            available?
     * @param submitWork          a method where all work should be submitted
     */
    protected DeterministicTaskScheduler(
            @NonNull final TraceableWiringModel model,
            @NonNull final String name,
            @NonNull final TaskSchedulerType type,
            @NonNull final ObjectCounter onRamp,
            @NonNull final ObjectCounter offRamp,
            final long capacity,
            final boolean flushEnabled,
            final boolean squelchingEnabled,
            final boolean insertionIsBlocking,
            @NonNull final Consumer<Runnable> submitWork) {
        super(
                model,
                name,
                type,
                ExceptionHandlers.RETHROW_UNCAUGHT_EXCEPTION,
                flushEnabled,
                squelchingEnabled,
                insertionIsBlocking);

        this.onRamp = Objects.requireNonNull(onRamp);
        this.offRamp = Objects.requireNonNull(offRamp);
        this.submitWork = Objects.requireNonNull(submitWork);
        this.capacity = capacity;
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
        // Future work: flushing is incompatible with deterministic task schedulers.
        // This is because flushing currently requires us to block thread A while
        // thread B does work, but with turtle there is only a single thread.
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void put(@NonNull final Consumer<Object> handler, @NonNull final Object data) {
        onRamp.onRamp();
        submitWork.accept(() -> {
            handler.accept(data);
            offRamp.offRamp();
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean offer(@NonNull final Consumer<Object> handler, @NonNull final Object data) {
        if (onRamp.attemptOnRamp()) {
            submitWork.accept(() -> {
                handler.accept(data);
                offRamp.offRamp();
            });
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void inject(@NonNull final Consumer<Object> handler, @NonNull final Object data) {
        onRamp.forceOnRamp();
        submitWork.accept(() -> {
            handler.accept(data);
            offRamp.offRamp();
        });
    }
}
