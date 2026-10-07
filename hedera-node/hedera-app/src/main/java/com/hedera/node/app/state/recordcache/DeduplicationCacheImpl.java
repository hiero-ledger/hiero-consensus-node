// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.TransactionID;
import com.hedera.node.app.state.DeduplicationCache;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.HederaConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.InstantSource;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;
import javax.inject.Singleton;

/** An implementation of {@link DeduplicationCache}. */
@Singleton
public final class DeduplicationCacheImpl implements DeduplicationCache {

    private final AtomicLong latestPruneEpochSecond = new AtomicLong();
    private final AtomicLong latestApproxSecond = new AtomicLong();
    private final AtomicLong latestApproxValidSecond = new AtomicLong();

    /**
     * The {@link TransactionID}s that this node has already submitted to the platform, bucketed by the epoch
     * second of their transaction valid-start time. Bucketing (rather than a single totally-ordered set) lets
     * {@code add}/{@code contains} be O(1) hash lookups instead of an O(log n) comparator-based walk, and lets
     * pruning evict whole expired buckets instead of polling element-by-element.
     * <p>
     * Note that an ID with scheduled set is different from the same ID without scheduled set.
     * In fact, an ID with scheduled set will always match the ID of the ScheduleCreate transaction that created
     * the schedule, except scheduled is set.
     */
    private final ConcurrentHashMap<Long, Set<TransactionID>> submittedTxns = new ConcurrentHashMap<>();

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
        final var validStartSecond =
                transactionID.transactionValidStartOrThrow().seconds();
        if (validStartSecond >= epochSeconds) {
            submittedTxns
                    .computeIfAbsent(validStartSecond, second -> ConcurrentHashMap.newKeySet())
                    .add(transactionID);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean contains(@NonNull final TransactionID transactionID) {
        // We will prune the set here as well. By pruning before looking up, we are sure that we only return true
        // if the transactionID is still valid
        final var epochSeconds = approxEarliestValidStartSecond();
        removeTransactionsOlderThan(epochSeconds);
        final var bucket =
                submittedTxns.get(transactionID.transactionValidStartOrThrow().seconds());
        return bucket != null && bucket.contains(transactionID);
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
        final var seconds = instantSource.millis() / 1000;
        if (seconds <= latestApproxSecond.get()) {
            return latestApproxValidSecond.get();
        }
        final var config = configProvider.getConfiguration().getConfigData(HederaConfig.class);
        final var validSeconds = seconds - config.transactionMaxValidDuration();
        latestApproxValidSecond.accumulateAndGet(validSeconds, Math::max);
        latestApproxSecond.accumulateAndGet(seconds, Math::max);
        return validSeconds;
    }

    /**
     * Removes all expired {@link TransactionID}s from the cache. Safe to call concurrently: the pruning threshold
     * is advanced atomically so that only threads which actually observe new work to do will scan for expired
     * buckets, and eviction removes whole per-second buckets rather than individual elements.
     *
     * @param earliestEpochSecond The earliest epoch second that should be kept in the cache.
     */
    private void removeTransactionsOlderThan(final long earliestEpochSecond) {
        final var prior = latestPruneEpochSecond.getAndUpdate(current -> Math.max(current, earliestEpochSecond));
        if (earliestEpochSecond <= prior) {
            return;
        }
        submittedTxns.keySet().removeIf(second -> second < earliestEpochSecond);
    }
}
