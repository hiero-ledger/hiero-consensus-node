// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components.consensus;

import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.Consensus;
import com.swirlds.platform.ConsensusImpl;
import com.swirlds.platform.consensus.ConsensusConfig;
import com.swirlds.platform.consensus.RoundCalculationUtils;
import com.swirlds.platform.event.linking.ConsensusLinker;
import com.swirlds.platform.event.linking.InOrderLinker;
import com.swirlds.platform.freeze.FreezeCheckHolder;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.metrics.AddedEventMetrics;
import com.swirlds.platform.metrics.ConsensusMetrics;
import com.swirlds.platform.metrics.ConsensusMetricsImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.config.EventConfig;
import org.hiero.consensus.event.FutureEventBuffer;
import org.hiero.consensus.model.event.AncientMode;
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

    /** The way the ancient threshold is defined */
    private final AncientMode ancientMode;

    private final int roundsNonAncient;

    private final AddedEventMetrics eventAddedMetrics;

    /** Checks if consensus time has reached the freeze period */
    private final FreezeCheckHolder freezeChecker;
    /** When the consensus engine is frozen, it will not process any incoming events ever, so consensus will not advance */
    private boolean frozen = false;

    /**
     * Constructor
     *
     * @param platformContext the platform context
     * @param roster          the current roster
     * @param selfId          the ID of the node
     * @param freezeChecker   checks if the consensus time has reached the freeze period
     */
    public DefaultConsensusEngine(
            @NonNull final PlatformContext platformContext,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final FreezeCheckHolder freezeChecker) {

        final ConsensusMetrics consensusMetrics = new ConsensusMetricsImpl(selfId, platformContext.getMetrics());
        consensus = new ConsensusImpl(platformContext, consensusMetrics, roster);

        linker = new ConsensusLinker(platformContext, selfId);
        futureEventBuffer = new FutureEventBuffer(platformContext.getConfiguration(), platformContext.getMetrics());
        ancientMode = platformContext
                .getConfiguration()
                .getConfigData(EventConfig.class)
                .getAncientMode();
        roundsNonAncient = platformContext
                .getConfiguration()
                .getConfigData(ConsensusConfig.class)
                .roundsNonAncient();

        eventAddedMetrics = new AddedEventMetrics(selfId, platformContext.getMetrics());
        this.freezeChecker = Objects.requireNonNull(freezeChecker);
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

        if (frozen) {
            // If we are frozen, ignore all events
            return List.of();
        }

        final PlatformEvent consensusRelevantEvent = futureEventBuffer.addEvent(event);

        if (consensusRelevantEvent == null) {
            return List.of();
        }
        final List<ConsensusRound> consensusRounds = addToConsensusAlgorithm(consensusRelevantEvent);
        boolean newRoundReachedConsensus = !consensusRounds.isEmpty();

        // If any rounds reached consensus, we need to process the last event window and add any events released
        // by the future event buffer to the consensus algorithm. This may cause more rounds to reach consensus,
        // so we need to keep looping until no more rounds reach consensus, or the freeze round has reached consensus.
        while (newRoundReachedConsensus && !frozen) {
            newRoundReachedConsensus = false;
            // If multiple rounds reach consensus at the same moment there is no need to keep every event window.
            // The latest event window is sufficient to keep event storage clean and release all events from the
            // future events buffer with a birth round less than or equal to the pending round.
            final EventWindow eventWindow = consensusRounds.getLast().getEventWindow();
            linker.setEventWindow(eventWindow);
            for (final PlatformEvent releasedEvent : futureEventBuffer.updateEventWindow(eventWindow)) {
                newRoundReachedConsensus |= consensusRounds.addAll(addToConsensusAlgorithm(releasedEvent));
            }
        }
        return consensusRounds;
    }

    /**
     * Links an event to its parents and adds it to the consensus algorithm. Any rounds that reach consensus as a result
     * of the event being added are returned.
     *
     * @param event the event to add
     * @return a list of rounds that reached consensus, or an empty list if no rounds reached consensus
     */
    @NonNull
    private List<ConsensusRound> addToConsensusAlgorithm(@NonNull final PlatformEvent event) {
        final EventImpl linkedEvent = linker.linkEvent(event);
        if (linkedEvent == null) {
            // linker discarded an ancient event
            return List.of();
        }

        final List<ConsensusRound> consensusRounds = consensus.addEvent(linkedEvent);
        eventAddedMetrics.eventAdded(linkedEvent);

        if (!consensusRounds.isEmpty()) {
            // if multiple rounds reach consensus at the same time and multiple rounds are in the freeze period,
            // we need to freeze on the first one. this means discarding the rest of the rounds and not releasing
            // any more events from the future event buffer
            if (filterFreezeRounds(consensusRounds)) {
                // If the consensus time has reached the freeze period, we will not process any more events
                frozen = true;
                return consensusRounds;
            }
        }

        return consensusRounds;
    }

    /**
     * Checks all rounds to see if any are in the freeze period. If there are multiple rounds in the freeze period, only
     * the first one will be kept and the rest will be removed from the list.
     *
     * @param consensusRounds the list of consensus rounds to check
     * @return true if any rounds are in the freeze period, false otherwise
     */
    private boolean filterFreezeRounds(@NonNull final List<ConsensusRound> consensusRounds) {
        boolean freezeRoundFound = false;
        final Iterator<ConsensusRound> iterator = consensusRounds.iterator();
        while (iterator.hasNext()) {
            final ConsensusRound round = iterator.next();
            if (freezeRoundFound) {
                iterator.remove();
                continue;
            }
            if (freezeChecker.test(round.getConsensusTimestamp())) {
                freezeRoundFound = true;
            }
        }
        return freezeRoundFound;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void outOfBandSnapshotUpdate(@NonNull final ConsensusSnapshot snapshot) {
        final long ancientThreshold = RoundCalculationUtils.getAncientThreshold(roundsNonAncient, snapshot);
        final EventWindow eventWindow =
                new EventWindow(snapshot.round(), ancientThreshold, ancientThreshold, ancientMode);

        linker.clear();
        linker.setEventWindow(eventWindow);
        futureEventBuffer.clear();
        futureEventBuffer.updateEventWindow(eventWindow);
        consensus.loadSnapshot(snapshot);
    }
}
