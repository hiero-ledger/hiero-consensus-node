// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.platform;

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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.otter.fixtures.turtle.app.TransactionHandlers;
import org.hiero.otter.fixtures.turtle.app.TurtleAppState;
import org.hiero.otter.fixtures.turtle.app.TurtleTransaction;

/**
 * An implementation of {@link ConsensusStateEventHandler} for container-based consensus nodes.
 */
public class DockerStateEventHandler implements ConsensusStateEventHandler<TurtleAppState> {

    private static final Logger LOGGER = LogManager.getLogger(DockerStateEventHandler.class);

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPreHandle(
            @NonNull final Event event,
            @NonNull final TurtleAppState state,
            @NonNull
                    final Consumer<ScopedSystemTransaction<StateSignatureTransaction>>
                            stateSignatureTransactionCallback) {}

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
                    TransactionHandlers.handleTransaction(state, event, transaction, callback);
                } catch (IOException ex) {
                    LOGGER.warn("Failed to parse transaction", ex);
                }
            });
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onSealConsensusRound(@NonNull final Round round, @NonNull final TurtleAppState state) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onStateInitialized(
            @NonNull final TurtleAppState state,
            @NonNull final Platform platform,
            @NonNull final InitTrigger trigger,
            @Nullable final SemanticVersion previousVersion) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void onUpdateWeight(
            @NonNull final TurtleAppState state,
            @NonNull final AddressBook configAddressBook,
            @NonNull final PlatformContext context) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void onNewRecoveredState(@NonNull final TurtleAppState recoveredState) {}
}
