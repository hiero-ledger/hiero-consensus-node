// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache;

import static com.hedera.hapi.node.base.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hedera.hapi.util.HapiUtils.TIMESTAMP_COMPARATOR;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.hapi.util.HapiUtils.isBefore;
import static com.hedera.node.app.spi.records.RecordCache.matchesExceptNonce;
import static com.hedera.node.app.state.HederaRecordCache.DuplicateCheckResult.NO_DUPLICATE;
import static com.hedera.node.app.state.HederaRecordCache.DuplicateCheckResult.OTHER_NODE;
import static com.hedera.node.app.state.HederaRecordCache.DuplicateCheckResult.SAME_NODE;
import static com.hedera.node.app.state.recordcache.RecordCacheService.NAME;
import static com.hedera.node.app.state.recordcache.schemas.V0490RecordCacheSchema.TRANSACTION_RECEIPTS_STATE_ID;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.recordcache.TransactionReceiptEntries;
import com.hedera.hapi.node.state.recordcache.TransactionReceiptEntry;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.impl.ImmediateStateChangeListener;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.records.RecordSource;
import com.hedera.node.app.state.DeduplicationCache;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.state.WorkingStateAccessor;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.types.StreamMode;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.WritableQueueState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * An implementation of {@link HederaRecordCache}
 *
 * <p>This implementation stores all records in a time-ordered queue of {@link TransactionRecord}s, where records are
 * ordered by <strong>consensus timestamp</strong> and kept for {@code maxTxnDuration} seconds before expiry. These
 * records are stored in state because they must be deterministic across all nodes in the network, and therefore must
 * also be part of reconnect, and state saving and loading. A queue provides superior performance to a map in this
 * particular case because all items removed from the queue are always removed from the head, and all items added to
 * the queue are always added to the end.
 *
 * <p>However, storing them in a queue of this nature does not provide efficient access to the data itself. For this
 * reason, in-memory data structures are used to provide efficient access to the data. These data structures are rebuilt
 * after reconnect or restart, and kept in sync with the data in state.
 *
 * <p>Some transactions produce additional "child" transactions or "preceding" transactions. For example, when an
 * account is to be auto-created due to a crypto transfer to an unknown alias, we create a preceding transaction. Or,
 * a smart contract call may create child transactions when working with HTS. In all cases, each of these transactions
 * are treated as separate transactions, in that they are individually added to the queue, in the appropriate order, and
 * individually added to the history. However, child transactions are always included in the history of the user
 * transaction that triggered them, because they need to be available to the user when querying for all records for a
 * given transaction ID, or for a given payer.
 *
 * <p>Mutation methods must be called during startup, reconnect, or on the "handle" thread. Getters may be called from
 * any thread.
 */
@Singleton
public class RecordCacheImpl implements HederaRecordCache {
    private static final Logger logger = LogManager.getLogger(RecordCacheImpl.class);
    /**
     * Comparator for sorting {@link TransactionReceiptEntry} by the transaction valid start timestamp.
     */
    public static final Comparator<TransactionReceiptEntry> TRANSACTION_VALID_START_COMPARATOR = Comparator.comparing(
            e -> e.transactionIdOrElse(TransactionID.DEFAULT).transactionValidStartOrElse(Timestamp.DEFAULT),
            TIMESTAMP_COMPARATOR);

    /**
     * This empty History is returned whenever a transaction is known to the deduplication cache, but not yet
     * added to this cache.
     */
    private static final History EMPTY_HISTORY = new History();

    /**
     * This empty History is returned whenever a transaction is known to the deduplication cache, but not yet
     * added to this cache.
     */
    private static final HistorySource EMPTY_HISTORY_SOURCE = new HistorySource();

    /**
     * Used for looking up fee collection account for a node that failed due diligence. This must be looked up dynamically.
     */
    private final NetworkInfo networkInfo;
    /**
     * Used for looking up the max valid duration window for a transaction. This must be looked up dynamically.
     */
    private final ConfigProvider configProvider;
    /**
     * Every record added to the cache has a unique transaction ID. Each of these must be recorded in the dedupe cache
     * to help avoid duplicate transactions being ingested. And, when a history is looked up, if the transaction ID is
     * known to the deduplication cache, but unknown to this record cache, then we return an empty history rather than
     * null.
     */
    private final DeduplicationCache deduplicationCache;
    /**
     * A map of transaction IDs to the sources of records for those transaction ids and their children.
     */
    private final Map<TransactionID, HistorySource> historySources = new ConcurrentHashMap<>();
    /**
     * A secondary index that maps from the AccountID of the payer account to a set of transaction IDs that were
     * submitted by this payer. This is only needed for answering queries. Ideally such queries would exist on the
     * mirror node instead. The answer to this query will include child records that were created as a consequence
     * of the original user transaction, but not any preceding records triggered by it.
     */
    private final Map<AccountID, Set<TransactionID>> payerTxnIds = new ConcurrentHashMap<>();
    /**
     * The list of transaction receipts for the current round.
     */
    private final List<TransactionReceiptEntry> transactionReceipts = new ArrayList<>();

    /**
     * Contains history of transactions submitted with the same "base" {@link TransactionID};
     * i.e., with the same payer and valid start time.
     * <p>
     * This history has two parts:
     * <ol>
     *     <li>A {@code nodeIdCounts} map with the number of queued receipts each node has for a properly
     *     screened transaction with the scoped base {@link TransactionID}; its keys are used to
     *     classify duplicate transactions.</li>
     *     <li>A {@code recordSources} list with all the sources of records for the relevant
     *     base {@link TransactionID}. This is used to construct {@link TransactionRecord}
     *     records for answering queries.</li>
     * </ol>
     *
     * @param nodeIdCounts The number of queued receipts for a properly screened transaction, by node id
     * @param recordSources The sources of records for the relevant base {@link TransactionID}
     * @param sourceReceiptCounts The number of queued receipts from each record source, in the same order
     */
    private record HistorySource(
            @NonNull Map<Long, Integer> nodeIdCounts,
            @NonNull List<RecordSource> recordSources,
            @NonNull Deque<Integer> sourceReceiptCounts)
            implements ReceiptSource {
        public HistorySource() {
            this(new HashMap<>(), new CopyOnWriteArrayList<>(), new ArrayDeque<>());
        }

        /**
         * Returns the ids of the nodes with a queued receipt for a properly screened transaction.
         */
        Set<Long> nodeIds() {
            return nodeIdCounts.keySet();
        }

        /**
         * Adds a queued receipt from the given record source.
         */
        void addReceipt(final long nodeId, @NonNull final ResponseCodeEnum status, @NonNull final RecordSource source) {
            if (!NODE_FAILURES.contains(status)) {
                nodeIdCounts.merge(nodeId, 1, Integer::sum);
            }
            // All receipts from a source are added together, so a source already in this history is its last one
            if (!recordSources.isEmpty() && recordSources.getLast() == source) {
                sourceReceiptCounts.addLast(sourceReceiptCounts.removeLast() + 1);
            } else {
                recordSources.add(source);
                sourceReceiptCounts.addLast(1);
            }
        }

        /**
         * Removes a purged receipt, returning whether no queued receipt is left.
         */
        boolean removeReceipt(final long nodeId, @NonNull final ResponseCodeEnum status) {
            if (!NODE_FAILURES.contains(status)) {
                nodeIdCounts.computeIfPresent(nodeId, (ignore, count) -> count == 1 ? null : count - 1);
            }
            // Receipts are purged in the order they were added, so this one came from the first source
            final int left = sourceReceiptCounts.removeFirst() - 1;
            if (left > 0) {
                sourceReceiptCounts.addFirst(left);
            } else {
                recordSources.removeFirst();
            }
            return recordSources.isEmpty();
        }

        @Override
        public @NonNull TransactionReceipt priorityReceipt(@NonNull final TransactionID txnId) {
            requireNonNull(txnId);
            // Iterate over a snapshot, since purging can remove sources while a query reads them
            TransactionReceipt firstPriorityReceipt = null;
            for (final var source : recordSources) {
                final var receipt = source.receiptOf(txnId);
                if (!NODE_FAILURES.contains(receipt.status())) {
                    return receipt;
                }
                if (firstPriorityReceipt == null) {
                    firstPriorityReceipt = receipt;
                }
            }
            return firstPriorityReceipt == null ? PENDING_RECEIPT : firstPriorityReceipt;
        }

        @Override
        public @Nullable TransactionReceipt childReceipt(@NonNull final TransactionID txnId) {
            requireNonNull(txnId);
            for (final var source : recordSources) {
                try {
                    return source.receiptOf(txnId);
                } catch (IllegalArgumentException ignore) {
                }
            }
            return null;
        }

        @Override
        public @NonNull List<TransactionReceipt> duplicateReceipts(@NonNull final TransactionID txnId) {
            requireNonNull(txnId);
            final List<TransactionReceipt> receipts = new ArrayList<>();
            recordSources.forEach(source -> receipts.add(source.receiptOf(txnId)));
            receipts.remove(priorityReceipt(txnId));
            return receipts;
        }

        @Override
        public @NonNull List<TransactionReceipt> childReceipts(@NonNull final TransactionID txnId) {
            requireNonNull(txnId);
            final List<TransactionReceipt> receipts = new ArrayList<>();
            recordSources.forEach(source -> receipts.addAll(source.childReceiptsOf(txnId)));
            return receipts;
        }

        /**
         * Returns a {@link History} that summarizes all duplicate and child records for a given {@link TransactionID}
         * from this history source in canonical order.
         *
         * @param userTxnId the user {@link TransactionID} to summarize records for
         * @return the canonical history
         */
        History historyOf(@NonNull final TransactionID userTxnId) {
            final List<TransactionRecord> duplicateRecords = new ArrayList<>();
            final List<TransactionRecord> childRecords = new ArrayList<>();
            for (final var recordSource : recordSources) {
                recordSource.forEachTxnRecord(txnRecord -> {
                    final var txnId = txnRecord.transactionIDOrThrow();
                    if (matchesExceptNonce(txnId, userTxnId)) {
                        final var source = txnId.nonce() > 0 ? childRecords : duplicateRecords;
                        source.add(txnRecord);
                    }
                });
            }
            return new History(nodeIds(), duplicateRecords, childRecords);
        }
    }

    /**
     * Called once during startup to create this singleton. Rebuilds the in-memory data structures based on the current
     * working state at the moment of startup. The size of these data structures is fixed based on the length of time
     * the node was configured to keep records for. In other words, the amount of time it takes to rebuild this
     * data structure is not dependent on the size of state, but rather, the number of transactions that had occurred
     * within a 3-minute window prior to the node stopping.
     * <p>
     * A new instance of the cache is constructed every time the node restarts or reconnects (and hence re-initializes
     * its Dagger2 components). This is because the cache is stateful and must be rebuilt from the state at the time of
     * startup. This is a deterministic process, and the cache will always be rebuilt in the same way, given the same
     * state.
     *
     * @param deduplicationCache A cache containing known {@link TransactionID}s, used for deduplication
     * @param workingStateAccessor Gives access to the current working state to use in rebuilding the cache
     * @param configProvider Used for looking up the max valid duration window for a transaction dynamically
     * @param networkInfo the network information
     */
    @Inject
    public RecordCacheImpl(
            @NonNull final DeduplicationCache deduplicationCache,
            @NonNull final WorkingStateAccessor workingStateAccessor,
            @NonNull final ConfigProvider configProvider,
            @NonNull final NetworkInfo networkInfo) {
        this.deduplicationCache = requireNonNull(deduplicationCache);
        this.configProvider = requireNonNull(configProvider);
        this.networkInfo = requireNonNull(networkInfo);

        deduplicationCache.clear();
        final var iter = getReadableQueue(workingStateAccessor).iterator();
        while (iter.hasNext()) {
            final var roundReceipts = iter.next();
            // Each queued entry gets its own record source per id, so purging the entry removes only its receipts
            final Map<TransactionID, PartialRecordSource> entrySources = new HashMap<>();
            for (final var receipt : roundReceipts.entries()) {
                final var txnId = receipt.transactionIdOrThrow();
                // We group history by the base transaction ID, which is the transaction ID with a nonce of 0
                final var baseTxnId = txnId.nonce() == 0
                        ? txnId
                        : txnId.copyBuilder().nonce(0).build();
                // Ensure this node won't submit duplicate transactions and be penalized for it
                deduplicationCache.add(baseTxnId);
                // Now update the history of this transaction id
                final var historySource = historySources.computeIfAbsent(baseTxnId, ignore -> new HistorySource());
                // These steps only make a partial transaction record available for answering queries, and are not
                // of critical importance for the operation of the node
                final var recordSource = entrySources.computeIfAbsent(baseTxnId, ignore -> new PartialRecordSource());
                recordSource.incorporate(asTxnRecord(receipt));
                // Honest nodes use the set of node ids that have submitted classifiable transactions with this id to
                // classify user versus node duplicates; so reconstructing the set here is critical for deterministic
                // transaction handling across all nodes in the network
                historySource.addReceipt(receipt.nodeId(), receipt.status(), recordSource);
                payerTxnIds
                        .computeIfAbsent(txnId.accountIDOrThrow(), ignored -> new HashSet<>())
                        .add(txnId);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Implementation methods of HederaRecordCache
    // ---------------------------------------------------------------------------------------------------------------
    @Override
    public void addRecordSource(
            final long nodeId,
            @NonNull final TransactionID userTxnId,
            @NonNull final DueDiligenceFailure dueDiligenceFailure,
            @NonNull final RecordSource recordSource) {
        requireNonNull(userTxnId);
        requireNonNull(recordSource);
        for (final var identifiedReceipt : recordSource.identifiedReceipts()) {
            final var txnId = identifiedReceipt.txnId();
            final var status = identifiedReceipt.receipt().status();
            transactionReceipts.add(new TransactionReceiptEntry(nodeId, txnId, status));
            final var baseTxnId =
                    txnId.nonce() == 0 ? txnId : txnId.copyBuilder().nonce(0).build();
            final var historySource = historySources.computeIfAbsent(baseTxnId, ignore -> new HistorySource());
            // We don't let improperly submitted transactions keep properly submitted transactions from using an id
            historySource.addReceipt(nodeId, status, recordSource);
            final AccountID effectivePayerId;
            if (dueDiligenceFailure == DueDiligenceFailure.YES && matchesExceptNonce(txnId, userTxnId)) {
                effectivePayerId = requireNonNull(networkInfo.nodeInfo(nodeId)).accountId();
            } else {
                effectivePayerId = txnId.accountIDOrThrow();
            }
            payerTxnIds
                    .computeIfAbsent(effectivePayerId, ignored -> new HashSet<>())
                    .add(txnId);
        }
    }

    @Override
    public void resetRoundReceipts() {
        transactionReceipts.clear();
    }

    @Override
    public void maybeCommitReceiptsBatch(
            @NonNull final State state,
            @NonNull final Instant consensusNow,
            @NonNull final ImmediateStateChangeListener immediateStateChangeListener,
            final int receiptEntriesBatchSize,
            @NonNull final BlockStreamManager blockStreamManager,
            @NonNull final StreamMode streamMode) {
        if (transactionReceipts.size() >= receiptEntriesBatchSize) {
            commitReceipts(state, consensusNow, immediateStateChangeListener, blockStreamManager, streamMode);
            transactionReceipts.clear();
        }
    }

    @Override
    public void commitReceipts(
            @NonNull final State state,
            @NonNull final Instant consensusNow,
            @NonNull final ImmediateStateChangeListener immediateStateChangeListener,
            @NonNull final BlockStreamManager blockStreamManager,
            @NonNull final StreamMode streamMode) {
        requireNonNull(state);
        requireNonNull(consensusNow);
        requireNonNull(blockStreamManager);
        requireNonNull(streamMode);
        if (streamMode != RECORDS) {
            immediateStateChangeListener.resetQueueStateChanges();
        }
        final var states = state.getWritableStates(NAME);
        final var queue = states.<TransactionReceiptEntries>getQueue(TRANSACTION_RECEIPTS_STATE_ID);
        purgeExpiredReceiptEntries(queue, consensusNow);
        if (!transactionReceipts.isEmpty()) {
            queue.add(new TransactionReceiptEntries(new ArrayList<>(transactionReceipts), asTimestamp(consensusNow)));
        }
        if (states instanceof CommittableWritableStates committable) {
            committable.commit();
        }
        if (streamMode != RECORDS) {
            final var changes = immediateStateChangeListener.getQueueStateChanges();
            if (!changes.isEmpty()) {
                blockStreamManager.writeItem((now -> BlockItem.newBuilder()
                        .stateChanges(new StateChanges(now, new ArrayList<>(changes)))
                        .build()));
            }
        }
    }

    @NonNull
    @Override
    public DuplicateCheckResult hasDuplicate(@NonNull final TransactionID txnId, final long nodeId) {
        requireNonNull(txnId);
        final var historySource = historySources.get(txnId);
        // If there is no history for this transaction id; or all its history consists of
        // unclassifiable records, return that it is effectively a unique id
        if (historySource == null || historySource.nodeIds().isEmpty()) {
            return NO_DUPLICATE;
        }
        return historySource.nodeIds().contains(nodeId) ? SAME_NODE : OTHER_NODE;
    }

    /**
     * Removes all expired {@link TransactionID}s from the cache.
     */
    private void purgeExpiredReceiptEntries(
            @NonNull final WritableQueueState<TransactionReceiptEntries> queue,
            @NonNull final Instant consensusTimestamp) {
        // Compute the earliest valid start timestamp that is still within the max transaction duration window.
        final var config = configProvider.getConfiguration().getConfigData(HederaConfig.class);
        final var earliestValidStart = new Timestamp(
                consensusTimestamp.getEpochSecond() - config.transactionMaxValidDuration(),
                consensusTimestamp.getNano());
        // Loop in order and expunge the entry if even the latest TransactionReceiptEntry is expired
        TransactionReceiptEntries roundReceipts;
        while ((roundReceipts = queue.peek()) != null) {
            if (roundReceipts.entries().isEmpty()) {
                logger.warn("Unexpected empty round receipts in the queue, removing them");
                queue.poll();
                continue;
            }
            final var latestReceiptValidStart = roundReceipts.entries().stream()
                    .max(TRANSACTION_VALID_START_COMPARATOR)
                    .map(entry -> entry.transactionIdOrElse(TransactionID.DEFAULT)
                            .transactionValidStartOrElse(Timestamp.DEFAULT))
                    .orElseThrow();
            // A valid start after the round's own consensus time belongs to a transaction that never executed, so
            // it must not keep this round (and every round after it) from being purged
            final var roundConsensusTime = roundReceipts.consensusTimestamp();
            final var latestValidStart =
                    roundConsensusTime != null && isBefore(roundConsensusTime, latestReceiptValidStart)
                            ? roundConsensusTime
                            : latestReceiptValidStart;
            // If even the latest valid start time is before the earliest valid start, then all transaction
            // ids used in this round are expired and cannot be duplicated
            if (isBefore(latestValidStart, earliestValidStart)) {
                // Remove the in-memory context for these receipts.  Note that all transactions are added to this
                // map keyed to the "user transaction" ID, so removing the entry here removes both "parent" and
                // "child" transaction records associated with that ID.  Only this round's receipts are removed, so
                // the entry stays as long as a later round still holds a receipt for the same ID.
                for (final var receipt : roundReceipts.entries()) {
                    final var txnId = receipt.transactionIdOrThrow();
                    historySources.computeIfPresent(
                            txnId.nonce() == 0
                                    ? txnId
                                    : txnId.copyBuilder().nonce(0).build(),
                            (ignore, historySource) -> historySource.removeReceipt(receipt.nodeId(), receipt.status())
                                    ? null
                                    : historySource);
                    // Remove from the payer to transaction index
                    var payerId = txnId.accountIDOrThrow();
                    var txnIds = payerTxnIds.computeIfAbsent(payerId, ignored -> new HashSet<>());
                    if (!txnIds.remove(txnId)) {
                        // The submitting node account must have been the payer
                        payerId = requireNonNull(networkInfo.nodeInfo(receipt.nodeId()))
                                .accountId();
                        txnIds = payerTxnIds.computeIfAbsent(payerId, ignored -> new HashSet<>());
                        if (!txnIds.remove(txnId) && receipt.status() != DUPLICATE_TRANSACTION) {
                            logger.warn(
                                    "Non-duplicate {} not cached for either payer or submitting node {}",
                                    txnId,
                                    payerId);
                        }
                    }
                    if (txnIds.isEmpty()) {
                        payerTxnIds.remove(payerId);
                    }
                }
                // Remove the round receipts from the queue
                queue.poll();
            } else {
                break;
            }
        }
    }
    // ---------------------------------------------------------------------------------------------------------------
    // Implementation methods of RecordCache
    // ---------------------------------------------------------------------------------------------------------------

    @Nullable
    @Override
    public History getHistory(@NonNull final TransactionID txnId) {
        requireNonNull(txnId);
        final var historySource = historySources.get(txnId);
        return historySource != null
                ? historySource.historyOf(txnId)
                : (deduplicationCache.contains(txnId) ? EMPTY_HISTORY : null);
    }

    @Override
    public @Nullable ReceiptSource getReceipts(@NonNull final TransactionID txnId) {
        requireNonNull(txnId);
        final var historySource = historySources.get(txnId);
        return historySource != null
                ? historySource
                : (deduplicationCache.contains(txnId) ? EMPTY_HISTORY_SOURCE : null);
    }

    @NonNull
    @Override
    public List<TransactionRecord> getRecords(@NonNull final AccountID accountID) {
        final var txnIds = payerTxnIds.get(accountID);
        if (txnIds == null) {
            return emptyList();
        }
        // Note that at **most** LedgerConfig#recordsMaxQueryableByAccount() records will be available, even if the
        // given account has paid for more than this number of transactions in the last 180 seconds.
        var maxRemaining = configProvider
                .getConfiguration()
                .getConfigData(LedgerConfig.class)
                .recordsMaxQueryableByAccount();
        // While we still need to gather more records, collect them from the different histories.
        final var records = new ArrayList<TransactionRecord>(maxRemaining);
        // Because the set of transaction IDs could be concurrently modified by
        // the handle thread, wrap this in a try-catch block to deal with a CME
        // and return whatever we are able to gather. (I.e. this is a best-effort
        // query, and not a critical path; unused in production environments)
        try {
            for (final var txnId : txnIds) {
                final var historySource = historySources.get(txnId);
                if (historySource != null) {
                    final var history = historySource.historyOf(txnId);
                    final var sourcedRecords = history.orderedRecords();
                    records.addAll(
                            sourcedRecords.size() > maxRemaining
                                    ? sourcedRecords.subList(0, maxRemaining)
                                    : sourcedRecords);
                    maxRemaining -= sourcedRecords.size();
                    if (maxRemaining <= 0) {
                        break;
                    }
                }
            }
        } catch (ConcurrentModificationException ignore) {
            // Ignore the exception and return what we found; this query is unused in production environments
        }
        records.sort((a, b) -> TIMESTAMP_COMPARATOR.compare(
                a.consensusTimestampOrElse(Timestamp.DEFAULT), b.consensusTimestampOrElse(Timestamp.DEFAULT)));
        return records;
    }

    /**
     * Utility method that get the readable queue from the working state
     */
    private ReadableQueueState<TransactionReceiptEntries> getReadableQueue(
            final WorkingStateAccessor workingStateAccessor) {
        final var states = requireNonNull(workingStateAccessor.getState()).getReadableStates(NAME);
        return states.getQueue(TRANSACTION_RECEIPTS_STATE_ID);
    }

    private static TransactionRecord asTxnRecord(final TransactionReceiptEntry receipt) {
        return TransactionRecord.newBuilder()
                .receipt(
                        TransactionReceipt.newBuilder().status(receipt.status()).build())
                .transactionID(receipt.transactionId())
                .build();
    }
}
