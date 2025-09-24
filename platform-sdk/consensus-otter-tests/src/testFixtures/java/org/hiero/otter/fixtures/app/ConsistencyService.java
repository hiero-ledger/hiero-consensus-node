// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app;

import static org.hiero.base.utility.ByteUtils.byteArrayToLong;
import static org.hiero.base.utility.NonCryptographicHashing.hash64;
import static org.hiero.otter.fixtures.app.OtterStateId.CONSISTENCY_SINGLETON_STATE_ID;
import static org.hiero.otter.fixtures.app.V1ConsistencyStateSchema.CONSISTENCY_SINGLETON_KEY;

import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.transaction.Transaction;
import org.hiero.otter.fixtures.app.state.ConsistencyState;

/**
 * A service that ensures the consistency of rounds and transactions sent by the platform to the execution layer for
 * handling. It checks these aspects of consistency:
 * <ol>
 *     <li>Consensus rounds increase in number monotonically</li>
 *     <li>Consensus rounds are received only once</li>
 *     <li>Differences in rounds or transactions sent to {@link #recordRoundContents} on different nodes will cause an ISS</li>
 *     <li>Consensus transactions were previous received in preHandle</li>
 *     <li>After a restart, any rounds that reach consensus in PCES replay exactly match the rounds calculated previously.</li>
 * </ol>
 */
public class ConsistencyService {
    public static final String NAME = "ConsistencyStateService";

    private Long stateRunningHash = null;

    /**
     * Records the contents of all rounds, even empty ones. This method calculates a running hash that includes the
     * round number and all transactions, and stores the number of rounds handled in state.
     *
     * @param writableStates the writable states used to modify the consistency state
     * @param round the round to handle
     */
    public void recordRoundContents(@NonNull final WritableStates writableStates, @NonNull final Round round) {
        final WritableSingletonState<ConsistencyState> writableSingletonState = writableStates.getSingleton(
                CONSISTENCY_SINGLETON_STATE_ID.id());
        final ConsistencyState consistencyState = writableSingletonState.get();
        stateRunningHash = consistencyState.runningHash();

        // Update the running hash with each transaction in the round
        round.forEachTransaction(transaction -> {
            final long transactionContents = getTransactionContents(transaction);
            stateRunningHash = hash64(stateRunningHash, transactionContents);
        });

        // Update the running hash with the round number
        stateRunningHash = hash64(stateRunningHash, round.getRoundNum());

        // Update the state with the data for this round.
        writableSingletonState.put(consistencyState
                .copyBuilder()
                .roundsHandled(consistencyState.roundsHandled() + 1)
                .runningHash(stateRunningHash)
                .build());

        recordRound(round);
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

    private static long getTransactionContents(@NonNull final Transaction transaction) {
        return byteArrayToLong(transaction.getApplicationTransaction().toByteArray(), 0);
    }
}
