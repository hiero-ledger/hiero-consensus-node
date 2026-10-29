// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;
import java.util.List;

/**
 * Configuration of the latency metrics of the consensus layer.
 * <p>
 * A latency window should hold about 100 values beyond its highest percentile {@code p}, so it needs a length of at
 * least {@code 100 / ((1 - p) * rate)}. A node creates its own events at up to {@code event.creation.maxCreationRate}
 * per second, and in a network of {@code N} nodes a stage that all events pass sees up to {@code N} times as many. The
 * defaults assume the default rate of 20 per second and 30 nodes: p99.9 of all events needs at least 2.8 minutes, p99
 * of own events alone at least 8.3 minutes. The rate of transactions is set by the load instead: p90 needs at least
 * 3.3 transactions per second on the node for the default window of 5 minutes. A longer window costs no memory, but
 * reacts more slowly.
 * <p>
 * Each window has its own list of percentiles. Changing a list adds or removes gauges, as each percentile is a gauge
 * named after it, such as {@code eventLatency_R2C_p99_9}.
 *
 * @param averageAndMaxEnabled          if {@code true}, the platform will collect and report the average and maximum
 *                                      delay of events at each stage of the event pipeline
 * @param latencyEnabled                if {@code true}, the platform will collect and report percentiles and the
 *                                      maximum, over a sliding window, of the delay of events at each stage of the
 *                                      event pipeline, of {@code eventLatency_R2C}, and of the latency of transactions
 * @param latencyWindow                 the length of the sliding window of stages that gossiped events pass and of
 *                                      {@code eventLatency_R2C}
 * @param latencyPercentiles            the percentiles reported over {@code latencyWindow}, each above 0 and below 100
 * @param selfEventsLatencyWindow       the length of the sliding window of stages that only the node's self events
 *                                      pass
 * @param selfEventsLatencyPercentiles  the percentiles reported over {@code selfEventsLatencyWindow}, each above 0 and
 *                                      below 100
 * @param transactionLatencyWindow      the length of the sliding window of {@code transactionLatency_total} and
 *                                      {@code transactionLatency_poolWait}
 * @param transactionLatencyPercentiles the percentiles reported over {@code transactionLatencyWindow}, each above 0
 *                                      and below 100
 */
@ConfigData("event.pipeline.metrics")
public record ConsensusLayerMetricsConfig(
        @ConfigProperty(defaultValue = "true") boolean averageAndMaxEnabled,
        @ConfigProperty(defaultValue = "true") boolean latencyEnabled,
        @ConfigProperty(defaultValue = "5m") Duration latencyWindow,
        @ConfigProperty(defaultValue = "50,90,99,99.9") List<Double> latencyPercentiles,
        @ConfigProperty(defaultValue = "10m") Duration selfEventsLatencyWindow,
        @ConfigProperty(defaultValue = "50,90,99") List<Double> selfEventsLatencyPercentiles,
        @ConfigProperty(defaultValue = "5m") Duration transactionLatencyWindow,
        @ConfigProperty(defaultValue = "50,90") List<Double> transactionLatencyPercentiles) {}
