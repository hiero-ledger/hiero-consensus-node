// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.signed;

import static com.swirlds.base.units.TimeUnit.UNIT_MICROSECONDS;
import static com.swirlds.base.units.TimeUnit.UNIT_NANOSECONDS;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.metrics.RunningAverageMetric;

/**
 * This class is responsible for the deletion of signed states. In case signed state deletion is expensive, we never
 * want to delete a signed state on the last thread that releases it.
 */
public class DefaultStateGarbageCollector implements StateGarbageCollector {

    private static final Logger logger = LogManager.getLogger(DefaultStateGarbageCollector.class);

    private final List<SignedState> states = new LinkedList<>();

    private static final RunningAverageMetric.Config STATES_INELIGIBLE_FOR_DELETION = new RunningAverageMetric.Config(
                    "platform", "statesIneligibleForDeletion")
            .withDescription(
                    "Average number of states in the state garbage collector that are not yet eligible for deletion")
            .withUnit("count");
    private final RunningAverageMetric undeletableStates;

    private static final RunningAverageMetric.Config TIME_TO_DELETE_STATE_CONFIG = new RunningAverageMetric.Config(
                    "platform", "timeToDeleteState")
            .withDescription("Average time to delete a state in the state garbage collector")
            .withUnit("microseconds");
    private final RunningAverageMetric timeToDeleteState;

    /**
     * Create a new instance of the garbage collector.
     *
     * @param metrics the metrics system to use for recording garbage collector metrics
     */
    public DefaultStateGarbageCollector(@NonNull final Metrics metrics) {
        undeletableStates = metrics.getOrCreate(STATES_INELIGIBLE_FOR_DELETION);
        timeToDeleteState = metrics.getOrCreate(TIME_TO_DELETE_STATE_CONFIG);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerState(@NonNull final ReservedSignedState reservedState) {
        try (reservedState) {
            final SignedState state = reservedState.get();
            if (state.shouldDeleteOnBackgroundThread()) {
                // Intentionally hold a java reference without a signed state reference count.
                // This is the only place in the codebase that is allowed to do this.
                states.add(state);
            } else {
                logger.error(
                        EXCEPTION.getMarker(),
                        "State for round {} is not configured to be deleted on background thread",
                        state.getRound());
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void heartbeat() {
        final Iterator<SignedState> iterator = states.iterator();
        while (iterator.hasNext()) {
            final SignedState signedState = iterator.next();
            if (signedState.isEligibleForDeletion()) {

                final Instant start = Instant.now();
                signedState.delete();
                final Instant end = Instant.now();
                final Duration duration = Duration.between(start, end);
                timeToDeleteState.update(UNIT_NANOSECONDS.convertTo(duration.toNanos(), UNIT_MICROSECONDS));

                iterator.remove();
            }
        }

        undeletableStates.update(states.size());
    }
}
