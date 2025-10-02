// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import com.hedera.hapi.node.base.TransactionID;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.impl.ImmediateStateChangeListener;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.spi.records.RecordSource;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.types.StreamMode;
import com.hederahashgraph.api.proto.java.TransactionReceiptEntries;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * A time-limited cache of transaction records and receipts.
 *
 * <p>Each {@link com.hedera.hapi.node.base.Transaction} has a unique {@link TransactionID}. Each {@link TransactionID}
 * is valid for only a certain period of time. If submitted before the {@link TransactionID#transactionValidStart()}
 * then the transaction is invalid. If submitted after the network configured expiration time from that start time,
 * then the transaction is invalid. Between those two times, the network needs to deduplicate transactions.
 *
 * <p>It may be that the same transaction is submitted by the user multiple times to the same node. The node uses this
 * cache as a mechanism to detect those duplicate transactions and reject the duplicates.
 *
 * <p>It may be that transactions with the same {@link TransactionID} may be submitted by the user to multiple nodes.
 * If this happens, we need to only process one of those transactions, but charge the node + network fees to the user
 * for each transaction they submitted.
 *
 * <p>It may be that a dishonest node receives a transaction from the user and sends multiple copies of it. This
 * needs to be detected and the node charged for the duplicate transactions.
 */
/*@ThreadSafe*/
public interface HederaRecordCache extends RecordCache {
    enum DueDiligenceFailure {
        YES,
        NO
    }

    /**
     * Incorporates a source of records for transactions whose consensus times were assigned relative to the given user
     * transaction {@link TransactionID} into the cache, using the given payer account id as the effective payer for
     * all the transactions whose id matches the user transaction.
     *
     * @param nodeId the node id of the node that submitted the user transaction
     * @param userTxnId the id of the user transaction
     * @param dueDiligenceFailure whether the node failed due diligence
     * @param recordSource the source of records for the transactions
     */
    void addRecordSource(
            long nodeId,
            @NonNull TransactionID userTxnId,
            @NonNull DueDiligenceFailure dueDiligenceFailure,
            @NonNull RecordSource recordSource);

    /**
     * Checks if the given transaction ID has been seen by this node. If it has not, the result is
     * {@link DuplicateCheckResult#NO_DUPLICATE}. If it has, then the result is {@link DuplicateCheckResult#SAME_NODE} if the
     * transaction was submitted by the given node before, or {@link DuplicateCheckResult#OTHER_NODE} if the transaction was
     * submitted by different node(s).
     *
     * @param transactionID The {@link TransactionID} to check
     * @param nodeId The node ID of the node that submitted the current transaction
     * @return The result of the check
     */
    @NonNull
    DuplicateCheckResult hasDuplicate(@NonNull TransactionID transactionID, long nodeId);

    /**
     * Resets the receipts pf all transactions stored per round. This is called at the end of each round to
     * clear out the receipts from the previous round.
     */
    void resetRoundReceipts();

    /**
     * Commits the current round's transaction receipts to the transaction receipt queue in {@link State},
     * doing additional work as needed to purge the receipts from any round whose transaction ids cannot
     * be duplicated because they are now expired.
     * <p>
     * Purging receipts works as follows:
     * <ol>
     *     <li>Find the latest {@link TransactionID#transactionValidStart()} of the all the receipts in the
     *     {@link TransactionReceiptEntries} object at the head of the round receipt queue.</li>
     *     <li>If even the latest valid start is more than {@link HederaConfig#transactionMaxValidDuration()}
     *     seconds before consensus time now, purge the history of all receipts from that round, and remove it
     *     from the queue.</li>
     *     <li>Repeat this process until the queue is empty or the round at the head of the queue has a valid
     *     start within {@link HederaConfig#transactionMaxValidDuration()} seconds of the given consensus time
     *     (meaning it might still be duplicated).</li>
     * </ol>
     *
     * @param state                        The state to commit the transaction receipts to
     * @param consensusNow                 The current consensus time
     * @param immediateStateChangeListener The listener to capture the state changes
     * @param blockStreamManager           The block stream manager
     * @param streamMode                   The stream mode
     */
    void commitReceipts(
            @NonNull State state,
            @NonNull Instant consensusNow,
            @NonNull ImmediateStateChangeListener immediateStateChangeListener,
            @NonNull BlockStreamManager blockStreamManager,
            @NonNull StreamMode streamMode);

    /**
     * Does the same work as {@link #commitReceipts(State, Instant, ImmediateStateChangeListener, BlockStreamManager, StreamMode)},
     * <b>if</b> the number of receipts accumulated so far in the round exceeds the given batch size.
     *
     * @param state                        The state to commit the transaction receipts to
     * @param consensusNow                 The current consensus time
     * @param immediateStateChangeListener The listener to capture the state changes
     * @param blockStreamManager           The block stream manager
     * @param receiptEntriesBatchSize      The maximum number of receipts to accumulate in a batch before committing
     * @param streamMode                   The stream mode
     */
    void maybeCommitReceiptsBatch(
            @NonNull State state,
            @NonNull Instant consensusNow,
            @NonNull ImmediateStateChangeListener immediateStateChangeListener,
            int receiptEntriesBatchSize,
            @NonNull BlockStreamManager blockStreamManager,
            @NonNull StreamMode streamMode);

    /** The possible results of a duplicate check */
    enum DuplicateCheckResult {
        /** No duplicate found **/
        NO_DUPLICATE,

        /** A duplicate from the same node was found **/
        SAME_NODE,

        /** A duplicate from a different node was found **/
        OTHER_NODE
    }
}
