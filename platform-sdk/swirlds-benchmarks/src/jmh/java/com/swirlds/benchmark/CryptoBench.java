// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static com.swirlds.benchmark.BenchmarkKeyUtils.longToKey;
import static com.swirlds.benchmark.Utils.RUN_DELIMITER;
import static org.hiero.base.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.virtualmap.VirtualMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.AbstractTask;
import org.hiero.base.concurrent.framework.config.CompositeThreadNameProvider;
import org.hiero.base.concurrent.framework.config.ThreadConfiguration;
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
                        .setThreadNameProvider(CompositeThreadNameProvider.createNumbered("benchmark", "prefetch"))
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

    /**
     * Emulates crypto transfer.
     * Fetches a batch of "accounts" in parallel, updates the "accounts" in order by transferring
     * a random amount from one to another.
     */
    @Benchmark
    public void transferParallel() {
        logger.info(RUN_DELIMITER);

        final ForkJoinPool pool = new ForkJoinPool(numThreads);
        final ForkJoinPool priorityPool = new ForkJoinPool(2);

        final long startTime = System.nanoTime();
        long prevTime = startTime;
        final long[] keys = new long[numRecords * KEYS_PER_RECORD];
        for (int i = 1; i <= numFiles; ++i) {
            // Generate a new set of random keys
            generateKeySet(keys);

            final MainCache mainCache = new MainCache(virtualMap);
            FlushTask finalTask = null;
            FlushTask currentFlushTask = new FlushTask(priorityPool, virtualMap);
            HandleTask currentHandleTask = new HandleTask(priorityPool, mainCache, currentFlushTask);
            // This is the very first task in a daisy chain of sequential handle/flush tasks,
            // emulate its resolved dependency from the non-existent previous task
            currentHandleTask.send();
            currentFlushTask.send();

            for (int j = 0; j < numRecords; ++j) {
                final long keyId1 = keys[j * KEYS_PER_RECORD];
                final long keyId2 = keys[j * KEYS_PER_RECORD + 1];
                final long amount = Utils.randomLong(MAX_AMOUNT);

                if (verify) {
                    verificationMap[Math.toIntExact(keyId1)] += amount;
                    verificationMap[Math.toIntExact(keyId2)] -= amount;
                    verificationMap[FIXED_KEY_ID1] += 1;
                    verificationMap[FIXED_KEY_ID2] += 1;
                }

                // Prehandle tasks: all running in parallel
                new PrehandleTask(pool, mainCache, keyId1, keyId2, amount, j, currentHandleTask).send();

                // The chain of flushing tasks
                final FlushTask nextFlushTask = new FlushTask(priorityPool, virtualMap);
                currentFlushTask.send(nextFlushTask);
                finalTask = currentFlushTask;
                currentFlushTask = nextFlushTask;

                // The chain of handling tasks
                final HandleTask nextHandleTask = new HandleTask(priorityPool, mainCache, nextFlushTask);
                currentHandleTask.send(nextHandleTask);
                currentHandleTask = nextHandleTask;
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
        priorityPool.close();
    }

    static class PrehandleTask extends AbstractTask {

        private final Cache cache;
        private final long skey;
        private final long rkey;
        private final long amount;
        private final long timestamp;
        private final HandleTask out;

        PrehandleTask(
                final ForkJoinPool pool,
                final Cache cache,
                final long skey,
                final long rkey,
                final long amount,
                final long timestamp,
                final HandleTask out) {
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
            final Transaction txn = new Transaction(skey, rkey, amount, timestamp);
            txn.prehandle(cache);
            out.send(txn);
            return true;
        }

        @Override
        protected void onException(final Throwable t) {
            logger.error("Error occurred while executing PrehandleTask", t);
        }
    }

    class HandleTask extends AbstractTask {

        private Transaction txn;
        private final MainCache mainCache;
        private HandleTask next;
        private final FlushTask out;

        // Dependencies: prev handle task is executed, pre-handle task is executed (txn
        // is provided), next handle task is provided
        HandleTask(final ForkJoinPool pool, final MainCache mainCache, final FlushTask out) {
            super(pool, 3);
            this.mainCache = mainCache;
            this.out = out;
        }

        void update(final Bytes key, final long amount) {
            BenchmarkValue value = mainCache.get(key).value;
            if (value == null) {
                value = new BenchmarkValue(0);
            }
            value = value.copyBuilder().update(l -> l + amount).build();
            mainCache.put(key, new VersionedValue(value, txn.timestamp));
        }

        @Override
        protected boolean onExecute() {
            boolean accept = true;
            // Check if transaction prehandle results can be reused
            for (final Map.Entry<Bytes, VersionedValue> entry : txn.readCache.cache.entrySet()) {
                final VersionedValue cached = mainCache.cache.get(entry.getKey());
                if ((cached != null) && (cached.timestamp > entry.getValue().timestamp)) {
                    // Other transactions updated the value. The transaction has to be replayed
                    accept = false;
                    break;
                }
            }
            if (!accept) {
                txn.handle();
            }
            // Schedule a flush
            out.send(txn);
            // Update the main cache. This has to be done before the next transaction is
            // handled (next.send() below), since it will check the main cache
            mainCache.putAll(txn.writeCache.cache);

            // Model fees
            update(fixedKey1, 1);
            update(fixedKey2, 1);

            // Handle the next transaction
            next.send();
            return true;
        }

        @Override
        protected void onException(final Throwable t) {
            logger.error("Error occurred while executing HandleTask", t);
        }

        void send(final HandleTask next) {
            this.next = next;
            send();
        }

        void send(final Transaction txn) {
            this.txn = txn;
            send();
        }
    }

    static class FlushTask extends AbstractTask {

        private final VirtualMap virtualMap;
        // Even elements: keys, odd elements: versioned values. Not using a map, since
        // the order is important
        private List<Object> updates;
        private FlushTask next;

        // Dependencies: prev flush task is executed, handle task is executed (txn
        // is provided), next flush task is provided
        FlushTask(final ForkJoinPool pool, final VirtualMap virtualMap) {
            super(pool, 3);
            this.virtualMap = virtualMap;
        }

        @Override
        protected boolean onExecute() {
            for (int i = 0; i < updates.size(); i += 2) {
                final Bytes key = (Bytes) updates.get(i);
                final VersionedValue value = (VersionedValue) updates.get(i + 1);
                virtualMap.put(key, value.value, BenchmarkValueCodec.INSTANCE);
            }
            next.send();
            return true;
        }

        @Override
        protected void onException(final Throwable t) {
            logger.error("Error occurred while executing FlushTask", t);
        }

        void send(final FlushTask next) {
            this.next = next;
            send();
        }

        void send(final Transaction txn) {
            this.updates = txn.getUpdates();
            send();
        }
    }

    record VersionedValue(BenchmarkValue value, long timestamp) {}

    abstract static class Cache {

        Map<Bytes, VersionedValue> cache;

        abstract VersionedValue get(final Bytes key);

        abstract void put(final Bytes key, final VersionedValue vval);
    }

    static class ReadCache extends Cache {

        private final Cache delegate;

        ReadCache(final Cache delegate) {
            this.cache = new HashMap<>(2);
            this.delegate = delegate;
        }

        VersionedValue get(final Bytes key) {
            VersionedValue vv = cache.get(key);
            if (vv != null) {
                return vv;
            }
            vv = delegate.get(key);
            cache.put(key, vv);
            return vv;
        }

        void put(final Bytes key, final VersionedValue vval) {
            throw new IllegalStateException("ReadCache.put() called");
        }
    }

    static class WriteCache extends Cache {

        private final Cache delegate;
        private final List<Object> updates;

        WriteCache(final Cache delegate) {
            this.cache = new HashMap<>(2);
            this.updates = new ArrayList<>(4);
            this.delegate = delegate;
        }

        VersionedValue get(final Bytes key) {
            VersionedValue vv = cache.get(key);
            if (vv != null) {
                return vv;
            }
            return delegate.get(key);
        }

        void put(final Bytes key, final VersionedValue vval) {
            cache.put(key, vval);
            updates.add(key);
            updates.add(vval);
        }

        List<Object> getUpdates() {
            return updates;
        }
    }

    class MainCache extends Cache {

        private final VirtualMap virtualMap;

        MainCache(final VirtualMap virtualMap) {
            this.cache = new ConcurrentHashMap<>(1 << 20);
            this.virtualMap = virtualMap;
        }

        VersionedValue get(final Bytes key) {
            final VersionedValue vv = cache.get(key);
            if (vv != null) {
                return vv;
            }
            final BenchmarkValue value = virtualMap.get(key, BenchmarkValueCodec.INSTANCE);
            return new VersionedValue(value, -1);
        }

        void put(final Bytes key, final VersionedValue vval) {
            cache.put(key, vval);
        }

        void putAll(final Map<Bytes, VersionedValue> source) {
            cache.putAll(source);
        }

        void commit() {
            virtualMap.put(fixedKey1, cache.get(fixedKey1).value, BenchmarkValueCodec.INSTANCE);
            virtualMap.put(fixedKey2, cache.get(fixedKey2).value, BenchmarkValueCodec.INSTANCE);
        }
    }

    static class Transaction {

        private final long timestamp;
        private final long amount;
        private ReadCache readCache;
        private WriteCache writeCache;
        private final Bytes sender;
        private final Bytes receiver;

        Transaction(final long skey, final long rkey, final long amount, final long timestamp) {
            this.timestamp = timestamp;
            this.amount = amount;
            this.sender = longToKey(skey);
            this.receiver = longToKey(rkey);
        }

        // Called in parallel in prehandle tasks
        void prehandle(final Cache delegate) {
            readCache = new ReadCache(delegate);
            writeCache = new WriteCache(readCache);
            exec(writeCache);
        }

        // Called sequentially in handle tasks
        void handle() {
            readCache.cache.clear();
            writeCache.cache.clear();
            exec(writeCache);
        }

        private void exec(final Cache cache) {
            update(cache, sender, amount);
            update(cache, receiver, -amount);
        }

        private void update(final Cache cache, final Bytes key, final long amount) {
            BenchmarkValue value = cache.get(key).value;
            if (value == null) {
                value = new BenchmarkValue(amount);
            } else {
                value = value.copyBuilder().update(l -> l + amount).build();
            }
            cache.put(key, new VersionedValue(value, timestamp));
        }

        List<Object> getUpdates() {
            return writeCache.getUpdates();
        }
    }

    static void main() throws Exception {
        // This entry point is intended for local IDE profiling.
        // Run in-process so the IntelliJ profiler attaches to the benchmark workload instead of a JMH fork.
        // If a larger heap is needed, set it in the IDE run configuration VM options.
        new Runner(new OptionsBuilder()
                        .include(CryptoBench.class.getSimpleName() + ".transferPrefetch")
                        .forks(0)
                        .build())
                .run();
    }
}
