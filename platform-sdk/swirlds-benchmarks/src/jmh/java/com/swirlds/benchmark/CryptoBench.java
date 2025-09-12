// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.virtualmap.VirtualMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@State(Scope.Thread)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
public class CryptoBench extends VirtualMapBench {

    private static final Logger logger = LogManager.getLogger(CryptoBench.class);

    private static final int MAX_AMOUNT = 1000;
    private static final int MILLISECONDS = 1000;
    private static final int EMA_FACTOR = 100;

    /* Number of random keys updated in one simulated transaction */
    private static final int KEYS_PER_RECORD = 2;

    /* Fixed keys to model paying fees */
    private static final int FIXED_KEY_ID1 = 0;
    private static final int FIXED_KEY_ID2 = 1;
    private Bytes fixedKey1;
    private Bytes fixedKey2;

    @Override
    String benchmarkName() {
        return "CryptoBench";
    }

    private void initializeFixedAccounts(VirtualMap virtualMap) {
        fixedKey1 = BenchmarkKey.longToKey(FIXED_KEY_ID1);
        if (virtualMap.get(fixedKey1, BenchmarkValueCodec.INSTANCE) == null) {
            virtualMap.put(fixedKey1, new BenchmarkValue(0), BenchmarkValueCodec.INSTANCE);
        }
        fixedKey2 = BenchmarkKey.longToKey(FIXED_KEY_ID2);
        if (virtualMap.get(fixedKey2, BenchmarkValueCodec.INSTANCE) == null) {
            virtualMap.put(fixedKey2, new BenchmarkValue(0), BenchmarkValueCodec.INSTANCE);
        }
    }

    private Integer[] generateKeySet() {
        int numKeys = numRecords * KEYS_PER_RECORD;
        HashSet<Integer> set = new HashSet<>(numKeys);
        while (set.size() < numKeys) {
            int keyId = Utils.randomInt(maxKey);
            if (keyId != FIXED_KEY_ID1 && keyId != FIXED_KEY_ID2) {
                set.add(keyId);
            }
        }
        return set.toArray(new Integer[0]);
    }

    /* Exponential moving average */
    private long ema;
    /* Platform metric for TPS */
    private LongGauge tps;

    private long average(long time) {
        return (long) numRecords * MILLISECONDS / Math.max(time, 1);
    }

    private void updateTPS(int iteration, long delta) {
        // EMA is a simple average while iteration <= EMA_FACTOR
        final int weight = Math.min(iteration, EMA_FACTOR);
        ema = iteration == 1 ? delta : (ema * (weight - 1) + delta) / weight;
        logger.info(
                "{} transactions, TPS (EMA): {}, TPS (current): {}",
                (long) numRecords * iteration,
                average(ema),
                average(delta));
        tps.set(average(delta));
    }

    private void totalTPS(long totalTime) {
        long totalTxns = (long) numRecords * numFiles;
        logger.info(
                "Total transactions: {}, time: {} sec, TPS: {}",
                totalTxns,
                totalTime / MILLISECONDS,
                totalTxns * MILLISECONDS / Math.max(totalTime, 1));
    }

    /**
     * Emulates crypto transfer.
     * Reads a batch of "account" pairs and updates them by transferring a random amount from one to another.
     * Single-threaded.
     */
    @Benchmark
    public void transferSerial() throws Exception {
        beforeTest("transferSerial");

        logger.info(RUN_DELIMITER);

        if (getBenchmarkConfig().enableSnapshots()) {
            enableSnapshots();
        }

        final long[] map = new long[verify ? maxKey : 0];
        VirtualMap virtualMap = createMap(map);

        tps = BenchmarkMetrics.registerTPS();
        initializeFixedAccounts(virtualMap);

        long startTime = System.currentTimeMillis();
        long prevTime = startTime;
        for (int i = 1; i <= numFiles; ++i) {
            // Generate a new set of unique random keys
            final Integer[] keys = generateKeySet();

            // Update values in order
            for (int j = 0; j < numRecords; ++j) {
                int keyId1 = keys[j * KEYS_PER_RECORD];
                int keyId2 = keys[j * KEYS_PER_RECORD + 1];
                Bytes key1 = BenchmarkKey.longToKey(keyId1);
                Bytes key2 = BenchmarkKey.longToKey(keyId2);
                BenchmarkValue value1 = virtualMap.get(key1, BenchmarkValueCodec.INSTANCE);
                BenchmarkValue value2 = virtualMap.get(key2, BenchmarkValueCodec.INSTANCE);

                long amount = Utils.randomLong(MAX_AMOUNT);
                if (value1 == null) {
                    value1 = new BenchmarkValue(amount);
                } else {
                    value1 = value1.copyBuilder().update(l -> l + amount).build();
                }
                virtualMap.put(key1, value1, BenchmarkValueCodec.INSTANCE);

                if (value2 == null) {
                    value2 = new BenchmarkValue(-amount);
                } else {
                    value2 = value2.copyBuilder().update(l -> l - amount).build();
                }
                virtualMap.put(key2, value2, BenchmarkValueCodec.INSTANCE);

                // Model fees
                value1 = virtualMap.get(fixedKey1, BenchmarkValueCodec.INSTANCE);
                assert value1 != null;
                value1 = value1.copyBuilder().update(l -> l + 1).build();
                virtualMap.put(fixedKey1, value1, BenchmarkValueCodec.INSTANCE);
                value2 = virtualMap.get(fixedKey2, BenchmarkValueCodec.INSTANCE);
                assert value2 != null;
                value2 = value2.copyBuilder().update(l -> l + 1).build();
                virtualMap.put(fixedKey2, value2, BenchmarkValueCodec.INSTANCE);

                if (verify) {
                    map[keyId1] += amount;
                    map[keyId2] -= amount;
                    map[FIXED_KEY_ID1] += 1;
                    map[FIXED_KEY_ID2] += 1;
                }
            }

            virtualMap = copyMap(virtualMap);

            // Report TPS
            final long curTime = System.currentTimeMillis();
            updateTPS(i, curTime - prevTime);
            prevTime = curTime;
        }
        totalTPS(System.currentTimeMillis() - startTime);

        // Ensure the map is done with hashing/merging/flushing
        final VirtualMap finalMap = flushMap(virtualMap);

        verifyMap(map, finalMap);

        afterTest(() -> {
            finalMap.release();
            finalMap.getDataSource().close();
        });
    }

    @Benchmark
    public void transferPrefetch() throws Exception {
        beforeTest("transferPrefetch");

        logger.info(RUN_DELIMITER);

        if (getBenchmarkConfig().enableSnapshots()) {
            enableSnapshots();
        }

        final long[] map = new long[verify ? maxKey : 0];
        VirtualMap virtualMap = createMap(map);

        // Use a custom queue and executor for warmups. It may happen that some warmup jobs
        // aren't complete by the end of the round, so they will start piling up. To fix it,
        // clear the queue in the end of each round
        final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        final ExecutorService prefetchPool = new ThreadPoolExecutor(
                numThreads,
                numThreads,
                1,
                TimeUnit.SECONDS,
                queue,
                new ThreadConfiguration(getStaticThreadManager())
                        .setComponent("benchmark")
                        .setThreadName("prefetch")
                        .setExceptionHandler((t, ex) -> logger.error("Uncaught exception during prefetching", ex))
                        .buildFactory());

        tps = BenchmarkMetrics.registerTPS();

        initializeFixedAccounts(virtualMap);

        long startTime = System.currentTimeMillis();
        long prevTime = startTime;
        for (int i = 1; i <= numFiles; ++i) {
            // Generate a new set of unique random keys
            final Integer[] keys = generateKeySet();

            // Warm keys in parallel asynchronously
            final VirtualMap currentMap = virtualMap;
            for (int j = 0; j < keys.length; j += KEYS_PER_RECORD) {
                final int key = j;
                prefetchPool.execute(() -> {
                    try {
                        currentMap.warm(BenchmarkKey.longToKey(keys[key]));
                        currentMap.warm(BenchmarkKey.longToKey(keys[key + 1]));
                    } catch (final Exception e) {
                        logger.error("Warmup exception", e);
                    }
                });
            }

            // Update values in order
            for (int j = 0; j < numRecords; ++j) {
                int keyId1 = keys[j * KEYS_PER_RECORD];
                int keyId2 = keys[j * KEYS_PER_RECORD + 1];
                Bytes key1 = BenchmarkKey.longToKey(keyId1);
                Bytes key2 = BenchmarkKey.longToKey(keyId2);
                BenchmarkValue value1 = virtualMap.get(key1, BenchmarkValueCodec.INSTANCE);
                BenchmarkValue value2 = virtualMap.get(key2, BenchmarkValueCodec.INSTANCE);

                long amount = Utils.randomLong(MAX_AMOUNT);
                if (value1 == null) {
                    value1 = new BenchmarkValue(amount);
                } else {
                    value1 = value1.copyBuilder().update(l -> l + amount).build();
                }
                virtualMap.put(key1, value1, BenchmarkValueCodec.INSTANCE);

                if (value2 == null) {
                    value2 = new BenchmarkValue(-amount);
                } else {
                    value2 = value2.copyBuilder().update(l -> l - amount).build();
                }
                virtualMap.put(key2, value2, BenchmarkValueCodec.INSTANCE);

                // Model fees
                value1 = virtualMap.get(fixedKey1, BenchmarkValueCodec.INSTANCE);
                assert value1 != null;
                value1 = value1.copyBuilder().update(l -> l + 1).build();
                virtualMap.put(fixedKey1, value1, BenchmarkValueCodec.INSTANCE);
                value2 = virtualMap.get(fixedKey2, BenchmarkValueCodec.INSTANCE);
                assert value2 != null;
                value2 = value2.copyBuilder().update(l -> l + 1).build();
                virtualMap.put(fixedKey2, value2, BenchmarkValueCodec.INSTANCE);

                if (verify) {
                    map[keyId1] += amount;
                    map[keyId2] -= amount;
                    map[FIXED_KEY_ID1] += 1;
                    map[FIXED_KEY_ID2] += 1;
                }
            }

            queue.clear();

            virtualMap = copyMap(virtualMap);

            // Report TPS
            final long curTime = System.currentTimeMillis();
            updateTPS(i, curTime - prevTime);
            prevTime = curTime;
        }
        totalTPS(System.currentTimeMillis() - startTime);

        // Ensure the map is done with hashing/merging/flushing
        final VirtualMap finalMap = flushMap(virtualMap);

        verifyMap(map, finalMap);

        afterTest(() -> {
            finalMap.release();
            finalMap.getDataSource().close();
        });
    }

    /**
     * Emulates crypto transfer.
     * Fetches a batch of "accounts" in parallel, updates the "accounts" in order by transferring
     * a random amount from one to another.
     */
    @Benchmark
    public void transferParallel() throws Exception {
        beforeTest("transferParallel");

        logger.info(RUN_DELIMITER);

        if (getBenchmarkConfig().enableSnapshots()) {
            enableSnapshots();
        }

        final long[] map = new long[verify ? maxKey : 0];
        VirtualMap virtualMap = createMap(map);

        final ExecutorService prefetchPool =
                Executors.newCachedThreadPool(new ThreadConfiguration(getStaticThreadManager())
                        .setComponent("benchmark")
                        .setThreadName("prefetch")
                        .setExceptionHandler((t, ex) -> logger.error("Uncaught exception during prefetching", ex))
                        .buildFactory());

        tps = BenchmarkMetrics.registerTPS();

        final int QUEUE_CAPACITY = 1000;
        final List<BlockingQueue<Optional<BenchmarkValue>>> queues = new ArrayList<>(numThreads);
        for (int i = 0; i < numThreads; ++i) {
            queues.add(new LinkedBlockingQueue<>(QUEUE_CAPACITY));
        }

        final List<Deque<Optional<BenchmarkValue>>> buffers = new ArrayList<>(numThreads);
        for (int i = 0; i < numThreads; ++i) {
            buffers.add(new ArrayDeque<>(QUEUE_CAPACITY));
        }

        initializeFixedAccounts(virtualMap);

        long startTime = System.currentTimeMillis();
        long prevTime = startTime;
        for (int i = 1; i <= numFiles; ++i) {
            // Generate a new set of unique random keys
            final Integer[] keys = generateKeySet();

            // Read keys in parallel asynchronously
            final VirtualMap currentMap = virtualMap;
            for (int thread = 0; thread < numThreads; ++thread) {
                final int idx = thread;
                prefetchPool.execute(() -> {
                    try {
                        final BlockingQueue<Optional<BenchmarkValue>> queue = queues.get(idx);
                        for (int j = idx * KEYS_PER_RECORD; j < keys.length; j += numThreads * KEYS_PER_RECORD) {
                            queue.put(Optional.ofNullable(
                                    currentMap.get(BenchmarkKey.longToKey(keys[j]), BenchmarkValueCodec.INSTANCE)));
                            queue.put(Optional.ofNullable(
                                    currentMap.get(BenchmarkKey.longToKey(keys[j + 1]), BenchmarkValueCodec.INSTANCE)));
                        }
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                        Thread.currentThread().interrupt();
                    }
                });
            }

            // Update values in order
            for (int j = 0; j < numRecords; ++j) {
                final int idx = j % queues.size();
                Deque<Optional<BenchmarkValue>> buffer = buffers.get(idx);
                while (buffer.size() < KEYS_PER_RECORD) {
                    queues.get(idx).drainTo(buffer, QUEUE_CAPACITY);
                }
                BenchmarkValue value1 = buffer.removeFirst().orElse(new BenchmarkValue(0));
                BenchmarkValue value2 = buffer.removeFirst().orElse(new BenchmarkValue(0));
                long amount = Utils.randomLong(MAX_AMOUNT);
                value1 = value1.copyBuilder().update(l -> l + amount).build();
                value2 = value2.copyBuilder().update(l -> l - amount).build();
                int keyId1 = keys[j * KEYS_PER_RECORD];
                int keyId2 = keys[j * KEYS_PER_RECORD + 1];
                currentMap.put(BenchmarkKey.longToKey(keyId1), value1, BenchmarkValueCodec.INSTANCE);
                currentMap.put(BenchmarkKey.longToKey(keyId2), value2, BenchmarkValueCodec.INSTANCE);

                // Model fees
                value1 = virtualMap.get(fixedKey1, BenchmarkValueCodec.INSTANCE);
                assert value1 != null;
                value1 = value1.copyBuilder().update(l -> l + 1).build();
                virtualMap.put(fixedKey1, value1, BenchmarkValueCodec.INSTANCE);
                value2 = virtualMap.get(fixedKey2, BenchmarkValueCodec.INSTANCE);
                assert value2 != null;
                value2 = value2.copyBuilder().update(l -> l + 1).build();
                virtualMap.put(fixedKey2, value2, BenchmarkValueCodec.INSTANCE);

                if (verify) {
                    map[keyId1] += amount;
                    map[keyId2] -= amount;
                    map[FIXED_KEY_ID1] += 1;
                    map[FIXED_KEY_ID2] += 1;
                }
            }
            totalTPS(System.currentTimeMillis() - startTime);

            virtualMap = copyMap(virtualMap);

            // Report TPS
            final long curTime = System.currentTimeMillis();
            updateTPS(i, curTime - prevTime);
            prevTime = curTime;
        }

        // Ensure the map is done with hashing/merging/flushing
        final VirtualMap finalMap = flushMap(virtualMap);

        verifyMap(map, finalMap);

        afterTest(() -> {
            finalMap.release();
            finalMap.getDataSource().close();
        });
    }

    public static void main(String[] args) throws Exception {
        final CryptoBench bench = new CryptoBench();
        bench.setup();
        bench.beforeTest();
        bench.transferPrefetch();
        bench.afterTest();
        bench.destroy();
    }
}
