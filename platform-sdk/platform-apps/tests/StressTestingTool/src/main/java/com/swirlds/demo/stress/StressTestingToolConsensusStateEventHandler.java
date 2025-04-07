// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stress;

import static com.swirlds.demo.stress.TransactionPool.APPLICATION_TRANSACTION_MARKER;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.address.AddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.utility.ByteUtils;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.Transaction;

public class StressTestingToolConsensusStateEventHandler implements ConsensusStateEventHandler<StressTestingToolState> {
    private static final Logger logger = LogManager.getLogger(StressTestingToolConsensusStateEventHandler.class);

    /** supplies the app config */
    private StressTestingToolConfig config;

    @Override
    public void onStateInitialized(
            @NonNull final StressTestingToolState state,
            @NonNull final Platform platform,
            @NonNull final InitTrigger trigger,
            @Nullable final SemanticVersion previousVersion) {
        this.config = platform.getContext().getConfiguration().getConfigData(StressTestingToolConfig.class);
    }

    @Override
    public void onPreHandle(
            @NonNull Event event,
            @NonNull StressTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        event.forEachTransaction(transaction -> {
            if (areTransactionBytesSystemOnes(transaction)) {
                consumeSystemTransaction(transaction, event, stateSignatureTransactionCallback);
            }
        });

        busyWait(config.preHandleTime());
    }

    @SuppressWarnings("StatementWithEmptyBody")
    private void busyWait(@NonNull final Duration duration) {
        if (!duration.isZero() && !duration.isNegative()) {
            final long start = System.nanoTime();
            final long nanos = duration.toNanos();
            while (System.nanoTime() - start < nanos) {
                // busy wait
            }
        }
    }

    @Override
    public void onHandleConsensusRound(
            @NonNull Round round,
            @NonNull StressTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        state.throwIfImmutable();
        for (final var event : round) {
            event.consensusTransactionIterator().forEachRemaining(transaction -> {
                if (areTransactionBytesSystemOnes(transaction)) {
                    consumeSystemTransaction(transaction, event, stateSignatureTransactionCallback);
                } else {
                    handleTransaction(transaction, state);
                }
            });
        }
    }

    private void handleTransaction(@NonNull final ConsensusTransaction trans, StressTestingToolState state) {
        state.incrementRunningSum(
                ByteUtils.byteArrayToLong(trans.getApplicationTransaction().toByteArray(), 0));
        busyWait(config.handleTime());
    }

    @Override
    public boolean onSealConsensusRound(@NonNull Round round, @NonNull StressTestingToolState state) {
        // no-op
        return true;
    }

    @Override
    public void onUpdateWeight(
            @NonNull StressTestingToolState state,
            @NonNull AddressBook configAddressBook,
            @NonNull PlatformContext context) {
        // no-op
    }

    @Override
    public void onNewRecoveredState(@NonNull StressTestingToolState recoveredState) {
        // no-op
    }

    /**
     * Checks if the transaction bytes are system ones. The test creates application transactions with max length of 4.
     * System transactions will be always bigger than that.
     *
     * @param transaction the consensus transaction to check
     * @return true if the transaction bytes are system ones, false otherwise
     */
    private boolean areTransactionBytesSystemOnes(@NonNull final Transaction transaction) {
        final var transactionBytes = transaction.getApplicationTransaction();

        if (transactionBytes.length() == 0) {
            return false;
        }

        return transactionBytes.getByte(0) != APPLICATION_TRANSACTION_MARKER;
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
        } catch (final com.hedera.pbj.runtime.ParseException e) {
            logger.error("Failed to parse StateSignatureTransaction", e);
        }
    }
}
