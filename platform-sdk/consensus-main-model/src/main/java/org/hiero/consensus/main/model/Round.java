// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.main.model;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import org.hiero.base.crypto.RunningHash;

/**
 * A collection of unique events that reached consensus at the same time. The consensus data for every event in the
 * round will never change, and no more events will ever be added to the round. A round with a lower round number will
 * always reach consensus before a round with a higher round number.
 * <p>
 * IMPORTANT: Although this interface is not sealed, it should only be implemented by internal classes. This interface
 * may be changed at any time, in any way, without notice or prior deprecation. Third parties should NOT implement this
 * interface.
 */
public interface Round {

    /**
     * Provides the unique round number for this round. Lower numbers reach consensus before higher numbers. This method
     * is thread safe.
     *
     * @return the round number
     */
    long getRoundNum();

    /**
     * Check if the round is empty.
     *
     * @return true if this round has no events, else returns false.
     */
    default boolean isEmpty() {
        return getConsensusEvents().isEmpty();
    }

    /**
     * Get the events in this round, in consensus order.
     *
     * @return the events of the round
     */
    List<ConsensusEvent> getConsensusEvents();

    /**
     * Get the roster that was used to compute consensus for this round.
     *
     * @return the roster that was used to compute consensus for this round
     */
    @NonNull
    Roster getConsensusRoster();

    /**
     * The timestamp of the end of a round. Is equal to the consensus timestamp of the last transaction in the last
     * event in the round, or the consensus timestamp of the last event in the round if there are no transactions in the
     * last event. The consensus timestamp of a round is guaranteed to be strictly greater than the consensus timestamp
     * of the previous round.
     *
     * @return the timestamp of the end of a round
     */
    @NonNull
    Instant getConsensusTimestamp();

    /**
     * The consensus snapshot for this round. This data must be persisted in order for the consensus layer to recover
     * from this point in consensus time.
     *
     * @return the consensus snapshot of this round
     */
    @NonNull
    ConsensusSnapshot getConsensusSnapshot();

    /**
     * Indicates if this round reached consensus while replaying PCES data from disk. This happens after the node
     * restarts in order to recover the latest consensus state.
     *
     * @return true if the round reached consensus while replaying data from disk, false otherwise
     */
    boolean isPcesRound();

    /**
     * The wall clock time at which this round reached consensus. Used for metrics.
     *
     * @return the wall clock time this round reached consensus
     */
    Instant getReachedConsTimestamp();

    @NonNull
    RunningHash getLastEventRunningHash();
}
