package org.hiero.consensus.model.test.fixtures.hashgraph;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * Builder class for creating instances of {@link EventWindow}.
 */
public class EventWindowBuilder {

    private Long latestConsensusRound;
    private Long ancientThreshold;
    private Long expiredThreshold;
    private AncientMode ancientMode;

    private EventWindowBuilder() {
    }

    public static EventWindowBuilder builder(){
        return new EventWindowBuilder();
    }

    public static EventWindowBuilder birthRoundMode(){
        return new EventWindowBuilder().setAncientMode(AncientMode.BIRTH_ROUND_THRESHOLD);
    }

    public static EventWindowBuilder generationMode(){
        return new EventWindowBuilder().setAncientMode(AncientMode.GENERATION_THRESHOLD);
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
     * Sets the ancient threshold.
     *
     * @param ancientThreshold the minimum ancient indicator value for an event to be considered non-ancient
     * @return the builder instance
     */
    public EventWindowBuilder setAncientThreshold(final long ancientThreshold) {
        this.ancientThreshold = ancientThreshold;
        return this;
    }

    /**
     * Sets the ancient threshold.
     *
     * @param ancientThreshold the minimum ancient indicator value for an event to be considered non-ancient
     * @return the builder instance
     */
    public EventWindowBuilder setAncientThresholdOrGenesis(final long ancientThreshold) {
        if(ancientMode == null){
            throw new IllegalArgumentException("Ancient mode must be set");
        }
        this.ancientThreshold = Math.max(ancientMode.getGenesisIndicator(), ancientThreshold);
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
     * Sets the expired threshold.
     *
     * @param expiredThreshold the minimum ancient indicator value for an event to be considered not expired
     * @return the builder instance
     */
    public @NonNull EventWindowBuilder setExpiredThresholdOrGenesis(final long expiredThreshold) {
        if(ancientMode == null){
            throw new IllegalArgumentException("Ancient mode must be set");
        }
        this.expiredThreshold = Math.max(ancientMode.getGenesisIndicator(), expiredThreshold);
        return this;
    }

    /**
     * Sets the ancient mode.
     *
     * @param ancientMode the mode for determining ancient events
     * @return the builder instance
     */
    public @NonNull EventWindowBuilder setAncientMode(@NonNull final AncientMode ancientMode) {
        this.ancientMode = ancientMode;
        return this;
    }

    /**
     * Builds and returns an instance of {@link EventWindow}.
     *
     * @return a new {@link EventWindow} instance
     * @throws IllegalArgumentException if any required fields are invalid
     */
    public EventWindow build() {
        if(this.ancientMode == null){
            throw new IllegalArgumentException("Ancient mode must be set");
        }
        return new EventWindow(
                latestConsensusRound == null ? ConsensusConstants.ROUND_FIRST : latestConsensusRound,
                ancientThreshold == null ? ancientMode.getGenesisIndicator() : ancientThreshold,
                expiredThreshold == null ? ancientMode.getGenesisIndicator() : expiredThreshold,
                ancientMode);
    }
}