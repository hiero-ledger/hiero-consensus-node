// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.app;

import static org.assertj.core.api.Assertions.fail;
import static org.hiero.otter.fixtures.turtle.app.TransactionHandlers.handleTransaction;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * Simple application that can process all transactions required to run tests on Turtle
 */
@SuppressWarnings("removal")
public enum TurtleApp implements ConsensusStateEventHandler<TurtleAppState> {
    INSTANCE;

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPreHandle(
            @NonNull final Event event,
            @NonNull final TurtleAppState state,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        // No pre-handling required yet
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onHandleConsensusRound(
            @NonNull final Round round,
            @NonNull final TurtleAppState state,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        for (final ConsensusEvent event : round) {
            event.forEachTransaction(txn -> {
                final Bytes payload = txn.getApplicationTransaction();
                try {
                    final TurtleTransaction transaction = TurtleTransaction.parseFrom(payload.toInputStream());
                    handleTransaction(state, event, transaction, callback);
                } catch (IOException ex) {
                    fail("Failed to parse transaction: " + payload, ex);
                }
            });
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onSealConsensusRound(@NonNull final Round round, @NonNull final TurtleAppState state) {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onStateInitialized(
            @NonNull final TurtleAppState state,
            @NonNull final Platform platform,
            @NonNull final InitTrigger trigger,
            @Nullable final SemanticVersion previousVersion) {
        // No initialization required yet
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onUpdateWeight(
            @NonNull final TurtleAppState state,
            @NonNull final AddressBook configAddressBook,
            @NonNull final PlatformContext context) {
        // No weight update required yet
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onNewRecoveredState(@NonNull final TurtleAppState recoveredState) {
        // No new recovered state required yet
    }
}
