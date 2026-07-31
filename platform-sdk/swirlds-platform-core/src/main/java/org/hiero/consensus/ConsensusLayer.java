// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

/**
 * initialization notes: If a freeze time is provided during construction of the consensus layer, that is the freeze
 * time and it must be honored. If there is no freeze time provided at construction time, there is no known future
 * freeze until this method is called.
 *
 * <p>The consensus layer does not need to know about lastFrozenTime. If a freeze time is provided
 * during construction of the consensus layer, that is the freeze time and it must be honored. If there is no freeze
 * time provided at construction time, there is no known future freeze until this method is called.
 */
public interface ConsensusLayer {

    void start();

    void destroy();

    /**
     * <p>This method causes more events to be added to the consensus algorithm. It will continue to feed events
     * into the consensus algorithm until the next round (or rounds) reach consensus. Then it will stop feeding it
     * events.
     *
     * <p>TODO Add roster behavior explanation
     *
     * <p>The freeze time informs the consensus layer of the next desired freeze time. A value of {@code null}
     * indicates that there is no freeze time, and any existing freeze time should be cleared.
     *
     * @param newRoster  a new roster to be applied at a deterministic number of rounds in the future, or {@code null}
     *                   if there is no new roster to adopt
     * @param freezeTime the next freeze time, or {@code null} if there is no planned freeze time
     */
    void requestNextRound(@Nullable final Roster newRoster, @Nullable final Instant freezeTime);

    /**
     * <p>Instructs the consensus layer on what its quiescence state should be. It will use the latest command that has
     * been provided. If multiple threads call this method at the same time, there is no guarantee about which command
     * will be used.
     *
     * <p>This command is also used to stop event creation once it is no longer needed during the freeze process. It is
     * important to stop event creation in order to limit memory usage, since events created after the freeze time are
     * not garbage collected.
     *
     * @param command the quiescence command
     */
    void sendQuiescenceCommand(@NonNull final QuiescenceCommand command);

    /**
     * Informs the consensus layer that there is a new oldest state we must be able to restart from. The consensus layer
     * uses this to determine which PCES files on disk are no longer needed and can be safely deleted.
     *
     * @param consensusSnapshot the oldest consensus snapshot we must be able to restart from
     */
    void oldestRestartableSnapshot(@NonNull final ConsensusSnapshot consensusSnapshot);

    /**
     * Informs the consensus layer of a status change based on events owned by the execution layer.
     *
     * @param status the new status
     */
    void onStatusUpdate(@NonNull final StatusUpdate status);

    enum StatusUpdate {
        /**
         * The node has completed the freeze.
         */
        FREEZE_COMPLETE,
        /**
         * The node has encountered a failure, and is unable to continue. The consensus layer is idle.
         */
        CATASTROPHIC_FAILURE;

    }
}
