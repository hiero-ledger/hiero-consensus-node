// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.results.Result;

/**
 * Checks that {@link LatencyProfiler} reports a percentile only if the iteration recorded at least 100 calls above it,
 * and names the skipped percentiles in {@link LatencyProfiler#SKIPPED}.
 */
class LatencyProfilerTest {

    @Test
    void reportsEveryPercentileWithEnoughCalls() {
        final Map<String, Result<?>> results = runIteration(1_000_000);

        assertThat(results)
                .containsKeys(
                        "latency.p50", "latency.p90", "latency.p99", "latency.p99.9", "latency.p99.99", "latency.max")
                .doesNotContainKey(LatencyProfiler.SKIPPED);
    }

    @Test
    void skipsThePercentilesWithTooFewCalls() {
        final Map<String, Result<?>> results = runIteration(99_999);

        assertThat(results)
                .containsKeys("latency.p50", "latency.p90", "latency.p99", "latency.max")
                .doesNotContainKeys("latency.p99.9", "latency.p99.99");
        assertThat(results.get(LatencyProfiler.SKIPPED).extendedInfo())
                .isEqualTo("99999 calls, too few for p99.9 (needs 100000), p99.99 (needs 1000000)"
                        + System.lineSeparator());
    }

    @Test
    void skipsEveryPercentileWithFewCalls() {
        final Map<String, Result<?>> results = runIteration(10);

        assertThat(results).containsKeys("latency.mean", "latency.min", "latency.max", "latency.samples");
        assertThat(results.keySet()).noneMatch(label -> label.startsWith("latency.p"));
        assertThat(results.get(LatencyProfiler.SKIPPED).extendedInfo())
                .startsWith("10 calls, too few for p50 (needs 200)");
    }

    /** Runs one iteration that records the given number of calls and returns its results by label. */
    private static Map<String, Result<?>> runIteration(final int calls) {
        final LatencyProfiler profiler = new LatencyProfiler();
        profiler.beforeIteration(null, null);
        final LatencyRecorder recorder = LatencyProfiler.recorder();
        recorder.startMeasurement();
        for (int i = 0; i < calls; i++) {
            recorder.lap();
        }
        recorder.stopMeasurement();
        final List<? extends Result<?>> results = profiler.afterIteration(null, null, null);

        assertThat(recorder.discardedPasses())
                .as("a garbage collection discarded the pass")
                .isZero();
        return results.stream().collect(Collectors.toMap(Result::getLabel, Function.identity()));
    }
}
