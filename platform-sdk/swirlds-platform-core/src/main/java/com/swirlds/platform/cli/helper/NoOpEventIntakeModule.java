// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli.helper;

import com.swirlds.base.time.Time;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.event.intake.config.EventIntakeWiringConfig;
import org.hiero.consensus.metrics.event.EventPipelineTracker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.transaction.TransactionLimits;

/**
 * A no-operation implementation of the EventIntakeModule for the {@link com.swirlds.platform.cli.DiagramCommand}.
 */
public class NoOpEventIntakeModule implements EventIntakeModule {

    private final ComponentWiring<EventIntakeModule, PlatformEvent> componentWiring;

    /**
     * Constructs a NoOpEventIntakeModule.
     *
     * @param model the wiring model
     * @param configuration the configuration
     */
    public NoOpEventIntakeModule(@NonNull final WiringModel model, @NonNull final Configuration configuration) {
        final TaskSchedulerConfiguration taskSchedulerConfiguration =
                configuration.getConfigData(EventIntakeWiringConfig.class).eventHasher();
        componentWiring = new ComponentWiring<>(model, EventIntakeModule.class, taskSchedulerConfiguration);
        componentWiring.bind(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(
            @NonNull final WiringModel model,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final RosterHistory rosterHistory,
            @NonNull final NodeId selfId,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final TransactionLimits transactionLimits,
            @NonNull final RecycleBin recycleBin,
            final long startingRound,
            @Nullable final EventPipelineTracker pipelineTracker) {}

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputWire<PlatformEvent> validatedEventsOutputWire() {
        return componentWiring.getOutputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformEvent> unhashedEventsInputWire() {
        return componentWiring.getInputWire(EventIntakeModule::unhashedEventsInputWire);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformEvent> selfEventsInputWire() {
        return componentWiring.getInputWire(EventIntakeModule::selfEventsInputWire);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<EventWindow> eventWindowInputWire() {
        return componentWiring.getInputWire(EventIntakeModule::eventWindowInputWire);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<RosterHistory> rosterHistoryInputWire() {
        return componentWiring.getInputWire(EventIntakeModule::rosterHistoryInputWire);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() {}

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Object> clearComponentsInputWire() {
        return componentWiring.getInputWire(EventIntakeModule::clearComponentsInputWire);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Object> beginStreamingNewEventsInputWire() {
        return componentWiring.getInputWire(EventIntakeModule::beginStreamingNewEventsInputWire);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Long> registerDiscontinuityInputWire() {
        return componentWiring.getInputWire(EventIntakeModule::registerDiscontinuityInputWire);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Long> setMinimumAncientIdentifierToStore() {
        return componentWiring.getInputWire(EventIntakeModule::setMinimumAncientIdentifierToStore);
    }
}
