// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components.consensus;

import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.Consensus;
import com.swirlds.platform.ConsensusImpl;
import com.swirlds.platform.consensus.ConsensusConfig;
import com.swirlds.platform.consensus.EventWindowUtils;
import com.swirlds.platform.event.linking.ConsensusLinker;
import com.swirlds.platform.event.linking.InOrderLinker;
import com.swirlds.platform.freeze.FreezeCheckHolder;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.metrics.AddedEventMetrics;
import com.swirlds.platform.metrics.ConsensusMetrics;
import com.swirlds.platform.metrics.ConsensusMetricsImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import org.hiero.consensus.event.FutureEventBuffer;
import org.hiero.consensus.event.FutureEventBufferingOption;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * The default implementation of the {@link ConsensusEngine} interface
 */
public class DefaultConsensusEngine implements ConsensusEngine {

    /**
     * Stores non-ancient events and manages linking and unlinking.
     */
    private final InOrderLinker linker;

    /** Buffers events until needed by the consensus algorithm based on their birth round */
    private final FutureEventBuffer futureEventBuffer;

    /**
     * Executes the hashgraph consensus algorithm.
     */
    private final Consensus consensus;

    private final int roundsNonAncient;

    private final AddedEventMetrics eventAddedMetrics;

    private final FreezeRoundController freezeRoundController;

    /**
     * Constructor
     *
     * @param platformContext the platform context
     * @param roster the current roster
     * @param selfId the ID of the node
     * @param freezeChecker checks if the consensus time has reached the freeze period
     */
    public DefaultConsensusEngine(
            @NonNull final PlatformContext platformContext,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final FreezeCheckHolder freezeChecker) {

        final ConsensusMetrics consensusMetrics = new ConsensusMetricsImpl(selfId, platformContext.getMetrics());
        consensus = new ConsensusImpl(platformContext, consensusMetrics, roster);

        linker = new ConsensusLinker(platformContext);
        futureEventBuffer = new FutureEventBuffer(
                platformContext.getMetrics(), FutureEventBufferingOption.PENDING_CONSENSUS_ROUND, "consensus");
        roundsNonAncient = platformContext
                .getConfiguration()
                .getConfigData(ConsensusConfig.class)
                .roundsNonAncient();

        eventAddedMetrics = new AddedEventMetrics(selfId, platformContext.getMetrics());
        this.freezeRoundController = new FreezeRoundController(freezeChecker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus platformStatus) {
        consensus.setPcesMode(platformStatus == REPLAYING_EVENTS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<ConsensusRound> addEvent(@NonNull final PlatformEvent event) {
        Objects.requireNonNull(event);

        if (freezeRoundController.isFrozen()) {
            // If we are frozen, ignore all events
            return List.of();
        }

        final PlatformEvent consensusRelevantEvent = futureEventBuffer.addEvent(event);
        if (consensusRelevantEvent == null) {
            // The event is a future event and cannot be added to consensus yet.
            return List.of();
        }

        final Queue<PlatformEvent> eventsToAdd = new LinkedList<>();
        eventsToAdd.add(consensusRelevantEvent);

        final List<ConsensusRound> allConsensusRounds = new ArrayList<>();

        while (!eventsToAdd.isEmpty()) {
            final PlatformEvent eventToAdd = eventsToAdd.poll();
            final EventImpl linkedEvent = linker.linkEvent(eventToAdd);
            if (linkedEvent == null) {
                // linker discarded an ancient event
                continue;
            }

            allConsensusRounds.addAll(consensus.addEvent(linkedEvent));
            eventAddedMetrics.eventAdded(linkedEvent);

            if (allConsensusRounds.isEmpty()) {
                continue;
            }

            // If consensus is reached, we need to process the last event window and add any events released
            // from the future event buffer to the consensus algorithm.
            final EventWindow eventWindow = allConsensusRounds.getLast().getEventWindow();
            linker.setEventWindow(eventWindow);
            eventsToAdd.addAll(futureEventBuffer.updateEventWindow(eventWindow));
        }

        // If multiple rounds reach consensus and multiple rounds are in the freeze period,
        // we need to freeze on the first one. this means discarding the rest of the rounds.
        return freezeRoundController.filterAndModify(allConsensusRounds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void outOfBandSnapshotUpdate(@NonNull final ConsensusSnapshot snapshot) {
        final EventWindow eventWindow = EventWindowUtils.createEventWindow(snapshot, roundsNonAncient);
        linker.clear();
        linker.setEventWindow(eventWindow);
        futureEventBuffer.clear();
        futureEventBuffer.updateEventWindow(eventWindow);
        consensus.loadSnapshot(snapshot);
    }
}
