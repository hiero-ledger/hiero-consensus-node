// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.hiero.base.utility.DurationUtils;
import org.hiero.consensus.config.PlatformStatusConfig;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.triggers.FallenBehindTrigger;
import org.hiero.consensus.status.triggers.FreezePeriodEnteredTrigger;
import org.hiero.consensus.status.triggers.SelfEventReachedConsensusTrigger;
import org.hiero.consensus.status.triggers.TimeElapsedTrigger;

/**
 * Class containing the state machine logic for the {@link PlatformStatus#ACTIVE} status.
 */
public class ActiveStatusLogic extends AbstractStatusLogic {
    /**
     * The platform status config
     */
    private final PlatformStatusConfig config;

    /**
     * The last wall clock time a self event was observed reaching consensus
     */
    private Instant lastWallClockTimeSelfEventReachedConsensus;

    /**
     * Constructor
     *
     * @param lastWallClockTimeSelfEventReachedConsensus the wall clock time when the self event that caused the
     *                                                   transition to {@link PlatformStatus#ACTIVE} reached consensus
     * @param config                                     the platform status config
     */
    public ActiveStatusLogic(
            @NonNull final Instant lastWallClockTimeSelfEventReachedConsensus,
            @NonNull final PlatformStatusConfig config) {

        super(PlatformStatus.ACTIVE);
        this.lastWallClockTimeSelfEventReachedConsensus =
                Objects.requireNonNull(lastWallClockTimeSelfEventReachedConsensus);
        this.config = Objects.requireNonNull(config);
    }

    /**
     * {@link PlatformStatus#ACTIVE} status unconditionally transitions to {@link PlatformStatus#BEHIND} when a
     * {@link FallenBehindTrigger} is processed.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onFallenBehind(@NonNull final FallenBehindTrigger trigger) {
        return new BehindStatusLogic(config);
    }

    /**
     * {@link PlatformStatus#ACTIVE} status unconditionally transitions to {@link PlatformStatus#FREEZING} when a
     * {@link FreezePeriodEnteredTrigger} is processed.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onFreezePeriodEntered(@NonNull final FreezePeriodEnteredTrigger trigger) {
        return new FreezingStatusLogic(trigger.freezeRound());
    }

    /**
     * Receiving a {@link SelfEventReachedConsensusTrigger} while in {@link PlatformStatus#ACTIVE} doesn't ever result in
     * a status transition, but this logic method does record the wall clock time the event reached consensus.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onSelfEventReachedConsensus(@NonNull final SelfEventReachedConsensusTrigger trigger) {
        lastWallClockTimeSelfEventReachedConsensus = trigger.wallClockTime();
        return this;
    }

    /**
     * When a {@link TimeElapsedTrigger} is received while in {@link PlatformStatus#ACTIVE}, this method evaluates whether
     * the platform should transition to {@link PlatformStatus#CHECKING} based on two timing conditions:
     * <ul>
     *   <li><b>Quiescing state check:</b> If the platform is currently quiescing, it remains in
     *       {@link PlatformStatus#ACTIVE} regardless of elapsed time.</li>
     *   <li><b>Time since (not/break) quiescence command:</b> If a grace period has not elapsed since the instruction to
     *   stop quiescence, the status remains {@link PlatformStatus#ACTIVE}. This gives a recently created new event after
     *   quiescing enough time to reach consensus.</li>
     *   <li><b>Time since last consensus:</b> If both above conditions pass, the method checks whether enough time has
     *     elapsed since the last self event reached consensus and transitions to {@link PlatformStatus#CHECKING} if
     *     so.</li>
     * </ul>
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onTimeElapsed(@NonNull final TimeElapsedTrigger trigger) {
        final var isQuiescing = trigger.quiescingStatus().isQuiescing();
        final boolean stopQuiesceGracePeriodElapsed = DurationUtils.isLonger(
                Duration.between(trigger.quiescingStatus().since(), trigger.instant()), config.activeStatusDelay());

        if (isQuiescing || !stopQuiesceGracePeriodElapsed) {
            return this;
        }

        final Duration timeSinceSelfEventReachedConsensus =
                Duration.between(lastWallClockTimeSelfEventReachedConsensus, trigger.instant());

        if (DurationUtils.isLonger(timeSinceSelfEventReachedConsensus, config.activeStatusDelay())) {
            return new CheckingStatusLogic(config);
        } else {
            return this;
        }
    }
}
