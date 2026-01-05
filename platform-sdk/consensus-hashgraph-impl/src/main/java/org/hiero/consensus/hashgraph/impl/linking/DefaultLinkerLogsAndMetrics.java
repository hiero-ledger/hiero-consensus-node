// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.linking;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.base.time.Time;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.utility.throttle.RateLimitedLogger;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Default implementation of {@link LinkerLogsAndMetrics}
 */
public class DefaultLinkerLogsAndMetrics implements LinkerLogsAndMetrics {
    /**
     * The minimum period between log messages for a specific mode of failure.
     */
    private static final Duration MINIMUM_LOG_PERIOD = Duration.ofMinutes(1);

    private static final Logger logger = LogManager.getLogger(DefaultLinkerLogsAndMetrics.class);

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
    public DefaultLinkerLogsAndMetrics(@NonNull final Metrics metrics, @NonNull final Time time) {
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
     * {@inheritDoc}
     */
    @Override
    public void childHasMissingParent(
            @NonNull final PlatformEvent child, @NonNull final EventDescriptorWrapper parentDescriptor) {
        missingParentLogger.error(
                EXCEPTION.getMarker(),
                "Child has a missing parent. This should not be possible. Child: {}, Parent EventDescriptor: {}",
                child,
                parentDescriptor);
        missingParentAccumulator.update(1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void parentHasIncorrectBirthRound(
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
     * {@inheritDoc}
     */
    @Override
    public void childTimeIsNotAfterSelfParentTime(
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
