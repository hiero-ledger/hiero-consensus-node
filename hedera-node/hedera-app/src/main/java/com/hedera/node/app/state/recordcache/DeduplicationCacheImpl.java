// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache;

import static com.hedera.hapi.util.HapiUtils.ACCOUNT_ID_COMPARATOR;
import static com.hedera.hapi.util.HapiUtils.TIMESTAMP_COMPARATOR;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.hapi.util.HapiUtils.minus;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.node.app.state.DeduplicationCache;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.HederaConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.InstantSource;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import javax.inject.Inject;
import javax.inject.Singleton;

/** An implementation of {@link DeduplicationCache}. */
@Singleton
public final class DeduplicationCacheImpl implements DeduplicationCache {
    /**
     * The {@link TransactionID}s that this node has already submitted to the platform, sorted by transaction start
     * time, such that earlier start times come first.
     * <p>
     * Note that an ID with scheduled set is different from the same ID without scheduled set.
     * In fact, an ID with scheduled set will always match the ID of the ScheduleCreate transaction that created
     * the schedule, except scheduled is set.
     */
    private final Set<TransactionID> submittedTxns =
            new ConcurrentSkipListSet<>(Comparator.<TransactionID, Timestamp>comparing(
                            txnId -> txnId.transactionValidStartOrElse(Timestamp.DEFAULT), TIMESTAMP_COMPARATOR)
                    .thenComparing(txnId -> txnId.accountIDOrElse(AccountID.DEFAULT), ACCOUNT_ID_COMPARATOR)
                    .thenComparing(TransactionID::scheduled)
                    .thenComparing(TransactionID::nonce));

    /** Used for looking up the max transaction duration window. */
    private final ConfigProvider configProvider;
    /**
     * Used to estimate the earliest valid start timestamp that is still within the max transaction duration
     * window that the ingest workflow will be using to screen transactions.
     */
    private final InstantSource instantSource;

    /** Constructs a new {@link DeduplicationCacheImpl}. */
    @Inject
    public DeduplicationCacheImpl(
            @NonNull final ConfigProvider configProvider, @NonNull final InstantSource instantSource) {
        this.configProvider = requireNonNull(configProvider);
        this.instantSource = requireNonNull(instantSource);
    }

    /** {@inheritDoc} */
    @Override
    public void add(@NonNull final TransactionID transactionID) {
        // We don't want to use another thread to prune the set, so we will take the opportunity here to do so.
        // Remember that at this point we have passed through all the throttles, so this method is only called
        // at most 10,000 / (Number of nodes) times per second, which is not a lot.
        final var epochSeconds = approxEarliestValidStartSecond();
        removeTransactionsOlderThan(epochSeconds);

        // If the transaction is within the max transaction duration window, then add it to the set.
        if (transactionID.transactionValidStartOrThrow().seconds() >= epochSeconds) {
            submittedTxns.add(transactionID);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean contains(@NonNull final TransactionID transactionID) {
        // We will prune the set here as well. By pruning before looking up, we are sure that we only return true
        // if the transactionID is still valid
        final var epochSeconds = approxEarliestValidStartSecond();
        removeTransactionsOlderThan(epochSeconds);
        return submittedTxns.contains(transactionID);
    }

    /** {@inheritDoc} */
    @Override
    public void clear() {
        submittedTxns.clear();
    }

    /**
     * Gets the earliest valid start timestamp that is still within the max transaction duration window based on
     * wall-clock time.
     */
    private long approxEarliestValidStartSecond() {
        // Compute the earliest valid start timestamp that is still within the max transaction duration window.
        final var now = asTimestamp(instantSource.instant());
        final var config = configProvider.getConfiguration().getConfigData(HederaConfig.class);
        final var earliestValidState = minus(now, config.transactionMaxValidDuration());
        return earliestValidState.seconds();
    }

    /**
     * Removes all expired {@link TransactionID}s from the cache. This method is not threadsafe and should only be
     * called from within a block synchronized on {@link #submittedTxns}.
     *
     * @param earliestEpochSecond The earliest epoch second that should be kept in the cache.
     */
    private void removeTransactionsOlderThan(final long earliestEpochSecond) {
        final var itr = submittedTxns.iterator();
        while (itr.hasNext()) {
            final var txId = itr.next();
            if (txId.transactionValidStartOrThrow().seconds() < earliestEpochSecond) {
                itr.remove();
            } else {
                return;
            }
        }
    }
}
