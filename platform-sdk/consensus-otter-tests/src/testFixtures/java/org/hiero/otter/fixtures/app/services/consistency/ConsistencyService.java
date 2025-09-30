// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.consistency;

import static org.hiero.base.utility.ByteUtils.byteArrayToLong;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.Transaction;
import org.hiero.otter.fixtures.app.OtterService;

/**
 * A service that ensures the consistency of rounds and transactions sent by the platform to the execution layer for
 * handling. It checks these aspects of consistency:
 * <ol>
 *     <li>Consensus rounds increase in number monotonically</li>
 *     <li>Consensus rounds are received only once</li>
 *     <li>Differences in rounds or transactions sent to {@link #recordRound(Round)} on different nodes will cause an ISS</li>
 *     <li>Consensus transactions were previous received in preHandle</li>
 *     <li>After a restart, any rounds that reach consensus in PCES replay exactly match the rounds calculated previously.</li>
 * </ol>
 */
public class ConsistencyService implements OtterService {
    public static final String NAME = "ConsistencyStateService";

    /**
     * Records the contents of all rounds, even empty ones. This method calculates a running hash that includes the
     * round number and all transactions, and stores the number of rounds handled in state.
     *
     * @param writableStates the writable states used to modify the consistency state
     * @param round the round to handle
     */
    @Override
    public void onRound(@NonNull final WritableStates writableStates, @NonNull final Round round) {
        new WritableConsistencyStateStore(writableStates)
                .accumulateRunningChecksum(round.getRoundNum())
                .increaseRoundsHandled();
    }

    /**
     * This method updates the running hash that includes the contents of all
     * transactions.
     */
    @Override
    public void onTransaction(
            @NonNull final WritableStates writableStates,
            @NonNull final Event event,
            @NonNull final Transaction transaction,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        final long transactionChecksum = getTransactionChecksum(transaction);
        new WritableConsistencyStateStore(writableStates).accumulateRunningChecksum(transactionChecksum);
    }

    private void recordRound(@NonNull final Round round) {
        // FUTURE WORK: Write the round data to in-memory structure and disk. Write to in-memory structure
        // so we can verify that rounds increase monotonically (no rounds are repeated or skipped). Write to
        // disk so that we can verify that the same rounds reach consensus after a restart during PCES replay.

        // FUTURE WORK: Compare the round to rounds previous recorded in memory and do basic validations, like
        // checking that the round number is one greater than the previous round number, and that all transactions
        // were previously received in prehandle.
    }

    public void initialize() {
        // FUTURE WORK: Read round data from disk (written in recordRound()) into in-memory structure.
    }

    public void recordPreHandleTransactions(@NonNull final Event event) {
        // FUTURE WORK: Record the prehandle transactions so that we can verify all
        // consensus transactions were previously sent to prehandle.
    }

    private static long getTransactionChecksum(@NonNull final Transaction transaction) {
        return byteArrayToLong(transaction.getApplicationTransaction().toByteArray(), 0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String name() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Schema genesisSchema(@NonNull final SemanticVersion version) {
        return new V1ConsistencyStateSchema(version);
    }
}
