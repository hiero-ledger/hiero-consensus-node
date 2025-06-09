// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui;

import static org.hiero.consensus.model.event.EventConstants.FIRST_GENERATION;

import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.Consensus;
import com.swirlds.platform.ConsensusImpl;
import com.swirlds.platform.consensus.ConsensusConfig;
import com.swirlds.platform.consensus.RoundCalculationUtils;
import com.swirlds.platform.event.linking.SimpleLinker;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.metrics.NoOpConsensusMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.roster.RosterRetriever;

/**
 * This class is responsible for storing events utilized by the GUI.
 */
public class GuiEventStorage {

    // A note on concurrency: although all input to this class is sequential and thread safe, access to this class
    // happens asynchronously. This requires all methods to be synchronized.

    private long maxGeneration = FIRST_GENERATION;

    private final Consensus consensus;
    private final SimpleLinker linker;
    private final Configuration configuration;
    private ConsensusRound lastConsensusRound;
    private Map<GossipEvent, BranchedEventMetadata> branchedEventsMetadata = new HashMap<>();

    /**
     * Creates an empty instance
     *
     * @param configuration this node's configuration
     * @param addressBook   the network's address book
     */
    public GuiEventStorage(@NonNull final Configuration configuration, @NonNull final AddressBook addressBook) {

        this.configuration = Objects.requireNonNull(configuration);
        final PlatformContext platformContext = PlatformContext.create(configuration);

        this.consensus = new ConsensusImpl(
                platformContext, new NoOpConsensusMetrics(), RosterRetriever.buildRoster(addressBook));
        this.linker = new SimpleLinker();
    }

    /**
     * Creates an instance with the given consensus, linker, and configuration.
     * @param consensus the consensus object
     * @param linker the linker object
     * @param configuration the configuration object
     */
    public GuiEventStorage(
            @NonNull final Consensus consensus,
            @NonNull final SimpleLinker linker,
            @NonNull final Configuration configuration) {
        this.consensus = consensus;
        this.linker = linker;
        this.configuration = configuration;
        maxGeneration = linker.getNonAncientEvents().stream()
                .mapToLong(EventImpl::getNGen)
                .max()
                .orElse(FIRST_GENERATION);
    }

    /**
     * Get the consensus object. This is a local copy, not one used by an active platform.
     */
    @NonNull
    public Consensus getConsensus() {
        return consensus;
    }

    /**
     * Handle a preconsensus event. Called after events are released from the orphan buffer.
     *
     * @param event the event to handle
     */
    public synchronized void handlePreconsensusEvent(@NonNull final PlatformEvent event) {
        maxGeneration = Math.max(maxGeneration, event.getGeneration());

        // since the gui will modify the event, we need to copy it
        final EventImpl eventImpl = linker.linkEvent(event.copyGossipedData());
        if (eventImpl == null) {
            return;
        }
        eventImpl.getBaseEvent().setNGen(event.getNGen());

        final List<ConsensusRound> rounds = consensus.addEvent(eventImpl);

        if (rounds.isEmpty()) {
            return;
        }
        lastConsensusRound = rounds.getLast();

        linker.setNonAncientThreshold(rounds.getLast().getEventWindow().ancientThreshold());
    }

    /**
     * Handle a consensus snapshot override (i.e. what happens when we start from a node state at restart/reconnect
     * boundaries).
     *
     * @param snapshot the snapshot to handle
     */
    public synchronized void handleSnapshotOverride(@NonNull final ConsensusSnapshot snapshot) {
        consensus.loadSnapshot(snapshot);
        linker.clear();
        linker.setNonAncientThreshold(RoundCalculationUtils.getAncientThreshold(
                configuration.getConfigData(ConsensusConfig.class).roundsNonAncient(), snapshot));
        lastConsensusRound = null;
    }

    /**
     * Get the maximum generation of any event in the hashgraph.
     *
     * @return the maximum generation of any event in the hashgraph
     */
    public synchronized long getMaxGeneration() {
        return maxGeneration;
    }

    /**
     * Get a list of all non-ancient events in the hashgraph.
     */
    @NonNull
    public synchronized List<EventImpl> getNonAncientEvents() {
        return linker.getNonAncientEvents();
    }

    /**
     * @return the last round that reached consensus
     */
    public synchronized @Nullable ConsensusRound getLastConsensusRound() {
        return lastConsensusRound;
    }

    /**
     * Get map with a link between a branched event and its metadata
     *
     * @return the map
     */
    @NonNull
    public Map<GossipEvent, BranchedEventMetadata> getBranchedEventsMetadata() {
        return branchedEventsMetadata;
    }

    public void setBranchedEventsMetadata(
            @NonNull final Map<GossipEvent, BranchedEventMetadata> branchedEventsMetadata) {
        this.branchedEventsMetadata = branchedEventsMetadata;
    }
}
