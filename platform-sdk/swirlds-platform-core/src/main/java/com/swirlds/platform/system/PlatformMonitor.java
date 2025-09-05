// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.state.StateSavingResult;
import org.hiero.consensus.model.status.PlatformStatus;

public interface PlatformMonitor {
    /**
     * Inform the state machine that time has elapsed
     *
     * @param time the current time
     * @return the new status after processing the time update, or null if the status did not change
     */
    @Nullable
    @InputWireLabel("evaluate status")
    PlatformStatus heartbeat(@NonNull Instant time);

    PlatformStatus stateWrittenToDisk(@NonNull StateSavingResult result);

    /**
     * Given an ISS notification, produce the appropriate status action.
     *
     * @param notifications a list of ISS notifications
     * @return the status action, or null if no action is needed
     */
    @Nullable
    PlatformStatus issNotification(List<IssNotification> notifications);

    /**
     * Submit a status action
     *
     * @param action the action to submit
     * @return the new status after processing the action, or null if the status did not change
     */
    @Nullable
    @InputWireLabel("PlatformStatusAction")
    PlatformStatus submitStatusAction(@NonNull final PlatformStatusAction action);

    PlatformStatus consensusRound(@NonNull final ConsensusRound round);
}
