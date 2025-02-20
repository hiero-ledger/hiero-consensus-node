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
import java.util.function.Function;

/**
 * Transforms data on a wire from one type to another.
 *
 * @param <A> the input type
 * @param <B> the output type
 */
public class WireTransformer<A, B> {

    private final BindableInputWire<A, B> inputWire;
    private final OutputWire<B> outputWire;

    /**
     * Constructor. Immediately binds the transformation function to the input wire.
     *
     * @param model                the wiring model containing this output channel
     * @param transformerName      the name of the transformer
     * @param transformerInputName the label for the input wire going into the transformer
     * @param transformer          an object that transforms from type A to type B. If this method returns null then no
     *                             data is forwarded. This method must be very fast. Putting large amounts of work into
     *                             this transformer violates the intended usage pattern of the wiring framework and may
     *                             result in very poor system performance.
     */
    public WireTransformer(
            @NonNull final WiringModel model,
            @NonNull final String transformerName,
            @NonNull final String transformerInputName,
            @NonNull final Function<A, B> transformer) {
        Objects.requireNonNull(transformer);

        final TaskScheduler<B> taskScheduler = model.<B>schedulerBuilder(transformerName)
                .withType(TaskSchedulerType.DIRECT_THREADSAFE)
                .build();

        inputWire = taskScheduler.buildInputWire(transformerInputName);
        inputWire.bind(transformer);
        outputWire = taskScheduler.getOutputWire();
    }

    /**
     * Constructor. Requires the input wire to be bound later.
     *
     * @param model                the wiring model containing this output channel
     * @param transformerName      the name of the transformer
     * @param transformerInputName the label for the input wire going into the transformer
     */
    public WireTransformer(
            @NonNull final WiringModel model,
            @NonNull final String transformerName,
            @NonNull final String transformerInputName) {

        final TaskScheduler<B> taskScheduler = model.<B>schedulerBuilder(transformerName)
                .withType(TaskSchedulerType.DIRECT_THREADSAFE)
                .build();

        inputWire = taskScheduler.buildInputWire(transformerInputName);
        outputWire = taskScheduler.getOutputWire();
    }

    /**
     * Get the input wire for this transformer.
     *
     * @return the input wire
     */
    @NonNull
    public InputWire<A> getInputWire() {
        return inputWire;
    }

    /**
     * Get the output wire for this transformer.
     *
     * @return the output wire
     */
    @NonNull
    public OutputWire<B> getOutputWire() {
        return outputWire;
    }

    /**
     * Bind the transformation function to the input wire. Do not call this if the transformation function was provided
     * in the constructor. Must be called prior to use if the transformation function was not provided in the
     * constructor.
     *
     * @param transformer the transformation function
     */
    public void bind(@NonNull final Function<A, B> transformer) {
        inputWire.bind(transformer);
    }
}
