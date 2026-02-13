// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces.impl;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.pces.config.PcesConfig;
import org.hiero.consensus.pces.config.PcesWiringConfig;
import org.hiero.consensus.pces.impl.common.PcesFileManager;
import org.hiero.consensus.pces.impl.common.PcesFileReader;
import org.hiero.consensus.pces.impl.common.PcesFileTracker;
import org.hiero.consensus.pces.impl.common.PcesUtilities;
import org.hiero.consensus.pces.impl.writer.DefaultInlinePcesWriter;
import org.hiero.consensus.pces.impl.writer.InlinePcesWriter;

/**
 * Default implementation of the {@link PcesModule}.
 */
public class DefaultPcesModule implements PcesModule {

    @Nullable
    private ComponentWiring<InlinePcesWriter, PlatformEvent> pcesWriterWiring;

    @Nullable
    private PcesFileTracker initialPcesFiles;

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(
            @NonNull final WiringModel model,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final RecycleBin recycleBin,
            final long startingRound,
            @Nullable final EventPipelineTracker pipelineTracker) {
        //noinspection VariableNotUsedInsideIf
        if (pcesWriterWiring != null) {
            throw new IllegalStateException("Already initialized");
        }

        // Set up wiring
        final PcesWiringConfig wiringConfig = configuration.getConfigData(PcesWiringConfig.class);
        this.pcesWriterWiring = new ComponentWiring<>(model, InlinePcesWriter.class, wiringConfig.pcesInlineWriter());

        // Wire metrics
        if (pipelineTracker != null) {
            pipelineTracker.registerMetric("pces");
            this.pcesWriterWiring
                    .getOutputWire()
                    .solderForMonitoring(
                            platformEvent -> pipelineTracker.recordEvent("pces", platformEvent.getTimeReceived()));
        }

        // Force not soldered wires to be built
        pcesWriterWiring.getInputWire(InlinePcesWriter::registerDiscontinuity);

        // Create and bind components
        try {
            final Path databaseDirectory = PcesUtilities.getDatabaseDirectory(configuration, selfId);
            // When we perform the migration to using birth round bounding, we will need to read
            // the old type and start writing the new type.
            final boolean permitGaps =
                    configuration.getConfigData(PcesConfig.class).permitGaps();
            initialPcesFiles = PcesFileReader.readFilesFromDisk(
                    configuration, recycleBin, databaseDirectory, startingRound, permitGaps);
            final PcesFileManager fileManager = new PcesFileManager(
                    configuration, metrics, time, initialPcesFiles, databaseDirectory, startingRound);
            final InlinePcesWriter pcesWriter =
                    new DefaultInlinePcesWriter(configuration, metrics, time, fileManager, selfId);
            pcesWriterWiring.bind(pcesWriter);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputWire<PlatformEvent> writtenEventsOutputWire() {
        return requireNonNull(pcesWriterWiring, "Not initialized").getOutputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformEvent> eventsToWriteInputWire() {
        return requireNonNull(pcesWriterWiring, "Not initialized").getInputWire(InlinePcesWriter::writeEvent);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<EventWindow> eventWindowInputWire() {
        return requireNonNull(pcesWriterWiring, "Not initialized")
                .getInputWire(InlinePcesWriter::updateNonAncientEventBoundary);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Long> minimumAncientIdentifierInputWire() {
        return requireNonNull(pcesWriterWiring, "Not initialized")
                .getInputWire(InlinePcesWriter::setMinimumAncientIdentifierToStore);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<NoInput> beginStreamingnewEventsInputWire() {
        return requireNonNull(pcesWriterWiring, "Not initialized")
                .getInputWire(InlinePcesWriter::beginStreamingNewEvents);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Long> discontinuityInputWire() {
        return requireNonNull(pcesWriterWiring, "Not initialized")
                .getInputWire(InlinePcesWriter::registerDiscontinuity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public IOIterator<PlatformEvent> storedEvents(final long pcesReplayLowerBound, final long startingRound) {
        return requireNonNull(initialPcesFiles, "Not initialized")
                .getEventIterator(pcesReplayLowerBound, startingRound);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() {
        requireNonNull(pcesWriterWiring, "Not initialized").flush();
    }
}
