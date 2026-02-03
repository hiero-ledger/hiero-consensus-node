// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake;

import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
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
     * @param intakeEventCounter counter for the number of events in the intake pipeline
     * @param transactionLimits provides transaction limits
     * @param eventPipelineTracker tracker for event pipeline statistics, or null if not used
     */
    void initialize(
            @NonNull WiringModel model,
            @NonNull Configuration configuration,
            @NonNull Metrics metrics,
            @NonNull Time time,
            @NonNull RosterHistory rosterHistory,
            @NonNull IntakeEventCounter intakeEventCounter,
            @NonNull TransactionLimits transactionLimits,
            @Nullable EventPipelineTracker eventPipelineTracker);

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
    @InputWireLabel("non-validated events")
    @NonNull
    InputWire<PlatformEvent> nonValidatedEventsInputWire();

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
     * Get an {@link InputWire} to clear the state of the internal components.
     *
     * @return the {@link InputWire} to clear the internal state
     */
    @InputWireLabel("clear")
    @NonNull
    InputWire<Object> clearComponentsInputWire();

    /**
     * Flushes all events of the internal components.
     */
    void flush();

    /**
     * Destroys the module.
     */
    void destroy();
}
