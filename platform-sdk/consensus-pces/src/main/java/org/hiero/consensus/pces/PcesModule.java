// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.status.monitor.StatusMonitorModule;
import org.hiero.consensus.wiring.framework.component.InputWireLabel;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.wires.input.InputWire;
import org.hiero.consensus.wiring.framework.wires.output.OutputWire;

/**
 * Public interface of the pces module which is responsible for the preconsensus event stream (PCES). It provides
 * functionality to store all validated, ordered events and replay them.
 */
public interface PcesModule {

    /**
     * Initialize the PCES module.
     *
     * @param model the wiring model
     * @param configuration the configuration
     * @param metrics the metrics system
     * @param time the time source
     * @param selfId the ID of this node
     * @param recycleBin the recycle bin for deleting old PCES files
     * @param fileSystemManager the file system manager for managing file locations on disk
     * @param startingRound the round from which to start replaying events
     * @param flushPrimaryPipeline a {@link Runnable} that triggers flushing of PCES events to the required modules before resuming normal operations
     * @param statusMonitorModule the {@link StatusMonitorModule} for monitoring the status of the platform
     * @param signalEndOfPcesReplay a {@link Runnable} that signals to the system that PCES replay is complete
     * @param pipelineTracker an optional {@link EventPipelineTracker} for tracking events through the pipeline
     */
    void initialize(
            @NonNull WiringModel model,
            @NonNull Configuration configuration,
            @NonNull Metrics metrics,
            @NonNull Time time,
            @NonNull NodeId selfId,
            @NonNull RecycleBin recycleBin,
            @NonNull FileSystemManager fileSystemManager,
            long startingRound,
            @NonNull Runnable flushPrimaryPipeline,
            @NonNull StatusMonitorModule statusMonitorModule,
            @NonNull Runnable signalEndOfPcesReplay,
            @Nullable EventPipelineTracker pipelineTracker);

    /**
     * Replay preconsensus events from storage.
     *
     * @param pcesReplayLowerBound the minimum birth round of events to replay
     * @param startingRound the round from which to start replaying events
     */
    void replayPcesEvents(long pcesReplayLowerBound, long startingRound);

    /**
     * {@link OutputWire} for events from the preconsensus event stream to replay.
     *
     * @return the {@link OutputWire} for events to replay
     */
    @NonNull
    OutputWire<PlatformEvent> pcesEventsToReplay();

    /**
     * {@link InputWire} for events to write to the preconsensus event stream.
     *
     * @return the {@link InputWire} for events to write
     */
    @InputWireLabel("events to write")
    @NonNull
    InputWire<PlatformEvent> eventsToWriteInputWire();

    /**
     * {@link OutputWire} for events that have been durably written to the preconsensus event stream. Events are streamed
     * in the order they were provided to the {@link #eventsToWriteInputWire} input wire.
     *
     * @return the {@link OutputWire} for written events
     */
    @NonNull
    OutputWire<PlatformEvent> writtenEventsOutputWire();

    /**
     * {@link InputWire} for the consensus round received from the {@code Hashgraph} component.
     *
     * @return the {@link InputWire} for the consensus round
     */
    @InputWireLabel("consensus round")
    @NonNull
    InputWire<ConsensusRound> consensusRoundInputWire();

    /**
     * {@link InputWire} for the initial event window.
     *
     * @return the {@link InputWire} for the initial event window
     */
    @InputWireLabel("initial event window")
    @NonNull
    InputWire<EventWindow> initialEventWindowInputWire();

    /**
     * {@link InputWire} for the minimum birth round to store on disk.
     *
     * @return the {@link InputWire} for the minimum birth round
     */
    @InputWireLabel("minimum birth round to store")
    @NonNull
    InputWire<Long> minimumBirthRoundInputWire();

    /**
     * {@link InputWire} for signaling a discontinuity in the preconsensus event stream.
     *
     * @return the {@link InputWire} for discontinuity signals
     */
    @InputWireLabel("discontinuity")
    @NonNull
    InputWire<Long> discontinuityInputWire();

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
     * @param fileSystemManager the file system manager for managing file locations on disk
     * @param destinationDirectory the directory to copy files to
     * @param lowerBound the minimum birth round of events to copy, events with lower birth round are not copied
     * @param round the round of the state that is being written
     */
    void copyPcesFilesRetryOnFailure(
            @NonNull Configuration configuration,
            @NonNull NodeId selfId,
            @NonNull FileSystemManager fileSystemManager,
            @NonNull Path destinationDirectory,
            long lowerBound,
            long round);
}
