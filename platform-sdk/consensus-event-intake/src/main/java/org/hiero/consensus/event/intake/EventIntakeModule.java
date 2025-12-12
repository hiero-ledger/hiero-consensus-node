// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake;

import com.swirlds.base.time.Time;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.transaction.TransactionLimits;

/**
 * Validates incoming events from other nodes and self events. Will discard invalid events and pass
 * valid events to the hashgraph module and event creator module.
 */
public interface EventIntakeModule {

    /**
     * Initialize the event intake module.
     *
     * @param model the wiring model
     * @param configuration provides the configuration
     * @param metrics provides the metrics
     * @param time provides the time source
     * @param rosterHistory provides the roster history
     * @param selfId the ID of this node
     * @param intakeEventCounter counter for the number of events in the intake pipeline
     * @param transactionLimits provides transaction limits
     * @param recycleBin provides a recycle bin for files
     * @param startingRound the round number of the system's initial state
     */
    void initialize(
            @NonNull WiringModel model,
            @NonNull Configuration configuration,
            @NonNull Metrics metrics,
            @NonNull Time time,
            @NonNull RosterHistory rosterHistory,
            @NonNull NodeId selfId,
            @NonNull IntakeEventCounter intakeEventCounter,
            @NonNull TransactionLimits transactionLimits,
            @NonNull RecycleBin recycleBin,
            long startingRound);

    /**
     * {@link OutputWire} for ordered, validated, and recorded events.
     *
     * @return the {@link OutputWire} for validated events
     */
    @NonNull
    OutputWire<PlatformEvent> validatedEventsOutputWire();

    /**
     * {@link InputWire} for gossiped events received from other nodes.
     *
     * @return the {@link InputWire} for gossiped events
     */
    @InputWireLabel("unhashed events")
    @NonNull
    InputWire<PlatformEvent> unhashedEventsInputWire();

    /**
     * {@link InputWire} for self events created by this node.
     *
     * @return the {@link InputWire} for self events
     */
    @InputWireLabel("self-events")
    @NonNull
    InputWire<PlatformEvent> selfEventsInputWire();

    /**
     * {@link InputWire} for the event window received from the {@code Hashgraph} component.
     *
     * @return the {@link InputWire} for the event window
     */
    @InputWireLabel("event window")
    @NonNull
    InputWire<EventWindow> eventWindowInputWire();

    /**
     * {@link InputWire} for the roster history.
     *
     * @return the {@link InputWire} for the roster history
     */
    @InputWireLabel("roster history")
    @NonNull
    InputWire<RosterHistory> rosterHistoryInputWire();

    /**
     * Destroys the module.
     */
    void destroy();

    // *************************************************************
    // Temporary workaround to allow reuse of the EventIntake module
    // *************************************************************

    /**
     * Flushes all events of the internal components.
     *
     * <p>Please note that this method is a temporary workaround and will be removed in the future.
     */
    void flush();

    /**
     * Get an {@link InputWire} to clear the state of the internal components.
     *
     * <p>Please note that this method is a temporary workaround and will be removed in the future.
     *
     * @return the {@link InputWire} to clear the internal state
     */
    @InputWireLabel("clear")
    @NonNull
    InputWire<Object> clearComponentsInputWire();

    /**
     * Prior to this method being called, all events added to the preconsensus event stream are assumed to be events
     * read from the preconsensus event stream on disk. The events from the stream on disk are not re-written to the
     * disk, and are considered to be durable immediately upon ingest.
     *
     * <p>Please note that this method is a temporary workaround and will be removed in the future.
     *
     * @return the {@link InputWire} to signal beginning of new event streaming
     */
    @InputWireLabel("done streaming pces")
    @NonNull
    InputWire<Object> beginStreamingNewEventsInputWire();

    /**
     * Inform the preconsensus event writer that a discontinuity has occurred in the preconsensus event stream.
     *
     * <p>Please note that this method is a temporary workaround and will be removed in the future.
     *
     * @return the {@link InputWire} for the round of the state that the new stream will be starting from
     */
    @InputWireLabel("discontinuity")
    @NonNull
    InputWire<Long> registerDiscontinuityInputWire();

    /**
     * Set the minimum ancient indicator needed to be kept on disk.
     *
     * <p>Please note that this method is a temporary workaround and will be removed in the future.
     *
     * @return the {@link InputWire} for the minimum ancient indicator required to be stored on disk
     */
    @InputWireLabel("minimum identifier to store")
    @NonNull
    InputWire<Long> setMinimumAncientIdentifierToStore();
}
