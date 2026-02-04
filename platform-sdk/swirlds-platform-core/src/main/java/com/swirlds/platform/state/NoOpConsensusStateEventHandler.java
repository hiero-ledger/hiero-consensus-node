// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.VirtualMapState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * A no-op implementation of {@link ConsensusStateEventHandler} that does nothing.
 * It's useful for auxiliary code that doesn't handle new transactions (State Editor, State commands, Event Recovery workflow, etc.).
 */
public enum NoOpConsensusStateEventHandler implements ConsensusStateEventHandler {
    NO_OP_CONSENSUS_STATE_EVENT_HANDLER;

    @Override
    public void onPreHandle(
            @NonNull Event event,
            @NonNull VirtualMapState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        // no-op
    }

    @Override
    public void onHandleConsensusRound(
            @NonNull Round round,
            @NonNull VirtualMapState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        // no-op
    }

    @Override
    public boolean onSealConsensusRound(@NonNull Round round, @NonNull VirtualMapState state) {
        // no-op
        return true;
    }

    @Override
    public void onStateInitialized(
            @NonNull final VirtualMapState state,
            @NonNull final Platform platform,
            @NonNull final InitTrigger trigger,
            @Nullable final SemanticVersion previousVersion) {
        // no-op
    }

    @Override
    public void onNewRecoveredState(@NonNull VirtualMapState recoveredState) {
        // no-op
    }
}
