// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces;

import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * Public interface of the pces module which is responsible for the preconsensus event stream (PCES).
 * It provides functionality to store all validated, ordered events and replay them.
 */
public interface PcesModule {

    /**
     * Initialize the PCES module.
     *
     * @param model the wiring model
     * @param configuration the configuration
     */
    void initialize(
            @NonNull WiringModel model,
            @NonNull Configuration configuration,
            @NonNull Metrics metrics,
            @NonNull Time time,
            @NonNull NodeId selfId,
            @NonNull RecycleBin recycleBin,
            long startingRound,
            @Nullable EventPipelineTracker pipelineTracker);

    /**
     * {@link OutputWire} for events that have been durably written to the preconsensus event stream.
     *
     * @return the {@link OutputWire} for written events
     */
    @NonNull
    OutputWire<PlatformEvent> writtenEventsOutputWire();

    /**
     * {@link InputWire} for events to write to the preconsensus event stream.
     *
     * @return the {@link InputWire} for events to write
     */
    @InputWireLabel("events to write")
    @NonNull
    InputWire<PlatformEvent> eventsToWriteInputWire();

    /**
     * {@link InputWire} for the event window received from the {@code Hashgraph} component.
     *
     * @return the {@link InputWire} for the event window
     */
    @InputWireLabel("event window")
    @NonNull
    InputWire<EventWindow> eventWindowInputWire();

    /**
     * {@link InputWire} for the minimum ancient identifier to store on disk.
     *
     * @return the {@link InputWire} for the minimum ancient identifier
     */
    @InputWireLabel("minimum identifier to store")
    @NonNull
    InputWire<Long> minimumAncientIdentifierInputWire();

    /**
     * {@link InputWire} to signal that the PCES replaying from disk is complete.
     *
     * <p>This is a temporary wire that will be removed once the {@code PcesReplayer} also moves into this module.
     *
     * @return the {@link InputWire} to signal that PCES replaying is done
     */
    @InputWireLabel("done streaming pces")
    @NonNull
    InputWire<NoInput> beginStreamingnewEventsInputWire();

    /**
     * {@link InputWire} for signaling a discontinuity in the preconsensus event stream.
     *
     * <p>This is a temporary wire that will be removed once the {@code PcesReplayer} also moves into this module.
     *
     * @return the {@link InputWire} for discontinuity signals
     */
    @InputWireLabel("discontinuity")
    @NonNull
    InputWire<Long> discontinuityInputWire();

    /**
     * Get an iterator over stored events starting from a given ancient indicator and round.
     *
     * <p>This is a temporary method that will be removed once the {@code PcesReplayer} also moves into this module.
     *
     * @param pcesReplayLowerBound the minimum ancient indicator of events to return, events with lower ancient indicators
     *                             are not returned
     * @param startingRound        the round from which to start returning events
     * @return an iterator over stored events
     */
    @NonNull
    IOIterator<PlatformEvent> storedEvents(final long pcesReplayLowerBound, final long startingRound);

    /**
     * Flushes all events of the internal components.
     */
    void flush();
}
