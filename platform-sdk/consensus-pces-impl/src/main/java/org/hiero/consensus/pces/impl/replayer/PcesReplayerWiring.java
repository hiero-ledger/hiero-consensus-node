// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces.impl.replayer;

import static org.hiero.consensus.wiring.framework.model.diagram.HyperlinkBuilder.platformCoreHyperlink;
import static org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerType.DIRECT;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.schedulers.TaskScheduler;
import org.hiero.consensus.wiring.framework.wires.input.BindableInputWire;
import org.hiero.consensus.wiring.framework.wires.input.InputWire;
import org.hiero.consensus.wiring.framework.wires.input.NoInput;
import org.hiero.consensus.wiring.framework.wires.output.OutputWire;
import org.hiero.consensus.wiring.framework.wires.output.StandardOutputWire;

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
