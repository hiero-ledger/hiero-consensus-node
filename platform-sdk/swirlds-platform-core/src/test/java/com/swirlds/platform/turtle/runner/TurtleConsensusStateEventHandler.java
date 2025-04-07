// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.turtle.runner;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.ParseException;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.utility.NonCryptographicHashing;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.address.AddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.Transaction;

/**
 * This class handles the lifecycle events for the {@link TurtleTestingToolState}.
 */
enum TurtleConsensusStateEventHandler implements ConsensusStateEventHandler<TurtleTestingToolState> {
    TURTLE_CONSENSUS_STATE_EVENT_HANDLER;

    @Override
    public void onPreHandle(
            @NonNull Event event,
            @NonNull TurtleTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        event.forEachTransaction(transaction -> {
            consumeSystemTransaction(transaction, event, stateSignatureTransactionCallback);
        });
    }

    @Override
    public void onHandleConsensusRound(
            @NonNull Round round,
            @NonNull TurtleTestingToolState turtleTestingToolState,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        turtleTestingToolState.state = NonCryptographicHashing.hash64(
                turtleTestingToolState.state,
                round.getRoundNum(),
                round.getConsensusTimestamp().getNano(),
                round.getConsensusTimestamp().getEpochSecond());

        round.forEachEventTransaction((ev, tx) -> {
            consumeSystemTransaction(tx, ev, stateSignatureTransactionCallback);
        });
    }

    @Override
    public boolean onSealConsensusRound(@NonNull Round round, @NonNull TurtleTestingToolState state) {
        // no op
        return true;
    }

    @Override
    public void onStateInitialized(
            @NonNull final TurtleTestingToolState state,
            @NonNull final Platform platform,
            @NonNull final InitTrigger trigger,
            @Nullable final SemanticVersion previousVersion) {
        // no op
    }

    @Override
    public void onUpdateWeight(
            @NonNull TurtleTestingToolState state,
            @NonNull AddressBook configAddressBook,
            @NonNull PlatformContext context) {
        // no op
    }

    @Override
    public void onNewRecoveredState(@NonNull TurtleTestingToolState recoveredState) {
        // no op
    }

    /**
     * Converts a transaction to a {@link StateSignatureTransaction} and then consumes it into a callback.
     *
     * @param transaction the transaction to consume
     * @param event the event that contains the transaction
     * @param stateSignatureTransactionCallback the callback to call with the system transaction
     */
    private void consumeSystemTransaction(
            final @NonNull Transaction transaction,
            final @NonNull Event event,
            final @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>>
                            stateSignatureTransactionCallback) {
        try {
            final var stateSignatureTransaction =
                    StateSignatureTransaction.PROTOBUF.parse(transaction.getApplicationTransaction());
            stateSignatureTransactionCallback.accept(new ScopedSystemTransaction<>(
                    event.getCreatorId(), event.getSoftwareVersion(), stateSignatureTransaction));
        } catch (final ParseException e) {
            throw new RuntimeException("Failed to parse StateSignatureTransaction", e);
        }
    }
}
