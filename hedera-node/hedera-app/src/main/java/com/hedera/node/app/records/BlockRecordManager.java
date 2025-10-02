// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * {@link BlockRecordManager} is responsible for managing blocks and writing the block record stream. It manages:
 * <ul>
 *     <li>Packaging transaction records into files and sending for writing</li>
 *     <li>Updating block number</li>
 *     <li>Computing running hashes</li>
 *     <li>Updating State for blocks and running hashes</li>
 * </ul>
 *
 * <p>This API is used exclusively by {@link com.hedera.node.app.workflows.handle.HandleWorkflow}
 *
 * <p>This is {@link AutoCloseable} so it can wait for all inflight threads to finish and leave things in
 * a good state.
 *
 * <p>The {@link BlockRecordManager} operates on the principle that the consensus time on user transactions
 * <b>ALWAYS</b> increase with time. Transaction TX_2 will always have a higher consensus time than TX_1, and
 * TX_3 will be higher than TX_2, even if TX_2 were to fail, or be a duplicate. Likewise, we know for certain that
 * the entire set of user transaction, preceding transactions, and child transactions of TX_1 will have a consensus
 * time that comes before every preceding, user, and child transaction of TX_2.
 *
 * <p>This property allows us to make some assumptions that radically simplify the API and implementation.
 *
 * <p>While we currently produce block records on a fixed-second boundary (for example, every 2 seconds), it is possible
 * that some transactions have a consensus time that lies outside that boundary. This is OK, because it is not possible
 * to take the consensus time of a transaction and map back to which block it came from. Blocks use auto-incrementing
 * numbers, and if the network were idle for the duration of a block, there may be no block generated for that slice
 * of time. Thus, since you cannot map consensus time to block number, it doesn't matter if some preceding transactions
 * may have a consensus time that lies outside the "typical" block boundary.
 */
public interface BlockRecordManager extends BlockRecordInfo, AutoCloseable {

    /**
     * Inform {@link BlockRecordManager} of the new consensus time at the beginning of a new transaction. This should
     * only be called before <b>user transactions</b> because the workflow knows 100% that there can not be ANY user
     * transactions that proceed this one in consensus time.
     *
     * <p>This allows {@link BlockRecordManager} to set up the correct block information for the user transaction that
     * is about to be executed. So block questions are answered correctly.
     *
     * <p>The BlockRecordManager may choose to close one or more files if the consensus time threshold has passed.
     *
     * @param consensusTime The consensus time of the user transaction we are about to start executing. It must be the
     * adjusted consensus time, not the platform assigned consensus time. Assuming the two are
     * different.
     * @param state The state to read BlockInfo from and update when new blocks are created
     * @return true if a new block was created, false otherwise
     */
    boolean startUserTransaction(@NonNull Instant consensusTime, @NonNull State state);

    /**
     * Check if a user transaction will start a new block, without any side effects.
     * @param consensusTime the current consensus time
     * @param state the state to read BlockInfo from
     * @return true if a new block will be created, false otherwise
     */
    boolean willOpenNewBlock(@NonNull Instant consensusTime, @NonNull State state);

    /**
     * "Advances the consensus clock" by updating the latest top-level timestamp that the node has handled (which at
     * the time of the call, is <i>also</i> the last-used consensus timestamp).
     * @param consensusTime the most recent consensus timestamp that the node has <b>started</b> to handle
     */
    void setLastTopLevelTime(@NonNull Instant consensusTime, @NonNull State state);

    /**
     * Sets just the last used consensus time, without updating the latest top-level time.
     * @param consensusTime the most recent consensus timestamp that the node has <b>started</b> to handle
     * @param state the state to set the last used consensus time in
     */
    void setLastUsedConsensusTime(@NonNull Instant consensusTime, @NonNull State state);

    /**
     * Returns the timestamp of the last execution processed by the block stream.
     */
    @NonNull
    Instant lastUsedConsensusTime();

    /**
     * Sets the last interval process time.
     *
     * @param lastIntervalProcessTime the last interval process time
     * @param state the state to set the last interval process time in
     */
    void setLastIntervalProcessTime(@NonNull Instant lastIntervalProcessTime, @NonNull State state);

    /**
     * Get the consensus time at which an interval was last processed.
     *
     * @return the consensus time at which an interval was last processed
     */
    @NonNull
    Instant lastIntervalProcessTime();

    /**
     * Add a user transaction's records to the record stream. They must be in exact consensus time order! This must only
     * be called after the user transaction has been committed to state and is 100% done. It must include the record of
     * the user transaction along with all preceding child transactions and any child or transactions after. System
     * transactions are treated as though they were user transactions, calling
     * {@link #startUserTransaction(Instant, State)} and this method.
     *
     * @param recordStreamItems Stream of records produced while handling the user transaction
     * @param state             The state to read {@link BlockInfo} from
     */
    void endUserTransaction(@NonNull Stream<SingleTransactionRecord> recordStreamItems, @NonNull State state);

    /**
     * Called at the end of a round to make sure the running hash and block information is up-to-date in state.
     * This should be called <b>AFTER</b> the last end user transaction in that round has been passed to
     * {@link #endUserTransaction(Stream, State)}.
     *
     * @param state The state to update
     */
    void endRound(@NonNull State state);

    /**
     * Closes this BlockRecordManager and wait for any threads to finish.
     */
    @Override
    void close();

    /**
     * Get the consensus time of the latest handled transaction, or EPOCH if no transactions have been handled yet
     */
    @NonNull
    Instant consTimeOfLastHandledTxn();

    /**
     * Notifies the block record manager that any startup migration records have been streamed.
     */
    void markMigrationRecordsStreamed();
}
