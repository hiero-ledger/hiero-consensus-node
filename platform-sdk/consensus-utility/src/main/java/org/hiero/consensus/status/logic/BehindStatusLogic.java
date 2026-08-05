// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.config.PlatformStatusConfig;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.actions.FreezePeriodEnteredAction;

/**
 * Class containing the state machine logic for the {@link PlatformStatus#BEHIND} status.
 */
public class BehindStatusLogic extends AbstractStatusLogic {

    /**
     * The round number of the freeze period if one has been entered, otherwise null
     */
    private Long freezeRound = null;

    /**
     * Constructor
     */
    public BehindStatusLogic() {
        super(PlatformStatus.BEHIND);
    }

    /**
     * Receiving a {@link FreezePeriodEnteredAction} while in {@link PlatformStatus#BEHIND} doesn't ever result in a
     * status transition, but this logic method does record the freeze round, to log an exception if we receive another.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onFreezePeriodEntered(@NonNull final FreezePeriodEnteredAction action) {
        validateFreezeRound(freezeRound, action);
        freezeRound = action.freezeRound();
        return this;
    }
}
