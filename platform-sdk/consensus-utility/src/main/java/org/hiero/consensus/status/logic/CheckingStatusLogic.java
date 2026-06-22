// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.config.PlatformStatusConfig;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.triggers.FallenBehindTrigger;
import org.hiero.consensus.status.triggers.FreezePeriodEnteredTrigger;
import org.hiero.consensus.status.triggers.SelfEventReachedConsensusTrigger;
import org.hiero.consensus.status.triggers.TimeElapsedTrigger;

/**
 * Class containing the state machine logic for the {@link PlatformStatus#CHECKING} status.
 */
public class CheckingStatusLogic extends AbstractStatusLogic {
    /**
     * The platform status config
     */
    private final PlatformStatusConfig config;

    /**
     * Constructor
     *
     * @param config the platform status config
     */
    public CheckingStatusLogic(@NonNull final PlatformStatusConfig config) {
        super(PlatformStatus.CHECKING);
        this.config = Objects.requireNonNull(config);
    }

    /**
     * {@link PlatformStatus#CHECKING} status unconditionally transitions to {@link PlatformStatus#BEHIND} when a
     * {@link FallenBehindTrigger} is processed.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onFallenBehind(@NonNull final FallenBehindTrigger trigger) {
        return new BehindStatusLogic(config);
    }

    /**
     * {@link PlatformStatus#CHECKING} status unconditionally transitions to {@link PlatformStatus#FREEZING} when a
     * {@link FreezePeriodEnteredTrigger} is processed.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onFreezePeriodEntered(@NonNull final FreezePeriodEnteredTrigger trigger) {
        return new FreezingStatusLogic(trigger.freezeRound());
    }

    /**
     * {@link PlatformStatus#CHECKING} status unconditionally transitions to {@link PlatformStatus#ACTIVE} when a
     * {@link SelfEventReachedConsensusTrigger} is processed.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onSelfEventReachedConsensus(@NonNull final SelfEventReachedConsensusTrigger trigger) {
        return new ActiveStatusLogic(trigger.wallClockTime(), config);
    }

    /**
     * When a {@link TimeElapsedTrigger} is received while in {@link PlatformStatus#CHECKING}, the status transitions to
     * {@link PlatformStatus#ACTIVE} if the platform is currently quiescing, otherwise it remains in
     * {@link PlatformStatus#CHECKING}.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onTimeElapsed(@NonNull final TimeElapsedTrigger trigger) {
        if (trigger.quiescingStatus().isQuiescing()) {
            return new ActiveStatusLogic(trigger.instant(), config);
        }
        return this;
    }
}
