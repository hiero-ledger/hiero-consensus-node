// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.actions.PlatformStatusAction;

/**
 * Class containing the state machine logic for the {@link PlatformStatus#FREEZE_COMPLETE} status.
 * <p>
 * This status is terminal: no action can cause the status to transition from it.
 */
public class FreezeCompleteStatusLogic implements PlatformStatusLogic {
    @NonNull
    @Override
    public PlatformStatusLogic process(@NonNull final PlatformStatusAction action) {
        return this;
    }

    @NonNull
    @Override
    public PlatformStatus getStatus() {
        return PlatformStatus.FREEZE_COMPLETE;
    }
}
