// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.hiero.consensus.benchmark.tools.histogram.LatencyHistogram;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the reports of {@link ConsensusLatencyResult}.
 */
@DisplayName("ConsensusLatencyResult Test")
class ConsensusLatencyResultTest {

    @Test
    @DisplayName("The CSV header names the key column and one column per reported statistic")
    void csvHeader() {
        assertThat(ConsensusLatencyResult.csvHeader("NumNodes"))
                .isEqualTo("NumNodes;meanC2C;p50C2C;p90C2C;p99C2C;p99.9C2C;maxC2C;events/s;bytes/s");
    }

    @Test
    @DisplayName("A CSV row reports latencies in seconds, followed by the throughput")
    void csvRow() {
        // Equal values make every percentile exact: a percentile is never reported above the maximum
        final LatencyHistogram c2c = histogramOf(1_500_000, 1_500_000, 1_500_000);
        final ConsensusLatencyResult result = new ConsensusLatencyResult(4, c2c, 20, 3_000, Map.of());

        assertThat(result.toCsvRow("4")).isEqualTo("4;0.001500;0.001500;0.001500;0.001500;0.001500;0.001500;20;3000");
    }

    @Test
    @DisplayName("A CSV row of a result without events reports zero latencies")
    void csvRowOfEmptyResult() {
        final ConsensusLatencyResult result = new ConsensusLatencyResult(4, histogramOf(), 0, 0, Map.of());

        assertThat(result.toCsvRow("4")).isEqualTo("4;0.000000;0.000000;0.000000;0.000000;0.000000;0.000000;0;0");
    }

    @Test
    @DisplayName("Seconds are formatted at microsecond precision with a decimal point")
    void secondsFormatting() {
        assertThat(ConsensusLatencyResult.seconds(Duration.ZERO)).isEqualTo("0.000000");
        assertThat(ConsensusLatencyResult.seconds(Duration.ofNanos(1_234_567))).isEqualTo("0.001235");
        assertThat(ConsensusLatencyResult.seconds(Duration.ofSeconds(12, 500_000_000)))
                .isEqualTo("12.500000");
    }

    @Test
    @DisplayName("The summary lists the statistics of the distribution")
    void summary() {
        final ConsensusLatencyResult result =
                new ConsensusLatencyResult(4, histogramOf(1_000_000, 3_000_000), 20, 3_000, Map.of());

        final String summary = result.toString();

        assertThat(summary)
                .contains("Num nodes:    4")
                .contains("C2C samples:  2")
                .contains("C2C min:      PT0.001S")
                .contains("C2C mean:     PT0.002S")
                .contains("C2C p50:")
                .contains("C2C p90:")
                .contains("C2C p99:")
                .contains("C2C p99.9:")
                .contains("C2C max:      PT0.003S")
                .contains("Ev/sec:       20")
                .contains("Bytes/sec:    3,000");
    }

    /**
     * Creates a histogram with the layout used by the recorder and records the given latencies in it.
     *
     * @param nanos the latencies in nanoseconds
     * @return the histogram
     */
    private static LatencyHistogram histogramOf(final long... nanos) {
        final LatencyHistogram histogram =
                new LatencyHistogram(ConsensusLatencyRecorder.PRECISION_BITS, ConsensusLatencyRecorder.RANGE_BITS);
        for (final long value : nanos) {
            histogram.record(value);
        }
        return histogram;
    }
}
