package org.hiero.consensus.hashgraph.impl;

import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.hiero.consensus.event.FutureEventBuffer;
import org.hiero.consensus.event.FutureEventBufferingOption;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.round.EventWindowUtils;

public class DefaultConsensusEngineBuffer implements ConsensusEngineBuffer {

    private final ConsensusEngine consensusEngine;

    /** Buffers events until needed by the consensus algorithm based on their birth round */
    private final FutureEventBuffer futureEventBuffer;

    /** Holds the computed results of the ConsensusEngine, but have not yet been requested by the execution layer. */
    private final Queue<ConsensusResult> consensusResultQueue = new ArrayDeque<>();

    /** These events have been released from the FEB, but have not yet been sent to the ConsensusEngine. */
    private final Queue<PlatformEvent> pendingEventQueue = new ArrayDeque<>();

    /** The number of requests for rounds that have not yet been fulfilled. */
    private int requestCounter;

    private final int roundsNonAncient;


    public record ConsensusEngineBufferOutput(@NonNull List<PlatformEvent> preConsensusEvents,
                                              @NonNull List<ConsensusResult> consensusResults) {
        public static final ConsensusEngineBufferOutput EMPTY_INSTANCE = new ConsensusEngineBufferOutput(List.of(),
                List.of());
    }

    public DefaultConsensusEngineBuffer(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final ConsensusEngine consensusEngine) {
        this.consensusEngine = consensusEngine;
        futureEventBuffer =
                new FutureEventBuffer(metrics, FutureEventBufferingOption.PENDING_CONSENSUS_ROUND, "consensus");
        roundsNonAncient = configuration.getConfigData(ConsensusConfig.class).roundsNonAncient();
    }

    @NonNull
    @Override
    public ConsensusEngineBufferOutput requestRound() {
        requestCounter++;
        return maybeGetConsensusResult();
    }

    @NonNull
    @Override
    public ConsensusEngineBufferOutput addEvent(@NonNull final PlatformEvent event) {
        final PlatformEvent consensusRelevantEvent = futureEventBuffer.addEvent(event);
        if (consensusRelevantEvent == null) {
            // The event is either a future event or an ancient event.
            // If it is a future event, it will be added later when the event window is updated.
            return ConsensusEngineBufferOutput.EMPTY_INSTANCE;
        }
        pendingEventQueue.add(consensusRelevantEvent);
        return maybeGetConsensusResult();
    }

    private ConsensusEngineBufferOutput maybeGetConsensusResult() {
        if (requestCounter == 0) {
            return ConsensusEngineBufferOutput.EMPTY_INSTANCE;
        }

        final List<ConsensusResult> resultsToReturn = getBufferedConsensusResults();

        final List<PlatformEvent> preConsensusEvents = new ArrayList<>();
        while (requestCounter > 0 && !pendingEventQueue.isEmpty()) {
            final PlatformEvent eventToAdd = pendingEventQueue.poll();
            final ConsensusEngineOutput output = consensusEngine.addEvent(eventToAdd);
            preConsensusEvents.addAll(output.preConsensusEvents());
            final ConsensusResult firstResult = addResultsToBuffer(output.consensusResult());
            resultsToReturn.add(firstResult);
            advanceFutureEventBufferWindow(firstResult.consensusRound().getEventWindow());
            requestCounter--;
            resultsToReturn.addAll(getBufferedConsensusResults());
        }

        return new ConsensusEngineBufferOutput(preConsensusEvents, resultsToReturn);
    }

    /**
     * Drains the consensus result queue up to the requestCounter number of items and returns them. The requestCounter
     * is decremented for each item returned.
     */
    private List<ConsensusResult> getBufferedConsensusResults() {
        final List<ConsensusResult> results = new ArrayList<>();
        while (requestCounter > 0 && !consensusResultQueue.isEmpty()) {
            final ConsensusResult nextResult = consensusResultQueue.poll();
            if (nextResult != null) {
                results.add(nextResult);
                advanceFutureEventBufferWindow(nextResult.consensusRound().getEventWindow());
                requestCounter--;
            }
        }
        return results;
    }

    private void advanceFutureEventBufferWindow(@NonNull final EventWindow eventWindow) {
        pendingEventQueue.addAll(futureEventBuffer.updateEventWindow(eventWindow));
    }

    @NonNull
    private ConsensusResult addResultsToBuffer(@NonNull final List<ConsensusResult> output) {
        consensusResultQueue.addAll(output.subList(1, output.size()));
        return output.getFirst();
    }

    @Override
    public void outOfBandSnapshotUpdate(@NonNull final ConsensusSnapshot snapshot) {
        final EventWindow eventWindow = EventWindowUtils.createEventWindow(snapshot, roundsNonAncient);
        futureEventBuffer.clear();
        futureEventBuffer.updateEventWindow(eventWindow);
        consensusEngine.outOfBandSnapshotUpdate(snapshot);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus platformStatus) {
        consensusEngine.updatePlatformStatus(platformStatus);
    }
}
