// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.hiero.consensus.config.PlatformStatusConfig;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.triggers.FallenBehindTrigger;
import org.hiero.consensus.status.triggers.FreezePeriodEnteredTrigger;
import org.hiero.consensus.status.triggers.TimeElapsedTrigger;

/**
 * Class containing the state machine logic for the {@link PlatformStatus#OBSERVING} status.
 */
public class ObservingStatusLogic extends AbstractStatusLogic {
    /**
     * The platform status config
     */
    private final PlatformStatusConfig config;

    /**
     * The time at which the platform entered the {@link PlatformStatus#OBSERVING} status
     */
    private final Instant statusStartTime;

    /**
     * The round number of the freeze period if one has been entered, otherwise null
     */
    private Long freezeRound = null;

    /**
     * Constructor
     *
     * @param statusStartTime the time at which the platform entered the {@link PlatformStatus#OBSERVING} status
     * @param config          the platform status config
     */
    public ObservingStatusLogic(@NonNull final Instant statusStartTime, @NonNull final PlatformStatusConfig config) {
        super(PlatformStatus.OBSERVING);
        this.statusStartTime = Objects.requireNonNull(statusStartTime);
        this.config = Objects.requireNonNull(config);
    }

    /**
     * {@link PlatformStatus#OBSERVING} status unconditionally transitions to {@link PlatformStatus#BEHIND} when a
     * {@link FallenBehindTrigger} is processed.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onFallenBehind(@NonNull final FallenBehindTrigger trigger) {
        return new BehindStatusLogic(config);
    }

    /**
     * Receiving a {@link FreezePeriodEnteredTrigger} while in {@link PlatformStatus#OBSERVING} doesn't ever result in a
     * status transition, but this logic method does record the freeze round, which will inform the status progression
     * once the observing period has elapsed.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onFreezePeriodEntered(@NonNull final FreezePeriodEnteredTrigger trigger) {
        freezeRound = validateFreezeRound(freezeRound, trigger);
        return this;
    }

    /**
     * {@link PlatformStatus#OBSERVING} status always transitions to a new status once the observation period has
     * elapsed.
     * <p>
     * The status transitions to {@link PlatformStatus#FREEZING} if a freeze period was entered during the observation
     * period, otherwise it transitions to {@link PlatformStatus#CHECKING}.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onTimeElapsed(@NonNull final TimeElapsedTrigger trigger) {
        if (Duration.between(statusStartTime, trigger.instant()).compareTo(config.observingStatusDelay()) < 0) {
            // if the wait period hasn't elapsed, then stay in this status
            return this;
        }

        if (freezeRound != null) {
            return new FreezingStatusLogic(freezeRound);
        } else {
            return new CheckingStatusLogic(config);
        }
    }
}
