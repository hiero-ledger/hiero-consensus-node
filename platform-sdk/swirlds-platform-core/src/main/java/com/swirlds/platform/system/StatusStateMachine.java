// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Monitors the platform and updates the platform's status state machine.
 */
public interface StatusStateMachine {

    /**
     * Submit a status action
     *
     * @param action the action to submit
     * @return the new status after processing the action, or null if the status did not change
     */
    @Nullable
    @InputWireLabel("PlatformStatusAction")
    PlatformStatus submitStatusAction(@NonNull final PlatformStatusAction action);
}
