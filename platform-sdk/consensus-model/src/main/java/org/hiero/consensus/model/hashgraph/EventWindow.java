// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.hashgraph;

import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_NEGATIVE_INFINITY;

import com.swirlds.base.utility.ToStringBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Describes the current window of events that the platform is using.
 *
 * @param latestConsensusRound the latest round that has come to consensus
 * @param newEventBirthRound   the round that newly created events should use as a birth round
 * @param ancientThreshold     the minimum ancient indicator value for an event to be considered non-ancient
 * @param expiredThreshold     the minimum ancient indicator value for an event to be considered not expired
 */
public record EventWindow(
        long latestConsensusRound, long newEventBirthRound, long ancientThreshold, long expiredThreshold) {

    /**
     * Create a new EventWindow with the given bounds. The latestConsensusRound must be greater than or equal to the
     * first round of consensus.  If the minimum round non-ancient is set to a number lower than the first round of
     * consensus, the first round of consensus is used instead.  The minGenNonAncient value must be greater than or
     * equal to the first generation for events.
     *
     * @throws IllegalArgumentException if the latestConsensusRound is less than the first round of consensus or if the
     *                                  minGenNonAncient value is less than the first generation for events.
     */
    public EventWindow {
        if (latestConsensusRound < ROUND_NEGATIVE_INFINITY) {
            throw new IllegalArgumentException(
                    "The latest consensus round cannot be less than ROUND_NEGATIVE_INFINITY (%d)."
                            .formatted(ROUND_NEGATIVE_INFINITY));
        }

        if (newEventBirthRound < ROUND_FIRST) {
            throw new IllegalArgumentException(
                    "The event birth round cannot be less than the first round (%d)".formatted(ROUND_FIRST));
        }

        if (ancientThreshold < ROUND_FIRST) {
            throw new IllegalArgumentException(
                    "the minimum round non-ancient cannot be lower than the first round of consensus.");
        }
        if (expiredThreshold < ROUND_FIRST) {
            throw new IllegalArgumentException(
                    "the minimum round non-expired cannot be lower than the first round of consensus.");
        }
    }

    /**
     * Creates a genesis event window
     *
     * @return a genesis event window
     */
    @NonNull
    public static EventWindow getGenesisEventWindow() {
        return new EventWindow(ROUND_NEGATIVE_INFINITY, ROUND_FIRST, ROUND_FIRST, ROUND_FIRST);
    }

    /**
     * @return true if this is a genesis event window, false otherwise.
     */
    public boolean isGenesis() {
        return latestConsensusRound == ROUND_NEGATIVE_INFINITY;
    }

    /**
     * The round that will come to consensus next.
     *
     * @return the pending round coming to consensus, i.e. 1  + the latestConsensusRound
     */
    public long getPendingConsensusRound() {
        return latestConsensusRound + 1;
    }

    /**
     * Determines if the given event is ancient.
     *
     * @param event the event to check for being ancient.
     * @return true if the event is ancient, false otherwise.
     */
    public boolean isAncient(@NonNull final PlatformEvent event) {
        return event.getBirthRound() < ancientThreshold;
    }

    /**
     * Determines if the given event is ancient.
     *
     * @param event the event to check for being ancient.
     * @return true if the event is ancient, false otherwise.
     */
    public boolean isAncient(@NonNull final EventDescriptorWrapper event) {
        return event.birthRound() < ancientThreshold;
    }

    /**
     * Determines if the given long value is ancient.
     *
     * @param testValue the value to check for being ancient.
     * @return true if the value is ancient, false otherwise.
     */
    public boolean isAncient(final long testValue) {
        return testValue < ancientThreshold;
    }

    @Override
    @NonNull
    public String toString() {
        return new ToStringBuilder(this)
                .append("latestConsensusRound", latestConsensusRound)
                .append("ancientThreshold", ancientThreshold)
                .append("expiredThreshold", expiredThreshold)
                .toString();
    }
}
