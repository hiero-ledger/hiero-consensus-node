// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.test.fixtures.hashgraph;

import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * Builder class for creating instances of {@link EventWindow}.
 */
public class EventWindowBuilder {

    private Long latestConsensusRound;
    private Long newEventBirthRound;
    private Long ancientThreshold;
    private Long expiredThreshold;

    private EventWindowBuilder() {}

    /**
     * Creates a new instance
     * @return a new instance of {@link EventWindowBuilder}
     */
    public static @NonNull EventWindowBuilder builder() {
        return new EventWindowBuilder();
    }

    /**
     * Sets the latest consensus round.
     *
     * @param latestConsensusRound the latest round that has come to consensus
     * @return the builder instance
     */
    public @NonNull EventWindowBuilder setLatestConsensusRound(final long latestConsensusRound) {
        this.latestConsensusRound = latestConsensusRound;
        return this;
    }

    /**
     * Sets the event birth round.
     *
     * @param newEventBirthRound the birth round of newly created events
     * @return the builder instance
     */
    public @NonNull EventWindowBuilder setNewEventBirthRound(final long newEventBirthRound) {
        this.newEventBirthRound = newEventBirthRound;
        return this;
    }

    /**
     * Sets the ancient threshold.
     *
     * @param ancientThreshold the minimum ancient indicator value for an event to be considered non-ancient
     * @return the builder instance
     */
    public @NonNull EventWindowBuilder setAncientThreshold(final long ancientThreshold) {
        this.ancientThreshold = ancientThreshold;
        return this;
    }

    /**
     * Sets the ancient threshold. If the supplied threshold is less than the genesis indicator of the ancient mode,
     * it will be set to the genesis indicator.
     *
     * @param ancientThreshold the minimum ancient indicator value for an event to be considered non-ancient
     * @return the builder instance
     * @throws IllegalArgumentException if the ancient mode is not set
     */
    public @NonNull EventWindowBuilder setAncientThresholdOrGenesis(final long ancientThreshold) {
        this.ancientThreshold = Math.max(ROUND_FIRST, ancientThreshold);
        return this;
    }

    /**
     * Sets the expired threshold.
     *
     * @param expiredThreshold the minimum ancient indicator value for an event to be considered not expired
     * @return the builder instance
     */
    public @NonNull EventWindowBuilder setExpiredThreshold(final long expiredThreshold) {
        this.expiredThreshold = expiredThreshold;
        return this;
    }

    /**
     * Sets the expired threshold. If the supplied threshold is less than the genesis indicator of the ancient mode,
     * it will be set to the genesis indicator.
     *
     * @param expiredThreshold the minimum ancient indicator value for an event to be considered not expired
     * @return the builder instance
     * @throws IllegalArgumentException if the ancient mode is not set
     */
    public @NonNull EventWindowBuilder setExpiredThresholdOrGenesis(final long expiredThreshold) {
        this.expiredThreshold = Math.max(ROUND_FIRST, expiredThreshold);
        return this;
    }

    /**
     * Builds and returns an instance of {@link EventWindow}.
     *
     * @return a new {@link EventWindow} instance
     * @throws IllegalArgumentException if any required fields are invalid
     */
    public @NonNull EventWindow build() {
        return new EventWindow(
                latestConsensusRound == null ? ConsensusConstants.ROUND_FIRST : latestConsensusRound,
                newEventBirthRound == null ? ConsensusConstants.ROUND_FIRST : newEventBirthRound,
                ancientThreshold == null ? ROUND_FIRST : ancientThreshold,
                expiredThreshold == null ? ROUND_FIRST : expiredThreshold);
    }
}
