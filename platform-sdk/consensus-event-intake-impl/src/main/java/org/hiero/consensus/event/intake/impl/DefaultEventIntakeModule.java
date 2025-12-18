// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.metrics.event.EventPipelineTracker;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.event.intake.config.EventIntakeWiringConfig;
import org.hiero.consensus.event.intake.impl.validation.DefaultInternalEventValidator;
import org.hiero.consensus.event.intake.impl.validation.InternalEventValidator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.transaction.TransactionLimits;

/**
 * The default implementation of the {@link EventIntakeModule}.
 */
public class DefaultEventIntakeModule implements EventIntakeModule {

    @Nullable
    private ComponentWiring<EventHasher, PlatformEvent> eventHasherWiring;

    @Nullable
    private ComponentWiring<InternalEventValidator, PlatformEvent> eventValidatorWiring;

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
            @Nullable final EventPipelineTracker pipelineTracker) {
        //noinspection VariableNotUsedInsideIf
        if (eventHasherWiring != null) {
            throw new IllegalStateException("Already initialized");
        }

        // Set up wiring
        final EventIntakeWiringConfig wiringConfig = configuration.getConfigData(EventIntakeWiringConfig.class);
        this.eventHasherWiring = new ComponentWiring<>(model, EventHasher.class, wiringConfig.eventHasher());
        this.eventValidatorWiring =
                new ComponentWiring<>(model, InternalEventValidator.class, wiringConfig.internalEventValidator());

        // Wire components
        eventHasherWiring
                .getOutputWire()
                .solderTo(eventValidatorWiring.getInputWire(InternalEventValidator::validateEvent));

        // Wire metrics
        if (pipelineTracker != null) {
            pipelineTracker.registerMetric("hashing");
            this.eventHasherWiring
                    .getOutputWire()
                    .solderForMonitoring(platformEvent -> pipelineTracker.recordEvent("hashing", platformEvent));
            pipelineTracker.registerMetric("validation");
            this.eventValidatorWiring
                    .getOutputWire()
                    .solderForMonitoring(platformEvent -> pipelineTracker.recordEvent("validation", platformEvent));
        }

        // Create and bind components
        final EventHasher eventHasher = new DefaultEventHasher();
        eventHasherWiring.bind(eventHasher);
        final InternalEventValidator internalEventValidator =
                new DefaultInternalEventValidator(metrics, time, intakeEventCounter, transactionLimits);
        eventValidatorWiring.bind(internalEventValidator);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputWire<PlatformEvent> validatedEventsOutputWire() {
        return requireNonNull(eventValidatorWiring, "Not initialized").getOutputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformEvent> unhashedEventsInputWire() {
        return requireNonNull(eventHasherWiring, "Not initialized").getInputWire(EventHasher::hashEvent);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformEvent> selfEventsInputWire() {
        return requireNonNull(eventValidatorWiring, "Not initialized")
                .getInputWire(InternalEventValidator::validateEvent);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<EventWindow> eventWindowInputWire() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<RosterHistory> rosterHistoryInputWire() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        throw new UnsupportedOperationException("Shutdown mechanism not implemented yet");
    }

    // *************************************************************
    // Temporary workaround to allow reuse of the EventIntake module
    // *************************************************************

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() {
        requireNonNull(eventHasherWiring, "Not initialized").flush();
        requireNonNull(eventValidatorWiring, "Not initialized").flush();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Object> clearComponentsInputWire() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Object> beginStreamingNewEventsInputWire() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Long> registerDiscontinuityInputWire() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Long> setMinimumAncientIdentifierToStore() {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
