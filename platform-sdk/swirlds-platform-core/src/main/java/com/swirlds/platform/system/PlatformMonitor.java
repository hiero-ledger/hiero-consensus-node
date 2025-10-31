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
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.state.StateSavingResult;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Monitors the platform and updates the platform's status state machine.
 */
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

    /**
     * Inform the monitor that a state has been written to disk
     *
     * @return the new status after processing this information, or null if the status did not change
     */
    @Nullable
    @InputWireLabel("state saving monitoring")
    PlatformStatus stateWrittenToDisk(@NonNull StateSavingResult result);

    /**
     * Inform the monitor of ISS notifications
     *
     * @param notifications a list of ISS notifications
     * @return the new status after processing this information, or null if the status did not change
     */
    @Nullable
    @InputWireLabel("ISS notification monitoring")
    PlatformStatus issNotification(@NonNull List<IssNotification> notifications);

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
     * Inform the monitor that a round has reached consensus
     *
     * @return the new status after processing this information, or null if the status did not change
     */
    @Nullable
    @InputWireLabel("monitor consensus round")
    PlatformStatus consensusRound(@NonNull final ConsensusRound round);

    /**
     * Inform the monitor the last requested quiescence command
     */
    @InputWireLabel("sets the quiescence command")
    void quiescenceCommand(@NonNull final QuiescenceCommand command);
}
