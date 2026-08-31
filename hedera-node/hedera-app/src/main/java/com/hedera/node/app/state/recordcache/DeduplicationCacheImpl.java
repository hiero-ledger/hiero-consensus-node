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
    /**
     * Live transaction IDs this node has submitted or seen, for O(1) containment.
     * <p>
     * Note that an ID with scheduled set is different from the same ID without scheduled set.
     * In fact, an ID with scheduled set will always match the ID of the ScheduleCreate transaction that created
     * the schedule, except scheduled is set.
     */
    private final ConcurrentHashMap<TransactionID, Boolean> submittedTxns = new ConcurrentHashMap<>();
    /** IDs grouped by valid-start second so expiry can drop a bucket instead of walking the whole set. */
    private final ConcurrentHashMap<Long, Set<TransactionID>> byValidStartSecond = new ConcurrentHashMap<>();

    /** Used for looking up the max transaction duration window. */
    private final ConfigProvider configProvider;
    /**
     * Used to estimate the earliest valid start timestamp that is still within the max transaction duration
     * window that the ingest workflow will be using to screen transactions.
     */
    private final InstantSource instantSource;

    private final AtomicLong cachedNowSecond = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong cachedEarliestSecond = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastPruneEarliestSecond = new AtomicLong(Long.MIN_VALUE);

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
        putIfAbsent(transactionID);
    }

    /** {@inheritDoc} */
    @Override
    public boolean putIfAbsent(@NonNull final TransactionID transactionID) {
        requireNonNull(transactionID);
        final var earliest = approxEarliestValidStartSecond();
        maybePrune(earliest);
        final var validStart = transactionID.transactionValidStartOrThrow().seconds();
        if (validStart < earliest) {
            return false;
        }
        if (submittedTxns.putIfAbsent(transactionID, Boolean.TRUE) != null) {
            return false;
        }
        byValidStartSecond
                .computeIfAbsent(validStart, second -> ConcurrentHashMap.newKeySet())
                .add(transactionID);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void remove(@NonNull final TransactionID transactionID) {
        requireNonNull(transactionID);
        if (submittedTxns.remove(transactionID) == null) {
            return;
        }
        final var bucket = byValidStartSecond.get(
                transactionID.transactionValidStartOrThrow().seconds());
        if (bucket != null) {
            bucket.remove(transactionID);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean contains(@NonNull final TransactionID transactionID) {
        requireNonNull(transactionID);
        final var earliest = approxEarliestValidStartSecond();
        maybePrune(earliest);
        if (transactionID.transactionValidStartOrThrow().seconds() < earliest) {
            return false;
        }
        return submittedTxns.containsKey(transactionID);
    }

    /** {@inheritDoc} */
    @Override
    public void clear() {
        submittedTxns.clear();
        byValidStartSecond.clear();
        cachedNowSecond.set(Long.MIN_VALUE);
        cachedEarliestSecond.set(Long.MIN_VALUE);
        lastPruneEarliestSecond.set(Long.MIN_VALUE);
    }

    /**
     * Gets the earliest valid start second that is still within the max transaction duration window based on
     * wall-clock time. Refreshes at most once per wall-clock second.
     */
    private long approxEarliestValidStartSecond() {
        final var nowSecond = instantSource.instant().getEpochSecond();
        if (nowSecond == cachedNowSecond.get()) {
            return cachedEarliestSecond.get();
        }
        final var config = configProvider.getConfiguration().getConfigData(HederaConfig.class);
        final var earliest = nowSecond - config.transactionMaxValidDuration();
        cachedNowSecond.set(nowSecond);
        cachedEarliestSecond.set(earliest);
        return earliest;
    }

    /**
     * Drops expired second-buckets. Only one thread performs the walk per new earliest second.
     */
    private void maybePrune(final long earliestEpochSecond) {
        final var last = lastPruneEarliestSecond.get();
        if (earliestEpochSecond <= last) {
            return;
        }
        if (!lastPruneEarliestSecond.compareAndSet(last, earliestEpochSecond)) {
            return;
        }
        byValidStartSecond.forEach((second, ids) -> {
            if (second < earliestEpochSecond && byValidStartSecond.remove(second, ids)) {
                for (final var id : ids) {
                    submittedTxns.remove(id);
                }
            }
        });
    }
}
