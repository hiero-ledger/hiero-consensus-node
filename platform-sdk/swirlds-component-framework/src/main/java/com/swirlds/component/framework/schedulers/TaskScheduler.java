// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers;

import com.swirlds.component.framework.counters.ObjectCounter;
import com.swirlds.component.framework.model.TraceableWiringModel;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerBuilder;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.schedulers.internal.DefaultSquelcher;
import com.swirlds.component.framework.schedulers.internal.Squelcher;
import com.swirlds.component.framework.schedulers.internal.ThrowingSquelcher;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.input.TaskSchedulerInput;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Schedules tasks for a component.
 * <p>
 * The lifecycle of a task is as follows:
 * <ol>
 * <li>Unscheduled: the task has not been passed to the scheduler yet (e.g. via {@link InputWire#put(Object)})</li>
 * <li>Scheduled but not processed: the task has been passed to the scheduler but the corresponding handler has not
 * yet returned (either because the handler has not yet been called or because the handler has been called but hasn't
 * finished yet)</li>
 * <li>Processed: the corresponding handle method for the task has been called and has returned.</li>
 * </ol>
 *
 * @param <OUT> the output type of the primary output wire (use {@link Void} if no output is needed)
 */
public abstract class TaskScheduler<OUT> extends TaskSchedulerInput<OUT> {

    private final boolean flushEnabled;
    private final TraceableWiringModel model;
    private final String name;
    private final TaskSchedulerType type;
    private final UncaughtExceptionHandler uncaughtExceptionHandler;
    private final StandardOutputWire<OUT> primaryOutputWire;
    private final boolean insertionIsBlocking;

    /**
     * Handles squelching for this task scheduler. Will be a valid object whether or not squelching is enabled for this
     * task scheduler.
     */
    private final Squelcher squelcher;

    /**
     * Constructor.
     *
     * @param model                    the wiring model containing this task scheduler
     * @param name                     the name of the task scheduler
     * @param type                     the type of task scheduler
     * @param uncaughtExceptionHandler the handler for uncaught exceptions
     * @param flushEnabled             if true, then {@link #flush()} will be enabled, otherwise it will throw.
     * @param squelchingEnabled        if true, then squelching will be enabled, otherwise trying to squelch will
     *                                 throw.
     * @param insertionIsBlocking      when data is inserted into this task scheduler, will it block until capacity is
     *                                 available?
     */
    protected TaskScheduler(
            @NonNull final TraceableWiringModel model,
            @NonNull final String name,
            @NonNull final TaskSchedulerType type,
            @NonNull final UncaughtExceptionHandler uncaughtExceptionHandler,
            final boolean flushEnabled,
            final boolean squelchingEnabled,
            final boolean insertionIsBlocking) {

        this.model = Objects.requireNonNull(model);
        this.name = Objects.requireNonNull(name);
        this.type = Objects.requireNonNull(type);
        this.uncaughtExceptionHandler = Objects.requireNonNull(uncaughtExceptionHandler);
        this.flushEnabled = flushEnabled;

        if (squelchingEnabled) {
            this.squelcher = new DefaultSquelcher();
        } else {
            this.squelcher = new ThrowingSquelcher();
        }

        primaryOutputWire = buildPrimaryOutputWire(model, name);
        this.insertionIsBlocking = insertionIsBlocking;
    }

    /**
     * Build an input wire for passing data to this task scheduler. In order to use this wire, a handler must be bound
     * via {@link BindableInputWire#bind(Function)} {@link BindableInputWire#bindConsumer(Consumer)}.
     *
     * @param name the name of the input wire
     * @param <I>  the type of data that is inserted via this input wire
     * @return the input wire
     */
    @NonNull
    public <I> BindableInputWire<I, OUT> buildInputWire(@NonNull final String name) {
        return new BindableInputWire<>(model, this, name);
    }

    /**
     * Build the primary output wire for this scheduler.
     *
     * @param model the wiring model that contains this scheduler
     * @param name  the name of this scheduler
     * @return the primary output wire
     */
    @NonNull
    protected StandardOutputWire<OUT> buildPrimaryOutputWire(
            @NonNull final TraceableWiringModel model, @NonNull final String name) {
        return new StandardOutputWire<>(model, name, getUncaughtExceptionHandler());
    }

    /**
     * Get the default output wire for this task scheduler. Sometimes referred to as the "primary" output wire. All data
     * returned by handlers is passed ot this output wire. Calling this method more than once will always return the
     * same object.
     *
     * @return the primary output wire
     */
    @NonNull
    public OutputWire<OUT> getOutputWire() {
        return primaryOutputWire;
    }

    /**
     * By default a component has a single output wire (i.e. the primary output wire). This method allows additional
     * output wires to be created.
     *
     * <p>
     * Unlike primary wires, secondary output wires need to be passed to a component's constructor. It is considered a
     * violation of convention to push data into a secondary output wire from any code that is not executing within this
     * task scheduler.
     *
     * @param <T> the type of data that is transmitted over this output wire
     * @return the secondary output wire
     */
    @NonNull
    public <T> StandardOutputWire<T> buildSecondaryOutputWire() {
        // Intentionally do not register this with the model. Connections using this output wire will be represented
        // in the model in the same way as connections to the primary output wire.
        return new StandardOutputWire<>(model, name, getUncaughtExceptionHandler());
    }

    /**
     * Get the name of this task scheduler.
     *
     * @return the name of this task scheduler
     */
    @NonNull
    public String getName() {
        return name;
    }

    /**
     * Get the type of this task scheduler.
     *
     * @return the type of this task scheduler
     */
    @NonNull
    public TaskSchedulerType getType() {
        return type;
    }

    /**
     * Return the handler for uncaught exceptions
     *
     * @return the handler for uncaught exceptions
     */
    @NonNull
    protected UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return uncaughtExceptionHandler;
    }

    /**
     * Get whether or not this task scheduler can block when data is inserted into it.
     *
     * @return true if this task scheduler can block when data is inserted into it, false otherwise
     */
    public boolean isInsertionBlocking() {
        return insertionIsBlocking;
    }

    /**
     * Get the number of unprocessed tasks. A task is considered to be unprocessed until the data has been passed to the
     * handler method (i.e. the one given to {@link BindableInputWire#bind(Function)} or
     * {@link BindableInputWire#bindConsumer(Consumer)}) and that handler method has returned.
     * <p>
     * Returns {@link ObjectCounter#COUNT_UNDEFINED} if this task scheduler is not monitoring the number of unprocessed
     * tasks. Schedulers do not track the number of unprocessed tasks by default. This method will always return
     * {@link ObjectCounter#COUNT_UNDEFINED} unless one of the following is true:
     * <ul>
     * <li>{@link TaskSchedulerBuilder#withUnhandledTaskMetricEnabled(boolean)} is called with the value
     * true</li>
     * <li>{@link TaskSchedulerBuilder#withUnhandledTaskCapacity(long)} is passed a positive value</li>
     * <li>{@link TaskSchedulerBuilder#withOnRamp(ObjectCounter)} is passed a counter that is not a no op counter</li>
     * </ul>
     */
    public abstract long getUnprocessedTaskCount();

    /**
     * Get this task scheduler's desired maximum desired capacity. If {@link TaskSchedulerBuilder#UNLIMITED_CAPACITY} is
     * returned, then this task scheduler does not have a maximum capacity.
     *
     * @return the maximum desired capacity of this task scheduler
     */
    public abstract long getCapacity();

    /**
     * Flush all data in the task scheduler. Blocks until all data currently in flight has been processed.
     *
     * <p>
     * Note: must be enabled by passing true to {@link TaskSchedulerBuilder#withFlushingEnabled(boolean)}.
     *
     * <p>
     * Warning: some implementations of flush may block indefinitely if new work is continuously added to the scheduler
     * while flushing. Such implementations are guaranteed to finish flushing once new work is no longer being added.
     * Some implementations do not have this restriction, and will return as soon as all of the in flight work has been
     * processed, regardless of whether or not new work is being added.
     *
     * @throws UnsupportedOperationException if {@link TaskSchedulerBuilder#withFlushingEnabled(boolean)} was set to
     *                                       false (or was unset, default is false)
     */
    public abstract void flush();

    /**
     * Throw an {@link UnsupportedOperationException} if flushing is not enabled.
     */
    protected final void throwIfFlushDisabled() {
        if (!flushEnabled) {
            throw new UnsupportedOperationException("Flushing is not enabled for the task scheduler " + name);
        }
    }

    /**
     * Start squelching, and continue doing so until {@link #stopSquelching()} is called.
     *
     * @throws UnsupportedOperationException if squelching is not supported by this scheduler
     * @throws IllegalStateException         if scheduler is already squelching
     */
    public void startSquelching() {
        squelcher.startSquelching();
    }

    /**
     * Stop squelching.
     *
     * @throws UnsupportedOperationException if squelching is not supported by this scheduler
     * @throws IllegalStateException         if scheduler is not currently squelching
     */
    public void stopSquelching() {
        squelcher.stopSquelching();
    }

    /**
     * Get whether or not this task scheduler is currently squelching.
     *
     * @return true if this task scheduler is currently squelching, false otherwise
     */
    public final boolean currentlySquelching() {
        return squelcher.shouldSquelch();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void forward(@NonNull final OUT data) {
        primaryOutputWire.forward(data);
    }
}
