// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static com.swirlds.benchmark.BenchmarkKeyUtils.longToKey;
import static com.swirlds.benchmark.Utils.RUN_DELIMITER;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.virtualmap.VirtualMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.AbstractTask;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;
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
        fixedKey1 = longToKey(FIXED_KEY_ID1);
        if (virtualMap.get(fixedKey1, BenchmarkValueCodec.INSTANCE) == null) {
            virtualMap.put(fixedKey1, new BenchmarkValue(0), BenchmarkValueCodec.INSTANCE);
        }
        fixedKey2 = longToKey(FIXED_KEY_ID2);
        if (virtualMap.get(fixedKey2, BenchmarkValueCodec.INSTANCE) == null) {
            virtualMap.put(fixedKey2, new BenchmarkValue(0), BenchmarkValueCodec.INSTANCE);
        }
    }

    private void generateKeySet(long[] keySet) {
        for (int i = 0; i < keySet.length; ++i) {
            long keyId = Utils.randomLong(maxKey);
            if ((keyId == FIXED_KEY_ID1) || (keyId == FIXED_KEY_ID2) || (((i % 2) == 1) && (keyId == keySet[i - 1]))) {
                continue;
            }
            keySet[i] = keyId;
        }
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
        setTestDir("transferSerial");

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
        final long[] keys = new long[numRecords * KEYS_PER_RECORD];
        for (int i = 1; i <= numFiles; ++i) {
            // Generate a new set of random keys
            generateKeySet(keys);

            // Update values in order
            for (int j = 0; j < numRecords; ++j) {
                long keyId1 = keys[j * KEYS_PER_RECORD];
                long keyId2 = keys[j * KEYS_PER_RECORD + 1];
                Bytes key1 = longToKey(keyId1);
                Bytes key2 = longToKey(keyId2);
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
                    map[Math.toIntExact(keyId1)] += amount;
                    map[Math.toIntExact(keyId2)] -= amount;
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
        final VirtualMap finalMap = flushAndOptionallySaveMap(virtualMap);

        verifyMap(map, finalMap);

        finalMap.release();
        finalMap.getDataSource().close();
    }

    @Benchmark
    public void transferPrefetch() throws Exception {
        setTestDir("transferPrefetch");

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
        final long[] keys = new long[numRecords * KEYS_PER_RECORD];
        for (int i = 1; i <= numFiles; ++i) {
            // Generate a new set of random keys
            generateKeySet(keys);

            // Warm keys in parallel asynchronously
            final VirtualMap currentMap = virtualMap;
            for (int j = 0; j < keys.length; j += KEYS_PER_RECORD) {
                final int key = j;
                prefetchPool.execute(() -> {
                    try {
                        currentMap.warm(longToKey(keys[key]));
                        currentMap.warm(longToKey(keys[key + 1]));
                    } catch (final Exception e) {
                        logger.error("Warmup exception", e);
                    }
                });
            }

            // Update values in order
            for (int j = 0; j < numRecords; ++j) {
                long keyId1 = keys[j * KEYS_PER_RECORD];
                long keyId2 = keys[j * KEYS_PER_RECORD + 1];
                Bytes key1 = longToKey(keyId1);
                Bytes key2 = longToKey(keyId2);
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
                    map[Math.toIntExact(keyId1)] += amount;
                    map[Math.toIntExact(keyId2)] -= amount;
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
        prefetchPool.close();

        // Ensure the map is done with hashing/merging/flushing
        final VirtualMap finalMap = flushAndOptionallySaveMap(virtualMap);

        verifyMap(map, finalMap);

        finalMap.release();
        finalMap.getDataSource().close();
    }

    static class WarmupTask extends AbstractTask {

        VirtualMap currentMap;
        long key1, key2;
        TransferTask out;

        WarmupTask(ForkJoinPool pool, VirtualMap currentMap, long key1, long key2, TransferTask out) {
            super(pool, 1);
            this.currentMap = currentMap;
            this.key1 = key1;
            this.key2 = key2;
            this.out = out;
        }

        @Override
        protected boolean onExecute() {
            Bytes keyBytes1 = longToKey(key1);
            currentMap.warm(keyBytes1);
            Bytes keyBytes2 = longToKey(key2);
            currentMap.warm(keyBytes2);
            out.send(keyBytes1, keyBytes2);
            return true;
        }

        @Override
        protected void onException(final Throwable t) {
            logger.error("Error occurred while executing task", t);
        }
    }

    class TransferTask extends AbstractTask {

        VirtualMap currentMap;
        Bytes sender;
        Bytes receiver;
        long amount;
        TransferTask next;

        TransferTask(ForkJoinPool pool, VirtualMap currentMap) {
            super(pool, 3);
            this.currentMap = currentMap;
            this.amount = Utils.randomLong(MAX_AMOUNT);
        }

        void update(Bytes key, long amount) {
            BenchmarkValue value = currentMap.get(key, BenchmarkValueCodec.INSTANCE);
            if (value == null) value = new BenchmarkValue(0);
            value = value.copyBuilder().update(l -> l + amount).build();
            currentMap.put(key, value, BenchmarkValueCodec.INSTANCE);
        }

        @Override
        protected boolean onExecute() {
            update(sender, -amount);
            update(receiver, amount);

            // Model fees
            update(fixedKey1, 1);
            update(fixedKey2, 1);

            next.send();
            return true;
        }

        @Override
        protected void onException(final Throwable t) {
            logger.error("Error occurred while executing task", t);
        }

        void send(TransferTask next) {
            this.next = next;
            send();
        }

        void send(Bytes key1, Bytes key2) {
            sender = key1;
            receiver = key2;
            send();
        }
    }

    /**
     * Emulates crypto transfer.
     * Fetches a batch of "accounts" in parallel, updates the "accounts" in order by transferring
     * a random amount from one to another.
     */
    @Benchmark
    public void transferParallel() throws Exception {
        setTestDir("transferParallel");

        logger.info(RUN_DELIMITER);

        if (getBenchmarkConfig().enableSnapshots()) {
            enableSnapshots();
        }

        final long[] map = new long[verify ? maxKey : 0];
        VirtualMap virtualMap = createMap(map);

        final ForkJoinPool pool = new ForkJoinPool(numThreads);

        tps = BenchmarkMetrics.registerTPS();

        initializeFixedAccounts(virtualMap);

        long startTime = System.currentTimeMillis();
        long prevTime = startTime;
        final long[] keys = new long[numRecords * KEYS_PER_RECORD];
        for (int i = 1; i <= numFiles; ++i) {
            // Generate a new set of random keys
            generateKeySet(keys);

            TransferTask prevTask = null;
            TransferTask currentTask = new TransferTask(pool, virtualMap);
            // This is the very first task in a daisy chain of sequential TransferTasks,
            // emulate its resolved dependency from the non-existent previous task
            currentTask.send();

            for (int j = 0; j < numRecords; ++j) {
                long keyId1 = keys[j * KEYS_PER_RECORD];
                long keyId2 = keys[j * KEYS_PER_RECORD + 1];

                if (verify) {
                    map[Math.toIntExact(keyId1)] -= currentTask.amount;
                    map[Math.toIntExact(keyId2)] += currentTask.amount;
                    map[FIXED_KEY_ID1] += 1;
                    map[FIXED_KEY_ID2] += 1;
                }

                new WarmupTask(pool, virtualMap, keyId1, keyId2, currentTask).send();
                TransferTask nextTask = new TransferTask(pool, virtualMap);
                currentTask.send(nextTask);
                prevTask = currentTask;
                currentTask = nextTask;
            }
            // currentTask has no associated TransferTask, can be silently dropped
            prevTask.join();

            virtualMap = copyMap(virtualMap);

            // Report TPS
            final long curTime = System.currentTimeMillis();
            updateTPS(i, curTime - prevTime);
            prevTime = curTime;
        }
        totalTPS(System.currentTimeMillis() - startTime);
        pool.close();

        // Ensure the map is done with hashing/merging/flushing
        final VirtualMap finalMap = flushAndOptionallySaveMap(virtualMap);

        verifyMap(map, finalMap);

        finalMap.release();
        finalMap.getDataSource().close();
    }

    public static void main(String[] args) throws Exception {
        final CryptoBench bench = new CryptoBench();
        bench.setupTrial();
        bench.setupInvocation();
        bench.transferPrefetch();
        bench.tearDownInvocation();
        bench.tearDownTrial();
    }
}
