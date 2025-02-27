// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.wires.input;

import com.swirlds.component.framework.model.TraceableWiringModel;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An input wire that doesn't actually do anything. When asked to bind a handler, it does nothing. When asked to insert
 * data, it does nothing.
 *
 * @param <IN>  the type of data that passes into the wire
 * @param <OUT> the type of the primary output wire for the scheduler that is associated with this object
 */
public class NoOpInputWire<IN, OUT> extends BindableInputWire<IN, OUT> {
    /**
     * Constructor.
     *
     * @param model         the wiring model containing this input wire
     * @param taskScheduler the scheduler to insert data into
     * @param name          the name of the input wire
     */
    public NoOpInputWire(
            @NonNull final TraceableWiringModel model,
            @NonNull final TaskScheduler<OUT> taskScheduler,
            @NonNull final String name) {
        super(model, taskScheduler, name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bindConsumer(@NonNull final Consumer<IN> handler) {
        // intentional no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bind(@NonNull final Function<IN, OUT> handler) {
        // intentional no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void put(@NonNull final IN data) {
        // intentional no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean offer(@NonNull final IN data) {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void inject(@NonNull final IN data) {
        // intentional no-op
    }
}
