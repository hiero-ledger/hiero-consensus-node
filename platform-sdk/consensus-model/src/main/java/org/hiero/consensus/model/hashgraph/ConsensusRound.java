// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.hashgraph;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.utility.ToStringBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.hiero.base.iterator.TypedIterator;
import org.hiero.base.utility.CommonUtils;
import org.hiero.consensus.model.event.CesEvent;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.transaction.Transaction;

/** A consensus round with events and all other relevant data. */
public class ConsensusRound implements Round {

    /**
     * an unmodifiable list of consensus events in this round, in consensus order
     */
    private final List<PlatformEvent> consensusEvents;
    /**
     * the same events that are stored in {@link #consensusEvents} but repackaged for the Consensus Event Stream. since
     * the CES is something that will be removed as soon as possible, this additional list allows us to decouple the CES
     * from the rest of the event structure.
     */
    private final List<CesEvent> streamedEvents;

    /**
     * the event window for this round
     */
    private final EventWindow eventWindow;

    /**
     * The number of application transactions in this round
     */
    private int numAppTransactions = 0;

    /**
     * A snapshot of consensus at this consensus round
     */
    private final ConsensusSnapshot snapshot;

    /**
     * The consensus roster for this round.
     */
    private final Roster consensusRoster;

    /**
     * True if this round reached consensus during the replaying of the preconsensus event stream.
     */
    private final boolean pcesRound;
    /** the local time (not consensus time) at which this round reached consensus */
    private final Instant reachedConsTimestamp;

    /**
     * Create a new instance with the provided consensus events.
     *
     * @param consensusRoster      the consensus roster for this round
     * @param consensusEvents      the events in the round, in consensus order
     * @param eventWindow          the event window for this round
     * @param snapshot             snapshot of consensus at this round
     * @param pcesRound            true if this round reached consensus during the replaying of the preconsensus event
     *                             stream
     * @param reachedConsTimestamp the local time (not consensus time) at which this round reached consensus
     */
    public ConsensusRound(
            @NonNull final Roster consensusRoster,
            @NonNull final List<PlatformEvent> consensusEvents,
            @NonNull final EventWindow eventWindow,
            @NonNull final ConsensusSnapshot snapshot,
            final boolean pcesRound,
            @NonNull final Instant reachedConsTimestamp) {

        this.consensusRoster = Objects.requireNonNull(consensusRoster);
        this.consensusEvents = Collections.unmodifiableList(Objects.requireNonNull(consensusEvents));
        this.eventWindow = Objects.requireNonNull(eventWindow);
        this.snapshot = Objects.requireNonNull(snapshot);
        this.pcesRound = pcesRound;
        this.reachedConsTimestamp = Objects.requireNonNull(reachedConsTimestamp);

        this.streamedEvents = new ArrayList<>(consensusEvents.size());
        for (final Iterator<PlatformEvent> iterator = consensusEvents.iterator(); iterator.hasNext(); ) {
            final PlatformEvent e = iterator.next();
            final Iterator<Transaction> ti = e.transactionIterator();
            while (ti.hasNext()) {
                ti.next();
                numAppTransactions++;
            }
            streamedEvents.add(new CesEvent(e, snapshot.round(), !iterator.hasNext()));
        }
    }

    /**
     * Returns the number of application transactions in this round
     *
     * @return the number of application transactions
     */
    public int getNumAppTransactions() {
        return numAppTransactions;
    }

    /**
     * Provides an unmodifiable list of the consensus event in this round.
     *
     * @return the list of events in this round
     */
    public @NonNull List<PlatformEvent> getConsensusEvents() {
        return consensusEvents;
    }

    /**
     * @return the list of CES events in this round
     */
    public @NonNull List<CesEvent> getStreamedEvents() {
        return streamedEvents;
    }

    /**
     * @return the event window for this round
     */
    public @NonNull EventWindow getEventWindow() {
        return eventWindow;
    }

    /**
     * @return a snapshot of consensus at this consensus round
     */
    public @NonNull ConsensusSnapshot getSnapshot() {
        return snapshot;
    }

    /**
     * @return the number of events in this round
     */
    public int getNumEvents() {
        return consensusEvents.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Iterator<ConsensusEvent> iterator() {
        return new TypedIterator<>(consensusEvents.iterator());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getRoundNum() {
        return snapshot.round();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEmpty() {
        return consensusEvents.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getEventCount() {
        return consensusEvents.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Roster getConsensusRoster() {
        return consensusRoster;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Instant getConsensusTimestamp() {
        return Objects.requireNonNull(CommonUtils.fromPbjTimestamp(snapshot.consensusTimestamp()));
    }

    /**
     * @return the local time (not consensus time) at which the round reached consensus
     */
    public @NonNull Instant getReachedConsTimestamp() {
        return reachedConsTimestamp;
    }

    public boolean isPcesRound() {
        return pcesRound;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        final ConsensusRound that = (ConsensusRound) other;
        return Objects.equals(consensusEvents, that.consensusEvents);
    }

    @Override
    public int hashCode() {
        return Objects.hash(consensusEvents);
    }

    @Override
    public String toString() {
        final String eventStrings = consensusEvents.stream()
                .map(event -> event.getDescriptor().toString())
                .collect(Collectors.joining(","));

        return new ToStringBuilder(this)
                .append("round", snapshot.round())
                .append("consensus events", eventStrings)
                .toString();
    }
}
