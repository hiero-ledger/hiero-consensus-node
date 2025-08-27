// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring.components;

import static com.swirlds.component.framework.model.diagram.HyperlinkBuilder.platformCoreHyperlink;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.DIRECT;

import com.swirlds.common.io.IOIterator;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import com.swirlds.platform.event.preconsensus.PcesReplayer;
import com.swirlds.platform.wiring.NoInput;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * The wiring for the {@link PcesReplayer}.
 *
 * @param pcesIteratorInputWire       the input wire for the iterator of events to replay
 * @param doneStreamingPcesOutputWire the output wire which indicates that PCES replay is complete
 * @param eventOutput                 the secondary output wire, for events to be passed into the intake pipeline during
 *                                    replay
 */
public record PcesReplayerWiring(
        @NonNull InputWire<IOIterator<PlatformEvent>> pcesIteratorInputWire,
        @NonNull OutputWire<NoInput> doneStreamingPcesOutputWire,
        @NonNull StandardOutputWire<PlatformEvent> eventOutput) {

    /**
     * Create a new instance of this wiring.
     *
     * @param model the wiring model
     * @return the new wiring instance
     */
    @NonNull
    public static PcesReplayerWiring create(@NonNull final WiringModel model) {
        final TaskScheduler<NoInput> taskScheduler = model.<NoInput>schedulerBuilder("pcesReplayer")
                .withType(DIRECT)
                .withHyperlink(platformCoreHyperlink(PcesReplayer.class))
                .build();

        return new PcesReplayerWiring(
                taskScheduler.buildInputWire("event files to replay"),
                taskScheduler.getOutputWire(),
                taskScheduler.buildSecondaryOutputWire());
    }

    /**
     * Bind the given {@link PcesReplayer} to this wiring.
     *
     * @param pcesReplayer the replayer to bind
     */
    public void bind(@NonNull final PcesReplayer pcesReplayer) {
        ((BindableInputWire<IOIterator<PlatformEvent>, NoInput>) pcesIteratorInputWire).bind(pcesReplayer::replayPces);
    }
}
