// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.pipeline;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.hiero.consensus.benchmark.tools.histogram.LatencyHistogram;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.wiring.framework.model.TraceableWiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.schedulers.TaskScheduler;

/**
 * TEMPORARY: the experiment of #27832, removed before the pull request. Records, for every pass of a
 * {@link PipelineTimer}, what the experiment compares, and prints it: one line starting with {@code PASS} per pass and
 * one starting with {@code ITER} per iteration.
 *
 * <ul>
 *   <li>garbage collections during the pass;</li>
 *   <li>the bytes all threads allocated, per input;</li>
 *   <li>the latency trend: the median latency of the last quarter of the inputs against the first, per second between
 *       them;</li>
 *   <li>the queue trend: the summed unprocessed tasks of the schedulers, sampled once per ms, last quarter against
 *       first, per second between them;</li>
 *   <li>the longest unhealthy duration the wiring model's health monitor reported;</li>
 *   <li>the counted inputs that did not arrive.</li>
 * </ul>
 *
 * <p>Use: build the timer with {@code experimentSampler(() -> probe.sample())}, create the probe before the model
 * starts (it attaches to the health monitor), and call {@link #startIteration}, {@link #beforePass}, {@link #afterPass},
 * and {@link #endIteration} from the benchmark.
 */
public final class ExperimentProbe {

    /** Room for the queue samples of a pass of up to about 17 minutes, at one per ms. */
    private static final int MAX_SAMPLES = 1 << 20;

    private final String label;
    private final PipelineTimer<?> timer;
    private final int expectedArrivals;
    private final List<TaskScheduler<?>> schedulers;
    private final long[] dueNanos;
    private final boolean[] counted;

    private final long[] sampleNanos = new long[MAX_SAMPLES];
    private final long[] sampleQueued = new long[MAX_SAMPLES];
    private int samples;
    private long passStart;
    private long[] gcBefore;

    /** The longest unhealthy duration the health monitor reported during the pass, in ns. */
    private volatile long maxUnhealthyNanos;

    private String iterationType = "?";
    private int iteration;
    private int pass;
    private int passes;
    private int gcPasses;
    private final LatencyHistogram iterationAll = LatencyRecorder.newHistogram();
    private final LatencyHistogram iterationWithoutGc = LatencyRecorder.newHistogram();

    /**
     * Creates a probe. Call it before the model starts.
     *
     * @param label the parameters printed with every line, as {@code key=value} pairs
     * @param timer the timer whose passes are probed
     * @param expectedArrivals the counted inputs expected at the output in every pass
     * @param schedulers the schedulers whose unprocessed tasks are sampled
     * @param model the wiring model whose health monitor is watched
     */
    public ExperimentProbe(
            @NonNull final String label,
            @NonNull final PipelineTimer<?> timer,
            final int expectedArrivals,
            @NonNull final List<TaskScheduler<?>> schedulers,
            @NonNull final WiringModel model) {
        this.label = requireNonNull(label);
        this.timer = requireNonNull(timer);
        this.expectedArrivals = expectedArrivals;
        this.schedulers = List.copyOf(schedulers);
        this.dueNanos = timer.experimentDueNanos();
        this.counted = timer.experimentCounted();
        model.getHealthMonitorWire().solderForMonitoring(duration -> {
            final long nanos = duration.toNanos();
            if (nanos > maxUnhealthyNanos) {
                maxUnhealthyNanos = nanos;
            }
        });
    }

    /**
     * Returns all schedulers of a wiring model, by reflection ({@code TraceableWiringModel#schedulers}).
     *
     * @param model the model
     * @return its schedulers
     */
    @NonNull
    public static List<TaskScheduler<?>> schedulersOf(@NonNull final WiringModel model) {
        try {
            final Field field = TraceableWiringModel.class.getDeclaredField("schedulers");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            final List<TaskScheduler<?>> all = (List<TaskScheduler<?>>) field.get(model);
            return List.copyOf(all);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read the schedulers of the wiring model", e);
        }
    }

    /**
     * Starts an iteration.
     *
     * @param type the iteration's type, warmup or measurement
     */
    public void startIteration(@NonNull final String type) {
        iterationType = type;
        iteration++;
        pass = 0;
        passes = 0;
        gcPasses = 0;
        iterationAll.reset();
        iterationWithoutGc.reset();
    }

    /** Samples the unprocessed tasks of the schedulers; the timer runs it once per ms during a pass. */
    public void sample() {
        if (samples == MAX_SAMPLES) {
            return;
        }
        final long now = System.nanoTime();
        long queued = 0;
        for (final TaskScheduler<?> scheduler : schedulers) {
            queued += Math.max(0, scheduler.getUnprocessedTaskCount());
        }
        sampleNanos[samples] = now - passStart;
        sampleQueued[samples] = queued;
        samples++;
    }

    /** Call it right before {@link PipelineTimer#replay()}. */
    public void beforePass() {
        samples = 0;
        maxUnhealthyNanos = 0;
        gcBefore = gcCounts();
        passStart = System.nanoTime();
    }

    /** Call it right after {@link PipelineTimer#replay()}: prints the pass's line. */
    public void afterPass() {
        final long passNanos = System.nanoTime() - passStart;
        final long[] gcAfter = gcCounts();
        final long gcPauses = gcAfter[0] - gcBefore[0];
        final long gcPauseMillis = gcAfter[1] - gcBefore[1];
        final long gcConcurrent = gcAfter[2] - gcBefore[2];

        final long[] latencies = timer.experimentPassLatencies();
        final LatencyHistogram passHistogram = LatencyRecorder.newHistogram();
        final long span = dueNanos[dueNanos.length - 1];
        final List<Long> first = new ArrayList<>();
        final List<Long> last = new ArrayList<>();
        int missing = 0;
        long firstMissingDue = -1;
        long lastMissingDue = -1;
        for (int i = 0; i < latencies.length; i++) {
            if (latencies[i] < 0 && counted[i]) {
                missing++;
                if (firstMissingDue < 0) {
                    firstMissingDue = dueNanos[i];
                }
                lastMissingDue = dueNanos[i];
            }
            if (latencies[i] >= 0) {
                passHistogram.record(latencies[i]);
                if (dueNanos[i] < span / 4) {
                    first.add(latencies[i]);
                } else if (dueNanos[i] >= span - span / 4) {
                    last.add(latencies[i]);
                }
            }
        }
        // the quarters' centers are 3/4 of the span apart
        final double quarterDistanceSeconds = 0.75 * span / 1e9;
        final double firstMedian = median(first);
        final double lastMedian = median(last);

        double queueFirst = 0;
        double queueLast = 0;
        int queueFirstCount = 0;
        int queueLastCount = 0;
        long queueMax = 0;
        for (int i = 0; i < samples; i++) {
            queueMax = Math.max(queueMax, sampleQueued[i]);
            if (sampleNanos[i] < span / 4) {
                queueFirst += sampleQueued[i];
                queueFirstCount++;
            } else if (sampleNanos[i] >= span - span / 4) {
                queueLast += sampleQueued[i];
                queueLastCount++;
            }
        }
        queueFirst = queueFirstCount == 0 ? Double.NaN : queueFirst / queueFirstCount;
        queueLast = queueLastCount == 0 ? Double.NaN : queueLast / queueLastCount;

        pass++;
        passes++;
        iterationAll.add(passHistogram);
        if (gcPauses == 0) {
            iterationWithoutGc.add(passHistogram);
        } else {
            gcPasses++;
        }
        System.out.printf(
                Locale.ROOT,
                "PASS %s pid=%d iteration=%d type=%s pass=%d passMs=%.1f inputs=%d arrivals=%d expected=%d"
                        + " gcPauses=%d gcPauseMs=%d gcConcurrent=%d allocBytes=%d allocPerInput=%.1f"
                        + " %s latFirstMedianUs=%.1f latLastMedianUs=%.1f latTrendUsPerS=%.2f"
                        + " queueFirst=%.1f queueLast=%.1f queueMax=%d queueTrendPerS=%.2f queueSamples=%d"
                        + " maxUnhealthyMs=%.3f missing=%d missingDueMs=%.1f..%.1f%n",
                label,
                ProcessHandle.current().pid(),
                iteration,
                iterationType,
                pass,
                passNanos / 1e6,
                latencies.length,
                timer.arrivals(),
                expectedArrivals,
                gcPauses,
                gcPauseMillis,
                gcConcurrent,
                timer.experimentPassAllocatedBytes(),
                (double) timer.experimentPassAllocatedBytes() / latencies.length,
                summary(passHistogram),
                firstMedian / 1e3,
                lastMedian / 1e3,
                (lastMedian - firstMedian) / 1e3 / quarterDistanceSeconds,
                queueFirst,
                queueLast,
                queueMax,
                (queueLast - queueFirst) / quarterDistanceSeconds,
                samples,
                maxUnhealthyNanos / 1e6,
                missing,
                firstMissingDue / 1e6,
                lastMissingDue / 1e6);
    }

    /** Prints the iteration's line: its percentiles over all passes and over the passes without a collection. */
    public void endIteration() {
        System.out.printf(
                Locale.ROOT,
                "ITER %s pid=%d iteration=%d type=%s passes=%d gcPasses=%d all=[%s] withoutGc=[%s]%n",
                label,
                ProcessHandle.current().pid(),
                iteration,
                iterationType,
                passes,
                gcPasses,
                summary(iterationAll),
                summary(iterationWithoutGc));
    }

    /** Returns the pause collections, their total time in ms, and the concurrent cycles, of all collectors. */
    @NonNull
    private static long[] gcCounts() {
        long pauses = 0;
        long pauseMillis = 0;
        long concurrent = 0;
        for (final GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getName().contains("Concurrent")) {
                concurrent += Math.max(0, bean.getCollectionCount());
            } else {
                pauses += Math.max(0, bean.getCollectionCount());
                pauseMillis += Math.max(0, bean.getCollectionTime());
            }
        }
        return new long[] {pauses, pauseMillis, concurrent};
    }

    @NonNull
    private static String summary(@NonNull final LatencyHistogram histogram) {
        if (histogram.totalCount() == 0) {
            return "n=0";
        }
        return String.format(
                Locale.ROOT,
                "n=%d meanUs=%.1f p50Us=%.1f p90Us=%.1f p99Us=%.1f p999Us=%.1f maxUs=%.1f",
                histogram.totalCount(),
                histogram.mean() / 1e3,
                histogram.valueAtPercentile(50) / 1e3,
                histogram.valueAtPercentile(90) / 1e3,
                histogram.valueAtPercentile(99) / 1e3,
                histogram.valueAtPercentile(99.9) / 1e3,
                histogram.max() / 1e3);
    }

    private static double median(@NonNull final List<Long> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        final long[] sorted = values.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
