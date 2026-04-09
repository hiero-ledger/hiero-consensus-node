// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static com.swirlds.benchmark.BenchmarkKeyUtils.longToKey;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.virtualmap.VirtualMap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;

public abstract class VirtualMapBaseBench extends BaseBench {

    protected static final Logger logger = LogManager.getLogger(VirtualMapBench.class);

    protected static final String SAVED = "saved";
    protected static final String SNAPSHOT = "snapshot";
    protected static final long SNAPSHOT_DELAY = 20_000;

    protected MerkleDbDataSourceBuilder dataSourceBuilder;

    /* This map may be pre-created on demand and reused between benchmarks/iterations */
    protected VirtualMap virtualMapP;

    /* Run snapshots periodically */
    private boolean doSnapshots;
    private final AtomicLong snapshotTime = new AtomicLong(0L);

    /* Asynchronous hasher */
    private final ExecutorService hasher =
            Executors.newSingleThreadExecutor(new ThreadConfiguration(getStaticThreadManager())
                    .setComponent("benchmark")
                    .setThreadName("hasher")
                    .setExceptionHandler((t, ex) -> logger.error("Uncaught exception during hashing", ex))
                    .buildFactory());

    protected void releaseAndCloseMap(final VirtualMap map) {
        if (map != null) {
            map.release();
            try {
                map.getDataSource().close();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onTrialSetup() {
        super.onTrialSetup();

        // Start with a relatively low virtual map size hint and let MerkleDb resize its HDHM
        dataSourceBuilder = new MerkleDbDataSourceBuilder(configuration, maxKey / 2);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onTrialTearDown() throws Exception {
        releaseAndCloseMap(virtualMapP);
        virtualMapP = null;
        hasher.shutdown();

        super.onTrialTearDown();
    }

    protected VirtualMap createEmptyMap() {
        return new VirtualMap(dataSourceBuilder, configuration);
    }

    protected VirtualMap createMap(final long[] map) {
        final long start = System.currentTimeMillis();
        VirtualMap virtualMap = restoreMap();
        if (virtualMap != null) {
            if (verify && map != null) {
                copyMapToArray(virtualMap, map);
                logger.info("Loaded {} keys in {} ms", map.length, System.currentTimeMillis() - start);
            } else {
                logger.info("Loaded map in {} ms", System.currentTimeMillis() - start);
            }
        } else {
            virtualMap = createEmptyMap();
        }
        BenchmarkMetrics.register(virtualMap::registerMetrics);
        return virtualMap;
    }

    /**
     * Copies values from the given {@link VirtualMap} into a {@code long[]} array,
     * where each index {@code i} maps to the key {@code longToKey(i)}.
     * This is used to build a verification reference for later comparison with {@link #verifyMap(long[], VirtualMap)}.
     *
     * @param srcMap the source virtual map to read from
     * @param map    the destination array; must be pre-allocated to the desired key range size
     */
    protected void copyMapToArray(final VirtualMap srcMap, final long[] map) {
        final int parallelism = ForkJoinPool.getCommonPoolParallelism();
        final AtomicLong numKeys = new AtomicLong();
        IntStream.range(0, parallelism).parallel().forEach(idx -> {
            long count = 0L;
            for (int i = idx; i < map.length; i += parallelism) {
                final BenchmarkValue value = srcMap.get(longToKey(i), BenchmarkValueCodec.INSTANCE);
                if (value != null) {
                    map[i] = value.toLong();
                    ++count;
                }
            }
            numKeys.addAndGet(count);
        });
        logger.info("Copied {} keys to verification array", numKeys.get());
    }

    private int snapshotIndex = 0;

    protected void enableSnapshots() {
        snapshotTime.set(System.currentTimeMillis() + SNAPSHOT_DELAY);
        doSnapshots = true;
    }

    protected VirtualMap copyMap(final VirtualMap virtualMap) {
        final VirtualMap newCopy = virtualMap.copy();
        hasher.execute(virtualMap::getHash);

        if (doSnapshots && System.currentTimeMillis() > snapshotTime.get()) {
            snapshotTime.set(Long.MAX_VALUE);
            new Thread(() -> {
                        try {
                            Path savedDir = getBenchDir().resolve(SNAPSHOT + snapshotIndex++);
                            if (!Files.exists(savedDir)) {
                                Files.createDirectory(savedDir);
                            }
                            virtualMap.getHash();
                            virtualMap.createSnapshot(savedDir);
                            virtualMap.release();
                            if (!getBenchmarkConfig().saveDataDirectory()) {
                                Utils.deleteRecursively(savedDir);
                            }

                            snapshotTime.set(System.currentTimeMillis() + SNAPSHOT_DELAY);
                        } catch (Exception ex) {
                            logger.error("Failed to take a snapshot", ex);
                        }
                    })
                    .start();
        } else {
            virtualMap.release();
        }

        return newCopy;
    }

    /*
     * Ensure map is fully flushed to disk.
     */
    protected VirtualMap flushMap(final VirtualMap virtualMap) {
        logger.info("Flushing map {}...", virtualMap.getLabel());
        final long start = System.currentTimeMillis();
        VirtualMap curMap = virtualMap;
        final VirtualMap oldCopy = curMap;
        curMap = curMap.copy();
        oldCopy.release();
        oldCopy.enableFlush();
        try {
            oldCopy.waitUntilFlushed();
        } catch (InterruptedException ex) {
            logger.warn("Interrupted", ex);
            Thread.currentThread().interrupt();
        }
        logger.info("Flushed map in {} ms", System.currentTimeMillis() - start);

        return curMap;
    }

    /**
     * Flushes the map to disk, and optionally saves a snapshot if configured.
     */
    protected VirtualMap flushAndOptionallySaveMap(final VirtualMap virtualMap) {
        VirtualMap flushed = flushMap(virtualMap);
        if (getBenchmarkConfig().saveDataDirectory()) {
            flushed = saveMap(flushed);
        }
        return flushed;
    }

    protected void verifyMap(long[] map, VirtualMap virtualMap) {
        if (!verify) {
            return;
        }

        long start = System.currentTimeMillis();
        final AtomicInteger index = new AtomicInteger(0);
        final AtomicInteger countGood = new AtomicInteger(0);
        final AtomicInteger countBad = new AtomicInteger(0);
        final AtomicInteger countMissing = new AtomicInteger(0);

        IntStream.range(0, 64).parallel().forEach(thread -> {
            int idx;
            while ((idx = index.getAndIncrement()) < map.length) {
                BenchmarkValue dataItem = virtualMap.get(longToKey(idx), BenchmarkValueCodec.INSTANCE);
                if (dataItem == null) {
                    if (map[idx] != 0L) {
                        countMissing.getAndIncrement();
                    }
                } else if (!dataItem.equals(new BenchmarkValue(map[idx]))) {
                    countBad.getAndIncrement();
                } else {
                    countGood.getAndIncrement();
                }
            }
        });
        if (countBad.get() != 0 || countMissing.get() != 0) {
            logger.error(
                    "FAIL verified {} keys, {} bad, {} missing in {} ms",
                    countGood.get(),
                    countBad.get(),
                    countMissing.get(),
                    System.currentTimeMillis() - start);
        } else {
            logger.info("PASS verified {} keys in {} ms", countGood.get(), System.currentTimeMillis() - start);
        }
    }

    protected VirtualMap saveMap(final VirtualMap virtualMap) {
        return saveMap(virtualMap, null);
    }

    /**
     * Saves a snapshot of the virtual map to disk using an auto-incremented directory scheme.
     *
     * @param virtualMap the map to save — will be released by this method
     * @param prefix     optional subdirectory prefix (e.g. "teacher"), or {@code null} for no prefix
     * @return a mutable copy of the original map
     */
    protected VirtualMap saveMap(final VirtualMap virtualMap, final String prefix) {
        final VirtualMap curMap = virtualMap.copy();
        try {
            final long start = System.currentTimeMillis();
            Path savedDir;
            for (int i = 0; ; i++) {
                savedDir = getBenchDir();
                if (prefix != null) {
                    savedDir = savedDir.resolve(prefix);
                }
                savedDir = savedDir.resolve(SAVED + i);
                if (!Files.exists(savedDir)) {
                    break;
                }
            }
            Files.createDirectories(savedDir);
            virtualMap.getHash();
            virtualMap.createSnapshot(savedDir);
            logger.info("Saved map to {} in {} ms", savedDir, System.currentTimeMillis() - start);
        } catch (IOException ex) {
            logger.error("Error saving VirtualMap", ex);
        } finally {
            virtualMap.release();
        }
        return curMap;
    }

    protected VirtualMap restoreMap() {
        return restoreMap(null);
    }

    /**
     * Restores the most recent snapshot of a virtual map from disk.
     *
     * @param prefix optional subdirectory prefix (e.g. "teacher"), or {@code null} for no prefix
     * @return the restored map, or {@code null} if no saved snapshot was found
     */
    protected VirtualMap restoreMap(final String prefix) {
        Path savedDir = null;
        for (int i = 0; ; i++) {
            Path nextSavedDir = getBenchDir();
            if (prefix != null) {
                nextSavedDir = nextSavedDir.resolve(prefix);
            }
            nextSavedDir = nextSavedDir.resolve(SAVED + i);
            if (!Files.isDirectory(nextSavedDir)) {
                break;
            }
            savedDir = nextSavedDir;
        }
        VirtualMap virtualMap = null;
        if (savedDir != null) {
            logger.info("Restoring map from {}", savedDir);
            virtualMap = VirtualMap.loadFromDirectory(savedDir, configuration, () -> dataSourceBuilder);
            logger.info("Restored map from {}", savedDir);
        }
        return virtualMap;
    }
}
