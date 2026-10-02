// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.rehash;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.VirtualMapStatistics;
import com.swirlds.virtualmap.internal.hash.DataSourceHashChunkPreloader;
import com.swirlds.virtualmap.internal.hash.FullLeafRehashHashListener;
import com.swirlds.virtualmap.internal.hash.VirtualHasher;
import com.swirlds.virtualmap.internal.reconnect.ConcurrentBlockingIterator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeoutException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;

/// A [FullRehasher] based on [VirtualHasher]. This is a baseline copy of the logic in
/// `VirtualMap.fullLeafRehashIfNecessary()`, used to compare it with other full rehash
/// implementations. The logic is intentionally kept as is, only these adaptations are made:
///
/// - the rehash is always performed, there is no check whether it's necessary
/// - hash chunks are preloaded with [DataSourceHashChunkPreloader] rather than the map cache
/// - a new [VirtualMapStatistics] instance is used to record flushes
/// - hashing runs in the provided fork-join pool rather than the virtual hasher pool
/// - the root hash is returned rather than set to the map
public final class VirtualHasherFullRehasher implements FullRehasher {

    private static final Logger logger = LogManager.getLogger(VirtualHasherFullRehasher.class);

    // Same as VirtualMap.MAX_REHASHING_BUFFER_SIZE
    private static final int MAX_REHASHING_BUFFER_SIZE = 10_000_000;

    private final ForkJoinPool pool;

    /// Creates a new rehasher.
    ///
    /// @param pool the fork-join pool to run hashing tasks in
    public VirtualHasherFullRehasher(@NonNull final ForkJoinPool pool) {
        this.pool = requireNonNull(pool);
    }

    @Override
    @Nullable
    @SuppressWarnings("rawtypes")
    public Hash rehash(@NonNull final VirtualMap map) {
        final VirtualDataSource dataSource = map.getDataSource();
        final VirtualMapConfig virtualMapConfig = map.getVirtualMapConfig();

        // getting a range that is relevant for the data source
        final long firstLeafPath = dataSource.getFirstLeafPath();
        final long lastLeafPath = dataSource.getLastLeafPath();

        if (firstLeafPath < 0 || lastLeafPath < 0) {
            logger.info(STARTUP.getMarker(), "VirtualMap is empty, skipping full rehash.");
            return null;
        }

        logger.info(STARTUP.getMarker(), "Doing full rehash for the path range: {} - {}", firstLeafPath, lastLeafPath);
        final FullLeafRehashHashListener hashListener = new FullLeafRehashHashListener(
                firstLeafPath,
                lastLeafPath,
                dataSource,
                new VirtualMapStatistics(VirtualMap.LABEL),
                // even though this listener has nothing to do with the reconnect, reconnect flush interval value
                // is appropriate to use here.
                virtualMapConfig.reconnectFlushInterval());

        final ConcurrentBlockingIterator<VirtualLeafBytes> rehashIterator =
                new ConcurrentBlockingIterator<>(MAX_REHASHING_BUFFER_SIZE);

        // VirtualHasher uses the pool of the current fork-join worker thread, if any, so hashing
        // tasks run in the provided pool rather than in the hasher's own pool
        final VirtualHasher hasher = new VirtualHasher(virtualMapConfig);
        final DataSourceHashChunkPreloader hashChunkPreloader = new DataSourceHashChunkPreloader(dataSource);
        try {
            // This background thread will be responsible for hashing the tree and sending the
            // data to the hash listener to flush.
            final CompletableFuture<Hash> fullRehashFuture = CompletableFuture.supplyAsync(
                            () -> hasher.hash(
                                    dataSource.getHashChunkHeight(),
                                    hashChunkPreloader,
                                    rehashIterator,
                                    firstLeafPath,
                                    lastLeafPath,
                                    hashListener),
                            pool)
                    .exceptionally(throwable -> {
                        // Shut down the iterator.
                        rehashIterator.close();
                        throw new RuntimeException(
                                "Exception occurred during full rehashing of the virtual map", throwable);
                    });

            final long onePercent = (lastLeafPath - firstLeafPath) / 100 + 1;
            final long start = System.currentTimeMillis();
            try {
                for (long i = firstLeafPath; i <= lastLeafPath; i++) {
                    try {
                        final VirtualLeafBytes<?> leafBytes = dataSource.loadLeafRecord(i);
                        assert leafBytes != null : "Leaf record should not be null";
                        try {
                            rehashIterator.supply(leafBytes);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(
                                    "Interrupted while waiting to supply a new leaf to the hashing iterator buffer", e);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    if (i % onePercent == 0) {
                        logger.info(
                                STARTUP.getMarker(), "Full rehash progress: {}%", (i - firstLeafPath) / onePercent + 1);
                    }
                }
            } finally {
                rehashIterator.close();
            }

            try {
                final long millisSpent = System.currentTimeMillis() - start;
                logger.info(
                        STARTUP.getMarker(), "It took {} seconds to feed all leaves to the hasher", millisSpent / 1000);
                final Hash rootHash =
                        fullRehashFuture.get(virtualMapConfig.fullRehashTimeoutMs() - millisSpent, MILLISECONDS);
                logger.info(
                        STARTUP.getMarker(),
                        "Full rehash took {} seconds",
                        (System.currentTimeMillis() - start) / 1000);
                return rootHash;
            } catch (ExecutionException e) {
                final var message = "Failed to get hash during full rehashing";
                throw new RuntimeException(message, e.getCause() != null ? e.getCause() : e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                final var message = "Interrupted while full rehashing";
                throw new RuntimeException(message, e);
            } catch (TimeoutException e) {
                final var message = "Wasn't able to finish full rehashing in time";
                throw new RuntimeException(message, e);
            }
        } finally {
            hasher.shutdown();
        }
    }
}
