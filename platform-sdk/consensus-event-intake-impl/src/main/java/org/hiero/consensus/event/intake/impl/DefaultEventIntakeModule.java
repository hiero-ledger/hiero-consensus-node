// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl;

import static com.swirlds.component.framework.wires.SolderType.INJECT;
import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.transformers.WireTransformer;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.function.UnaryOperator;
import org.hiero.consensus.crypto.ConsensusCryptoUtils;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.event.intake.config.EventIntakeWiringConfig;
import org.hiero.consensus.event.intake.impl.deduplication.EventDeduplicator;
import org.hiero.consensus.event.intake.impl.deduplication.StandardEventDeduplicator;
import org.hiero.consensus.event.intake.impl.signature.DefaultEventSignatureValidator;
import org.hiero.consensus.event.intake.impl.signature.EventSignatureValidator;
import org.hiero.consensus.event.intake.impl.validation.DefaultInternalEventValidator;
import org.hiero.consensus.event.intake.impl.validation.InternalEventValidator;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
import org.hiero.consensus.orphan.OrphanBuffer;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.transaction.TransactionLimits;

/**
 * The default implementation of the {@link EventIntakeModule}.
 */
public class DefaultEventIntakeModule implements EventIntakeModule {

    /** Transformer to dispatch event windows to components that need them. */
    @Nullable
    private WireTransformer<EventWindow, EventWindow> eventWindowDispatcher;

    @Nullable
    private WireTransformer<Object, Object> clearCommandDispatcher;

    @Nullable
    private ComponentWiring<EventHasher, PlatformEvent> eventHasherWiring;

    @Nullable
    private ComponentWiring<InternalEventValidator, PlatformEvent> eventValidatorWiring;

    @Nullable
    private ComponentWiring<EventDeduplicator, PlatformEvent> eventDeduplicatorWiring;

    @Nullable
    private ComponentWiring<EventSignatureValidator, PlatformEvent> eventSignatureValidatorWiring;

    @Nullable
    private ComponentWiring<OrphanBuffer, List<PlatformEvent>> orphanBufferWiring;

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
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final TransactionLimits transactionLimits,
            @Nullable final EventPipelineTracker pipelineTracker) {
        //noinspection VariableNotUsedInsideIf
        if (eventHasherWiring != null) {
            throw new IllegalStateException("Already initialized");
        }

        // Set up wiring
        this.eventWindowDispatcher =
                new WireTransformer<>(model, "EventWindowDispatcher", "event window", UnaryOperator.identity());
        this.clearCommandDispatcher =
                new WireTransformer<>(model, "ClearCommandDispatcher", "clear commands", UnaryOperator.identity());

        final EventIntakeWiringConfig wiringConfig = configuration.getConfigData(EventIntakeWiringConfig.class);
        this.eventHasherWiring = new ComponentWiring<>(model, EventHasher.class, wiringConfig.eventHasher());
        this.eventValidatorWiring =
                new ComponentWiring<>(model, InternalEventValidator.class, wiringConfig.internalEventValidator());
        this.eventDeduplicatorWiring =
                new ComponentWiring<>(model, EventDeduplicator.class, wiringConfig.eventDeduplicator());
        this.eventSignatureValidatorWiring =
                new ComponentWiring<>(model, EventSignatureValidator.class, wiringConfig.eventSignatureValidator());
        this.orphanBufferWiring = new ComponentWiring<>(model, OrphanBuffer.class, wiringConfig.orphanBuffer());

        // Wire components
        eventHasherWiring
                .getOutputWire()
                .solderTo(eventValidatorWiring.getInputWire(InternalEventValidator::validateEvent));
        eventValidatorWiring
                .getOutputWire()
                .solderTo(eventDeduplicatorWiring.getInputWire(EventDeduplicator::handleEvent));
        eventWindowDispatcher
                .getOutputWire()
                .solderTo(this.eventDeduplicatorWiring.getInputWire(EventDeduplicator::setEventWindow), INJECT);
        clearCommandDispatcher
                .getOutputWire()
                .solderTo(this.eventDeduplicatorWiring.getInputWire(EventDeduplicator::clear), INJECT);
        eventDeduplicatorWiring
                .getOutputWire()
                .solderTo(this.eventSignatureValidatorWiring.getInputWire(EventSignatureValidator::validateSignature));
        eventWindowDispatcher
                .getOutputWire()
                .solderTo(
                        this.eventSignatureValidatorWiring.getInputWire(EventSignatureValidator::setEventWindow),
                        INJECT);
        eventSignatureValidatorWiring
                .getOutputWire()
                .solderTo(this.orphanBufferWiring.getInputWire(OrphanBuffer::handleEvent, "unordered events"));
        eventWindowDispatcher
                .getOutputWire()
                .solderTo(this.orphanBufferWiring.getInputWire(OrphanBuffer::setEventWindow, "event window"), INJECT);
        clearCommandDispatcher
                .getOutputWire()
                .solderTo(this.orphanBufferWiring.getInputWire(OrphanBuffer::clear), INJECT);

        // Wire metrics
        if (pipelineTracker != null) {
            pipelineTracker.registerMetric("hashing", EventOrigin.GOSSIP, EventOrigin.STORAGE);
            this.eventHasherWiring
                    .getOutputWire()
                    .solderForMonitoring(platformEvent -> pipelineTracker.recordEvent("hashing", platformEvent));
            pipelineTracker.registerMetric("validation");
            this.eventValidatorWiring
                    .getOutputWire()
                    .solderForMonitoring(platformEvent -> pipelineTracker.recordEvent("validation", platformEvent));
            pipelineTracker.registerMetric("deduplication");
            this.eventDeduplicatorWiring
                    .getOutputWire()
                    .solderForMonitoring(platformEvent -> pipelineTracker.recordEvent("deduplication", platformEvent));
            pipelineTracker.registerMetric("verification");
            this.eventSignatureValidatorWiring
                    .getOutputWire()
                    .solderForMonitoring(platformEvent -> pipelineTracker.recordEvent("verification", platformEvent));
            pipelineTracker.registerMetric("orphanBuffer");
            this.orphanBufferWiring
                    .getSplitOutput()
                    .solderForMonitoring(platformEvent ->
                            pipelineTracker.recordEvent("orphanBuffer", (PlatformEvent) platformEvent));
        }

        // Force not soldered wires to be built
        this.eventDeduplicatorWiring.getInputWire(EventDeduplicator::clear);
        this.eventSignatureValidatorWiring.getInputWire(EventSignatureValidator::updateRosterHistory);
        this.orphanBufferWiring.getInputWire(OrphanBuffer::clear);

        // Create and bind components
        final EventHasher eventHasher = new DefaultEventHasher();
        eventHasherWiring.bind(eventHasher);
        final InternalEventValidator internalEventValidator =
                new DefaultInternalEventValidator(metrics, time, intakeEventCounter, transactionLimits);
        eventValidatorWiring.bind(internalEventValidator);
        final EventDeduplicator eventDeduplicator = new StandardEventDeduplicator(metrics, intakeEventCounter);
        eventDeduplicatorWiring.bind(eventDeduplicator);
        final EventSignatureValidator eventSignatureValidator = new DefaultEventSignatureValidator(
                metrics, time, ConsensusCryptoUtils::verifySignature, rosterHistory, intakeEventCounter);
        eventSignatureValidatorWiring.bind(eventSignatureValidator);
        final OrphanBuffer orphanBuffer = new DefaultOrphanBuffer(metrics, intakeEventCounter);
        orphanBufferWiring.bind(orphanBuffer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputWire<PlatformEvent> validatedEventsOutputWire() {
        return requireNonNull(orphanBufferWiring, "Not initialized").getSplitOutput();
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
    public InputWire<PlatformEvent> nonValidatedEventsInputWire() {
        return requireNonNull(eventValidatorWiring, "Not initialized")
                .getInputWire(InternalEventValidator::validateEvent);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<EventWindow> eventWindowInputWire() {
        return requireNonNull(eventWindowDispatcher, "Not initialized").getInputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<RosterHistory> rosterHistoryInputWire() {
        return requireNonNull(eventSignatureValidatorWiring, "Not initialized")
                .getInputWire(EventSignatureValidator::updateRosterHistory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Object> clearComponentsInputWire() {
        return requireNonNull(clearCommandDispatcher, "Not initialized").getInputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() {
        requireNonNull(eventHasherWiring, "Not initialized").flush();
        requireNonNull(eventValidatorWiring, "Not initialized").flush();
        requireNonNull(eventDeduplicatorWiring, "Not initialized").flush();
        requireNonNull(eventSignatureValidatorWiring, "Not initialized").flush();
        requireNonNull(orphanBufferWiring, "Not initialized").flush();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        throw new UnsupportedOperationException("Shutdown mechanism not implemented yet");
    }
}
