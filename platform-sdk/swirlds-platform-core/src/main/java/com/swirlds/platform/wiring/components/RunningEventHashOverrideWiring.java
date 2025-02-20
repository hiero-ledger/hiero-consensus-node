// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring.components;

import static com.swirlds.component.framework.model.diagram.HyperlinkBuilder.platformCoreHyperlink;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.DIRECT_THREADSAFE;

import com.swirlds.common.stream.RunningEventHashOverride;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A wiring object for distributing {@link RunningEventHashOverride}s
 *
 * @param runningHashUpdateInput  the input wire for running hash updates to be distributed
 * @param runningHashUpdateOutput the output wire for running hash updates to be distributed
 */
public record RunningEventHashOverrideWiring(
        @NonNull InputWire<RunningEventHashOverride> runningHashUpdateInput,
        @NonNull OutputWire<RunningEventHashOverride> runningHashUpdateOutput) {

    /**
     * Create a new wiring object
     *
     * @param model the wiring model
     * @return the new wiring object
     */
    @NonNull
    public static RunningEventHashOverrideWiring create(@NonNull final WiringModel model) {

        final TaskScheduler<RunningEventHashOverride> taskScheduler = model.<RunningEventHashOverride>schedulerBuilder(
                        "RunningEventHashOverride")
                .withType(DIRECT_THREADSAFE)
                .withHyperlink(platformCoreHyperlink(RunningEventHashOverrideWiring.class))
                .build();

        final BindableInputWire<RunningEventHashOverride, RunningEventHashOverride> inputWire =
                taskScheduler.buildInputWire("hash override");
        final RunningEventHashOverrideWiring wiring =
                new RunningEventHashOverrideWiring(inputWire, taskScheduler.getOutputWire());

        // this is just a pass through method
        inputWire.bind(runningHashUpdate -> runningHashUpdate);

        return wiring;
    }
}
