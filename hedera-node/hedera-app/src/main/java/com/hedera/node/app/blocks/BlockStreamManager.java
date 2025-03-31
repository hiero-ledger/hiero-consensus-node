// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.system.state.notifications.StateHashedListener;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import org.hiero.consensus.model.hashgraph.Round;

/**
 * Maintains the state and process objects needed to produce the block stream.
 * <p>
 * Must receive information about the round boundaries in the consensus algorithm, as it will need to create new hashing
 * objects and advance block metadata at the start of a round. At the end of a round it must commit the updated block
 * metadata to state. In principle, a block can include multiple rounds, although this would require coordination with
 * reconnect to ensure that new nodes always begin with a state on a block boundary.
 * <p>
 * Items written to the stream will be produced in the order they are written. The leaves of the input and output item
 * Merkle trees will be in the order they are written.
 */
public interface BlockStreamManager extends BlockRecordInfo, StateHashedListener {
    Bytes ZERO_BLOCK_HASH = Bytes.wrap(new byte[48]);

    /**
     * The types of work that may be identified as pending within a block.
     */
    enum PendingWork {
        /**
         * No work is pending.
         */
        NONE,
        /**
         * Genesis work is pending.
         */
        GENESIS_WORK,
        /**
         * Post-upgrade work is pending.
         */
        POST_UPGRADE_WORK
    }

    /**
     * Lifecycle interface for the block stream manager. This will allow any additional actions that
     * need to take place at start of block and end of block. For example, updating node rewards information.
     */
    interface Lifecycle {
        /**
         * Called when a block is opened. This will allow any additional actions that need to take place
         * at the start of the block.
         *
         * @param state the state of the network at the start of the block
         */
        void onOpenBlock(@NonNull State state);

        /**
         * Called when a block is closed. This will allow any additional actions that need to take place
         * at the end of the block.
         *
         * @param state the state of the network at the end of the block
         */
        void onCloseBlock(@NonNull State state);
    }

    /**
     * Returns whether the ledger ID has been set.
     * @return true if the ledger ID has been set, false otherwise
     */
    boolean hasLedgerId();

    /**
     * Initializes the block stream manager after a restart or during reconnect with the hash of the last block
     * incorporated in the state used in the restart or reconnect. (At genesis, this hash should be the
     * {@link #ZERO_BLOCK_HASH}.)
     *
     * @param blockHash the hash of the last block
     */
    void initLastBlockHash(@NonNull Bytes blockHash);

    /**
     * Updates the internal state of the block stream manager to reflect the start of a new round.
     *
     * @param round the round that has just started
     * @param state the state of the network at the beginning of the round
     * @throws IllegalStateException if the last block hash was not explicitly initialized
     */
    void startRound(@NonNull Round round, @NonNull State state);

    /**
     * Confirms that the post-upgrade work has been completed.
     */
    void confirmPendingWorkFinished();

    /**
     * Returns whether post-upgrade work is pending.
     *
     * @return whether post-upgrade work is pending
     */
    @NonNull
    PendingWork pendingWork();

    /**
     * Sets the last interval process time.
     *
     * @param lastIntervalProcessTime the last interval process time
     */
    void setLastIntervalProcessTime(@NonNull Instant lastIntervalProcessTime);

    /**
     * Get the consensus time at which an interval was last processed.
     *
     * @return the consensus time at which an interval was last processed
     */
    @NonNull
    Instant lastIntervalProcessTime();

    /**
     * Sets the last consensus time at which a user transaction was last handled.
     *
     * @param lastHandleTime the last consensus time at which a user transaction was handled
     */
    void setLastHandleTime(@NonNull Instant lastHandleTime);

    /**
     * Returns the consensus time at which a user transaction was last handled.
     */
    @NonNull
    Instant lastHandleTime();

    /**
     * Updates both the internal state of the block stream manager and the durable state of the network
     * to reflect the end of the last-started round.
     *
     * @param state    the mutable state of the network at the end of the round
     * @param roundNum the number of the round that has just ended
     * @return returns true if the round is the last round in the block
     */
    boolean endRound(@NonNull State state, long roundNum);

    /**
     * Writes a block item to the stream.
     *
     * @param item the block item to write
     * @throws IllegalStateException if the stream is closed
     */
    void writeItem(@NonNull BlockItem item);

    /**
     * Notifies the block stream manager that a fatal event has occurred, e.g. an ISS. This event should
     * trigger any essential fatal shutdown logic.
     */
    void notifyFatalEvent();

    /**
     * Synchronous method that, when invoked, blocks until the block stream manager signals a successful
     * completion of its fatal shutdown logic.
     *
     * @param timeout the maximum time to wait for block stream shutdown
     */
    void awaitFatalShutdown(@NonNull Duration timeout);
}
