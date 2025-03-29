// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.hashgraph;

import static org.hiero.consensus.model.event.AncientMode.GENERATION_THRESHOLD;
import static org.hiero.consensus.model.event.EventConstants.FIRST_GENERATION;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_NEGATIVE_INFINITY;

import com.swirlds.base.utility.ToStringBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Describes the current window of events that the platform is using.
 */
public class EventWindow {

    private final AncientMode ancientMode;
    private final long latestConsensusRound;

    private final long ancientThreshold;
    private final long expiredThreshold;

    /**
     * Create a new EventWindow with the given bounds. The latestConsensusRound must be greater than or equal to the
     * first round of consensus.  If the minimum round non-ancient is set to a number lower than the first round of
     * consensus, the first round of consensus is used instead.  The minGenNonAncient value must be greater than or
     * equal to the first generation for events.
     *
     * @param latestConsensusRound the latest round that has come to consensus
     * @param ancientThreshold     the minimum ancient indicator value for an event to be considered non-ancient
     * @param expiredThreshold     the minimum ancient indicator value for an event to be considered not expired
     * @param ancientMode          the ancient mode
     * @throws IllegalArgumentException if the latestConsensusRound is less than the first round of consensus or if the
     *                                  minGenNonAncient value is less than the first generation for events.
     */
    public EventWindow(
            final long latestConsensusRound,
            final long ancientThreshold,
            final long expiredThreshold,
            @NonNull final AncientMode ancientMode) {

        if (latestConsensusRound < ROUND_NEGATIVE_INFINITY) {
            throw new IllegalArgumentException(
                    "The latest consensus round cannot be less than 0 (ROUND_NEGATIVE_INFINITY).");
        }

        if (ancientMode == GENERATION_THRESHOLD) {
            if (ancientThreshold < FIRST_GENERATION) {
                throw new IllegalArgumentException(
                        "the minimum generation non-ancient cannot be lower than the first generation for events.");
            }
            if (expiredThreshold < FIRST_GENERATION) {
                throw new IllegalArgumentException(
                        "the minimum generation non-expired cannot be lower than the first generation for events.");
            }
        } else {
            if (ancientThreshold < ROUND_FIRST) {
                throw new IllegalArgumentException(
                        "the minimum round non-ancient cannot be lower than the first round of consensus.");
            }
            if (expiredThreshold < ROUND_FIRST) {
                throw new IllegalArgumentException(
                        "the minimum round non-expired cannot be lower than the first round of consensus.");
            }
        }

        this.latestConsensusRound = latestConsensusRound;
        this.ancientMode = ancientMode;
        this.ancientThreshold = ancientThreshold;
        this.expiredThreshold = expiredThreshold;
    }

    /**
     * Creates a genesis event window for the given ancient mode.
     *
     * @param ancientMode the ancient mode to use
     * @return a genesis event window.
     */
    @NonNull
    public static EventWindow getGenesisEventWindow(@NonNull final AncientMode ancientMode) {
        final long firstIndicator = ancientMode == GENERATION_THRESHOLD ? FIRST_GENERATION : ROUND_FIRST;
        return new EventWindow(ROUND_NEGATIVE_INFINITY, firstIndicator, firstIndicator, ancientMode);
    }

    /**
     * @return true if this is a genesis event window, false otherwise.
     */
    public boolean isGenesis() {
        return latestConsensusRound == ROUND_NEGATIVE_INFINITY;
    }

    /**
     * @return the ancient mode.
     */
    @NonNull
    public AncientMode getAncientMode() {
        return ancientMode;
    }

    /**
     * The round that has come to consensus most recently.
     *
     * @return the latest round that has come to consensus
     */
    public long getLatestConsensusRound() {
        return latestConsensusRound;
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
     * Get the ancient threshold. All events with an ancient indicator less than this value are considered ancient.
     *
     * @return the minimum ancient indicator value for an event to be considered non ancient.
     */
    public long getAncientThreshold() {
        return ancientThreshold;
    }

    /**
     * Get the expired threshold. All events with an ancient indicator less than this value are considered expired.
     *
     * @return the minimum ancient indicator value for an event to be considered not expired.
     */
    public long getExpiredThreshold() {
        return expiredThreshold;
    }

    /**
     * Determines if the given event is ancient.
     *
     * @param event the event to check for being ancient.
     * @return true if the event is ancient, false otherwise.
     */
    public boolean isAncient(@NonNull final PlatformEvent event) {
        return ancientMode.selectIndicator(event) < ancientThreshold;
    }

    /**
     * Determines if the given event is ancient.
     *
     * @param event the event to check for being ancient.
     * @return true if the event is ancient, false otherwise.
     */
    public boolean isAncient(@NonNull final EventDescriptorWrapper event) {
        return ancientMode.selectIndicator(event) < ancientThreshold;
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
    public String toString() {
        return new ToStringBuilder(this)
                .append("latestConsensusRound", latestConsensusRound)
                .append("ancientMode", ancientMode)
                .append("ancientThreshold", ancientThreshold)
                .append("expiredThreshold", expiredThreshold)
                .toString();
    }
}
