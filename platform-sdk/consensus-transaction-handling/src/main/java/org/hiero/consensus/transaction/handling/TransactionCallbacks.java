// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.transaction.handling;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * This is a temporary interface that will be replaced
 */
public interface TransactionCallbacks {
    /**
     * Called when an event is added to the hashgraph used to compute consensus ordering
     * for this node.
     *
     * @param event the event that was added
     * @param state the latest immutable state at the time of the event
     * @param stateSignatureTransactionCallback a consumer that will be used for callbacks
     */
    void onPreHandle(
            @NonNull Event event,
            @NonNull State state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback);

    /**
     * Called when a round of events have reached consensus, and are ready to be handled by the network.
     *
     * @param round the round that has just reached consensus
     * @param state the working state of the network
     * @param stateSignatureTransactionCallback a consumer that will be used for callbacks
     */
    void onHandleConsensusRound(
            @NonNull Round round,
            @NonNull State state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback);

    /**
     * Called by the platform after it has made all its changes to this state for the given round.
     * @param round the round whose platform state changes are completed
     * @return true if sealing this round completes a block, in effect signaling if it is safe to
     * sign this round's state
     */
    boolean onSealConsensusRound(@NonNull Round round, @NonNull State state);
}
