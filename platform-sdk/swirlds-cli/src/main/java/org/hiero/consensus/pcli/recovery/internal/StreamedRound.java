// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.recovery.internal;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.hiero.base.crypto.RunningHash;
import org.hiero.consensus.model.event.CesEvent;
import org.hiero.consensus.model.hashgraph.Round;

/**
 * An implementation of a {@link Round} used by streaming classes.
 */
public class StreamedRound implements Round {

    private final List<CesEvent> events;
    private final long roundNumber;
    private final Instant consensusTimestamp;
    private final Roster consensusRoster;

    public StreamedRound(
            @NonNull final Roster consensusRoster,
            @NonNull final List<CesEvent> events,
            final long roundNumber,
            final long transactionOffsetNanos) {
        this.events = Collections.unmodifiableList(events);
        this.roundNumber = roundNumber;
        events.stream()
                .map(CesEvent::getPlatformEvent)
                .forEach(e -> e.setConsensusTimestampsOnTransactions(transactionOffsetNanos));
        consensusTimestamp = events.getLast().getPlatformEvent().getConsensusTimestamp();
        this.consensusRoster = Objects.requireNonNull(consensusRoster);
    }

    public @NonNull List<CesEvent> getConsensusEvents() {
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

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public ConsensusSnapshot getConsensusSnapshot() {
        throw new UnsupportedOperationException("Not currently supported");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isPcesRound() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Instant getReachedConsTimestamp() {
        throw new UnsupportedOperationException("Not currently supported");
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public RunningHash getLastEventRunningHash() {
        return events.getLast().getRunningHash();
    }
}
