// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring.components;

import static org.hiero.consensus.wiring.framework.model.diagram.HyperlinkBuilder.platformCoreHyperlink;
import static org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerType.DIRECT_THREADSAFE;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.stream.RunningEventHashOverride;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.schedulers.TaskScheduler;
import org.hiero.consensus.wiring.framework.wires.input.BindableInputWire;
import org.hiero.consensus.wiring.framework.wires.input.InputWire;
import org.hiero.consensus.wiring.framework.wires.output.OutputWire;

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
