// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/** An interface for classes that calculate consensus of events */
public interface Consensus {

    /**
     * Set the flag to signal whether we are currently replaying the PCES (preconsensus event stream) or not.
     *
     * @param pcesMode true if we are currently replaying the PCES, false otherwise
     */
    void setPcesMode(final boolean pcesMode);

    /**
     * Get a list of events that were added to consensus that have not yet reached consensus. This list will not be
     * accurate if consensus is still waiting for init judges ({@link #waitingForInitJudges()} returns true).
     * @return a list of pre-consensus events
     */
    List<EventImpl> getPreConsensusEvents();

    /**
     * Adds an event to the consensus object. This should be the only public method that modifies the state of the
     * object.
     *
     * @param event the event to be added
     * @return A list of consensus rounds, each with a list of consensus events (that can be empty). The rounds are
     * stored in consensus order (round at index 0 occurs before the round at index 1 in consensus time). Returns an
     * empty list if no rounds reached consensus.
     */
    @NonNull
    List<ConsensusRound> addEvent(@NonNull EventImpl event);

    /**
     * Load consensus from a snapshot. This will continue consensus from the round of the snapshot once all the required
     * events are provided. This method is called at restart and reconnect boundaries.
     */
    void loadSnapshot(@NonNull ConsensusSnapshot snapshot);

    /**
     * When loading consensus from a snapshot (via {@link #loadSnapshot(ConsensusSnapshot)}), consensus has to receive
     * all init judges before it can proceed with consensus calculation. The method allows to check whether we are still
     * waiting for init judges to be added to consensus or not.
     *
     * @return true if consensus is still waiting for init judges
     */
    boolean waitingForInitJudges();

    /**
     * Return the max round number for which we have an event. If there are none yet, return {@link
     * ConsensusConstants#ROUND_UNDEFINED}.
     *
     * @return the max round number, or {@link ConsensusConstants#ROUND_UNDEFINED} if none.
     */
    long getMaxRound();

    /**
     * return the round number below which the fame of all witnesses has been decided for all earlier rounds.
     *
     * @return the round number
     */
    long getFameDecidedBelow();

    /**
     * @return the latest round for which fame has been decided
     */
    default long getLastRoundDecided() {
        return getFameDecidedBelow() - 1;
    }
}
