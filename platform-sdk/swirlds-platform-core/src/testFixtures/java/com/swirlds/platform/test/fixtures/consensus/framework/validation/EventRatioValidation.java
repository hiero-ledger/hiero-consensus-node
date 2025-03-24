// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;

public class EventRatioValidation implements ConsensusOutputValidation {
    /**
     * The minimum fraction of events (out of 1.0) that are expected to have reached consensus at
     * the end of the sequence.
     */
    private double minimumConsensusRatio;
    /**
     * The Maximum fraction of events (out of 1.0) that are expected to have reached consensus at
     * the end of the sequence.
     *
     * <p>The actual ratio of events reaching consensus may be greater than 1.0. This can happen if
     * events from a previous sequence reach consensus during this sequence.
     */
    private double maximumConsensusRatio;
    /** Get the minimum ratio of expected stale events. */
    private double minimumStaleRatio;
    /** Get the maximum ratio of expected stale events. */
    private double maximumStaleRatio;

    private EventRatioValidation(
            final double minimumConsensusRatio,
            final double maximumConsensusRatio,
            final double minimumStaleRatio,
            final double maximumStaleRatio) {
        this.minimumConsensusRatio = minimumConsensusRatio;
        this.maximumConsensusRatio = maximumConsensusRatio;
        this.minimumStaleRatio = minimumStaleRatio;
        this.maximumStaleRatio = maximumStaleRatio;
    }

    public static @NonNull EventRatioValidation blank() {
        return new EventRatioValidation(0d, Double.MAX_VALUE, 0d, Double.MAX_VALUE);
    }

    public static @NonNull EventRatioValidation standard() {
        return new EventRatioValidation(0.8, 1.0, 0.0, 0.01);
    }

    /**
     * Set the minimum fraction of events (out of 1.0) that are expected to have reached consensus
     * at the end of the sequence. Default 0.8.
     */
    public @NonNull EventRatioValidation setMinimumConsensusRatio(final double expectedConsensusRatio) {
        this.minimumConsensusRatio = expectedConsensusRatio;
        return this;
    }

    /**
     * Set the maximum fraction of events (out of 1.0) that are expected to have reached consensus
     * at the end of the sequence. Default 1.0.
     */
    public @NonNull EventRatioValidation setMaximumConsensusRatio(final double maximumConsensusRatio) {
        this.maximumConsensusRatio = maximumConsensusRatio;
        return this;
    }

    /**
     * Set the minimum ratio of expected stale events. Default 0.0.
     *
     * @return this
     */
    public @NonNull EventRatioValidation setMinimumStaleRatio(final double minimumStaleRatio) {
        this.minimumStaleRatio = minimumStaleRatio;
        return this;
    }

    /** Set the maximum ratio of expected stale events. Default 0.01. */
    public @NonNull EventRatioValidation setMaximumStaleRatio(final double maximumStaleRatio) {
        this.maximumStaleRatio = maximumStaleRatio;
        return this;
    }

    public void validate(@NonNull final ConsensusOutput output1, @NonNull final ConsensusOutput ignored) {
        // For each statistic we only need to check one list since other validators can verify them
        // to be identical.
        final List<PlatformEvent> allEvents1 = output1.getAddedEvents();
        final int numConsensus = output1.getConsensusRounds().stream()
                .mapToInt(r -> r.getConsensusEvents().size())
                .sum();

        if (allEvents1.isEmpty()) {
            // if no events were added, then there is nothing to validate
            return;
        }

        // Validate consensus ratio
        final double consensusRatio = ((double) numConsensus) / allEvents1.size();

        assertTrue(
                consensusRatio >= minimumConsensusRatio,
                String.format(
                        "Consensus ratio %s is less than the expected minimum %s",
                        consensusRatio, minimumConsensusRatio));
        assertTrue(
                consensusRatio <= maximumConsensusRatio,
                String.format(
                        "Consensus ratio %s is more than the expected maximum %s",
                        consensusRatio, maximumConsensusRatio));

        // Validate stale ratio
        final double staleRatio = ((double) output1.getStaleEvents().size()) / allEvents1.size();

        assertTrue(
                staleRatio >= minimumStaleRatio,
                String.format("Stale ratio %s is less than the expected minimum %s", staleRatio, minimumStaleRatio));
        assertTrue(
                staleRatio <= maximumStaleRatio,
                String.format("Stale ratio %s is more than the expected maximum %s", staleRatio, maximumStaleRatio));
    }
}
