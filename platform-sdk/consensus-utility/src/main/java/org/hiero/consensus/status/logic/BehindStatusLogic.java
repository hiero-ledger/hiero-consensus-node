// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.config.PlatformStatusConfig;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.triggers.FreezePeriodEnteredTrigger;
import org.hiero.consensus.status.triggers.ReconnectCompleteTrigger;

/**
 * Class containing the state machine logic for the {@link PlatformStatus#BEHIND} status.
 */
public class BehindStatusLogic extends AbstractStatusLogic {
    /**
     * The platform status config
     */
    private final PlatformStatusConfig config;

    /**
     * The round number of the freeze period if one has been entered, otherwise null
     */
    private Long freezeRound = null;

    /**
     * Constructor
     *
     * @param config the platform status config object
     */
    public BehindStatusLogic(@NonNull final PlatformStatusConfig config) {
        super(PlatformStatus.BEHIND);
        this.config = config;
    }

    /**
     * Receiving a {@link FreezePeriodEnteredTrigger} while in {@link PlatformStatus#BEHIND} doesn't ever result in a
     * status transition, but this logic method does record the freeze round, to be able to pass that information on to
     * the {@link ReconnectCompleteStatusLogic} once reconnect is complete.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onFreezePeriodEntered(@NonNull final FreezePeriodEnteredTrigger trigger) {
        freezeRound = validateFreezeRound(freezeRound, trigger);
        return this;
    }

    /**
     * {@link PlatformStatus#BEHIND} status unconditionally transitions to {@link PlatformStatus#RECONNECT_COMPLETE} when
     * a {@link ReconnectCompleteTrigger} is processed.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onReconnectComplete(@NonNull final ReconnectCompleteTrigger trigger) {
        return new ReconnectCompleteStatusLogic(trigger.reconnectStateRound(), freezeRound, config);
    }
}
