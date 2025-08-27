// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * Implements the major lifecycle events for the state. Normally, the implementation of this interface should be
 * stateless or effectively immutable, unless it's a special test implementation. An instance of this class is
 * meant to be created once at the start of the application and then used for the lifetime of the application.
 *
 */
public interface ConsensusStateEventHandler<T extends MerkleNodeState> {
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
            @NonNull T state,
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
            @NonNull T state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback);

    /**
     * Called by the platform after it has made all its changes to this state for the given round.
     * @param round the round whose platform state changes are completed
     * @return true if sealing this round completes a block, in effect signaling if it is safe to
     * sign this round's state
     */
    boolean onSealConsensusRound(@NonNull Round round, @NonNull T state);

    /**
     * Called when the platform is initializing the network state.
     *
     * @param state the working state of the network to be initialized
     * @param platform the platform used by this node
     * @param trigger the reason for the initialization
     * @param previousVersion if non-null, the network version that was previously in use
     */
    void onStateInitialized(
            @NonNull T state,
            @NonNull Platform platform,
            @NonNull InitTrigger trigger,
            @Nullable SemanticVersion previousVersion);

    /**
     * Called exclusively by platform test apps to update the weight of the address book. Should be removed
     * as these apps are refactored to stop using {@link com.swirlds.platform.Browser}.
     * @param state the working state of the network
     * @param configAddressBook the address book used to configure the network
     * @param context the current platform context
     */
    @Deprecated(forRemoval = true)
    void onUpdateWeight(@NonNull T state, @NonNull AddressBook configAddressBook, @NonNull PlatformContext context);

    /**
     * Called when event stream recovery finishes.
     *
     * @param recoveredState the recovered state after reapplying all events
     */
    void onNewRecoveredState(@NonNull T recoveredState);
}
