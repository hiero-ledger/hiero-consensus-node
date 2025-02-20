// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.records;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNKNOWN;
import static com.hedera.hapi.util.HapiUtils.TIMESTAMP_COMPARATOR;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.spi.RpcService;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Supplies {@link RpcService}s access to records and receipts.
 *
 * <p>A receipt is added when this node ingests a new transaction, or when this node pre-handles a transaction ingested
 * on another node. A receipt in this state will have a status of
 * {@link com.hedera.hapi.node.base.ResponseCodeEnum#UNKNOWN}.
 *
 * <p>Later, during {@code handle}, a final record and receipt is added based on the result of handling that
 * transaction. The receipt will no longer be "UNKNOWN" unless an unhandled exception occurred.
 */
public interface RecordCache {
    /**
     * For mono-service fidelity, records with these statuses do not prevent valid transactions with
     * the same id from reaching consensus and being handled.
     */
    Set<ResponseCodeEnum> NODE_FAILURES = EnumSet.of(INVALID_NODE_ACCOUNT, INVALID_PAYER_SIGNATURE);

    /**
     * And when ordering records for queries, we treat records with unclassifiable statuses as the
     * lowest "priority"; so that e.g. if a transaction with id {@code X} resolves to {@link ResponseCodeEnum#SUCCESS}
     * <i>after</i> we previously resolved an {@link ResponseCodeEnum#INVALID_NODE_ACCOUNT} for {@code X},
     * then {@link com.hedera.hapi.node.base.HederaFunctionality#TRANSACTION_GET_RECEIPT} will return
     * the success record.
     */
    // nested ternary expressions
    @SuppressWarnings("java:S3358")
    Comparator<TransactionRecord> RECORD_COMPARATOR = Comparator.<TransactionRecord, ResponseCodeEnum>comparing(
                    rec -> rec.receiptOrThrow().status(),
                    (a, b) -> NODE_FAILURES.contains(a) == NODE_FAILURES.contains(b)
                            ? 0
                            : (NODE_FAILURES.contains(b) ? -1 : 1))
            .thenComparing(rec -> rec.consensusTimestampOrElse(Timestamp.DEFAULT), TIMESTAMP_COMPARATOR);

    /**
     * Returns true if the two transaction IDs are equal in all fields except for the nonce.
     * @param aTxnId the first transaction ID
     * @param bTxnId the second transaction ID
     * @return true if the two transaction IDs are equal in all fields except for the nonce
     */
    static boolean matchesExceptNonce(@NonNull final TransactionID aTxnId, @NonNull final TransactionID bTxnId) {
        requireNonNull(aTxnId);
        requireNonNull(bTxnId);
        return aTxnId.accountIDOrElse(AccountID.DEFAULT).equals(bTxnId.accountIDOrElse(AccountID.DEFAULT))
                && aTxnId.transactionValidStartOrElse(Timestamp.DEFAULT)
                        .equals(bTxnId.transactionValidStartOrElse(Timestamp.DEFAULT))
                && aTxnId.scheduled() == bTxnId.scheduled();
    }

    /**
     * Returns true if the second transaction ID is a child of the first.
     * @param aTxnId the first transaction ID
     * @param bTxnId the second transaction ID
     * @return true if the second transaction ID is a child of the first
     */
    static boolean isChild(@NonNull final TransactionID aTxnId, @NonNull final TransactionID bTxnId) {
        requireNonNull(aTxnId);
        requireNonNull(bTxnId);
        return aTxnId.nonce() == 0 && bTxnId.nonce() != 0 && matchesExceptNonce(aTxnId, bTxnId);
    }

    /**
     * Just the receipts for a source of one or more {@link TransactionID}s instead of the full records.
     */
    interface ReceiptSource {
        /**
         * This receipt is returned whenever we know there is a transaction pending (i.e. we have a history for a
         * transaction ID), but we do not yet have a record for it.
         */
        TransactionReceipt PENDING_RECEIPT =
                TransactionReceipt.newBuilder().status(UNKNOWN).build();

        /**
         * The "priority" receipt for the transaction id, if known; or {@link ReceiptSource#PENDING_RECEIPT} if there are no
         * consensus receipts with this id. (The priority receipt is the first receipt in the id's history that had a
         * status not in {@link RecordCache#NODE_FAILURES}; or if all its receipts have such statuses, the first
         * one to have reached consensus.)
         * @return the priority receipt, if known
         */
        @NonNull
        TransactionReceipt priorityReceipt(@NonNull TransactionID txnId);

        /**
         * The child receipt with this transaction id, if any; or null otherwise.
         * @return the child receipt, if known
         */
        @Nullable
        TransactionReceipt childReceipt(@NonNull TransactionID txnId);

        /**
         * All the duplicate receipts for the transaction id, if any, with the statuses in
         * {@link RecordCache#NODE_FAILURES} coming last. The list is otherwise ordered by consensus timestamp.
         * @return the duplicate receipts, if any
         */
        @NonNull
        List<TransactionReceipt> duplicateReceipts(@NonNull TransactionID txnId);

        /**
         * All the child receipts for the transaction id, if any. The list is ordered by consensus timestamp.
         * @return the child receipts, if any
         */
        @NonNull
        List<TransactionReceipt> childReceipts(@NonNull TransactionID txnId);
    }

    /**
     * An item stored in the cache.
     *
     * <p>There is a new {@link History} instance created for each original user transaction that comes to consensus.
     * The {@code records} list contains every {@link TransactionRecord} for the original (first) user transaction with
     * a given transaction ID that came to consensus, as well as all duplicate transaction records for duplicate
     * transactions with the same ID that also came to consensus.
     *
     * <p>The {@code childRecords} list contains a list of all child transactions of the original user transaction.
     * Duplicate transactions never have child transactions.
     *
     * @param nodeIds The IDs of every node that submitted a transaction with the txId that came to consensus and was
     * handled. This is an unordered set, since deterministic ordering is not required for this in-memory
     * data structure
     * @param records Every {@link TransactionRecord} handled for every user transaction that came to consensus
     * @param childRecords The list of child records
     */
    record History(
            @NonNull Set<Long> nodeIds,
            @NonNull List<TransactionRecord> records,
            @NonNull List<TransactionRecord> childRecords) {

        /**
         * Create a new {@link History} instance with empty lists.
         */
        public History() {
            this(new HashSet<>(), new ArrayList<>(), new ArrayList<>());
        }

        /**
         * Gets the primary record, that is, the record associated with the user transaction itself. This record
         * will be associated with a transaction ID with a nonce of 0 and no parent consensus timestamp.
         *
         * @return The primary record, if there is one.
         */
        @Nullable
        public TransactionRecord userTransactionRecord() {
            return records.isEmpty() ? null : sortedRecords().getFirst();
        }

        public @NonNull TransactionReceipt priorityReceipt() {
            return records.isEmpty()
                    ? ReceiptSource.PENDING_RECEIPT
                    : sortedRecords().getFirst().receiptOrThrow();
        }

        /**
         * Gets the list of all duplicate records, as a view. Should the list of records change, this view will reflect
         * those changes.
         *
         * @return The list of all duplicate records.
         */
        @NonNull
        public List<TransactionRecord> duplicateRecords() {
            return records.isEmpty() ? emptyList() : sortedRecords().subList(1, records.size());
        }

        /**
         * Gets the number of duplicate records.
         *
         * @return The number of duplicate records.
         */
        public int duplicateCount() {
            return records.isEmpty() ? 0 : records.size() - 1;
        }

        /**
         * Returns a list of all records, ordered by consensus timestamp. Some elements of {@link #childRecords} may
         * come before those in {@link #records}, while some may come after some elements in {@link #records}.
         *
         * @return The list of all records, ordered by consensus timestamp.
         */
        public List<TransactionRecord> orderedRecords() {
            final var ordered = new ArrayList<>(records);
            ordered.addAll(childRecords);
            ordered.sort(RECORD_COMPARATOR);
            return ordered;
        }

        private List<TransactionRecord> sortedRecords() {
            return records.stream().sorted(RECORD_COMPARATOR).toList();
        }
    }

    /**
     * Gets the known history of the given {@link TransactionID} in this cache.
     *
     * @param transactionID The transaction ID to look up
     * @return the history, if any, stored in this cache for the given transaction ID. If the history does not exist
     * (i.e. it is null), then we have never heard of this transactionID. If the history is not null, but there
     * are no records within it, then we have heard of this transactionID (i.e. in pre-handle or ingest), but
     * we do not yet have a record for it (i.e. in handle). If there are records, then the first record will
     * be the "primary" or user-transaction record, and the others will be the duplicates.
     */
    @Nullable
    History getHistory(@NonNull TransactionID transactionID);

    /**
     * Gets the receipts for the given {@link TransactionID}, if known.
     * @param transactionID The transaction ID to look up
     * @return the receipts, if any, stored in this cache for the given transaction ID
     */
    @Nullable
    ReceiptSource getReceipts(@NonNull TransactionID transactionID);

    /**
     * Gets a list of all records for the given {@link AccountID}. The {@link AccountID} is the account of the Payer of
     * the transaction.
     *
     * @param accountID The accountID of the Payer of the transactions
     * @return The {@link TransactionRecord}s, if any, or else an empty list.
     */
    @NonNull
    List<TransactionRecord> getRecords(@NonNull AccountID accountID);
}
