// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.wires.input;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.NO_OP;

import com.swirlds.component.framework.model.TraceableWiringModel;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * An input wire that can be bound to an implementation.
 *
 * @param <IN>  the type of data that passes into the wire
 * @param <OUT> the type of the primary output wire for the scheduler that is associated with this object
 */
public class BindableInputWire<IN, OUT> extends InputWire<IN> implements Bindable<IN, OUT> {

    private final TaskSchedulerInput<OUT> taskSchedulerInput;
    private final String taskSchedulerName;
    private final TraceableWiringModel model;

    /**
     * Supplier for whether the task scheduler is currently squelching.
     * <p>
     * As long as this supplier returns true, the handler will be executed as a no-op, and no data will be forwarded.
     */
    private final Supplier<Boolean> currentlySquelching;

    /**
     * True if this is a wire on a no-op scheduler.
     */
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
        super(taskScheduler, name);
        this.model = Objects.requireNonNull(model);
        taskSchedulerInput = Objects.requireNonNull(taskScheduler);
        taskSchedulerName = taskScheduler.getName();
        currentlySquelching = taskScheduler::currentlySquelching;

        noOp = taskScheduler.getType() == NO_OP;

        if (noOp) {
            return;
        }
        model.registerInputWireCreation(taskSchedulerName, name);
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    public void bindConsumer(@NonNull final Consumer<IN> handler) {
        Objects.requireNonNull(handler);
        if (noOp) {
            return;
        }
        setHandler(i -> {
            if (currentlySquelching.get()) {
                return;
            }

            handler.accept((IN) i);
        });
        model.registerInputWireBinding(taskSchedulerName, getName());
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    public void bind(@NonNull final Function<IN, OUT> handler) {
        Objects.requireNonNull(handler);
        if (noOp) {
            return;
        }
        setHandler(i -> {
            if (currentlySquelching.get()) {
                return;
            }

            final OUT output = handler.apply((IN) i);
            if (output != null) {
                taskSchedulerInput.forward(output);
            }
        });
        model.registerInputWireBinding(taskSchedulerName, getName());
    }
}
