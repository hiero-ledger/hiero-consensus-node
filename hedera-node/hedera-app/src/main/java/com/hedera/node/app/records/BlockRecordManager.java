// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
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

    /**
     * Directly updates the in-memory wrapped hash state in the block record manager with the finalized
     * migration root hash values. This must be called after the vote handler finalizes the migration
     * root hash values so that subsequent {@code updateBlockInfo} calls (which use {@code lastBlockInfo}
     * as a base via {@code copyBuilder()}) preserve the correct migration field values.
     *
     * @param prevWrappedRecordBlockRootHash the finalized previous wrapped record block root hash
     * @param intermediateHashes the finalized intermediate hashing state
     * @param leafCount the finalized leaf count
     */
    void syncFinalizedMigrationHashes(
            @NonNull Bytes prevWrappedRecordBlockRootHash, @NonNull List<Bytes> intermediateHashes, long leafCount);

    /**
     * Syncs the voting metadata fields into the in-memory BlockInfo cache. Must be called after
     * {@link com.hedera.node.app.workflows.handle.record.SystemTransactions#maybeSetupJumpstartHashVoting}
     * writes these fields to state, so that subsequent {@code putLastBlockInfo} calls do not
     * overwrite them with stale cached values.
     *
     * @param votingComplete whether voting is complete
     * @param votingCompletionDeadlineBlockNumber the deadline block number for voting completion
     */
    void syncVotingMetadata(boolean votingComplete, long votingCompletionDeadlineBlockNumber);

    /**
     * Hook to compute wrapped record-file block data for the current in-progress record block
     * prior to a freeze. In such an event, this method has one of two possible responsibilities:
     * <ol>
     *   <li>If live wrapped record hash computation is enabled, it must persist the current wrapped
     *   block's hash to state.</li>
     *   <li>If live wrapped record hash computation is disabled, but writing wrapped record block
     *   hashes to disk is enabled, it must write the current wrapped record's component hashes to
     *   the wrapped hashes file on disk.</li>
     * </ol>
     * Note that the 'live' check is intentionally preferred over the 'disk' check, because if live
     * computation is enabled, there's no need to write data to the disk file.
     *
     * @param state the state to persist BlockInfo to
     */
    void writeFreezeBlockWrappedRecordFileBlockHashesToState(@NonNull final State state);

    /**
     * Persists wrapped record-file block hash inputs for the current in-progress block to the on-disk wrapped-hashes
     * file when {@link com.hedera.node.config.data.BlockRecordStreamConfig#writeWrappedRecordFileBlockHashesToDisk()
     * writeWrappedRecordFileBlockHashesToDisk} is enabled.
     *
     * <p>This freeze hook treats the open record block as if it were about to close: it snapshots the block number
     * ({@code lastBlockNumber + 1}), the block's first consensus time, the record stream running hash at end of the
     * round, and the in-memory record stream items and sidecars accumulated for that block, then appends that payload
     * asynchronously for downstream wrapped-hash computation.
     *
     * <p>Called from the handle workflow at the end of a freeze round (after user transactions for the round have been
     * streamed). If the disk feature flag is off, this method returns immediately without I/O.
     *
     * @param state the mutable state at the end of the freeze round (mirrors
     *              {@link #writeFreezeBlockWrappedRecordFileBlockHashesToState(State)}; the implementation may not read it)
     */
    void writeFreezeBlockWrappedRecordFileBlockHashesToDisk(@NonNull State state);
}
