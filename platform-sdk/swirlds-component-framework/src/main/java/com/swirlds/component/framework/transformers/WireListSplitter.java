// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.transformers;

import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Transforms a list of items to a sequence of individual items. Expects that there will not be any null values in the
 * collection.
 *
 * @param <T> the type of data in the list that is being split
 */
public class WireListSplitter<T> {

    private final BindableInputWire<List<T>, T> inputWire;
    private final StandardOutputWire<T> outputWire;

    /**
     * Constructor.
     *
     * @param model             the wiring model containing this output wire
     * @param splitterName      the name of the output channel
     * @param splitterInputName the label for the input wire going into the splitter
     */
    public WireListSplitter(
            @NonNull final WiringModel model,
            @NonNull final String splitterName,
            @NonNull final String splitterInputName) {
        final TaskScheduler<T> taskScheduler = model.<T>schedulerBuilder(splitterName)
                .withType(TaskSchedulerType.DIRECT_THREADSAFE)
                .build();

        inputWire = taskScheduler.buildInputWire(splitterInputName);
        outputWire = (StandardOutputWire<T>) taskScheduler.getOutputWire();

        inputWire.bindConsumer(list -> {
            for (final T t : list) {
                outputWire.forward(t);
            }
        });
    }

    /**
     * Get the input wire for this splitter.
     *
     * @return the input wire
     */
    @NonNull
    public InputWire<List<T>> getInputWire() {
        return inputWire;
    }

    /**
     * Get the output wire for this splitter.
     *
     * @return the output wire
     */
    @NonNull
    public OutputWire<T> getOutputWire() {
        return outputWire;
    }
}
