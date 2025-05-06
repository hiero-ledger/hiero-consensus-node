// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.node.app.Hedera;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.MerkleNodeState;
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
 * Implements the major lifecycle events for Hedera Services by delegating to a Hedera instance.
 */
public class ConsensusStateEventHandlerImpl implements ConsensusStateEventHandler<MerkleNodeState> {
    private final Hedera hedera;

    public ConsensusStateEventHandlerImpl(@NonNull final Hedera hedera) {
        this.hedera = requireNonNull(hedera);
    }

    @Override
    public void onPreHandle(
            @NonNull final Event event,
            @NonNull final MerkleNodeState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        hedera.onPreHandle(event, state, stateSignatureTransactionCallback);
    }

    @Override
    public void onHandleConsensusRound(
            @NonNull final Round round,
            @NonNull final MerkleNodeState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTxnCallback) {
        hedera.onHandleConsensusRound(round, state, stateSignatureTxnCallback);
    }

    @Override
    public boolean onSealConsensusRound(@NonNull final Round round, @NonNull final MerkleNodeState state) {
        requireNonNull(state);
        requireNonNull(round);
        return hedera.onSealConsensusRound(round, state);
    }

    @Override
    public void onStateInitialized(
            @NonNull final MerkleNodeState state,
            @NonNull final Platform platform,
            @NonNull final InitTrigger trigger,
            @Nullable final SemanticVersion previousVersion) {
        hedera.onStateInitialized(state, platform, trigger);
    }

    @Override
    public void onUpdateWeight(
            @NonNull final MerkleNodeState stateRoot,
            @NonNull final AddressBook configAddressBook,
            @NonNull final PlatformContext context) {
        // No-op
    }

    @Override
    public void onNewRecoveredState(@NonNull final MerkleNodeState recoveredStateRoot) {
        hedera.onNewRecoveredState();
    }
}
