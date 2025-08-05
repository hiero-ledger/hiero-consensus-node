// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.openjdk.jmh.annotations.TearDown;

public abstract class VirtualMapBaseBench extends BaseBench {

    protected static final Logger logger = LogManager.getLogger(VirtualMapBench.class);

    protected static final String LABEL = "vm";
    protected static final String SAVED = "saved";
    protected static final String SERDE_SUFFIX = ".serde";
    protected static final String SNAPSHOT = "snapshot";
    protected static final long SNAPSHOT_DELAY = 20_000;

    /* This map may be pre-created on demand and reused between benchmarks/iterations */
    protected VirtualMap virtualMapP;

    private int dbIndex = 0;

    /**
     * Use a different MerkleDb instance for every test run. With a single instance,
     * even if its folder is deleted before each run, there could be background
     * threads (virtual pipeline thread, data source compaction thread, etc.) from
     * the previous run that re-create the folder, and it results in a total mess.
     * <p>
     * This method must be called AFTER calling beforeTest(String), or at least
     * after setTestDir(String) because it needs the test directory path.
     */
    protected void updateMerkleDbPath() {
        final Path merkleDbPath = getTestDir().resolve("merkledb" + dbIndex++);
        MerkleDb.setDefaultPath(merkleDbPath);
    }

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

    @TearDown
    public void destroyLocal() {
        releaseAndCloseMap(virtualMapP);
        virtualMapP = null;
        hasher.shutdown();
    }

    protected VirtualMap createMap() {
        return createMap(null);
    }

    protected VirtualMap createEmptyMap(String label) {
        final MerkleDbConfig merkleDbConfig = getConfig(MerkleDbConfig.class);
        // Start with a relatively low virtual map size hint and let MerkleDb resize its HDHM
        final MerkleDbTableConfig tableConfig = new MerkleDbTableConfig(
                (short) 1, DigestType.SHA_384, maxKey / 2, merkleDbConfig.hashesRamToDiskThreshold());
        MerkleDbDataSourceBuilder dataSourceBuilder = new MerkleDbDataSourceBuilder(tableConfig, configuration);
        return new VirtualMap(label, dataSourceBuilder, configuration);
    }

    protected VirtualMap createMap(final long[] map) {
        final long start = System.currentTimeMillis();
        VirtualMap virtualMap = restoreMap(LABEL);
        if (virtualMap != null) {
            if (verify && map != null) {
                final int parallelism = ForkJoinPool.getCommonPoolParallelism();
                final AtomicLong numKeys = new AtomicLong();
                final VirtualMap srcMap = virtualMap;
                IntStream.range(0, parallelism).parallel().forEach(idx -> {
                    long count = 0L;
                    for (int i = idx; i < map.length; i += parallelism) {
                        final BenchmarkValue value =
                                srcMap.get(BenchmarkKey.longToKey(i), BenchmarkValueCodec.INSTANCE);
                        if (value != null) {
                            map[i] = value.toLong();
                            ++count;
                        }
                    }
                    numKeys.addAndGet(count);
                });
                logger.info("Loaded {} keys in {} ms", numKeys, System.currentTimeMillis() - start);
            } else {
                logger.info("Loaded map in {} ms", System.currentTimeMillis() - start);
            }
        } else {
            virtualMap = createEmptyMap(LABEL);
        }
        BenchmarkMetrics.register(virtualMap::registerMetrics);
        return virtualMap;
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
                            try (final SerializableDataOutputStream out = new SerializableDataOutputStream(
                                    Files.newOutputStream(savedDir.resolve(LABEL + SERDE_SUFFIX)))) {
                                virtualMap.serialize(out, savedDir);
                            }
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
     * Ensure map is fully flushed to disk. Save map to disk if saving data is specified.
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

        if (getBenchmarkConfig().saveDataDirectory()) {
            curMap = saveMap(curMap);
        }

        return curMap;
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
                BenchmarkValue dataItem = virtualMap.get(BenchmarkKey.longToKey(idx), BenchmarkValueCodec.INSTANCE);
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

    protected List<VirtualMap> saveMaps(final List<VirtualMap> virtualMaps) {
        try {
            Path savedDir;
            for (int i = 0; ; i++) {
                savedDir = getBenchDir().resolve(SAVED + i);
                if (!Files.exists(savedDir)) {
                    break;
                }
            }
            Files.createDirectories(savedDir);

            final Path finalSavedDir = savedDir;

            return virtualMaps.stream()
                    .map(virtualMap -> {
                        final long start = System.currentTimeMillis();
                        final VirtualMapMetadata virtualMapMetadata = virtualMap.getMetadata();
                        final String label = virtualMapMetadata.getLabel();
                        final VirtualMap curMap = virtualMap.copy();

                        virtualMap.getHash();
                        try (final SerializableDataOutputStream out = new SerializableDataOutputStream(
                                Files.newOutputStream(finalSavedDir.resolve(label + SERDE_SUFFIX)))) {
                            virtualMap.serialize(out, finalSavedDir);
                        } catch (IOException ex) {
                            logger.error("Error saving VirtualMap " + label, ex);
                        }
                        logger.info(
                                "Saved map {} to {} in {} ms",
                                label,
                                finalSavedDir,
                                System.currentTimeMillis() - start);
                        return curMap;
                    })
                    .collect(Collectors.toList());
        } catch (IOException ex) {
            logger.error("Error saving VirtualMap", ex);
            throw new UncheckedIOException(ex);
        } finally {
            virtualMaps.forEach(VirtualMap::release);
        }
    }

    protected VirtualMap saveMap(final VirtualMap virtualMap) {
        final VirtualMap curMap = virtualMap.copy();
        try {
            final long start = System.currentTimeMillis();
            Path savedDir;
            for (int i = 0; ; i++) {
                savedDir = getBenchDir().resolve(SAVED + i).resolve(LABEL);
                if (!Files.exists(savedDir)) {
                    break;
                }
            }
            Files.createDirectories(savedDir);
            virtualMap.getHash();
            try (final SerializableDataOutputStream out =
                    new SerializableDataOutputStream(Files.newOutputStream(savedDir.resolve(LABEL + SERDE_SUFFIX)))) {
                virtualMap.serialize(out, savedDir);
            }
            logger.info("Saved map {} to {} in {} ms", LABEL, savedDir, System.currentTimeMillis() - start);
        } catch (IOException ex) {
            logger.error("Error saving VirtualMap", ex);
        } finally {
            virtualMap.release();
        }
        return curMap;
    }

    protected VirtualMap restoreMap(final String label) {
        Path savedDir = null;
        for (int i = 0; ; i++) {
            final Path nextSavedDir = getBenchDir().resolve(SAVED + i).resolve(label);
            if (!Files.exists(nextSavedDir.resolve(label + SERDE_SUFFIX))) {
                break;
            }
            savedDir = nextSavedDir;
        }
        VirtualMap virtualMap = null;
        if (savedDir != null) {
            try {
                logger.info("Restoring map {} from {}", label, savedDir);
                virtualMap = new VirtualMap(configuration);
                try (final SerializableDataInputStream in =
                        new SerializableDataInputStream(Files.newInputStream(savedDir.resolve(label + SERDE_SUFFIX)))) {
                    virtualMap.deserialize(in, savedDir, virtualMap.getVersion());
                }
                logger.info("Restored map {} from {}", label, savedDir);
            } catch (IOException ex) {
                logger.error("Error loading saved map: {}", ex.getMessage());
            }
        }
        return virtualMap;
    }
}
