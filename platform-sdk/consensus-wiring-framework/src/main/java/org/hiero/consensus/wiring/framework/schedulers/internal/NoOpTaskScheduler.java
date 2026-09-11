// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.wiring.framework.schedulers.internal;

import static org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerBuilder.UNLIMITED_CAPACITY;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Consumer;
import org.hiero.consensus.wiring.framework.model.TraceableWiringModel;
import org.hiero.consensus.wiring.framework.schedulers.ExceptionHandlers;
import org.hiero.consensus.wiring.framework.schedulers.TaskScheduler;
import org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerType;
import org.hiero.consensus.wiring.framework.wires.input.BindableInputWire;
import org.hiero.consensus.wiring.framework.wires.input.NoOpInputWire;
import org.hiero.consensus.wiring.framework.wires.output.NoOpOutputWire;
import org.hiero.consensus.wiring.framework.wires.output.StandardOutputWire;

/**
 * A no-op task scheduler that does nothing.
 *
 * @param <OUT> the output type of the scheduler (use {@link Void} for a task scheduler with no output type). This is
 *              just to appease the compiler, as this scheduler never produces output.
 */
public class NoOpTaskScheduler<OUT> extends TaskScheduler<OUT> {
    private final TraceableWiringModel model;

    /**
     * Constructor.
     *
     * @param model             the wiring model containing this task scheduler
     * @param name              the name of the task scheduler
     * @param type              the type of task scheduler
     * @param flushEnabled      if true, then {@link #flush()} will be enabled, otherwise it will throw.
     * @param squelchingEnabled if true, then squelching will be enabled, otherwise trying to squelch will throw.
     */
    public NoOpTaskScheduler(
            @NonNull final TraceableWiringModel model,
            @NonNull final String name,
            @NonNull final TaskSchedulerType type,
            final boolean flushEnabled,
            final boolean squelchingEnabled) {
        super(model, name, type, ExceptionHandlers.NOOP_UNCAUGHT_EXCEPTION, flushEnabled, squelchingEnabled, false);

        this.model = Objects.requireNonNull(model);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getUnprocessedTaskCount() {
        return 0;
    }

    @Override
    public long getCapacity() {
        // No op schedulers have no concept of capacity
        return UNLIMITED_CAPACITY;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() {
        throwIfFlushDisabled();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void put(@NonNull final Consumer<Object> handler, @NonNull final Object data) {
        throw new UnsupportedOperationException(
                "Data should have been discarded before being sent to this no-op scheduler");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean offer(@NonNull final Consumer<Object> handler, @NonNull final Object data) {
        throw new UnsupportedOperationException(
                "Data should have been discarded before being sent to this no-op scheduler");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void inject(@NonNull final Consumer<Object> handler, @NonNull final Object data) {
        throw new UnsupportedOperationException(
                "Data should have been discarded before being sent to this no-op scheduler");
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    protected StandardOutputWire<OUT> buildPrimaryOutputWire(
            @NonNull final TraceableWiringModel model, @NonNull final String name) {
        return new NoOpOutputWire<>(model, getName());
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public <T> StandardOutputWire<T> buildSecondaryOutputWire() {
        return new NoOpOutputWire<>(model, getName());
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public <I> BindableInputWire<I, OUT> buildInputWire(@NonNull final String name) {
        return new NoOpInputWire<>(model, this, name);
    }
}
