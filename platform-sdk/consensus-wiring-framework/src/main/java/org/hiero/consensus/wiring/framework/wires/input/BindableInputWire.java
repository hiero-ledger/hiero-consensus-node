// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.wiring.framework.wires.input;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerType.NO_OP;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import java.util.function.Function;
import org.hiero.consensus.wiring.framework.model.TraceableWiringModel;
import org.hiero.consensus.wiring.framework.schedulers.TaskScheduler;
import org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerType;

/**
 * An input wire that can be bound to an implementation.
 *
 * @param <IN>  the type of data that passes into the wire
 * @param <OUT> the type of the primary output wire for the scheduler that is associated with this object
 */
public class BindableInputWire<IN, OUT> implements InputWire<IN> {

    private final TraceableWiringModel model;
    private final TaskScheduler<OUT> taskScheduler;
    private final String name;

    private Consumer<Object> handler;

    /** True if this is a wire on a no-op scheduler. */
    private final boolean noOp;

    /**
     * Constructor.
     *
     * @param model         the wiring model containing this input wire
     * @param taskScheduler the scheduler to insert data into
     * @param name          the name of the input wire
     */
    public BindableInputWire(
            @NonNull final TraceableWiringModel model,
            @NonNull final TaskScheduler<OUT> taskScheduler,
            @NonNull final String name) {
        this.model = requireNonNull(model);
        this.taskScheduler = requireNonNull(taskScheduler);
        this.name = requireNonNull(name);

        noOp = taskScheduler.getType() == NO_OP;

        if (noOp) {
            return;
        }
        model.registerInputWireCreation(taskScheduler.getName(), name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getName() {
        return name;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getTaskSchedulerName() {
        return taskScheduler.getName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public TaskSchedulerType getTaskSchedulerType() {
        return taskScheduler.getType();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void put(@NonNull final IN data) {
        taskScheduler.put(handler, data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean offer(@NonNull final IN data) {
        return taskScheduler.offer(handler, data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void inject(@NonNull final IN data) {
        taskScheduler.inject(handler, data);
    }

    /**
     * Bind this object to a handler. For things that don't send data to the output wire.
     *
     * @param handler the handler to bind to this input wire
     * @throws IllegalStateException if a handler is already bound and this method is called a second time
     */
    @SuppressWarnings("unchecked")
    public void bindConsumer(@NonNull final Consumer<IN> handler) {
        requireNonNull(handler);
        if (noOp) {
            return;
        }
        setHandler(i -> {
            if (taskScheduler.currentlySquelching()) {
                return;
            }

            handler.accept((IN) i);
        });
        model.registerInputWireBinding(taskScheduler.getName(), getName());
    }

    /**
     * Bind this object to a handler.
     *
     * @param handler the handler to bind to this input task scheduler, values returned are passed to the primary output
     *                wire of the associated scheduler.
     * @throws IllegalStateException if a handler is already bound and this method is called a second time
     */
    @SuppressWarnings("unchecked")
    public void bind(@NonNull final Function<IN, OUT> handler) {
        requireNonNull(handler);
        if (noOp) {
            return;
        }
        setHandler(i -> {
            if (taskScheduler.currentlySquelching()) {
                return;
            }

            final OUT output = handler.apply((IN) i);
            if (output != null) {
                // The cast is a little trick that makes forward() accessible
                ((TaskSchedulerInput<OUT>) taskScheduler).forward(output);
            }
        });
        model.registerInputWireBinding(taskScheduler.getName(), getName());
    }

    /**
     * Set the method that will handle data traveling over this wire.
     *
     * @param handler the method that will handle data traveling over this wire
     */
    @SuppressWarnings("VariableNotUsedInsideIf")
    private void setHandler(@NonNull final Consumer<Object> handler) {
        if (this.handler != null) {
            throw new IllegalStateException("Handler already bound");
        }
        this.handler = requireNonNull(handler);
    }
}
