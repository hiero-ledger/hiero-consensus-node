// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.hiero.consensus.benchmark.tools.histogram.LatencyHistogram;
import org.junit.jupiter.api.Test;

/**
 * Tests the latency statistics and the reports of {@link SimulationResult}. The layout of the reports must stay
 * identical to the one of the Falcon counterpart of this harness, so that their outputs can be compared.
 */
class SimulationResultTest {

    @Test
    void csvHeaderNamesTheKeyColumnAndOneColumnPerStatistic() {
        assertEquals(
                "NumNodes;meanC2C;p50C2C;p90C2C;p99C2C;p99.9C2C;maxC2C;events/s;bytes/s",
                SimulationResult.csvHeader("NumNodes"));
    }

    @Test
    void csvRowReportsLatenciesInSecondsFollowedByTheThroughput() {
        // Equal values make every percentile exact: a percentile is never reported above the maximum
        final SimulationResult result = new SimulationResult(4, histogramOf(1_500_000, 1_500_000, 1_500_000), 20, 3000);

        assertEquals("4;0.001500;0.001500;0.001500;0.001500;0.001500;0.001500;20;3000", result.toCsvRow("4"));
    }

    @Test
    void statisticsAreTakenFromTheHistogram() {
        // C2C values of 90, 100 and 150ms, as three events of a round would produce
        final SimulationResult result =
                new SimulationResult(4, histogramOf(90_000_000L, 100_000_000L, 150_000_000L), 20, 3000);

        assertEquals(3, result.c2cSamples());
        assertEquals(Duration.ofMillis(90), result.minC2C());
        assertEquals(Duration.ofNanos(340_000_000L / 3), result.meanC2C());
        assertEquals(Duration.ofMillis(150), result.maxC2C());
        assertEquals(Duration.ofMillis(150), result.c2cPercentile(100));
    }

    @Test
    void emptyResultReportsZeroLatencies() {
        final SimulationResult result = new SimulationResult(4, histogramOf(), 0, 0);

        assertEquals(0, result.c2cSamples());
        assertEquals(Duration.ZERO, result.minC2C());
        assertEquals(Duration.ZERO, result.meanC2C());
        assertEquals(Duration.ZERO, result.c2cPercentile(99));
        assertEquals(Duration.ZERO, result.maxC2C());
        assertEquals("4;0.000000;0.000000;0.000000;0.000000;0.000000;0.000000;0;0", result.toCsvRow("4"));
        assertTrue(result.toString().contains("C2C samples:  0"));
    }

    private static LatencyHistogram histogramOf(final long... nanos) {
        final LatencyHistogram histogram = new LatencyHistogram(10, 36);
        for (final long value : nanos) {
            histogram.record(value);
        }
        return histogram;
    }
}
