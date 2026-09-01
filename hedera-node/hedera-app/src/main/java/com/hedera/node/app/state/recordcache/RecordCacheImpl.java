// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache;

import static com.hedera.hapi.util.HapiUtils.TIMESTAMP_COMPARATOR;
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

import com.hedera.hapi.node.base.AccountID;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
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
     * A secondary index from payer {@link AccountID} to that payer's recent transaction IDs, bucketed by
     * valid-start second so expiry is a prefix drop instead of a {@link HashSet#remove} on a multi-million
     * entry set. Handle mutates; getters may read from other threads. Used only for account-id record
     * queries (unused on the production ingest path).
     */
    private final Map<AccountID, PayerTxnIndex> payerTxnIds = new ConcurrentHashMap<>();
    /**
     * Handle-thread registry of non-empty payer indexes keyed by their first valid-start second. This lets expiry visit
     * only indexes that have expired buckets, without scanning the concurrent query map or iterating empty seconds.
     */
    private final NavigableMap<Long, Set<PayerTxnIndex>> payerIndexesByFrontSecond = new TreeMap<>();
    /**
     * The list of transaction receipts for the current round.
     */
    private final List<TransactionReceiptEntry> transactionReceipts = new ArrayList<>();
    /**
     * Last earliest valid-start <em>second</em> for which {@link #purgeExpiredReceiptEntries} walked the
     * queue. Rounds in the same consensus second share this value, so later rounds skip the walk.
     */
    private long lastPurgeEarliestValidStartSecond = Long.MIN_VALUE;

    /**
     * History for one base {@link TransactionID}. The common CryptoTransfer case is one classifiable node and one
     * record source, so those are stored inline; a set/list is allocated only for duplicates.
     */
    private static final class HistorySource implements ReceiptSource {
        private static final long NO_NODE = Long.MIN_VALUE;

        private volatile long firstNodeId = NO_NODE;

        @Nullable
        private volatile Set<Long> extraNodeIds;

        @Nullable
        private volatile RecordSource firstRecordSource;

        @Nullable
        private volatile List<RecordSource> extraRecordSources;

        void addClassifiableNode(final long nodeId) {
            if (firstNodeId == NO_NODE) {
                firstNodeId = nodeId;
            } else if (firstNodeId != nodeId) {
                extraNodeIds().add(nodeId);
            }
        }

        boolean hasClassifiableNode() {
            return firstNodeId != NO_NODE;
        }

        boolean submittedBy(final long nodeId) {
            return firstNodeId == nodeId || (extraNodeIds != null && extraNodeIds.contains(nodeId));
        }

        @NonNull
        Set<Long> nodeIds() {
            if (firstNodeId == NO_NODE) {
                return Set.of();
            }
            if (extraNodeIds == null || extraNodeIds.isEmpty()) {
                return Set.of(firstNodeId);
            }
            final var all = new HashSet<Long>(extraNodeIds.size() + 1);
            all.add(firstNodeId);
            all.addAll(extraNodeIds);
            return all;
        }

        void addRecordSourceIfAbsent(@NonNull final RecordSource recordSource) {
            if (firstRecordSource == null) {
                firstRecordSource = recordSource;
                return;
            }
            if (firstRecordSource == recordSource) {
                return;
            }
            final var extras = extraRecordSources();
            if (!extras.contains(recordSource)) {
                extras.add(recordSource);
            }
        }

        @Override
        public @NonNull TransactionReceipt priorityReceipt(@NonNull final TransactionID txnId) {
            requireNonNull(txnId);
            if (firstRecordSource == null) {
                return PENDING_RECEIPT;
            }
            final var firstPriorityReceipt = firstRecordSource.receiptOf(txnId);
            if (!NODE_FAILURES.contains(firstPriorityReceipt.status()) || extraRecordSources == null) {
                return firstPriorityReceipt;
            }
            for (final var source : extraRecordSources) {
                final var nextPriorityReceipt = source.receiptOf(txnId);
                if (!NODE_FAILURES.contains(nextPriorityReceipt.status())) {
                    return nextPriorityReceipt;
                }
            }
            return firstPriorityReceipt;
        }

        @Override
        public @Nullable TransactionReceipt childReceipt(@NonNull final TransactionID txnId) {
            requireNonNull(txnId);
            if (firstRecordSource != null) {
                try {
                    return firstRecordSource.receiptOf(txnId);
                } catch (IllegalArgumentException ignore) {
                }
                if (extraRecordSources != null) {
                    for (final var source : extraRecordSources) {
                        try {
                            return source.receiptOf(txnId);
                        } catch (IllegalArgumentException ignore) {
                        }
                    }
                }
            }
            return null;
        }

        @Override
        public @NonNull List<TransactionReceipt> duplicateReceipts(@NonNull final TransactionID txnId) {
            requireNonNull(txnId);
            final List<TransactionReceipt> receipts = new ArrayList<>();
            forEachSource(source -> receipts.add(source.receiptOf(txnId)));
            receipts.remove(priorityReceipt(txnId));
            return receipts;
        }

        @Override
        public @NonNull List<TransactionReceipt> childReceipts(@NonNull final TransactionID txnId) {
            requireNonNull(txnId);
            final List<TransactionReceipt> receipts = new ArrayList<>();
            forEachSource(source -> receipts.addAll(source.childReceiptsOf(txnId)));
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
            forEachSource(recordSource -> recordSource.forEachTxnRecord(txnRecord -> {
                final var txnId = txnRecord.transactionIDOrThrow();
                if (matchesExceptNonce(txnId, userTxnId)) {
                    final var source = txnId.nonce() > 0 ? childRecords : duplicateRecords;
                    source.add(txnRecord);
                }
            }));
            return new History(nodeIds(), duplicateRecords, childRecords);
        }

        private void forEachSource(@NonNull final java.util.function.Consumer<RecordSource> action) {
            if (firstRecordSource == null) {
                return;
            }
            action.accept(firstRecordSource);
            if (extraRecordSources != null) {
                extraRecordSources.forEach(action);
            }
        }

        @NonNull
        private Set<Long> extraNodeIds() {
            if (extraNodeIds == null) {
                extraNodeIds = ConcurrentHashMap.newKeySet();
            }
            return extraNodeIds;
        }

        @NonNull
        private List<RecordSource> extraRecordSources() {
            if (extraRecordSources == null) {
                extraRecordSources = new CopyOnWriteArrayList<>();
            }
            return extraRecordSources;
        }
    }

    /**
     * Per-payer transaction IDs grouped by valid-start second. Handle-thread appends; query threads iterate.
     */
    private static final class PayerTxnIndex {
        private final AccountID payerId;
        private final ArrayDeque<PayerSecondBucket> buckets = new ArrayDeque<>();
        private long registeredFrontSecond;
        private boolean registered;

        private PayerTxnIndex(@NonNull final AccountID payerId) {
            this.payerId = payerId;
        }

        void add(@NonNull final TransactionID txnId) {
            final long second =
                    txnId.transactionValidStartOrElse(Timestamp.DEFAULT).seconds();
            var last = buckets.peekLast();
            if (last == null || last.validStartSecond < second) {
                last = new PayerSecondBucket(second);
                buckets.addLast(last);
            } else if (last.validStartSecond > second) {
                final var laterBuckets = new ArrayDeque<PayerSecondBucket>();
                do {
                    laterBuckets.addFirst(buckets.removeLast());
                    last = buckets.peekLast();
                } while (last != null && last.validStartSecond > second);
                if (last == null || last.validStartSecond != second) {
                    last = new PayerSecondBucket(second);
                    buckets.addLast(last);
                }
                buckets.addAll(laterBuckets);
            }
            last.txnIds.add(txnId);
        }

        void dropExpired(final long earliestValidStartSecond) {
            PayerSecondBucket first;
            while ((first = buckets.peekFirst()) != null && first.validStartSecond < earliestValidStartSecond) {
                buckets.pollFirst();
            }
        }

        boolean isEmpty() {
            return buckets.isEmpty();
        }

        long frontSecond() {
            return requireNonNull(buckets.peekFirst()).validStartSecond;
        }

        @NonNull
        Iterable<PayerSecondBucket> buckets() {
            return buckets;
        }
    }

    private static final class PayerSecondBucket {
        private final long validStartSecond;
        private final List<TransactionID> txnIds = new ArrayList<>();

        private PayerSecondBucket(final long validStartSecond) {
            this.validStartSecond = validStartSecond;
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
            for (final var receipt : roundReceipts.entries()) {
                final var txnId = receipt.transactionIdOrThrow();
                // We group history by the base transaction ID, which is the transaction ID with a nonce of 0
                final var baseTxnId = txnId.nonce() == 0
                        ? txnId
                        : txnId.copyBuilder().nonce(0).build();
                // Ensure this node won't submit duplicate transactions and be penalized for it
                deduplicationCache.add(baseTxnId);
                // Now update the history of this transaction id
                final var historySource = historySourceFor(baseTxnId);
                // Honest nodes use the set of node ids that have submitted classifiable transactions with this id to
                // classify user versus node duplicates; so reconstructing the set here is critical for deterministic
                // transaction handling across all nodes in the network
                if (!NODE_FAILURES.contains(receipt.status())) {
                    historySource.addClassifiableNode(receipt.nodeId());
                }
                // These steps only make a partial transaction record available for answering queries, and are not
                // of critical importance for the operation of the node
                if (historySource.firstRecordSource == null) {
                    historySource.addRecordSourceIfAbsent(new PartialRecordSource());
                }
                ((PartialRecordSource) historySource.firstRecordSource).incorporate(asTxnRecord(receipt));
                if (txnId.nonce() == 0) {
                    indexPayerTxn(txnId.accountIDOrThrow(), txnId);
                }
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
            final var historySource = historySourceFor(baseTxnId);
            // We don't let improperly submitted transactions keep properly submitted transactions from using an id
            if (!NODE_FAILURES.contains(status)) {
                historySource.addClassifiableNode(nodeId);
            }
            // Only add each record source once per history; since very few record sources contain more than one
            // transaction id, and few transaction ids have duplicates, this is almost always an existence check
            // on the first source
            historySource.addRecordSourceIfAbsent(recordSource);
            final AccountID effectivePayerId;
            if (dueDiligenceFailure == DueDiligenceFailure.YES && matchesExceptNonce(txnId, userTxnId)) {
                effectivePayerId = requireNonNull(networkInfo.nodeInfo(nodeId)).accountId();
            } else {
                effectivePayerId = txnId.accountIDOrThrow();
            }
            if (txnId.nonce() == 0) {
                indexPayerTxn(effectivePayerId, txnId);
            }
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
            queue.add(new TransactionReceiptEntries(new ArrayList<>(transactionReceipts)));
        }
        if (states instanceof CommittableWritableStates committable) {
            committable.commit();
        }
        if (streamMode != RECORDS) {
            blockStreamManager.writeStateChanges(immediateStateChangeListener.takeQueueStateChanges());
        }
    }

    @NonNull
    @Override
    public DuplicateCheckResult hasDuplicate(@NonNull final TransactionID txnId, final long nodeId) {
        requireNonNull(txnId);
        final var historySource = historySources.get(txnId);
        // If there is no history for this transaction id; or all its history consists of
        // unclassifiable records, return that it is effectively a unique id
        if (historySource == null || !historySource.hasClassifiableNode()) {
            return NO_DUPLICATE;
        }
        return historySource.submittedBy(nodeId) ? SAME_NODE : OTHER_NODE;
    }

    /**
     * Removes all expired {@link TransactionID}s from the cache.
     */
    private void purgeExpiredReceiptEntries(
            @NonNull final WritableQueueState<TransactionReceiptEntries> queue,
            @NonNull final Instant consensusTimestamp) {
        // Compute the earliest valid start second that is still within the max transaction duration window.
        // Skip the queue walk when this second has not advanced (many rounds share a consensus second).
        final var config = configProvider.getConfiguration().getConfigData(HederaConfig.class);
        final long earliestValidStartSecond =
                consensusTimestamp.getEpochSecond() - config.transactionMaxValidDuration();
        if (earliestValidStartSecond == lastPurgeEarliestValidStartSecond) {
            return;
        }
        lastPurgeEarliestValidStartSecond = earliestValidStartSecond;
        dropExpiredPayerBuckets(earliestValidStartSecond);
        final var earliestValidStart = new Timestamp(earliestValidStartSecond, 0);
        // Loop in order and expunge the entry if even the latest TransactionReceiptEntry is expired
        TransactionReceiptEntries roundReceipts;
        while ((roundReceipts = queue.peek()) != null) {
            if (roundReceipts.entries().isEmpty()) {
                logger.warn("Unexpected empty round receipts in the queue, removing them");
                queue.poll();
                continue;
            }
            Timestamp latestReceiptValidStart = null;
            for (final var entry : roundReceipts.entries()) {
                final var validStart =
                        entry.transactionIdOrElse(TransactionID.DEFAULT).transactionValidStartOrElse(Timestamp.DEFAULT);
                if (latestReceiptValidStart == null
                        || TIMESTAMP_COMPARATOR.compare(validStart, latestReceiptValidStart) > 0) {
                    latestReceiptValidStart = validStart;
                }
            }
            // If even the latest valid start time is before the earliest valid start, then all transaction
            // ids used in this round are expired and cannot be duplicated
            if (isBefore(latestReceiptValidStart, earliestValidStart)) {
                // Remove all in-memory context for these transaction ids.  Note that all transactions are added
                // to this map keyed to the "user transaction" ID, so removing the entry here removes both "parent"
                // and "child" transaction records associated with that ID.
                for (final var receipt : roundReceipts.entries()) {
                    final var txnId = receipt.transactionIdOrThrow();
                    historySources.remove(
                            txnId.nonce() == 0
                                    ? txnId
                                    : txnId.copyBuilder().nonce(0).build());
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
        final var payerIndex = payerTxnIds.get(accountID);
        if (payerIndex == null) {
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
        // Because the payer buckets could be concurrently modified by
        // the handle thread, wrap this in a try-catch block to deal with a CME
        // and return whatever we are able to gather. (I.e. this is a best-effort
        // query, and not a critical path; unused in production environments)
        try {
            final var seen = new HashSet<TransactionID>();
            gather:
            for (final var bucket : payerIndex.buckets()) {
                for (final var txnId : bucket.txnIds) {
                    if (!seen.add(txnId)) {
                        continue;
                    }
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
                            break gather;
                        }
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

    @NonNull
    private HistorySource historySourceFor(@NonNull final TransactionID baseTxnId) {
        var historySource = historySources.get(baseTxnId);
        if (historySource == null) {
            historySource = new HistorySource();
            historySources.put(baseTxnId, historySource);
        }
        return historySource;
    }

    private void indexPayerTxn(@NonNull final AccountID payerId, @NonNull final TransactionID txnId) {
        var index = payerTxnIds.get(payerId);
        if (index == null) {
            index = new PayerTxnIndex(payerId);
            payerTxnIds.put(payerId, index);
            index.add(txnId);
            discardAlreadyExpiredBucketsAndRegister(index);
            return;
        }
        final long previousFrontSecond = index.frontSecond();
        index.add(txnId);
        if (index.frontSecond() != previousFrontSecond) {
            relocatePayerIndex(index);
        }
    }

    private void dropExpiredPayerBuckets(final long earliestValidStartSecond) {
        Map.Entry<Long, Set<PayerTxnIndex>> expiredEntry;
        while ((expiredEntry = payerIndexesByFrontSecond.firstEntry()) != null
                && expiredEntry.getKey() < earliestValidStartSecond) {
            final var indexes = List.copyOf(expiredEntry.getValue());
            for (final var index : indexes) {
                unregisterPayerIndex(index);
            }
            for (final var index : indexes) {
                index.dropExpired(earliestValidStartSecond);
                if (index.isEmpty()) {
                    payerTxnIds.remove(index.payerId, index);
                } else {
                    registerPayerIndex(index);
                }
            }
        }
    }

    private void discardAlreadyExpiredBucketsAndRegister(@NonNull final PayerTxnIndex index) {
        index.dropExpired(lastPurgeEarliestValidStartSecond);
        if (index.isEmpty()) {
            payerTxnIds.remove(index.payerId, index);
        } else {
            registerPayerIndex(index);
        }
    }

    private void registerPayerIndex(@NonNull final PayerTxnIndex index) {
        if (index.registered || index.isEmpty()) {
            throw new IllegalStateException("Only an unregistered, non-empty payer index can be registered");
        }
        final long frontSecond = index.frontSecond();
        payerIndexesByFrontSecond
                .computeIfAbsent(frontSecond, ignored -> new HashSet<>())
                .add(index);
        index.registeredFrontSecond = frontSecond;
        index.registered = true;
    }

    private void unregisterPayerIndex(@NonNull final PayerTxnIndex index) {
        if (!index.registered) {
            return;
        }
        final var indexes = payerIndexesByFrontSecond.get(index.registeredFrontSecond);
        if (indexes != null) {
            indexes.remove(index);
            if (indexes.isEmpty()) {
                payerIndexesByFrontSecond.remove(index.registeredFrontSecond, indexes);
            }
        }
        index.registered = false;
    }

    private void relocatePayerIndex(@NonNull final PayerTxnIndex index) {
        unregisterPayerIndex(index);
        discardAlreadyExpiredBucketsAndRegister(index);
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
