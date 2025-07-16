// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.linking;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Links events to their parents. Expects events to be provided in topological order.
 * <p>
 * This implementation adds extra metrics to the base class.
 */
public class ConsensusLinker extends AbstractInOrderLinker {

    private final LongAccumulator missingParentAccumulator;
    private final LongAccumulator birthRoundMismatchAccumulator;
    private final LongAccumulator timeCreatedMismatchAccumulator;

    /**
     * Constructor
     *
     * @param platformContext    the platform context
     */
    public ConsensusLinker(@NonNull final PlatformContext platformContext) {
        super(platformContext);

        missingParentAccumulator = platformContext
                .getMetrics()
                .getOrCreate(new LongAccumulator.Config(PLATFORM_CATEGORY, "missingParents")
                        .withDescription("Parent child relationships where a parent was missing"));
        birthRoundMismatchAccumulator = platformContext
                .getMetrics()
                .getOrCreate(
                        new LongAccumulator.Config(PLATFORM_CATEGORY, "parentBirthRoundMismatch")
                                .withDescription(
                                        "Parent child relationships where claimed parent birth round did not match actual parent birth round"));
        timeCreatedMismatchAccumulator = platformContext
                .getMetrics()
                .getOrCreate(
                        new LongAccumulator.Config(PLATFORM_CATEGORY, "timeCreatedMismatch")
                                .withDescription(
                                        "Parent child relationships where child time created wasn't strictly after parent time created"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void eventHasBecomeAncient(@NonNull final EventImpl event) {
        event.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void childHasMissingParent(
            @NonNull final PlatformEvent child, @NonNull final EventDescriptorWrapper parentDescriptor) {
        super.childHasMissingParent(child, parentDescriptor);
        missingParentAccumulator.update(1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void parentHasIncorrectBirthRound(
            @NonNull final PlatformEvent child,
            @NonNull final EventDescriptorWrapper parentDescriptor,
            @NonNull final EventImpl candidateParent) {
        super.parentHasIncorrectBirthRound(child, parentDescriptor, candidateParent);
        birthRoundMismatchAccumulator.update(1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void childTimeIsNotAfterSelfParentTime(
            @NonNull final PlatformEvent child,
            @NonNull final EventImpl candidateParent,
            @NonNull final Instant parentTimeCreated,
            @NonNull final Instant childTimeCreated) {
        super.childTimeIsNotAfterSelfParentTime(child, candidateParent, parentTimeCreated, childTimeCreated);
        timeCreatedMismatchAccumulator.update(1);
    }
}
