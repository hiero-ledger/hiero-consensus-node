// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.main;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import org.hiero.consensus.main.model.QuiescenceCommand;
import org.hiero.consensus.main.model.Round;

/**
 * The public API of the consensus layer. The consensus layer is responsible for producing rounds consisting of
 * deterministically ordered events. It is created by the {@code ConsensusLayerFactory}, and requires
 * {@link ConsensusLayerInputs} for construction. The consensus layer is owned and driven by the execution layer.
 *
 * <p>The consensus layer produces consensus rounds on demand via {@link #requestNextRound(Roster, Instant)}. There is
 * no backpressure mechanism, however there are internal protections to prevent it from consuming too many resources. A
 * struggling consensus layer will simply take longer to produce the requested round(s).
 *
 * <p>A more complete architecture description is located at <i>platform-sdk/docs/proposals/consensus-layer/Consensus-Layer.md</i>.
 */
public interface ConsensusLayer {

    /**
     * Starts the consensus layer. This method must be called before any other methods are called. The method returns
     * once the consensus layer is fully started.
     */
    void start();

    /**
     * Destroys the consensus layer in an orderly manner. This method must be called when the consensus layer is no
     * longer needed. After this method is called, no other methods should be called, including {@link #start()}. A new
     * instance must be created and started. This method destroys all resources and closes all open connections. The
     * method returns once the consensus layer is fully destroyed.
     */
    void destroy();

    /**
     * <p>Requests the next consensus round be produced. This is the primary interaction point between the execution
     * layer and the consensus layer. The next consensus round is sent to the execution layer asynchronously as soon as
     * it is ready via {@link ExecutionLayerCallbacks#onRound(Round)}.
     *
     * <p>Calling this method causes more events to be added to the consensus algorithm. It will continue to feed
     * events into the consensus algorithm until the next round (or rounds) reach consensus. Then it will stop feeding
     * it events. No rounds are buffered except for any that reach consensus simultaneously. If buffering rounds is
     * desired, the execution layer must buffer them itself by eagerly requesting the next round. This method can be
     * called multiple times even before the next round is ready. A round will be produced for each invocation of this
     * method.
     *
     * <p>When requesting the next round, the execution layer can provide a new roster to be adopted at a deterministic
     * number of rounds in the future. A {@code null} roster value means that there is no new roster to adopt. The
     * roster used to calculate each consensus round is available via {@link Round#getConsensusRoster()}. This
     * functionality is not yet implemented. It will be completed as part of the final phase of dynamic address book.
     *
     * <p>The freeze time informs the consensus layer of the next desired freeze time. A value of {@code null}
     * indicates that there is no freeze time, and any previously specified freeze time should be cleared (unless the
     * freeze time has already been reached, in which case the freeze time cannot be cleared). Once the consensus round
     * that causes the freeze time to be reached or passed according to consensus time is produced, the consensus layer
     * will begin the freeze process. All future requests for new rounds will be ignored.
     *
     * <p><b>IMPORTANT</b> Once the freeze round is produced, is it important to call either
     * {@link #sendQuiescenceCommand(QuiescenceCommand)} with the {@link QuiescenceCommand#QUIESCE} value or call
     * {@link #destroy()} in order to halt event creation and unbounded memory consumption.
     *
     * @param newRoster  a new roster to be applied at a deterministic number of rounds in the future, or {@code null}
     *                   if there is no new roster to adopt
     * @param freezeTime the next freeze time, or {@code null} if there is no planned freeze time
     */
    void requestNextRound(@Nullable final Roster newRoster, @Nullable final Instant freezeTime);

    /**
     * <p>Instructs the consensus layer on what its quiescence state should be. It will use the latest command that has
     * been provided.
     *
     * <p>This command is also used to stop event creation once it is no longer needed during the freeze process. It is
     * important that either this method is invoked with {@link QuiescenceCommand#QUIESCE} or {@link #destroy()} is
     * called in a timely manner after freezing to stop event creation. Stopping event creation is important for
     * limiting memory and disk usage, since events created after the freeze time are written to disk and are not
     * garbage collected from memory.
     *
     * @param command the quiescence command
     */
    void sendQuiescenceCommand(@NonNull final QuiescenceCommand command);

    /**
     * Informs the consensus layer that there is a new oldest state on disk we must be able to restart from. The
     * consensus layer uses this to determine which PCES files on disk are no longer needed and can be safely deleted.
     * If this method is never invoked, PCES files will never be removed.
     *
     * @param consensusSnapshot the oldest consensus snapshot we must be able to restart from
     */
    void oldestRestartableSnapshot(@NonNull final ConsensusSnapshot consensusSnapshot);

    /**
     * Sets the reconnect peer protocol factory, which is provided by the adapter.
     *
     * <p>This method is temporary and will be deleted when reconnect teaching moves to the block node.
     *
     * @param reconnectPeerProtocolFactory the peer protocol factory
     */
    //    void setReconnectPeerProtocolFactory(@NonNull final PeerProtocolFactory reconnectPeerProtocolFactory);

    /**
     * Informs the consensus layer of a status change based on events owned by the execution layer. This method is
     * temporary. It exists now to allow the platform status reporting to show a complete picture of the consensus node
     * status. When the execution layer implements a similar status, this method will be removed.
     *
     * @param status the new status
     */
    void onStatusUpdate(@NonNull final StatusUpdate status);

    /**
     * Status updates communicated to the consensus layer by the execution layer.
     */
    enum StatusUpdate {
        /**
         * The node has completed the freeze. This is a terminal status.
         */
        FREEZE_COMPLETE,
        /**
         * The node has encountered a failure, and is unable to continue. The consensus layer is idle. This is a
         * terminal status.
         */
        CATASTROPHIC_FAILURE;
    }
}
