// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static com.swirlds.benchmark.Utils.RUN_DELIMITER;
import static org.awaitility.Awaitility.await;

import com.swirlds.benchmark.reconnect.MerkleBenchmarkUtils;
import com.swirlds.benchmark.reconnect.StateBuilder;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.virtualmap.VirtualMap;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

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

    private static final String TEACHER_MAP_NAME = "teacher";
    private VirtualMap teacherMap;
    private VirtualMap teacherMapCopy;

    private static final String LEARNER_MAP_NAME = "learner";
    private VirtualMap learnerMap;

    private VirtualMap reconnectedMap;

    private long[] teacherData;

    @Override
    String benchmarkName() {
        return "ReconnectBench";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onTrialSetup() {
        super.onTrialSetup();

        final Random random = new Random(randomSeed);

        if (getBenchmarkConfig().saveDataDirectory()) {
            teacherMap = restoreMap(TEACHER_MAP_NAME);
            learnerMap = restoreMap(LEARNER_MAP_NAME);

            // Both maps should be restored - otherwise, something went wrong
            if (teacherMap == null || learnerMap == null) {
                if (teacherMap != null) {
                    teacherMap.release();
                    teacherMap = null;
                }
                if (learnerMap != null) {
                    learnerMap.release();
                    learnerMap = null;
                }
            }
        }

        if (teacherMap == null || learnerMap == null) {
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
                            StateBuilder.buildVMPopulator(teacherRef),
                            StateBuilder.buildVMPopulator(learnerRef),
                            i -> {
                                if (i % numRecords == 0) {
                                    logger.info("Copying files for i={}", i);
                                    teacherRef.set(teacherMap = copyMap(teacherMap));
                                    learnerRef.set(learnerMap = copyMap(learnerMap));
                                }
                            });

            // Save learner map to disk
            learnerMap = flushMap(learnerMap);
            learnerMap = saveMap(learnerMap, LEARNER_MAP_NAME);

            // Save teacher map to disk
            teacherMap = flushMap(teacherMap);
            teacherMap = saveMap(teacherMap, TEACHER_MAP_NAME);
        }

        // Make teacher immutable by creating a copy; keep the copy as the mutable head
        teacherMapCopy = teacherMap.copy();

        // Pre-hash the teacher map once — it's never modified
        teacherMap.getHash();

        BenchmarkMetrics.register(learnerMap::registerMetrics);
        BenchmarkMetrics.register(teacherMap::registerMetrics);

        // Build the verification array once from the teacher map
        if (verify) {
            teacherData = new long[numRecords * numFiles * 2];
            copyMapToArray(teacherMap, teacherData);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onInvocationTearDown() throws Exception {
        if (verify) {
            verifyMap(teacherData, reconnectedMap);
        }

        reconnectedMap.release();

        super.onInvocationTearDown();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onTrialTearDown() throws Exception {
        learnerMap.release();
        teacherMap.release();
        teacherMapCopy.release();

        learnerMap = null;
        teacherMap = null;
        teacherMapCopy = null;
        teacherData = null;

        await().atMost(Duration.ofSeconds(30)).until(() -> MerkleDbDataSource.getCountOfOpenDatabases() == 0);

        super.onTrialTearDown();
    }

    @Benchmark
    public void reconnect() throws Exception {
        logger.info(RUN_DELIMITER);

        reconnectedMap = MerkleBenchmarkUtils.hashAndTestSynchronization(
                learnerMap,
                teacherMap,
                randomSeed,
                delayStorageMicroseconds,
                delayStorageFuzzRangePercent,
                delayNetworkMicroseconds,
                delayNetworkFuzzRangePercent,
                configuration);
    }

    static void main() throws Exception {
        // This entry point is intended for local IDE profiling.
        // Run in-process so the IntelliJ profiler attaches to the benchmark workload instead of a JMH fork.
        // If a larger heap is needed, set it in the IDE run configuration VM options.
        new Runner(new OptionsBuilder()
                        .include(ReconnectBench.class.getSimpleName())
                        .forks(0)
                        .build())
                .run();
    }
}
