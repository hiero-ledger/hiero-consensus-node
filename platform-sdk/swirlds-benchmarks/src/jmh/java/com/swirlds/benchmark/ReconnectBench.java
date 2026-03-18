// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.benchmark.reconnect.MerkleBenchmarkUtils;
import com.swirlds.benchmark.reconnect.StateBuilder;
import com.swirlds.virtualmap.VirtualMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.hiero.consensus.model.node.NodeId;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@Fork(value = 1)
@Warmup(iterations = 1)
@Measurement(iterations = 7)
public class ReconnectBench extends VirtualMapBaseBench {

    /** A random seed for the StateBuilder. */
    @Param({"9823452658"})
    public long randomSeed;

    /** The probability of the teacher map having an extra node. */
    @Param({"0.05"})
    public double teacherAddProbability;

    /** The probability of the teacher map having removed a node, while the learner still having it. */
    @Param({"0.05"})
    public double teacherRemoveProbability;

    /**
     * The probability of the teacher map having a value under a key that differs
     * from the value under the same key in the learner map.
     */
    @Param({"0.05"})
    public double teacherModifyProbability;

    /**
     * Emulated delay for sendAsync() calls in both Teaching- and Learning-Synchronizers,
     * or zero for no delay. This emulates slow disk I/O when reading data.
     */
    @Param({"0"})
    public long delayStorageMicroseconds;

    /**
     * A percentage fuzz range for the delayStorageMicroseconds values,
     * e.g. 0.15 for a -15%..+15% range around the value.
     */
    @Param({"0.15"})
    public double delayStorageFuzzRangePercent;

    /**
     * Emulated delay for serializeMessage() calls in both Teaching- and Learning-Synchronizers,
     * or zero for no delay. This emulates slow network I/O when sending data.
     */
    @Param({"0"})
    public long delayNetworkMicroseconds;

    /**
     * A percentage fuzz range for the delayNetworkMicroseconds values,
     * e.g. 0.15 for a -15%..+15% range around the value.
     */
    @Param({"0.15"})
    public double delayNetworkFuzzRangePercent;

    private VirtualMap teacherMap;
    private VirtualMap teacherMapCopy;

    private VirtualMap learnerMap;

    private VirtualMap reconnectedMap;

    String benchmarkName() {
        return "ReconnectBench";
    }

    /**
     * Builds a VirtualMap populator that is able to add/update, as well as remove nodes (when the value is null.)
     * Note that it doesn't support explicitly adding null values under a key.
     *
     * @param mapRef a reference to a VirtualMap instance
     * @return a populator for the map
     */
    private static BiConsumer<Bytes, BenchmarkValue> buildVMPopulator(final AtomicReference<VirtualMap> mapRef) {
        return (k, v) -> {
            if (v == null) {
                mapRef.get().remove(k, BenchmarkValueCodec.INSTANCE);
            } else {
                mapRef.get().put(k, v, BenchmarkValueCodec.INSTANCE);
            }
        };
    }

    /** Generate a state and save it to disk once for the entire benchmark. */
    @Setup
    public void setupBenchmark() {
        beforeTest("reconnect");

        final Random random = new Random(randomSeed);

        teacherMap = createEmptyMap();
        learnerMap = createEmptyMap();

        final AtomicReference<VirtualMap> teacherRef = new AtomicReference<>(teacherMap);
        final AtomicReference<VirtualMap> learnerRef = new AtomicReference<>(learnerMap);

        new StateBuilder(BenchmarkKeyUtils::longToKey, BenchmarkValue::new)
                .buildState(
                        random,
                        (long) numRecords * numFiles,
                        teacherAddProbability,
                        teacherRemoveProbability,
                        teacherModifyProbability,
                        buildVMPopulator(teacherRef),
                        buildVMPopulator(learnerRef),
                        i -> {
                            if (i % numRecords == 0) {
                                System.err.printf("Copying files for i=%,d\n", i);
                                teacherRef.set(teacherMap = copyMap(teacherMap));
                                learnerRef.set(learnerMap = copyMap(learnerMap));
                            }
                        });

        teacherMap = flushMap(teacherMap);
        learnerMap = flushMap(learnerMap);

        final List<VirtualMap> maps = new ArrayList<>();
        maps.add(teacherMap);
        maps.add(learnerMap);
        final List<VirtualMap> mapCopies = saveMaps(maps);
        mapCopies.forEach(this::releaseAndCloseMap);
    }

    /** Restore the saved state from disk as a new test on-disk copy for each iteration. */
    @Setup(Level.Invocation)
    public void setupInvocation() {
        teacherMap = restoreMap();
        if (teacherMap == null) {
            throw new RuntimeException("Failed to restore the 'teacher' map");
        }
        teacherMap = flushMap(teacherMap);
        BenchmarkMetrics.register(teacherMap::registerMetrics);

        learnerMap = restoreMap();
        if (learnerMap == null) {
            throw new RuntimeException("Failed to restore the 'learner' map");
        }
        learnerMap = flushMap(learnerMap);
        BenchmarkMetrics.register(learnerMap::registerMetrics);

        teacherMapCopy = teacherMap.copy();
    }

    @TearDown(Level.Invocation)
    public void tearDownInvocation() throws Exception {
        try {
            if (!learnerMap.isHashed()) {
                throw new IllegalStateException("Learner root node must be hashed");
            }
        } finally {
            reconnectedMap.release();
            teacherMap.release();
            learnerMap.release();
            teacherMapCopy.release();
        }

        afterTest(() -> {
            // Close all data sources
            teacherMap.getDataSource().close();
            learnerMap.getDataSource().close();

            // release()/close() would delete the DB files eventually but not right away.
            // The files/directories can even be re-created in background (see a comment at
            // beforeTest(String name) above.)
            // Add a short sleep to help prevent irrelevant warning messages from being printed
            // when the BaseBench.afterTest() deletes test files recursively right after
            // this current runnable finishes executing.
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignore) {
            }
        });

        teacherMap = null;
        learnerMap = null;
    }

    @Benchmark
    public void reconnect() throws Exception {
        reconnectedMap = MerkleBenchmarkUtils.hashAndTestSynchronization(
                learnerMap,
                teacherMap,
                randomSeed,
                delayStorageMicroseconds,
                delayStorageFuzzRangePercent,
                delayNetworkMicroseconds,
                delayNetworkFuzzRangePercent,
                new NodeId(),
                configuration);
    }

    public static void main(String[] args) throws Exception {
        final ReconnectBench bench = new ReconnectBench();
        bench.setup();
        bench.createLocal();
        bench.setupBenchmark();
        bench.beforeTest();
        bench.setupInvocation();
        bench.reconnect();
        bench.tearDownInvocation();
        bench.afterTest();
        bench.destroyLocal();
        bench.destroy();
    }
}
