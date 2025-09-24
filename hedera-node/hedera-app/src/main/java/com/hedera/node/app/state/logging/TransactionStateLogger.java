// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.logging;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.hashgraph.Round;

/**
 * A logger for state changes. This records a separate log file for all input state read and output state changes from
 * each transaction. This makes it easy to trace what happened if an ISS happens.
 *
 * <p>All methods are called on the handle transaction thread or check they are on handle transaction thread before
 * doing any work. Therefore state does not need to be thread safe.</p>
 */
public final class TransactionStateLogger {
    /** The logger we are using for Transaction State log */
    private static final Logger logger = LogManager.getLogger(TransactionStateLogger.class);

    /**
     * Log the start of a round if it contains any non-system transactions.
     *
     * @param round The round to log
     */
    public static void logStartRound(final Round round) {
        if (logger.isDebugEnabled()) {
            logger.debug(
                    "Starting round {} of {} events at {}",
                    round.getRoundNum(),
                    round.getEventCount(),
                    round.getConsensusTimestamp());
        }
    }

    /**
     * Log the start of an event if it contains any non-system transactions.
     *
     * @param event The event to log
     */
    public static void logStartEvent(final ConsensusEvent event, final NodeInfo creator) {
        if (logger.isDebugEnabled()) {
            logger.debug(
                    "  Starting event {} at {} from node {}",
                    event.getConsensusOrder(),
                    event.getConsensusTimestamp(),
                    creator.nodeId());
        }
    }

    /**
     * Log the start of a transaction.
     *
     * @param consensusTimestamp The transaction consensus timestamp
     * @param txBody The transaction body
     * @param payer The payer
     */
    public static void logStartUserTransaction(
            @NonNull final Instant consensusTimestamp,
            @Nullable final TransactionBody txBody,
            @NonNull final AccountID payer) {
        if (logger.isDebugEnabled()) {
            logger.debug(
                    "    Starting user transaction {} at platform time {} from payer 0.0.{}",
                    txBody == null ? "null" : formatTransactionId(txBody.transactionID()),
                    consensusTimestamp,
                    payer.accountNum());
        }
    }

    /**
     * Log the start of a user transaction pre-handle result.
     *
     * @param preHandleResult The pre-handle result
     */
    public static void logStartUserTransactionPreHandleResultP2(@NonNull PreHandleResult preHandleResult) {
        if (logger.isDebugEnabled()) {
            final var payer = preHandleResult.payer();
            final var payerKey = preHandleResult.payerKey();
            logger.debug(
                    "      with preHandleResult: payer 0.0.{} with key {} with status {} with response code {}",
                    payer == null ? "null" : payer.accountNum(),
                    payerKey == null ? "null" : payerKey.toString(),
                    preHandleResult.status(),
                    preHandleResult.responseCode());
        }
    }

    /**
     * Log the start of a user transaction pre-handle result.
     *
     * @param preHandleResult The pre-handle result
     */
    public static void logStartUserTransactionPreHandleResultP3(@NonNull PreHandleResult preHandleResult) {
        if (logger.isTraceEnabled()) {
            logger.trace(
                    "      with preHandleResult: txInfo {} with requiredKeys {} with verificationResults {}",
                    preHandleResult.txInfo(),
                    preHandleResult.requiredKeys(),
                    preHandleResult.verificationResults());
        }
    }

    /**
     * Record the end of a transaction, this can be a user, preceding or child transaction.
     *
     * @param txID The ID of the transaction
     * @param transactionRecord The record of the transaction execution
     */
    public static void logEndTransactionRecord(
            @NonNull final TransactionID txID, @NonNull final TransactionRecord transactionRecord) {
        if (logger.isDebugEnabled()) {
            final StringBuilder sb = new StringBuilder();
            sb.append("    Ending transaction ").append(formatTransactionId(txID));
            if (transactionRecord.receipt() != null) {
                sb.append("\n    responseCode: ")
                        .append(transactionRecord.receipt().status());
                sb.append("\n    ").append(transactionRecord.receipt());
                sb.append("\n    ").append(transactionRecord.transferList());
            }
            if (transactionRecord.transferList() != null) {
                sb.append("\n    ").append(transactionRecord.transferList());
            }
            if (transactionRecord.tokenTransferLists() != null
                    && !transactionRecord.tokenTransferLists().isEmpty()) {
                sb.append("\n    ").append(transactionRecord.tokenTransferLists());
            }
            if (transactionRecord.tokenTransferLists() != null
                    && !transactionRecord.tokenTransferLists().isEmpty()) {
                sb.append("\n    ").append(transactionRecord.tokenTransferLists());
            }
            if (transactionRecord.paidStakingRewards() != null
                    && !transactionRecord.paidStakingRewards().isEmpty()) {
                sb.append("\n    ").append(transactionRecord.paidStakingRewards());
            }
            if (transactionRecord.assessedCustomFees() != null
                    && !transactionRecord.assessedCustomFees().isEmpty()) {
                sb.append("\n    ").append(transactionRecord.assessedCustomFees());
            }
            sb.append("\n    transactionFee: ")
                    .append(transactionRecord.transactionFee())
                    .append(" transactionHash: ")
                    .append(transactionRecord.transactionHash() == null ? "null" : transactionRecord.transactionHash());
            sb.append("\n    scheduleRef: ")
                    .append(transactionRecord.scheduleRef() == null ? "null" : transactionRecord.scheduleRef())
                    .append(" parentConsensusTimestamp: ")
                    .append(
                            transactionRecord.parentConsensusTimestamp() == null
                                    ? "null"
                                    : transactionRecord.parentConsensusTimestamp());
            sb.append("\n    alias: ")
                    .append(transactionRecord.alias() == null ? "null" : transactionRecord.alias())
                    .append(" ethereumHash: ")
                    .append(transactionRecord.ethereumHash() == null ? "null" : transactionRecord.ethereumHash());
            sb.append("\n    evmAddress: ")
                    .append(transactionRecord.evmAddress() == null ? "null" : transactionRecord.evmAddress())
                    .append(" entropy: ")
                    .append(transactionRecord.entropy() == null ? "null" : transactionRecord.entropy());
            logger.debug(sb);
        }
    }

    // =========== Formatting Methods =========================================================================

    /**
     * Format entity id keys in form 0.0.123 rather than default toString() long form.
     *
     * @param key The key to format
     * @return The formatted key
     * @param <K> The type of the key
     */
    private static <K> String formatKey(@Nullable final K key) {
        if (key == null) {
            return "null";
        } else if (key instanceof AccountID accountID) {
            return accountID.shardNum() + "." + accountID.realmNum() + '.' + accountID.accountNum();
        } else if (key instanceof FileID fileID) {
            return fileID.shardNum() + "." + fileID.realmNum() + '.' + fileID.fileNum();
        } else if (key instanceof TokenID tokenID) {
            return tokenID.shardNum() + "." + tokenID.realmNum() + '.' + tokenID.tokenNum();
        } else if (key instanceof TopicID topicID) {
            return topicID.shardNum() + "." + topicID.realmNum() + '.' + topicID.topicNum();
        } else if (key instanceof ScheduleID scheduleID) {
            return scheduleID.shardNum() + "." + scheduleID.realmNum() + '.' + scheduleID.scheduleNum();
        }
        return key.toString();
    }

    /**
     * Nicer formatting for transaction IDs.
     *
     * @param transactionID The transaction ID to format
     * @return The formatted transaction ID
     */
    private static String formatTransactionId(@Nullable final TransactionID transactionID) {
        if (transactionID == null || transactionID.transactionValidStart() == null) {
            return "null";
        }
        return "TransactionID[transactionValidStart="
                + Instant.ofEpochSecond(
                        transactionID.transactionValidStart().seconds(),
                        transactionID.transactionValidStart().nanos())
                + ", accountID="
                + formatKey(transactionID.accountID()) + ", scheduled="
                + transactionID.scheduled() + ", nonce=" + transactionID.nonce() + "]";
    }
}
