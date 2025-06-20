// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * Produces a stream of block records. This is used by the {@link BlockRecordManagerImpl}.
 */
public interface BlockRecordStreamProducer extends AutoCloseable {
    /**
     * Initialize the current running hash of record stream items. This is called only once to initialize the running
     * hash on startup. This is called on the handle transaction thread. It is loaded from a saved state. At genesis,
     * there is no saved state, so this will be called with null.
     *
     * @param runningHashes The initial running hashes, all future running hashes produced will be
     *                                    computed based on this hash.
     */
    void initRunningHash(@NonNull RunningHashes runningHashes);

    /**
     * Get the current running hash of record stream items. This is called on the handle transaction thread. It will
     * block if a background thread is still hashing. It will always return the running hash after the last user
     * transaction was added. Hence, any pre-transactions or others not yet committed via
     * {@link BlockRecordStreamProducer#writeRecordStreamItems(Stream)} will not be included.
     *
     * @return The current running hash upto and including the last record stream item sent in writeRecordStreamItems().
     */
    @NonNull
    Bytes getRunningHash();

    /**
     * Get the previous, previous, previous runningHash of all RecordStreamObjects. This will block if
     * the running hash has not yet been computed for the most recent user transaction.
     *
     * @return the previous, previous, previous runningHash of all RecordStreamObject
     */
    @Nullable
    Bytes getNMinus3RunningHash();

    /**
     * Called at the end of a block and start of next block.
     *
     * <p>If there is a currently open block it closes any open stream files and writes the signature file.
     * It then opens a new record file for the new block.
     *
     * @param lastBlockNumber                       The number for the block we are closing. If lastBlockNumber is
     *                                              &lt;=0 then there is no block to close.
     * @param newBlockNumber                        The number for the block we are opening.
     * @param newBlockFirstTransactionConsensusTime The consensus time of the first <b>user</b> transaction in the new
     *                                              block. It must be the adjusted consensus time not the platform
     *                                              assigned consensus time, if the two are different.
     */
    void switchBlocks(
            long lastBlockNumber, long newBlockNumber, @NonNull Instant newBlockFirstTransactionConsensusTime);

    /**
     * Write record items to stream files. They must be in exact consensus time order! This must only be called after
     * the user transaction has been committed to state and is 100% done. So, it is called exactly once per user
     * transaction at the end, after it has been committed to state. Each call is for a complete set of transactions
     * that represent a single user transaction and its pre-transactions and child-transactions.
     *
     * @param recordStreamItems the record stream items to write
     */
    void writeRecordStreamItems(@NonNull final Stream<SingleTransactionRecord> recordStreamItems);
}
