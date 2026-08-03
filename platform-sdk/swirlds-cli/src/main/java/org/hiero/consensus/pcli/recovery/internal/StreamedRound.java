// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.recovery.internal;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import org.hiero.base.crypto.RunningHash;
import org.hiero.consensus.main.model.ConsensusEvent;
import org.hiero.consensus.main.model.Round;
import org.hiero.consensus.model.event.CesEvent;

/**
 * An implementation of a {@link Round} used by streaming classes.
 */
public class StreamedRound implements Round {

    private final List<ConsensusEvent> events;
    private final List<CesEvent> streamedEvents;
    private final long roundNumber;
    private final Instant consensusTimestamp;
    private final Roster consensusRoster;

    public StreamedRound(
            @NonNull final Roster consensusRoster,
            @NonNull final List<CesEvent> events,
            final long roundNumber,
            final long transactionOffsetNanos) {
        this.streamedEvents = requireNonNull(events);
        this.events = events.stream().map(ConsensusEvent.class::cast).toList();
        this.roundNumber = roundNumber;
        events.stream()
                .map(CesEvent::getPlatformEvent)
                .forEach(e -> e.setConsensusTimestampsOnTransactions(transactionOffsetNanos));
        consensusTimestamp = events.getLast().getPlatformEvent().getConsensusTimestamp();
        this.consensusRoster = requireNonNull(consensusRoster);
    }

    @NonNull
    public List<ConsensusEvent> getConsensusEvents() {
        return events;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getRoundNum() {
        return roundNumber;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEmpty() {
        return events.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Roster getConsensusRoster() {
        return consensusRoster;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Instant getConsensusTimestamp() {
        return consensusTimestamp;
    }

    @Override
    @NonNull
    public  ConsensusSnapshot getConsensusSnapshot() {
        return null;
    }

    @Override
    public boolean isPcesRound() {
        return false;
    }

    @Override
    public Instant getReachedConsTimestamp() {
        return null;
    }

    @NonNull
    @Override
    public RunningHash getLastEventRunningHash() {
        return streamedEvents.getLast().getRunningHash();
    }
}
