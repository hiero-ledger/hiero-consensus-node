// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.forensics;

import static com.hedera.node.app.hapi.utils.CommonUtils.timestampToInstant;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.streams.RecordStreamItem;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * Represents a single {@code (Transaction, TransactionRecord)} entry from a record stream,
 * including the consensus time as a {@link Instant} for convenience.
 *
 * @param parts         the transaction parts
 * @param txnRecord     the resolved record the transaction
 * @param consensusTime the consensus time
 */
public record RecordStreamEntry(TransactionParts parts, TransactionRecord txnRecord, Instant consensusTime)
        implements Comparable<RecordStreamEntry> {
    @Override
    public int compareTo(@NonNull RecordStreamEntry that) {
        return this.consensusTime.compareTo(that.consensusTime);
    }

    /**
     * Constructs a {@link RecordStreamEntry} from a {@link RecordStreamItem}.
     *
     * @param item the item to convert
     * @return the constructed entry
     */
    public static RecordStreamEntry from(@NonNull final RecordStreamItem item) {
        final var itemRecord = item.record();
        return new RecordStreamEntry(
                TransactionParts.from(item.transaction()),
                itemRecord,
                timestampToInstant(itemRecord.consensusTimestamp()));
    }

    public Transaction submittedTransaction() {
        return parts.wrapper();
    }

    public TransactionBody body() {
        return parts.body();
    }

    public ResponseCodeEnum finalStatus() {
        return txnRecord.receipt().status();
    }

    /**
     * Returns the account ID created by the transaction, if any.
     *
     * @return the created account ID
     */
    public AccountID createdAccountId() {
        return txnRecord.receipt().accountID();
    }

    /**
     * Returns the file ID created by the transaction, if any.
     *
     * @return the created file ID
     */
    public FileID createdFileId() {
        return txnRecord.receipt().fileID();
    }

    /**
     * Returns the schedule ID created by the transaction, if any.
     *
     * @return the created schedule ID
     */
    public ScheduleID createdScheduleId() {
        return txnRecord.receipt().scheduleID();
    }

    /**
     * Returns the transaction ID of the scheduled transaction triggered by this transaction (if any).
     *
     * @return the scheduled transaction ID
     */
    public TransactionID scheduledTransactionId() {
        return txnRecord.receipt().scheduledTransactionID();
    }

    public HederaFunctionality function() {
        return parts.function();
    }

    public TransactionRecord transactionRecord() {
        return txnRecord;
    }

    /**
     * Returns the consensus timestamp of the parent transaction.
     * @return the parent consensus timestamp
     */
    public Instant parentConsensusTimestamp() {
        return timestampToInstant(txnRecord.parentConsensusTimestamp());
    }

    /**
     * Returns the transaction ID of the transaction.
     * @return the transaction ID
     */
    public TransactionID txnId() {
        return txnRecord.transactionID();
    }

    @Override
    public String toString() {
        return String.format(
                "RecordStreamEntry{consensusTime=%s, txn=%s, record=%s}", consensusTime, body(), txnRecord);
    }
}
