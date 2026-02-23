// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.graph;

import static org.hiero.consensus.pcli.PcesSliceCommand.createDefaultPlatformContext;
import static org.hiero.consensus.pcli.PcesSliceCommand.generateSigners;

import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.hiero.consensus.pces.impl.common.CommonPcesWriter;
import org.hiero.consensus.pces.impl.common.PcesFileManager;
import org.hiero.consensus.pces.impl.common.PcesFileTracker;
import org.hiero.consensus.round.EventWindowUtils;

/**
 * Rewriter for PCES event graphs that reads from PCES files, filters and transforms events
 * while providing them with new hashes and signatures, then writes the results to a new PCES stream.
 * Events are processed and written one at a time to minimize memory usage.
 */
public class PcesGraphSlicer {

    private final Path pcesOutputLocation;
    private final Map<Bytes, EventDescriptor> migratedParents;
    private final PbjStreamHasher eventHasher;
    private final Map<NodeId, PlatformSigner> signers;
    private final PlatformContext context;
    private final Function<EventCore, EventCore> eventCoreModifier;
    private final EventGraphPipeline graphPipeline;

    /** The PCES writer, initialized before processing and closed after. */
    private CommonPcesWriter pcesWriter;

    /**
     * Creates a new builder for {@link PcesGraphSlicer}.
     *
     * @return a new builder instance
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    private PcesGraphSlicer(@NonNull final Builder builder) {
        Objects.requireNonNull(builder.keysAndCertsMap, "keysAndCertsMap is required");
        Objects.requireNonNull(builder.graphEventCoreModifier, "graphEventOverwriter is required");
        Objects.requireNonNull(builder.graphEventFilter, "graphEventFilter is required");
        Objects.requireNonNull(builder.existingPcesFilesLocation, "existingPcesFilesLocation is required");
        Objects.requireNonNull(builder.exportPcesFileLocation, "exportPcesFileLocation is required");

        this.pcesOutputLocation = builder.exportPcesFileLocation;
        this.migratedParents = new HashMap<>();
        this.eventHasher = new PbjStreamHasher();
        this.signers = generateSigners(builder.keysAndCertsMap, PlatformSigner::new);
        this.context = builder.context != null ? builder.context : createDefaultPlatformContext();
        this.eventCoreModifier = builder.graphEventCoreModifier;

        final PcesEventGraphSource rawSource =
                new PcesEventGraphSource(builder.existingPcesFilesLocation, this.context);
        // Use OrphanBufferEventGraphSource to process events through hasher and orphan buffer
        // This computes ngen and links parents without the overhead of running consensus
        final OrphanBufferEventGraphSource orphanBufferSource =
                new OrphanBufferEventGraphSource(rawSource, this.context);

        if (builder.consensusSnapshot != null) {
            final int roundsNonAncient = context.getConfiguration()
                    .getConfigData(ConsensusConfig.class)
                    .roundsNonAncient();
            final EventWindow eventWindow =
                    EventWindowUtils.createEventWindow(builder.consensusSnapshot, roundsNonAncient);
            orphanBufferSource.setEventWindow(eventWindow);
        }

        // Use EventSink for streaming writes - events are written one at a time
        this.graphPipeline = new EventGraphPipeline(
                orphanBufferSource, builder.graphEventFilter::test, this::process, this::writeEventToPces, null);
    }

    /**
     * Builder for {@link PcesGraphSlicer}.
     */
    public static class Builder {
        private PlatformContext context;
        private Map<NodeId, KeysAndCerts> keysAndCertsMap;
        private Function<EventCore, EventCore> graphEventCoreModifier;
        private Predicate<PlatformEvent> graphEventFilter;
        private Path existingPcesFilesLocation;
        private Path exportPcesFileLocation;
        private ConsensusSnapshot consensusSnapshot;

        private Builder() {}

        /**
         * Sets the platform context for configuration.
         *
         * @param context the platform context
         * @return this builder
         */
        @NonNull
        public Builder context(@NonNull final PlatformContext context) {
            this.context = Objects.requireNonNull(context);
            return this;
        }

        /**
         * Sets the keys and certificates for signing migrated events.
         *
         * @param keysAndCertsMap map of node IDs to their keys and certificates
         * @return this builder
         */
        @NonNull
        public Builder keysAndCertsMap(@NonNull final Map<NodeId, KeysAndCerts> keysAndCertsMap) {
            this.keysAndCertsMap = Objects.requireNonNull(keysAndCertsMap);
            return this;
        }

        /**
         * Sets the function to modify event properties (e.g., birth round).
         *
         * @param graphEventOverwriter function to transform event cores
         * @return this builder
         */
        @NonNull
        public Builder graphEventCoreModifier(@NonNull final Function<EventCore, EventCore> graphEventOverwriter) {
            this.graphEventCoreModifier = Objects.requireNonNull(graphEventOverwriter);
            return this;
        }

        /**
         * Sets the predicate that determines which events to include.
         * Events for which the predicate returns {@code true} are kept;
         * events for which it returns {@code false} are excluded.
         *
         * @param graphEventFilter predicate for filtering events
         * @return this builder
         */
        @NonNull
        public Builder graphEventFilter(@NonNull final EventGraphPipeline.EventFilter graphEventFilter) {
            this.graphEventFilter = Objects.requireNonNull(graphEventFilter);
            return this;
        }

        /**
         * Sets the path to source PCES files.
         *
         * @param existingPcesFilesLocation path to existing PCES files
         * @return this builder
         */
        @NonNull
        public Builder existingPcesFilesLocation(@NonNull final Path existingPcesFilesLocation) {
            this.existingPcesFilesLocation = Objects.requireNonNull(existingPcesFilesLocation);
            return this;
        }

        /**
         * Sets the path where migrated PCES files will be written.
         *
         * @param exportPcesFileLocation path for output PCES files
         * @return this builder
         */
        @NonNull
        public Builder exportPcesFileLocation(@NonNull final Path exportPcesFileLocation) {
            this.exportPcesFileLocation = Objects.requireNonNull(exportPcesFileLocation);
            return this;
        }

        /**
         * Sets the consensus snapshot to initialize the event window.
         * If not set, processing will start from genesis.
         *
         * @param consensusSnapshot the consensus snapshot, or null to start from genesis
         * @return this builder
         */
        @NonNull
        public Builder consensusSnapshot(@NonNull final ConsensusSnapshot consensusSnapshot) {
            this.consensusSnapshot = consensusSnapshot;
            return this;
        }

        /**
         * Builds the {@link PcesGraphSlicer} instance.
         *
         * @return a new PcesGraphSlicer
         * @throws NullPointerException if any required field is null
         */
        @NonNull
        public PcesGraphSlicer build() {
            return new PcesGraphSlicer(this);
        }
    }

    /**
     * Given an event, executes the {@code eventOverwriter} then rehashes and resigns the event.
     * Event needs to be already hashed.
     * @param event the event to process
     * @return the overwritten, rehashed and resigned event
     */
    @NonNull
    private PlatformEvent process(@NonNull final PlatformEvent event) {
        // Event needs to be already hashed
        // Create the new event core with modified properties
        final EventCore newEventCore = eventCoreModifier.apply(event.getEventCore());

        // Get parent descriptors from previously migrated events
        final List<EventDescriptor> parents = event.getAllParents().stream()
                .map(parent -> migratedParents.get(parent.hash().getBytes()))
                .filter(Objects::nonNull)
                .toList();

        final List<Bytes> transactions = event.getTransactions().stream()
                .map(TransactionWrapper::getApplicationTransaction)
                .toList();

        final var unsignedEvent = new UnsignedEvent(
                NodeId.of(newEventCore.creatorNodeId()),
                parents.stream().map(EventDescriptorWrapper::new).toList(),
                newEventCore.birthRound(),
                HapiUtils.asInstant(newEventCore.timeCreated()),
                transactions,
                newEventCore.coin());
        // Calculate new hash
        eventHasher.hashUnsignedEvent(unsignedEvent);
        final Hash newHash = unsignedEvent.getHash();

        final PlatformSigner signer = signers.get(event.getCreatorId());
        if (signer == null) {
            throw new IllegalStateException("No signer found for node " + event.getCreatorId()
                    + ". Ensure keysAndCertsMap contains all nodes.");
        }
        // Sign the new hash
        final Signature signature = signer.sign(newHash.getBytes().toByteArray());

        // Create descriptor for this event so future events can reference it as parent
        final EventDescriptor eventDescriptor = new EventDescriptor.Builder()
                .hash(newHash.getBytes())
                .creatorNodeId(newEventCore.creatorNodeId())
                .birthRound(newEventCore.birthRound())
                .build();

        // Store for parent lookup by subsequent events
        migratedParents.put(event.getHash().getBytes(), eventDescriptor);

        return new PlatformEvent(unsignedEvent, signature.getBytes(), EventOrigin.RUNTIME_CREATED);
    }

    /**
     * Initializes the PCES writer for streaming event writes.
     * Must be called before processing events.
     */
    private void initializePcesWriter() {
        try {
            Files.createDirectories(pcesOutputLocation);
            pcesWriter = new CommonPcesWriter(
                    context.getConfiguration(),
                    new PcesFileManager(
                            context.getConfiguration(),
                            context.getMetrics(),
                            context.getTime(),
                            new PcesFileTracker(),
                            pcesOutputLocation,
                            0));
            pcesWriter.beginStreamingNewEvents();
        } catch (final IOException e) {
            throw new RuntimeException("Failed to initialize PCES writer", e);
        }
    }

    /**
     * Writes a single event to the PCES stream.
     * Called for each event as it is processed.
     *
     * @param event the event to write
     */
    private void writeEventToPces(@NonNull final PlatformEvent event) {
        try {
            pcesWriter.prepareOutputStream(event);
            pcesWriter.getCurrentMutableFile().writeEvent(event);
        } catch (final IOException e) {
            throw new RuntimeException("Failed to write event to PCES", e);
        }
    }

    /**
     * Closes the PCES writer after all events have been written.
     * Must be called after processing completes.
     */
    private void closePcesWriter() {
        if (pcesWriter != null) {
            pcesWriter.closeCurrentMutableFile();
        }
    }

    /**
     * Takes a section of an existing graph saved in PCES files located at the configured input location,
     * slices it by filtering events using the configured predicate, transforms them using the configured
     * overwriter, rehashes and resigns the modified events, and writes them to a new PCES stream at the
     * configured output location.
     *
     * <p>Events are processed and written one at a time to minimize memory usage.
     */
    public void slice() {
        initializePcesWriter();
        try {
            graphPipeline.process();
        } finally {
            closePcesWriter();
        }
    }
}
