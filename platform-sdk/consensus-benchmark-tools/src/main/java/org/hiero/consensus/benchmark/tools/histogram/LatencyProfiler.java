// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ScalarResult;

/**
 * A JMH profiler that turns the measurements a benchmark records with a {@link LatencyRecorder} into results: latency
 * percentiles, the allocation per call, and the number of passes discarded because of a garbage collection.
 *
 * <h2>What it is for</h2>
 *
 * <p>JMH reports one main result per benchmark, its primary score, which here is the average time per call. Profilers
 * can add further results, which JMH prints below the score and writes to its JSON output. This profiler adds how the
 * latencies of the individual calls are distributed. The average hides the rare slow calls, which often matter most;
 * percentiles show them. For example, {@code latency.p99} is the latency that 99% of the calls stay at or below.
 *
 * <p>The results cannot come from JMH's {@code @AuxCounters}, which is meant for counting events: JMH adds up their
 * values across iterations and forks, and a sum of percentiles is meaningless.
 *
 * <h2>Results</h2>
 *
 * <ul>
 *   <li>{@code latency.mean}, {@code latency.min}, {@code latency.max}: exact, in ns;</li>
 *   <li>{@code latency.p50}, {@code latency.p90}, {@code latency.p99}, {@code latency.p99.9}, {@code latency.p99.99}:
 *       percentiles in ns, at most 0.1% above the exact value (see {@link LatencyHistogram});</li>
 *   <li>{@code latency.samples}: the number of recorded calls;</li>
 *   <li>{@code alloc.norm}: the bytes allocated per call, by the measured calls only;</li>
 *   <li>{@code measured.gc.discardedPasses}: the number of passes discarded because a garbage collection ran during
 *       their measured calls (see {@link LatencyRecorder}).</li>
 * </ul>
 *
 * <p>The latency results and {@code alloc.norm} are computed for each iteration, from the calls of that iteration.
 * JMH reports their average over all iterations and forks, with an error margin. {@code latency.p99} is therefore the
 * average of the iterations' p99 values, not the p99 of all calls of the run. A percentile needs enough calls per
 * iteration: p99.99 rests on the slowest 0.01% of them, which are only 100 of 1,000,000 calls. The counters
 * {@code latency.samples} and {@code measured.gc.discardedPasses} are summed over the run.
 *
 * <p>The latency results cover only passes without a garbage collection. JMH's primary score also includes the
 * discarded passes, so {@code latency.mean} is the average to track. An occasional discarded pass is not an error, but
 * if passes are discarded regularly, the heap is too small. If every pass of an iteration is discarded, the iteration
 * reports only {@code measured.gc.discardedPasses}.
 *
 * <h2>How it works</h2>
 *
 * <p>JMH creates the profiler itself and calls it before and after every iteration. It gives the benchmark no reference
 * to the profiler, so the two share only the static {@link #recorder()}:
 * <ol>
 *   <li>Before the iteration, the profiler creates an empty registry of recorders.</li>
 *   <li>During the iteration, each benchmark thread calls {@link #recorder()}, which returns the thread's recorder and
 *       registers it on the thread's first call and records its calls into it.</li>
 *   <li>After the iteration, the profiler combines the recorders of all threads, computes the results, and clears
 *       the registry.</li>
 * </ol>
 *
 * <h2>Running it</h2>
 *
 * <p>Pass the profiler to JMH with {@code -prof}. The Gradle tasks {@code jmhRun} and {@code jmhSmoke} add it for the
 * modules that configure it in {@code benchmarkRuns}. For example:
 *
 * <pre>
 * java -jar consensus-hashgraph-impl-*-jmh-merged.jar ConsensusImplBenchmark \
 *     -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler
 * </pre>
 *
 * <p>Without the profiler, {@link #recorder()} throws, so the benchmark fails instead of silently reporting no
 * percentiles. The profiler applies to every benchmark in the run. A benchmark that records nothing reports only
 * {@code measured.gc.discardedPasses}, which is zero.
 *
 * <h2>Several benchmark threads</h2>
 *
 * <p>With several benchmark threads ({@code @Threads}), each thread gets a recorder of its own from
 * {@link #recorder()}, and records without any synchronization. The registry is a concurrent map, so the threads can
 * register at the same time. JMH calls the profiler after the iteration only once all benchmark threads have
 * finished, which makes their recorded values visible to it. The results then cover the calls of all threads together:
 * the percentiles are those of all their calls, and {@code alloc.norm} is their total allocation divided by their total
 * number of calls.
 */
// The module requires JMH statically, without 'transitive': only JMH benchmarks use this class, and they depend on JMH
// themselves.
@SuppressWarnings("exports")
public final class LatencyProfiler implements InternalProfiler {

    /** A percentile to report, with its label and value. */
    private record Percentile(String label, double percentile) {}

    /** The reported percentiles, in the order of the results. */
    private static final List<Percentile> PERCENTILES = List.of(
            new Percentile("p50", 50),
            new Percentile("p90", 90),
            new Percentile("p99", 99),
            new Percentile("p99.9", 99.9),
            new Percentile("p99.99", 99.99));

    /** The recorders of the running iteration by thread, or {@code null} outside an iteration. */
    private static volatile Map<Thread, LatencyRecorder> recorders;

    /**
     * Returns the calling thread's recorder for the running iteration. Call it in a setup method that runs at least
     * once per iteration ({@code Level.Iteration} or {@code Level.Invocation}), outside the measured calls, and use the
     * recorder only on the calling thread. A recorder obtained in an earlier iteration is no longer reported.
     *
     * @return the recorder of the calling thread
     * @throws IllegalStateException if the benchmark runs without this profiler
     */
    @NonNull
    public static LatencyRecorder recorder() {
        final Map<Thread, LatencyRecorder> current = recorders;
        if (current == null) {
            throw new IllegalStateException("This benchmark reports its latency through a profiler. Run it with -prof "
                    + LatencyProfiler.class.getName());
        }
        return current.computeIfAbsent(Thread.currentThread(), _ -> new LatencyRecorder());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getDescription() {
        return "Per-call latency percentiles and allocation recorded by the benchmark";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void beforeIteration(final BenchmarkParams benchmarkParams, final IterationParams iterationParams) {
        recorders = new ConcurrentHashMap<>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<? extends Result<?>> afterIteration(
            @NonNull final BenchmarkParams benchmarkParams,
            @NonNull final IterationParams iterationParams,
            @NonNull final IterationResult result) {
        // JMH calls this after all benchmark threads have finished the iteration.
        final Map<Thread, LatencyRecorder> finished = recorders;
        recorders = null;

        final LatencyHistogram histogram = LatencyRecorder.newHistogram();
        long allocatedBytes = 0;
        long discardedPasses = 0;
        for (final LatencyRecorder recorder : finished.values()) {
            histogram.add(recorder.histogram());
            allocatedBytes += recorder.allocatedBytes();
            discardedPasses += recorder.discardedPasses();
        }

        final List<Result<?>> results = new ArrayList<>();
        final long calls = histogram.totalCount();
        if (calls > 0) {
            results.add(latency("mean", histogram.mean()));
            results.add(latency("min", histogram.min()));
            for (final Percentile percentile : PERCENTILES) {
                results.add(latency(percentile.label(), histogram.valueAtPercentile(percentile.percentile())));
            }
            results.add(latency("max", histogram.max()));
            results.add(new ScalarResult("latency.samples", calls, "#", AggregationPolicy.SUM));
            results.add(new ScalarResult("alloc.norm", (double) allocatedBytes / calls, "B/op", AggregationPolicy.AVG));
        }
        // reported even when every pass was discarded, so that such an iteration stays visible
        results.add(new ScalarResult("measured.gc.discardedPasses", discardedPasses, "#", AggregationPolicy.SUM));
        return results;
    }

    @NonNull
    private static ScalarResult latency(@NonNull final String statistic, final double nanos) {
        return new ScalarResult("latency." + statistic, nanos, "ns", AggregationPolicy.AVG);
    }
}
