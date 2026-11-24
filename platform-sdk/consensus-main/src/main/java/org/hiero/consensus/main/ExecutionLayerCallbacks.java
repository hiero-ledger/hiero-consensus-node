// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.main;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import org.hiero.consensus.main.model.Event;
import org.hiero.consensus.main.model.Round;
import org.hiero.consensus.main.model.TimestampedTransaction;
import org.hiero.consensus.main.model.TransactionLimits;

/**
 * The API of the Execution Layer exposed to the Consensus Layer. All methods are blocking; therefore they must
 * return quickly to allow the Consensus Layer to continue its work.
 */
public interface ExecutionLayerCallbacks {

    /**
     * Informs the execution layer that the consensus node is now too far behind enough peers to catch up via gossip and
     * a reconnect is required.
     */
    void onBehind();

    /**
     * Provides the next transactions for inclusion in a new event. Transactions should be returned in the desired order
     * of execution. The transactions returned MUST obey the specified
     * {@link TransactionLimits} provided to the consensus layer in
     * {@link ConsensusLayerInputs}. If they do not, the event will be invalid and the transactions dropped.
     *
     * <p>This method is called when a new event is about to be created.
     *
     * @return the list of transactions to include in the new event
     */
    List<TimestampedTransaction> getTransactionsForNewEvent();

    /**
     * Provides a stale event to the execution layer for any desired processing. Stale events have not and never will
     * reach consensus. Detection of stale events and reporting here is non-deterministic across nodes.
     *
     * @param event the event that went stale.
     */
    void onStaleEvent(@NonNull Event event);

    /**
     * An event to pre-handle. The event may or may not have reached consensus at the time of invocation. This method
     * can be used as a guaranteed pre-processing mechanism on events (and their transactions) before they are handled.
     * Each event has a latch to indicate that pre-handle is complete ({@code Event#signalPrehandleCompletion()}. To
     * guarantee that this method completes before it is handled, invoke this method on the event after it is
     * pre-handled, and wait for {@code Event#awaitPrehandleCompletion()} to return before handling.
     * <p>
     * Every event observed in this method will eventually be observed in either {@link #onRound(Round)} or
     * {@link #onStaleEvent(Event)}.
     *
     * @param event the event to pre-handle
     */
    void onPreHandle(@NonNull Event event);

    /**
     * Provides the next requested consensus round.
     *
     * @param consensusRound the next round of consensus events
     * @see ConsensusLayer#requestNextRound(Roster, Instant)
     */
    void onRound(@NonNull Round consensusRound);

    /**
     * Reports a new consensus layer status.
     *
     * @param status the new status of the consensus layer
     */
    void onConsensusLayerStatusChange(@NonNull ConsensusLayerStatus status);
}
