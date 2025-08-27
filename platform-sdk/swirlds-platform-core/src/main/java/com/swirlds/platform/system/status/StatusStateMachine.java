// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.status;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * A state machine that processes {@link PlatformStatusAction}s
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

    /**
     * Inform the state machine that time has elapsed
     *
     * @param time the current time
     * @return the new status after processing the time update, or null if the status did not change
     */
    @Nullable
    @InputWireLabel("evaluate status")
    PlatformStatus heartbeat(@NonNull final Instant time);
}
