// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.concurrent;

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
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.crypto.SigningFactory;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.event.intake.config.EventIntakeWiringConfig;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
import org.hiero.consensus.orphan.OrphanBuffer;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.transaction.TransactionLimits;

/**
 * An {@link EventIntakeModule} implementation that consolidates hashing, validation,
 * deduplication, and signature verification into a single {@code CONCURRENT} component.
 *
 * <p>This eliminates 3 inter-stage queues and thread pool dispatches compared to the
 * multi-component pipeline, improving cache locality and reducing scheduling overhead.
 *
 * <p>The pipeline is:
 * <pre>
 *   [EventIntakeProcessor (CONCURRENT)] → [OrphanBuffer (SEQUENTIAL)]
 * </pre>
 */
public class ConcurrentEventIntakeModule implements EventIntakeModule {

    @Nullable
    private WireTransformer<EventWindow, EventWindow> eventWindowDispatcher;

    @Nullable
    private WireTransformer<Object, Object> clearCommandDispatcher;

    @Nullable
    private ComponentWiring<EventIntakeProcessor, PlatformEvent> processorWiring;

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
        if (processorWiring != null) {
            throw new IllegalStateException("Already initialized");
        }

        final EventIntakeWiringConfig wiringConfig = configuration.getConfigData(EventIntakeWiringConfig.class);

        // --- Set up dispatchers ---
        this.eventWindowDispatcher =
                new WireTransformer<>(model, "EventWindowDispatcher", "event window", UnaryOperator.identity());
        this.clearCommandDispatcher =
                new WireTransformer<>(model, "ClearCommandDispatcher", "clear commands", UnaryOperator.identity());

        // --- Set up component wirings ---
        // Use the eventSignatureValidator config slot: CONCURRENT CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC
        this.processorWiring =
                new ComponentWiring<>(model, EventIntakeProcessor.class, wiringConfig.eventSignatureValidator());
        this.orphanBufferWiring = new ComponentWiring<>(model, OrphanBuffer.class, wiringConfig.orphanBuffer());

        // --- Wire data flow: processor → orphan buffer ---
        processorWiring
                .getOutputWire()
                .solderTo(orphanBufferWiring.getInputWire(OrphanBuffer::handleEvent, "unordered events"));

        // --- Wire INJECT: event window → processor + orphan buffer ---
        eventWindowDispatcher
                .getOutputWire()
                .solderTo(processorWiring.getInputWire(EventIntakeProcessor::setEventWindow), INJECT);
        eventWindowDispatcher
                .getOutputWire()
                .solderTo(orphanBufferWiring.getInputWire(OrphanBuffer::setEventWindow, "event window"), INJECT);

        // --- Wire INJECT: clear → processor + orphan buffer ---
        clearCommandDispatcher
                .getOutputWire()
                .solderTo(processorWiring.getInputWire(EventIntakeProcessor::clear), INJECT);
        clearCommandDispatcher
                .getOutputWire()
                .solderTo(orphanBufferWiring.getInputWire(OrphanBuffer::clear), INJECT);

        // --- Wire metrics ---
        if (pipelineTracker != null) {
            pipelineTracker.registerMetric("intake");
            processorWiring
                    .getOutputWire()
                    .solderForMonitoring(platformEvent -> pipelineTracker.recordEvent("intake", platformEvent));
            pipelineTracker.registerMetric("orphanBuffer");
            orphanBufferWiring
                    .getSplitOutput()
                    .solderForMonitoring(platformEvent ->
                            pipelineTracker.recordEvent("orphanBuffer", (PlatformEvent) platformEvent));
        }

        // Force not-yet-soldered wires to be built
        processorWiring.getInputWire(EventIntakeProcessor::updateRosterHistory);
        orphanBufferWiring.getInputWire(OrphanBuffer::clear);

        // --- Create and bind components ---
        final EventHasher eventHasher = new DefaultEventHasher();
        final EventFieldValidator eventFieldValidator =
                new DefaultEventFieldValidator(metrics, time, transactionLimits);
        final EventIntakeProcessor processor = new DefaultEventIntakeProcessor(
                metrics, time, eventHasher, eventFieldValidator, SigningFactory::createVerifier,
                rosterHistory, intakeEventCounter);
        processorWiring.bind(processor);

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
        return requireNonNull(processorWiring, "Not initialized")
                .getInputWire(EventIntakeProcessor::processUnhashedEvent);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformEvent> nonValidatedEventsInputWire() {
        return requireNonNull(processorWiring, "Not initialized")
                .getInputWire(EventIntakeProcessor::processHashedEvent);
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
        return requireNonNull(processorWiring, "Not initialized")
                .getInputWire(EventIntakeProcessor::updateRosterHistory);
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
        requireNonNull(processorWiring, "Not initialized").flush();
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
