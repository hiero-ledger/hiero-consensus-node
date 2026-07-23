// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status;

import static com.swirlds.base.units.TimeUnit.UNIT_MILLISECONDS;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.PLATFORM_STATUS;
import static java.util.Objects.requireNonNull;

import com.swirlds.base.formatting.UnitFormatter;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.logging.legacy.payload.PlatformStatusPayload;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.config.PlatformStatusConfig;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.actions.PlatformStatusAction;
import org.hiero.consensus.status.logic.PlatformStatusLogic;
import org.hiero.consensus.status.logic.StartingUpStatusLogic;

/**
 * A state machine that processes {@link PlatformStatusAction}s
 */
public class StatusStateMachine {
    private static final Logger logger = LogManager.getLogger(StatusStateMachine.class);

    /**
     * A source of time
     */
    private final Time time;

    /**
     * The object containing the state machine logic for the current status
     */
    private PlatformStatusLogic currentStatusLogic;

    /**
     * The time at which the current status started
     */
    private Instant currentStatusStartTime;

    private final PlatformStatusMetrics metrics;

    /**
     * Constructor
     *
     * @param configuration the configuration
     * @param metrics the metrics system
     * @param time a source of time
     */
    public StatusStateMachine(
            @NonNull final Configuration configuration, @NonNull final Metrics metrics, @NonNull final Time time) {
        this.time = requireNonNull(time);
        this.currentStatusLogic = new StartingUpStatusLogic(configuration.getConfigData(PlatformStatusConfig.class));
        this.currentStatusStartTime = time.now();
        this.metrics = new PlatformStatusMetrics(metrics);
    }

    /**
     * Passes the received action to the current status logic and returns whatever it returns.
     * <p>
     * If the logic throws an {@link IllegalPlatformStatusException}, this method will log the exception and return null.
     *
     * @param action the action to process
     * @return a new logic object, or null if the logic threw an exception
     */
    @Nullable
    private PlatformStatusLogic getNewLogic(@NonNull final PlatformStatusAction action) {
        requireNonNull(action);
        try {
            return currentStatusLogic.process(action);
        } catch (final IllegalPlatformStatusException e) {
            logger.error(EXCEPTION.getMarker(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Process a platform status action.
     * <p>
     * Repeated calls of this method cause the platform state machine to be traversed
     *
     * @param action the action to process
     * @return the new status after processing the action, or null if the status did not change
     */
    @Nullable
    public PlatformStatus submitStatusAction(@NonNull final PlatformStatusAction action) {
        requireNonNull(action);

        final PlatformStatusLogic newLogic = getNewLogic(action);

        if (newLogic == null || newLogic == currentStatusLogic) {
            // if status didn't change, there isn't anything to do
            return null;
        }

        final String previousStatusName = currentStatusLogic.getStatus().name();
        final String newStatusName = newLogic.getStatus().name();

        final Duration statusDuration = Duration.between(currentStatusStartTime, time.now());
        final UnitFormatter unitFormatter = new UnitFormatter(statusDuration.toMillis(), UNIT_MILLISECONDS);

        final String statusChangeMessage = "Platform spent %s in %s. Now in %s"
                .formatted(unitFormatter.render(), previousStatusName, newStatusName);

        logger.info(
                PLATFORM_STATUS.getMarker(),
                () -> new PlatformStatusPayload(statusChangeMessage, previousStatusName, newStatusName).toString());

        currentStatusLogic = newLogic;

        final PlatformStatus newStatus = currentStatusLogic.getStatus();
        currentStatusStartTime = time.now();

        metrics.setCurrentStatus(newStatus);
        return newStatus;
    }
}
