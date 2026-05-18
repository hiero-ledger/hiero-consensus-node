// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static com.swirlds.benchmark.Utils.RUN_DELIMITER;
import static org.awaitility.Awaitility.await;

import com.swirlds.benchmark.reconnect.StateBuilder;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.virtualmap.VirtualMap;
import java.time.Duration;
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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@State(Scope.Thread)
@Warmup(iterations = 1)
@Measurement(iterations = 5)
public class VirtualMapReadBench extends VirtualMapBaseBench {

    private VirtualMap virtualMap;

    @Override
    String benchmarkName() {
        return "VirtualMapReadBench";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onTrialSetup() {
        super.onTrialSetup();

        virtualMap = createEmptyMap();
        final AtomicReference<VirtualMap> mapRef = new AtomicReference<>(virtualMap);
        final long recordsPerCopy = maxKey / numFiles;

        final long start = System.currentTimeMillis();
        new StateBuilder(i -> BenchmarkUtils.longToBytes(i, keySize), i -> new BenchmarkValue(nextValue()))
                .populateState(
                        0,
                        maxKey,
                        i -> {
                            if (i > 0 && i % recordsPerCopy == 0) {
                                mapRef.set(virtualMap = copyMap(virtualMap));
                            }
                        },
                        StateBuilder.buildVMPopulator(mapRef));
        logger.info("Pre-created {} records in {} ms", maxKey, System.currentTimeMillis() - start);

        virtualMap = flushAndOptionallySaveMap(virtualMap);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onTrialTearDown() throws Exception {
        virtualMap.release();
        virtualMap = null;

        await().atMost(Duration.ofSeconds(30)).until(() -> MerkleDbDataSource.getCountOfOpenDatabases() == 0);

        super.onTrialTearDown();
    }

    /**
     * Read from a pre-created map. Parallel.
     */
    @Benchmark
    public void read() {
        logger.info(RUN_DELIMITER);

        final long start = System.currentTimeMillis();
        final AtomicLong total = new AtomicLong(0);
        IntStream.range(0, numThreads).parallel().forEach(thread -> {
            long sum = 0;
            for (int i = 0; i < numRecords; ++i) {
                final long id = Utils.randomLong(maxKey);
                final BenchmarkValue value =
                        virtualMap.get(BenchmarkUtils.longToBytes(id, keySize), BenchmarkValueCodec.INSTANCE);
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

    static void main() throws Exception {
        // This entry point is intended for local IDE profiling.
        // Run in-process so the IntelliJ profiler attaches to the benchmark workload instead of a JMH fork.
        // If a larger heap is needed, set it in the IDE run configuration VM options.
        new Runner(new OptionsBuilder()
                        .include(VirtualMapReadBench.class.getSimpleName())
                        .forks(0)
                        .build())
                .run();
    }
}
