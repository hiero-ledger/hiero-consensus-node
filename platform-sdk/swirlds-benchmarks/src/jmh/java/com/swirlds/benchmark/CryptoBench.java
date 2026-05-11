// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static com.swirlds.benchmark.BenchmarkKeyUtils.longToKey;
import static com.swirlds.benchmark.Utils.RUN_DELIMITER;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.virtualmap.VirtualMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@State(Scope.Thread)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
public class CryptoBench extends VirtualMapEditBench {

    private static final Logger logger = LogManager.getLogger(CryptoBench.class);

    private static final int MAX_AMOUNT = 1000;
    private static final int NANOSECONDS = 1_000_000_000;
    private static final int EMA_FACTOR = 100;

    /* Number of random keys updated in one simulated transaction */
    private static final int KEYS_PER_RECORD = 2;

    /* Fixed keys to model paying fees */
    private static final int FIXED_KEY_ID1 = 0;
    private static final int FIXED_KEY_ID2 = 1;
    private Bytes fixedKey1;
    private Bytes fixedKey2;

    /* Exponential moving average */
    private long ema;
    /* Platform metric for TPS */
    private LongGauge tps;

    @Override
    String benchmarkName() {
        return "CryptoBench";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onInvocationSetup() {
        super.onInvocationSetup();

        tps = BenchmarkMetrics.registerTPS();
        initializeFixedAccounts(virtualMap);
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

    private long average(long time) {
        return (long) numRecords * NANOSECONDS / Math.max(time, 1);
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
        final long totalTxns = (long) numRecords * numFiles;
        final long seconds = totalTime / NANOSECONDS;
        final long tps = (long) ((double) totalTxns * NANOSECONDS / Math.max(totalTime, 1));
        logger.info("Total transactions: {}, time: {} sec, TPS: {}", totalTxns, seconds, tps);
    }

    /**
     * Emulates crypto transfer.
     * Reads a batch of "account" pairs and updates them by transferring a random amount from one to another.
     * Single-threaded.
     */
    @Benchmark
    public void transferSerial() {
        logger.info(RUN_DELIMITER);

        final long startTime = System.nanoTime();
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
                    verificationMap[Math.toIntExact(keyId1)] += amount;
                    verificationMap[Math.toIntExact(keyId2)] -= amount;
                    verificationMap[FIXED_KEY_ID1] += 1;
                    verificationMap[FIXED_KEY_ID2] += 1;
                }
            }

            virtualMap = copyMap(virtualMap);

            // Report TPS
            final long curTime = System.nanoTime();
            updateTPS(i, curTime - prevTime);
            prevTime = curTime;
        }
        totalTPS(System.nanoTime() - startTime);
    }

    @Benchmark
    public void transferPrefetch() {
        logger.info(RUN_DELIMITER);

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

        final long startTime = System.nanoTime();
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
                    verificationMap[Math.toIntExact(keyId1)] += amount;
                    verificationMap[Math.toIntExact(keyId2)] -= amount;
                    verificationMap[FIXED_KEY_ID1] += 1;
                    verificationMap[FIXED_KEY_ID2] += 1;
                }
            }

            queue.clear();

            virtualMap = copyMap(virtualMap);

            // Report TPS
            final long curTime = System.nanoTime();
            updateTPS(i, curTime - prevTime);
            prevTime = curTime;
        }
        totalTPS(System.nanoTime() - startTime);
        prefetchPool.close();
    }

    static class AsyncTask extends AbstractTask {

        Transaction txn;
        Cache cache;
        long skey, rkey;
        long amount;
        long timestamp;
        SyncTask out;

        AsyncTask(ForkJoinPool pool, Cache cache, long skey, long rkey, long amount, long timestamp, SyncTask out) {
            super(pool, 1);
            this.cache = cache;
            this.skey = skey;
            this.rkey = rkey;
            this.amount = amount;
            this.timestamp = timestamp;
            this.out = out;
        }

        @Override
        protected boolean onExecute() {
            Transaction txn = new Transaction(skey, rkey, amount, timestamp);
            txn.execAsync(cache);
            out.send(txn);
            return true;
        }

        @Override
        protected void onException(final Throwable t) {
            logger.error("Error occurred while executing AsyncTask", t);
        }
    }

    class SyncTask extends AbstractTask {

        Transaction txn;
        MainCache mainCache;
        SyncTask next;
        FlushTask out;

        SyncTask(ForkJoinPool pool, MainCache mainCache, FlushTask out) {
            super(pool, 3);
            this.mainCache = mainCache;
            this.out = out;
        }

        void update(Bytes key, long amount) {
            BenchmarkValue value = mainCache.get(key).value;
            if (value == null) value = new BenchmarkValue(0);
            value = value.copyBuilder().update(l -> l + amount).build();
            mainCache.put(key, new VersionedValue(value, txn.timestamp));
        }

        @Override
        protected boolean onExecute() {

            boolean accept = true;
            for (Map.Entry<Bytes, VersionedValue> entry : txn.readCache.cache.entrySet()) { // TODO: cache
                VersionedValue vv0 = mainCache.cache.get(entry.getKey()); // TODO: cache
                if (vv0 != null && vv0.timestamp > entry.getValue().timestamp) {
                    accept = false;
                    break;
                }
            }
            if (!accept) {
                txn.execSync();
            }
            out.send(txn.writeCache.cache); // TODO: cache
            mainCache.addAll(txn.writeCache.cache); // TODO: cache

            // Model fees
            update(fixedKey1, 1);
            update(fixedKey2, 1);

            next.send();
            return true;
        }

        @Override
        protected void onException(final Throwable t) {
            logger.error("Error occurred while executing SyncTask", t);
        }

        void send(SyncTask next) {
            this.next = next;
            send();
        }

        void send(Transaction txn) {
            this.txn = txn;
            send();
        }
    }

    static class FlushTask extends AbstractTask {
        VirtualMap virtualMap;
        Map<Bytes, VersionedValue> cache;
        FlushTask next;

        FlushTask(ForkJoinPool pool, VirtualMap virtualMap) {
            super(pool, 3);
            this.virtualMap = virtualMap;
        }

        @Override
        protected boolean onExecute() {
            for (Map.Entry<Bytes, VersionedValue> entry : cache.entrySet()) {
                virtualMap.put(entry.getKey(), entry.getValue().value, BenchmarkValueCodec.INSTANCE);
            }
            next.send();
            return true;
        }

        @Override
        protected void onException(final Throwable t) {
            logger.error("Error occurred while executing FlushTask", t);
        }

        void send(FlushTask next) {
            this.next = next;
            send();
        }

        void send(Map<Bytes, VersionedValue> cache) {
            this.cache = cache;
            send();
        }
    }

    record VersionedValue(BenchmarkValue value, long timestamp) {}

    abstract static class Cache {
        Map<Bytes, VersionedValue> cache;

        abstract VersionedValue get(Bytes key);

        abstract void put(Bytes key, VersionedValue vval);
    }

    static class ReadCache extends Cache {
        Cache delegate;

        ReadCache(Cache delegate) {
            this.cache = new HashMap<>(2);
            this.delegate = delegate;
        }

        VersionedValue get(Bytes key) {
            VersionedValue vv = cache.get(key);
            if (vv != null) return vv;
            vv = delegate.get(key);
            cache.put(key, vv);
            return vv;
        }

        void put(Bytes key, VersionedValue vval) {
            throw new IllegalStateException("ReadCache.put() called");
        }
    }

    static class WriteCache extends Cache {
        Cache delegate;

        WriteCache(Cache delegate) {
            this.cache = new HashMap<>(2);
            this.delegate = delegate;
        }

        VersionedValue get(Bytes key) {
            VersionedValue vv = cache.get(key);
            if (vv != null) return vv;
            return delegate.get(key);
        }

        void put(Bytes key, VersionedValue vval) {
            cache.put(key, vval);
        }
    }

    class MainCache extends Cache {
        VirtualMap virtualMap;

        MainCache(VirtualMap virtualMap) {
            this.cache = new ConcurrentHashMap<>(1 << 20);
            this.virtualMap = virtualMap;
        }

        VersionedValue get(Bytes key) {
            VersionedValue vv = cache.get(key);
            if (vv != null) return vv;
            BenchmarkValue value = virtualMap.get(key, BenchmarkValueCodec.INSTANCE);
            return new VersionedValue(value, -1);
        }

        void put(Bytes key, VersionedValue vval) {
            cache.put(key, vval);
        }

        void addAll(Map<Bytes, VersionedValue> source) {
            cache.putAll(source);
        }

        void commit() {
            virtualMap.put(fixedKey1, cache.get(fixedKey1).value, BenchmarkValueCodec.INSTANCE);
            virtualMap.put(fixedKey2, cache.get(fixedKey2).value, BenchmarkValueCodec.INSTANCE);
        }
    }

    static class Transaction {
        final long timestamp;
        final long amount;
        final long skey;
        final long rkey;
        ReadCache readCache;
        WriteCache writeCache;
        Bytes sender;
        Bytes receiver;

        Transaction(long skey, long rkey, long amount, long timestamp) {
            this.timestamp = timestamp;
            this.amount = amount;
            this.skey = skey;
            this.rkey = rkey;
        }

        void update(Cache cache, Bytes key, long amount) {
            BenchmarkValue value = cache.get(key).value;
            if (value == null) {
                value = new BenchmarkValue(amount);
            } else {
                value = value.copyBuilder().update(l -> l + amount).build();
            }
            cache.put(key, new VersionedValue(value, timestamp));
        }

        void execAsync(Cache delegate) {
            readCache = new ReadCache(delegate);
            writeCache = new WriteCache(readCache);

            sender = longToKey(skey);
            receiver = longToKey(rkey);
            exec(writeCache);
        }

        void execSync() {
            readCache.cache.clear();
            writeCache.cache.clear();
            exec(writeCache);
        }

        void exec(Cache cache) {
            update(cache, sender, -amount);
            update(cache, receiver, amount);
        }
    }

    /**
     * Emulates crypto transfer.
     * Fetches a batch of "accounts" in parallel, updates the "accounts" in order by transferring
     * a random amount from one to another.
     */
    @Benchmark
    public void transferParallel() {
        logger.info(RUN_DELIMITER);

        final ForkJoinPool pool = new ForkJoinPool(numThreads);

        final long startTime = System.nanoTime();
        long prevTime = startTime;
        final long[] keys = new long[numRecords * KEYS_PER_RECORD];
        for (int i = 1; i <= numFiles; ++i) {
            // Generate a new set of random keys
            generateKeySet(keys);

            MainCache mainCache = new MainCache(virtualMap);
            FlushTask finalTask = null;
            FlushTask currentFlushTask = new FlushTask(pool, virtualMap);
            SyncTask currentSyncTask = new SyncTask(pool, mainCache, currentFlushTask);
            // This is the very first task in a daisy chain of sequential SyncTasks,
            // emulate its resolved dependency from the non-existent previous task
            currentSyncTask.send();
            currentFlushTask.send();

            for (int j = 0; j < numRecords; ++j) {
                final long keyId1 = keys[j * KEYS_PER_RECORD];
                final long keyId2 = keys[j * KEYS_PER_RECORD + 1];
                final long amount = Utils.randomLong(MAX_AMOUNT);

                if (verify) {
                    verificationMap[Math.toIntExact(keyId1)] -= amount;
                    verificationMap[Math.toIntExact(keyId2)] += amount;
                    verificationMap[FIXED_KEY_ID1] += 1;
                    verificationMap[FIXED_KEY_ID2] += 1;
                }

                new AsyncTask(pool, mainCache, keyId1, keyId2, amount, j, currentSyncTask).send();
                FlushTask nextFlushTask = new FlushTask(pool, virtualMap);
                currentFlushTask.send(nextFlushTask);
                finalTask = currentFlushTask;
                currentFlushTask = nextFlushTask;

                SyncTask nextSyncTask = new SyncTask(pool, mainCache, nextFlushTask);
                currentSyncTask.send(nextSyncTask);
                currentSyncTask = nextSyncTask;
            }
            finalTask.join();
            mainCache.commit();

            virtualMap = copyMap(virtualMap);

            // Report TPS
            final long curTime = System.nanoTime();
            updateTPS(i, curTime - prevTime);
            prevTime = curTime;
        }
        totalTPS(System.nanoTime() - startTime);
        pool.close();
    }

    static void main() throws Exception {
        // This entry point is intended for local IDE profiling.
        // Run in-process so the IntelliJ profiler attaches to the benchmark workload instead of a JMH fork.
        // If a larger heap is needed, set it in the IDE run configuration VM options.
        new Runner(new OptionsBuilder()
                        .include(CryptoBench.class.getSimpleName() + ".transferParallel")
                        .forks(0)
                        .build())
                .run();
    }
}
