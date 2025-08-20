// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.linking;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.base.time.Time;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Logs and metrics for the {@link ConsensusLinker}
 */
public class LinkerLogsAndMetrics {
    /**
     * The minimum period between log messages for a specific mode of failure.
     */
    private static final Duration MINIMUM_LOG_PERIOD = Duration.ofMinutes(1);

    private static final Logger logger = LogManager.getLogger(LinkerLogsAndMetrics.class);

    private final RateLimitedLogger missingParentLogger;
    private final RateLimitedLogger birthRoundMismatchLogger;
    private final RateLimitedLogger timeCreatedMismatchLogger;

    private final LongAccumulator missingParentAccumulator;
    private final LongAccumulator birthRoundMismatchAccumulator;
    private final LongAccumulator timeCreatedMismatchAccumulator;

    /**
     * Constructor.
     *
     * @param metrics the metrics instance to use
     * @param time    the time instance to use for log rate limiting
     */
    public LinkerLogsAndMetrics(@NonNull final Metrics metrics, @NonNull final Time time) {
        this.missingParentLogger = new RateLimitedLogger(logger, time, MINIMUM_LOG_PERIOD);
        this.birthRoundMismatchLogger = new RateLimitedLogger(logger, time, MINIMUM_LOG_PERIOD);
        this.timeCreatedMismatchLogger = new RateLimitedLogger(logger, time, MINIMUM_LOG_PERIOD);

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

    /**
     * This method is called when a child event has a missing parent.
     *
     * @param child            the child event
     * @param parentDescriptor the descriptor of the missing parent
     */
    protected void childHasMissingParent(
            @NonNull final PlatformEvent child, @NonNull final EventDescriptorWrapper parentDescriptor) {
        missingParentLogger.error(
                EXCEPTION.getMarker(),
                "Child has a missing parent. This should not be possible. Child: {}, Parent EventDescriptor: {}",
                child,
                parentDescriptor);
        missingParentAccumulator.update(1);
    }

    /**
     * This method is called when a child event has a parent with a different birth round than claimed.
     *
     * @param child            the child event
     * @param parentDescriptor the claimed descriptor of the parent
     * @param candidateParent  the parent event that we found in the parentHashMap
     */
    protected void parentHasIncorrectBirthRound(
            @NonNull final PlatformEvent child,
            @NonNull final EventDescriptorWrapper parentDescriptor,
            @NonNull final EventImpl candidateParent) {
        birthRoundMismatchLogger.warn(
                EXCEPTION.getMarker(),
                "Event has a parent with a different birth round than claimed. Child: {}, parent: {}, "
                        + "claimed birth round: {}, actual birth round: {}",
                child,
                candidateParent,
                parentDescriptor.eventDescriptor().birthRound(),
                candidateParent.getBirthRound());
        birthRoundMismatchAccumulator.update(1);
    }

    /**
     * This method is called when a child event has a self parent with a time created that is not strictly before the
     * child's time created.
     *
     * @param child             the child event
     * @param candidateParent   the parent event that we found in the parentHashMap
     * @param parentTimeCreated the time created of the parent event
     * @param childTimeCreated  the time created of the child event
     */
    protected void childTimeIsNotAfterSelfParentTime(
            @NonNull final PlatformEvent child,
            @NonNull final EventImpl candidateParent,
            @NonNull final Instant parentTimeCreated,
            @NonNull final Instant childTimeCreated) {
        timeCreatedMismatchLogger.error(
                EXCEPTION.getMarker(),
                "Child time created isn't strictly after self parent time created. "
                        + "Child: {}, parent: {}, child time created: {}, parent time created: {}",
                child,
                candidateParent,
                childTimeCreated,
                parentTimeCreated);
        timeCreatedMismatchAccumulator.update(1);
    }
}
