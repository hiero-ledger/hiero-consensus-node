package com.swirlds.platform.event.linking;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;

public class ConsensusLinkerMetrics {
    private final LongAccumulator missingParentAccumulator;
    private final LongAccumulator birthRoundMismatchAccumulator;
    private final LongAccumulator timeCreatedMismatchAccumulator;


    public ConsensusLinkerMetrics(@NonNull final Metrics metrics) {
        missingParentAccumulator = metrics.getOrCreate(new LongAccumulator.Config(PLATFORM_CATEGORY, "missingParents")
                        .withDescription("Parent child relationships where a parent was missing"));
        birthRoundMismatchAccumulator = metrics.getOrCreate(
                        new LongAccumulator.Config(PLATFORM_CATEGORY, "parentBirthRoundMismatch")
                                .withDescription(
                                        "Parent child relationships where claimed parent birth round did not match actual parent birth round"));
        timeCreatedMismatchAccumulator = metrics.getOrCreate(
                        new LongAccumulator.Config(PLATFORM_CATEGORY, "timeCreatedMismatch")
                                .withDescription(
                                        "Parent child relationships where child time created wasn't strictly after parent time created"));

    }

    public void missingParent() {
        missingParentAccumulator.update(1);
    }

    public void birthRoundMismatch() {
        birthRoundMismatchAccumulator.update(1);
    }

    public void timeCreatedMismatch() {
        timeCreatedMismatchAccumulator.update(1);
    }
}
