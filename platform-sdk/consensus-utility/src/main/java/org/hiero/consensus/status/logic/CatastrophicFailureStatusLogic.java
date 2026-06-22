// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.triggers.StatusMachineTrigger;

/**
 * Class containing the state machine logic for the {@link PlatformStatus#CATASTROPHIC_FAILURE} status.
 * <p>
 * This status is terminal: no trigger can cause the status to transition from it.
 */
public class CatastrophicFailureStatusLogic implements PlatformStatusLogic {
    @NonNull
    @Override
    public PlatformStatusLogic process(@NonNull final StatusMachineTrigger trigger) {
        return this;
    }

    @NonNull
    @Override
    public PlatformStatus getStatus() {
        return PlatformStatus.CATASTROPHIC_FAILURE;
    }
}
