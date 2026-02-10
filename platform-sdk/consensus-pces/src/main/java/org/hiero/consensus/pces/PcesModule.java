// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces;

import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.state.signed.ReservedSignedState;

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
            @NonNull final Runnable flushIntake,
            @NonNull final Runnable flushTransactionHandling,
            @NonNull final Supplier<ReservedSignedState> latestImmutableStateSupplier,
            @Nullable EventPipelineTracker pipelineTracker);

    /**
     * {@link OutputWire} for events that have been replayed from the preconsensus event stream.
     *
     * @return the {@link OutputWire} for replayed events
     */
    @NonNull
    OutputWire<PlatformEvent> replayedEventsOutputWire();

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
     * {@link InputWire} for the minimum birth round to store on disk.
     *
     * @return the {@link InputWire} for the minimum birth round
     */
    @InputWireLabel("minimum birth round to store")
    @NonNull
    InputWire<Long> minimumBirthRoundInputWire();

    /**
     * {@link InputWire} for the iterator of events to replay from the preconsensus event stream.
     *
     * <p>This is a temporary wire that will be removed once the {@code PcesCoordinator} also moves into this module.
     *
     * @return the {@link InputWire} for the iterator of events to replay
     */
    @NonNull
    InputWire<IOIterator<PlatformEvent>> eventsToReplayInputWire();

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
     * Get an iterator over stored events starting from a given birth round and round.
     *
     * <p>This is a temporary method that will be removed once the {@code PcesReplayer} also moves into this module.
     *
     * @param pcesReplayLowerBound the minimum birth round of events to return, events with lower birth rounds are not returned
     * @param startingRound        the round from which to start returning events
     * @return an iterator over stored events
     */
    @NonNull
    IOIterator<PlatformEvent> storedEvents(final long pcesReplayLowerBound, final long startingRound);

    /**
     * Flushes all events of the internal components.
     */
    void flush();

    /**
     * Copy all PCES files with events that have a birth round greater than or equal to the given lower bound and
     * that are from rounds greater than or equal to the given round, to the given destination directory.
     *
     * @param configuration the configuration
     * @param selfId the ID of this node
     * @param destinationDirectory the directory to copy files to
     * @param lowerBound the minimum birth round of events to copy, events with lower birth round are not copied
     * @param round the round of the state that is being written
     */
    void copyPcesFilesRetryOnFailure(
            @NonNull Configuration configuration,
            @NonNull NodeId selfId,
            @NonNull Path destinationDirectory,
            long lowerBound,
            long round);
}
