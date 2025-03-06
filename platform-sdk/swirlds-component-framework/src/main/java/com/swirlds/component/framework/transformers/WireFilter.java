// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.transformers;

import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Filters out data, allowing some objects to pass and blocking others.
 *
 * @param <T> the type of data being filtered
 */
public class WireFilter<T> {

    private final BindableInputWire<T, T> inputWire;
    private final OutputWire<T> outputWire;

    /**
     * Constructor. Immediately binds the transformation function to the input wire.
     *
     * @param model           the wiring model containing this output channel
     * @param filterName      the name of the filter
     * @param filterInputName the label for the input wire going into the filter
     * @param predicate       only data that causes this method to return true is forwarded. This method must be very
     *                        fast. Putting large amounts of work into this transformer violates the intended usage
     *                        pattern of the wiring framework and may result in very poor system performance.
     */
    public WireFilter(
            @NonNull final WiringModel model,
            @NonNull final String filterName,
            @NonNull final String filterInputName,
            @NonNull final Predicate<T> predicate) {

        Objects.requireNonNull(predicate);

        final TaskScheduler<T> taskScheduler = model.<T>schedulerBuilder(filterName)
                .withType(TaskSchedulerType.DIRECT_THREADSAFE)
                .build();

        inputWire = taskScheduler.buildInputWire(filterInputName);
        inputWire.bind(t -> {
            if (predicate.test(t)) {
                return t;
            }
            return null;
        });
        outputWire = taskScheduler.getOutputWire();
    }

    /**
     * Constructor.
     *
     * @param model           the wiring model containing this output channel
     * @param filterName      the name of the filter
     * @param filterInputName the label for the input wire going into the filter
     */
    public WireFilter(
            @NonNull final WiringModel model, @NonNull final String filterName, @NonNull final String filterInputName) {

        final TaskScheduler<T> taskScheduler = model.<T>schedulerBuilder(filterName)
                .withType(TaskSchedulerType.DIRECT_THREADSAFE)
                .build();

        inputWire = taskScheduler.buildInputWire(filterInputName);
        outputWire = taskScheduler.getOutputWire();
    }

    /**
     * Get the input wire for this filter.
     *
     * @return the input wire
     */
    @NonNull
    public InputWire<T> getInputWire() {
        return inputWire;
    }

    /**
     * Get the output wire for this filter.
     *
     * @return the output wire
     */
    @NonNull
    public OutputWire<T> getOutputWire() {
        return outputWire;
    }

    /**
     * Bind a predicate to this filter. Should not be called if this object was constructed using
     * {@link #WireFilter(WiringModel, String, String, Predicate)}. Must be called prior to use if this object was
     * constructed using {@link #WireFilter(WiringModel, String, String)}.
     *
     * @param predicate the predicate to bind
     */
    public void bind(@NonNull final Predicate<T> predicate) {
        inputWire.bind(t -> {
            if (predicate.test(t)) {
                return t;
            }
            return null;
        });
    }
}
