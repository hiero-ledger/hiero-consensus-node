// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static com.swirlds.benchmark.BenchmarkKeyUtils.longToKey;
import static com.swirlds.benchmark.Utils.RUN_DELIMITER;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.benchmark.reconnect.StateBuilder;
import com.swirlds.virtualmap.VirtualMap;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
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
@Warmup(iterations = 1)
@Measurement(iterations = 5)
public class VirtualMapBench extends VirtualMapBaseBench {

    @Override
    String benchmarkName() {
        return "VirtualMapBench";
    }

    /**
     * [Read-update or create-write] cycle. Single-threaded.
     */
    @Benchmark
    public void update() throws Exception {
        logger.info(RUN_DELIMITER);

        final long[] map = new long[verify ? maxKey : 0];
        VirtualMap virtualMap = createMap(map);

        if (getBenchmarkConfig().enableSnapshots()) {
            enableSnapshots();
        }

        // Update values
        long start = System.currentTimeMillis();
        for (int i = 0; i < numFiles; i++) {

            for (int j = 0; j < numRecords; ++j) {
                long id = Utils.randomLong(maxKey);
                Bytes key = longToKey(id);
                BenchmarkValue value = virtualMap.get(key, BenchmarkValueCodec.INSTANCE);
                long val = nextValue();
                if (value != null) {
                    if ((val & 0xff) == 0) {
                        virtualMap.remove(key);
                        if (verify) map[(int) id] = 0L;
                    } else {
                        value = value.copyBuilder().update(l -> l + val).build();
                        virtualMap.put(key, value, BenchmarkValueCodec.INSTANCE);
                        if (verify) map[(int) id] += val;
                    }
                } else {
                    value = new BenchmarkValue(val);
                    virtualMap.put(key, value, BenchmarkValueCodec.INSTANCE);
                    if (verify) map[(int) id] = val;
                }
            }

            virtualMap = copyMap(virtualMap);
        }

        logger.info("Updated {} copies in {} ms", numFiles, System.currentTimeMillis() - start);

        // Ensure the map is done with hashing/merging/flushing
        final var finalMap = flushAndOptionallySaveMap(virtualMap);

        verifyMap(map, finalMap);

        finalMap.release();
        finalMap.getDataSource().close();
    }

    /**
     * [Create-write or replace] cycle. Single-threaded.
     */
    @Benchmark
    public void create() throws Exception {
        logger.info(RUN_DELIMITER);

        final long[] map = new long[verify ? maxKey : 0];
        VirtualMap virtualMap = createMap(map);

        // Write files
        long start = System.currentTimeMillis();
        for (int i = 0; i < numFiles; i++) {
            for (int j = 0; j < numRecords; ++j) {
                long id = Utils.randomLong(maxKey);
                final Bytes key = longToKey(id);
                final long val = nextValue();
                final BenchmarkValue value = new BenchmarkValue(val);
                virtualMap.put(key, value, BenchmarkValueCodec.INSTANCE);
                if (verify) {
                    map[(int) id] = val;
                }
            }

            virtualMap = copyMap(virtualMap);
        }

        logger.info("Created {} copies in {} ms", numFiles, System.currentTimeMillis() - start);

        // Ensure the map is done with hashing/merging/flushing
        final var finalMap = flushAndOptionallySaveMap(virtualMap);

        verifyMap(map, finalMap);

        finalMap.release();
        finalMap.getDataSource().close();
    }

    /**
     * [Read-update or create-write][Remove expired] cycle. Single-threaded.
     */
    @Benchmark
    public void delete() throws Exception {
        logger.info(RUN_DELIMITER);

        final long[] map = new long[verify ? maxKey : 0];
        VirtualMap virtualMap = createMap(map);

        final int EXPIRY_DELAY = 180_000;
        record Expirable(long time, long id) {}
        final ArrayDeque<Expirable> expirables = new ArrayDeque<>();

        if (getBenchmarkConfig().enableSnapshots()) {
            enableSnapshots();
        }

        long start = System.currentTimeMillis();
        for (int i = 0; i < numFiles; i++) {
            // Add/update new values
            for (int j = 0; j < numRecords; ++j) {
                final long id = Utils.randomLong(maxKey);
                final Bytes key = longToKey(id);
                BenchmarkValue value = virtualMap.get(key, BenchmarkValueCodec.INSTANCE);
                final long val = nextValue();
                if (value != null) {
                    value = value.copyBuilder().update(l -> l + val).build();
                    virtualMap.put(key, value, BenchmarkValueCodec.INSTANCE);
                    if (verify) map[(int) id] += val;
                } else {
                    value = new BenchmarkValue(val);
                    virtualMap.put(key, value, BenchmarkValueCodec.INSTANCE);
                    if (verify) map[(int) id] = val;
                }
                expirables.addLast(new Expirable(System.currentTimeMillis() + EXPIRY_DELAY, id));
            }

            // Remove expired values
            final long curTime = System.currentTimeMillis();
            for (; ; ) {
                Expirable entry = expirables.peekFirst();
                if (entry == null || entry.time > curTime) {
                    break;
                }
                virtualMap.remove(longToKey(entry.id));
                if (verify) map[(int) entry.id] = 0L;
                expirables.removeFirst();
            }
            logger.info("Copy {} done, map size {}", i, virtualMap.size());

            virtualMap = copyMap(virtualMap);
        }

        logger.info("Updated {} copies in {} ms", numFiles, System.currentTimeMillis() - start);

        // Ensure the map is done with hashing/merging/flushing
        final var finalMap = flushAndOptionallySaveMap(virtualMap);

        verifyMap(map, finalMap);

        finalMap.release();
        finalMap.getDataSource().close();
    }

    /**
     * Read from a pre-created map. Parallel.
     */
    @Benchmark
    public void read() {
        logger.info(RUN_DELIMITER);

        if (virtualMapP == null) {
            virtualMapP = createEmptyMap();
            final AtomicReference<VirtualMap> mapRef = new AtomicReference<>(virtualMapP);
            final long recordsPerCopy = maxKey / numFiles;

            final long start = System.currentTimeMillis();
            new StateBuilder(BenchmarkKeyUtils::longToKey, i -> new BenchmarkValue(nextValue()))
                    .populateState(
                            0,
                            maxKey,
                            i -> {
                                if (i > 0 && i % recordsPerCopy == 0) {
                                    mapRef.set(virtualMapP = copyMap(virtualMapP));
                                }
                            },
                            StateBuilder.buildVMPopulator(mapRef));
            logger.info("Pre-created {} records in {} ms", maxKey, System.currentTimeMillis() - start);

            virtualMapP = flushAndOptionallySaveMap(virtualMapP);
        }

        final long start = System.currentTimeMillis();
        final AtomicLong total = new AtomicLong(0);
        IntStream.range(0, numThreads).parallel().forEach(thread -> {
            long sum = 0;
            for (int i = 0; i < numRecords; ++i) {
                final long id = Utils.randomLong(maxKey);
                final BenchmarkValue value = virtualMapP.get(longToKey(id), BenchmarkValueCodec.INSTANCE);
                sum += value.hashCode();
            }
            total.addAndGet(sum);
        });

        logger.info(
                "Read {} records from {} threads in {} ms",
                (long) numRecords * numThreads,
                numThreads,
                System.currentTimeMillis() - start);
    }
}
