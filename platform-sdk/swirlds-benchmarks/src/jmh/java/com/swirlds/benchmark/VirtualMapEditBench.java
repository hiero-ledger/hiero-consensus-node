// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static com.swirlds.benchmark.BenchmarkUtils.longToBytes;
import static com.swirlds.benchmark.Utils.RUN_DELIMITER;
import static org.awaitility.Awaitility.await;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.virtualmap.VirtualMap;
import java.time.Duration;
import java.util.ArrayDeque;
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
@Warmup(iterations = 1)
@Measurement(iterations = 5)
public class VirtualMapEditBench extends VirtualMapBaseBench {

    /** The mutable map used by write-based benchmarks. */
    protected VirtualMap virtualMap;

    /** Verification array for write-based benchmarks, or null when verify is false. */
    protected long[] verificationMap;

    @Override
    String benchmarkName() {
        return "VirtualMapEditBench";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onInvocationSetup() {
        super.onInvocationSetup();

        verificationMap = verify ? new long[maxKey] : new long[0];
        virtualMap = createMap(verificationMap);

        if (getBenchmarkConfig().enableSnapshots()) {
            enableSnapshots();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onInvocationTearDown() throws Exception {
        if (virtualMap != null) {
            final VirtualMap finalMap = flushAndOptionallySaveMap(virtualMap);
            if (verify) {
                verifyMap(verificationMap, finalMap);
            }
            finalMap.release();
            virtualMap = null;
        }
        verificationMap = null;

        await().atMost(Duration.ofSeconds(30)).until(() -> MerkleDbDataSource.getCountOfOpenDatabases() == 0);

        super.onInvocationTearDown();
    }

    /**
     * [Read-update or create-write] cycle. Single-threaded.
     */
    @Benchmark
    public void update() {
        logger.info(RUN_DELIMITER);

        // Update values
        long start = System.currentTimeMillis();
        for (int i = 0; i < numFiles; i++) {

            for (int j = 0; j < numRecords; ++j) {
                long id = Utils.randomLong(maxKey);
                Bytes key = longToBytes(id, keySize);
                BenchmarkValue value = virtualMap.get(key, BenchmarkValueCodec.INSTANCE);
                long val = nextValue();
                if (value != null) {
                    if ((val & 0xff) == 0) {
                        virtualMap.remove(key);
                        if (verify) verificationMap[(int) id] = 0L;
                    } else {
                        value = value.copyBuilder().update(l -> l + val).build();
                        virtualMap.put(key, value, BenchmarkValueCodec.INSTANCE);
                        if (verify) verificationMap[(int) id] += val;
                    }
                } else {
                    value = new BenchmarkValue(val);
                    virtualMap.put(key, value, BenchmarkValueCodec.INSTANCE);
                    if (verify) verificationMap[(int) id] = val;
                }
            }

            virtualMap = copyMap(virtualMap);
        }

        logger.info("Updated {} copies in {} ms", numFiles, System.currentTimeMillis() - start);
    }

    /**
     * [Create-write or replace] cycle. Single-threaded.
     */
    @Benchmark
    public void create() {
        logger.info(RUN_DELIMITER);

        // Write files
        long start = System.currentTimeMillis();
        for (int i = 0; i < numFiles; i++) {
            for (int j = 0; j < numRecords; ++j) {
                long id = Utils.randomLong(maxKey);
                final Bytes key = longToBytes(id, keySize);
                final long val = nextValue();
                final BenchmarkValue value = new BenchmarkValue(val);
                virtualMap.put(key, value, BenchmarkValueCodec.INSTANCE);
                if (verify) {
                    verificationMap[(int) id] = val;
                }
            }

            virtualMap = copyMap(virtualMap);
        }

        logger.info("Created {} copies in {} ms", numFiles, System.currentTimeMillis() - start);
    }

    /**
     * [Read-update or create-write][Remove expired] cycle. Single-threaded.
     */
    @Benchmark
    public void delete() {
        logger.info(RUN_DELIMITER);

        final int EXPIRY_DELAY = 180_000;
        record Expirable(long time, long id) {}
        final ArrayDeque<Expirable> expirables = new ArrayDeque<>();

        long start = System.currentTimeMillis();
        for (int i = 0; i < numFiles; i++) {
            // Add/update new values
            for (int j = 0; j < numRecords; ++j) {
                final long id = Utils.randomLong(maxKey);
                final Bytes key = longToBytes(id, keySize);
                BenchmarkValue value = virtualMap.get(key, BenchmarkValueCodec.INSTANCE);
                final long val = nextValue();
                if (value != null) {
                    value = value.copyBuilder().update(l -> l + val).build();
                    virtualMap.put(key, value, BenchmarkValueCodec.INSTANCE);
                    if (verify) verificationMap[(int) id] += val;
                } else {
                    value = new BenchmarkValue(val);
                    virtualMap.put(key, value, BenchmarkValueCodec.INSTANCE);
                    if (verify) verificationMap[(int) id] = val;
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
                virtualMap.remove(longToBytes(entry.id, keySize));
                if (verify) verificationMap[(int) entry.id] = 0L;
                expirables.removeFirst();
            }
            logger.info("Copy {} done, map size {}", i, virtualMap.size());

            virtualMap = copyMap(virtualMap);
        }

        logger.info("Updated {} copies in {} ms", numFiles, System.currentTimeMillis() - start);
    }

    static void main() throws Exception {
        // This entry point is intended for local IDE profiling.
        // Run in-process so the IntelliJ profiler attaches to the benchmark workload instead of a JMH fork.
        // If a larger heap is needed, set it in the IDE run configuration VM options.
        new Runner(new OptionsBuilder()
                        .include(VirtualMapEditBench.class.getSimpleName())
                        .forks(0)
                        .build())
                .run();
    }
}
