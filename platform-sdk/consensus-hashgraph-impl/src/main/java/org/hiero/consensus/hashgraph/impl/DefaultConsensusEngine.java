// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl;

import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import org.hiero.consensus.event.FutureEventBuffer;
import org.hiero.consensus.event.FutureEventBufferingOption;
import org.hiero.consensus.freeze.FreezePeriodChecker;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.hashgraph.impl.consensus.Consensus;
import org.hiero.consensus.hashgraph.impl.consensus.ConsensusImpl;
import org.hiero.consensus.hashgraph.impl.linking.ConsensusLinker;
import org.hiero.consensus.hashgraph.impl.linking.DefaultLinkerLogsAndMetrics;
import org.hiero.consensus.hashgraph.impl.metrics.ConsensusEngineMetrics;
import org.hiero.consensus.hashgraph.impl.metrics.ConsensusMetrics;
import org.hiero.consensus.hashgraph.impl.metrics.ConsensusMetricsImpl;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.round.EventWindowUtils;

/**
 * The default implementation of the {@link ConsensusEngine} interface
 */
public class DefaultConsensusEngine implements ConsensusEngine {

    /**
     * Stores non-ancient events and manages linking and unlinking.
     */
    private final ConsensusLinker linker;

    /**
     * Executes the hashgraph consensus algorithm.
     */
    private final Consensus consensus;

    private final int roundsNonAncient;

    private final ConsensusEngineMetrics consensusEngineMetrics;

    private final FreezeRoundController freezeRoundController;

    /**
     * Constructor
     *
     * @param configuration          the configuration
     * @param metrics                the metrics registry
     * @param time                   the time source
     * @param roster                 the current roster
     * @param selfId                 the ID of the node
     * @param freezeChecker          checks if the consensus time has reached the freeze period
     * @param transactionOffsetNanos nanoseconds to add to the first transaction's timestamp in an event
     */
    public DefaultConsensusEngine(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final FreezePeriodChecker freezeChecker,
            final long transactionOffsetNanos) {
        final ConsensusMetrics consensusMetrics = new ConsensusMetricsImpl(selfId, metrics);
        consensus = new ConsensusImpl(configuration, time, consensusMetrics, roster, transactionOffsetNanos);

        linker = new ConsensusLinker(new DefaultLinkerLogsAndMetrics(metrics, time));
        roundsNonAncient = configuration.getConfigData(ConsensusConfig.class).roundsNonAncient();

        consensusEngineMetrics = new ConsensusEngineMetrics(selfId, metrics, time);
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
    public ConsensusEngineOutput addEvent(@NonNull final PlatformEvent event) {
        Objects.requireNonNull(event);

        if (freezeRoundController.isFrozen()) {
            // Once the freeze round has been reached, no further rounds should reach consensus. But the platform may
            // still need post-freeze events to be pre-handled, for example to collect freeze-state signatures or other
            // application-controlled freeze-completion work.
            return new ConsensusEngineOutput(List.of(event), List.of());
        }

        final Queue<PlatformEvent> eventsToAdd = new LinkedList<>();
        final List<PlatformEvent> preConsensusEvents = new ArrayList<>();
        eventsToAdd.add(event);

        final List<ConsensusResult> allConsensusResults = new ArrayList<>();

        while (!eventsToAdd.isEmpty()) {
            final PlatformEvent eventToAdd = eventsToAdd.poll();
            final EventImpl linkedEvent = linker.linkEvent(eventToAdd);
            if (linkedEvent == null) {
                // linker discarded an ancient event
                continue;
            }

            // check if we have found init judges before adding the event
            final boolean waitingForJudgesBeforeAdd = consensus.waitingForInitJudges();
            // add the event to the consensus algorithm
            final List<ConsensusRound> consensusRounds = consensus.addEvent(linkedEvent);

            for (final ConsensusRound consensusRound : consensusRounds) {
                consensusEngineMetrics.recordEventsPerRound(consensusRound.getNumEvents());
                consensusEngineMetrics.recordConsensusTime(consensusRound.getConsensusTimestamp());
            }

            // check if we have found init judges after adding the event
            final boolean waitingForJudgesAfterAdd = consensus.waitingForInitJudges();

            consensusEngineMetrics.eventAdded(linkedEvent);

            if (waitingForJudgesAfterAdd) {
                // If we haven't found all the init judges yet, we should return an empty output.
                // We should not return the event we just added, since we are not sure if it will be a pre-consensus
                // event. It may be that it has reached consensus previously, but we cannot know that until we found
                // all the init judges.
                return ConsensusEngineOutput.emptyInstance();
            }
            if (waitingForJudgesBeforeAdd) {
                // This means that we have just found the last init judge.

                // Most of the time, when we find the last init judge, we will not have any consensus rounds yet.
                // But there is a possibility that this could happen. The most likely scenario is that there has been
                // a major change in the roster.
                // If this happens, we need to add all events that just reached consensus to the list of pre-consensus
                // events. This is to ensure that all consensus events are returned as pre-consensus events.
                consensusRounds.stream()
                        .map(ConsensusRound::getPlatformEvents)
                        .flatMap(List::stream)
                        .forEach(preConsensusEvents::add);

                // Also add all pre-consensus events now that we can identify them.
                // This will include the event we just added.
                consensus.getPreConsensusEvents().stream()
                        .map(EventImpl::getBaseEvent)
                        .forEach(preConsensusEvents::add);
            } else {
                // Return the event we just added as a pre-consensus event.
                preConsensusEvents.add(linkedEvent.getBaseEvent());
            }

            if (consensusRounds.isEmpty()) {
                continue;
            }

            for (final ConsensusRound consensusRound : consensusRounds) {
                // If consensus is reached, we need to process each event window and add any events released
                // from the future event buffer to the consensus algorithm.
                final EventWindow eventWindow = consensusRound.getEventWindow();
                // We update the linker with the next event window.
                // This will also return any ancient events that were previously linked.
                // Some of these ancient events may be stale, so we will add them to the stale events list.
                final List<EventImpl> ancientEvents = linker.setEventWindow(eventWindow);
                final List<PlatformEvent> staleEvents = ancientEvents.stream()
                        .filter(e -> !e.isConsensus())
                        .map(EventImpl::getBaseEvent)
                        .toList();
                staleEvents.forEach(consensusEngineMetrics::reportStaleEvent);
                allConsensusResults.add(new ConsensusResult(consensusRound, staleEvents));
            }
        }

        // If multiple rounds reach consensus and multiple rounds are in the freeze period,
        // we need to freeze on the first one. this means discarding the rest of the rounds.
        final List<ConsensusResult> modifiedResults = freezeRoundController.filterAndModify(allConsensusResults);
        return new ConsensusEngineOutput(preConsensusEvents, modifiedResults);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void outOfBandSnapshotUpdate(@NonNull final ConsensusSnapshot snapshot) {
        final EventWindow eventWindow = EventWindowUtils.createEventWindow(snapshot, roundsNonAncient);
        linker.clear();
        linker.setEventWindow(eventWindow);
        consensus.loadSnapshot(snapshot);
    }
}
