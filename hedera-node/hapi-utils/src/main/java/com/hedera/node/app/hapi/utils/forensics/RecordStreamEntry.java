// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.forensics;

import static com.hedera.node.app.hapi.utils.CommonUtils.timestampToInstant;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
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
        final var itemRecord = item.getRecord();
        return new RecordStreamEntry(
                TransactionParts.from(item.getTransaction()),
                itemRecord,
                timestampToInstant(itemRecord.getConsensusTimestamp()));
    }

    public Transaction submittedTransaction() {
        return parts.wrapper();
    }

    public TransactionBody body() {
        return parts.body();
    }

    public ResponseCodeEnum finalStatus() {
        return txnRecord.getReceipt().getStatus();
    }

    /**
     * Returns the account ID created by the transaction, if any.
     *
     * @return the created account ID
     */
    public AccountID createdAccountId() {
        return CommonPbjConverters.toPbj(txnRecord.getReceipt().getAccountID());
    }

    /**
     * Returns the file ID created by the transaction, if any.
     *
     * @return the created file ID
     */
    public FileID createdFileId() {
        return CommonPbjConverters.toPbj(txnRecord.getReceipt().getFileID());
    }

    /**
     * Returns the schedule ID created by the transaction, if any.
     *
     * @return the created schedule ID
     */
    public ScheduleID createdScheduleId() {
        return txnRecord.getReceipt().getScheduleID();
    }

    /**
     * Returns the transaction ID of the scheduled transaction triggered by this transaction (if any).
     *
     * @return the scheduled transaction ID
     */
    public TransactionID scheduledTransactionId() {
        return txnRecord.getReceipt().getScheduledTransactionID();
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
        return timestampToInstant(txnRecord.getParentConsensusTimestamp());
    }

    /**
     * Returns the transaction ID of the transaction.
     * @return the transaction ID
     */
    public TransactionID txnId() {
        return txnRecord.getTransactionID();
    }

    @Override
    public String toString() {
        return String.format(
                "RecordStreamEntry{consensusTime=%s, txn=%s, record=%s}", consensusTime, body(), txnRecord);
    }
}
