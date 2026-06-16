// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.system.state.notifications.StateHashedListener;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.hiero.base.crypto.Hash;
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
    byte[] HASH_OF_ZERO_BYTES = noThrowSha384HashOf(new byte[] {0x0});
    Bytes HASH_OF_ZERO = Bytes.wrap(HASH_OF_ZERO_BYTES);

    /*
     * Typically there are four siblings per block, but in our case the right penultimate root (i.e. the right child of a block's root hash) is merely a composition of its left child hash, requiring no other inputs. <b>This must change if we ever use one of the reserved roots for anything.</b>
     */
    int NUM_SIBLINGS_PER_BLOCK = 3;

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
     * Initializes the block stream manager after a restart or during reconnect with the hashes necessary to
     * infer the starting block tree states and the last block hash used in the restart or reconnect. At
     * genesis, the last block hash should be the {@link #HASH_OF_ZERO}. In all other cases, this value should
     * be null, and the method should calculate it from the intermediate subtree states.
     *
     * @param state the state to use
     * @param lastBlockHash the hash of the last block
     */
    void init(@NonNull State state, @Nullable Bytes lastBlockHash);

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
     * @param lastTopLevelTime the last consensus time at which a user transaction was handled
     */
    void setLastTopLevelTime(@NonNull Instant lastTopLevelTime);

    /**
     * Returns the consensus time at which a user transaction was last handled.
     */
    @NonNull
    Instant lastTopLevelConsensusTime();

    /**
     * Returns the timestamp of the last execution processed by the block stream.
     */
    @NonNull
    Instant lastUsedConsensusTime();

    /**
     * Returns whether ending the given round should close the current block, based on current manager state and
     * round metadata.
     *
     * @param state the mutable state of the network at the end of the round
     * @param roundNum the number of the round that is about to end
     * @return true if ending this round should close the current block
     */
    boolean willCloseBlock(@NonNull State state, long roundNum);

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
     * Writes a block item to the stream.
     *
     * @param itemSpec a function that takes a Timestamp and returns a BlockItem to be written
     * @throws IllegalStateException if the stream is closed
     */
    void writeItem(@NonNull Function<Timestamp, BlockItem> itemSpec);

    /**
     * Signals that the platform has reached a catastrophic failure (e.g. following an ISS). Sets a flag that
     * (a) stops the block stream from opening or mutating any further block state and (b) causes the next round
     * boundary on the handler thread to flush the contents of any open and pending blocks to local disk for triage.
     * Safe to call from any thread and idempotent; it does NOT itself flush. The block stream manager is otherwise
     * allowed to keep processing rounds normally up until this point. Callers wait for the flush to finish via
     * {@link #awaitFatalShutdown(Duration)}.
     */
    void notifyFatalEvent();

    /**
     * Synchronous method invoked, after {@link #notifyFatalEvent()}, when the platform has reached a catastrophic
     * failure. Blocks until the handler-thread flush of any open and pending blocks has completed, bounded by the
     * given timeout; if no further round boundary occurs within the timeout it flushes any already-closed pending
     * blocks itself (a race-free fallback) and returns.
     *
     * @param timeout the maximum time to wait for the open/pending blocks to be flushed to disk
     */
    void awaitFatalShutdown(@NonNull Duration timeout);

    /**
     * Returns the contents files ({@code .iss.gz} for the open block, {@code .pnd.gz} for pending blocks) that the
     * triage flush wrote to local disk after a catastrophic failure, for upload by the ISS-block-upload pipeline.
     * Empty until a triage flush has run (and on nodes that never flushed). Safe to read after
     * {@link #awaitFatalShutdown(Duration)} returns.
     *
     * @return the flushed triage block-contents files, oldest pending blocks first then the open block
     */
    @NonNull
    List<Path> flushedTriageBlockFiles();

    /**
     * Returns a future that completes when all currently pending blocks awaiting proofs have been fully signed.
     *
     * @return a future that completes when there are no pending block proofs
     */
    @NonNull
    CompletableFuture<Void> pendingBlockProofsFuture();

    /**
     * Returns whether this node has submitted its partial signatures for all blocks requested so far.
     *
     * @return true if all requested block signatures have been submitted
     */
    default boolean allBlocksSigned() {
        return true;
    }

    /**
     * Tracks that the given event hash has appeared in the current block.
     * @param eventHash the event hash to track
     */
    void trackEventHash(@NonNull Hash eventHash);

    /**
     * Returns the index of the given event hash in the current block, if it has appeared.
     * The index is the position of the event in the block, starting from 0.
     * @param eventHash the event hash to look up
     * @return the index of the event hash in the current block, if it has appeared
     */
    Optional<Integer> getEventIndex(@NonNull Hash eventHash);
}
