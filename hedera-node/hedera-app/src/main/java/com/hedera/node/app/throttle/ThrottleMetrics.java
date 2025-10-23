// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hapi.utils.throttles.CongestibleThrottle;
import com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle;
import com.hedera.node.app.hapi.utils.throttles.LeakyBucketDeterministicThrottle;
import com.hedera.node.app.hapi.utils.throttles.OpsDurationDeterministicThrottle;
import com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType;
import com.hedera.node.config.data.StatsConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.DoubleGauge.Config;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class maintains all metrics related to a {@link ThrottleAccumulator}.
 */
public class ThrottleMetrics {

    private static final Logger log = LogManager.getLogger(ThrottleMetrics.class);

    private static final String GAS_THROTTLE_ID = "<GAS>";
    private static final String BYTES_THROTTLE_ID = "<GAS>";
    private static final String OPS_DURATION_ID = "<OPS_DURATION>";

    private final Metrics metrics;
    private final String nameTemplate;
    private final String descriptionTemplate;
    private final Function<StatsConfig, List<String>> throttlesToSampleSupplier;
    private List<MetricPair> liveMetricPairs = List.of();
    private MetricPair gasThrottleMetricPair;
    private MetricPair opsDurationThrottleMetricPair;

    /**
     * Constructs a {@link ThrottleMetrics} instance.
     *
     * @param metrics the {@link Metrics} instance to use for registering metrics
     * @param throttleType the type of throttle to maintain metrics for
     */
    public ThrottleMetrics(@NonNull final Metrics metrics, @NonNull final ThrottleType throttleType) {
        this.metrics = requireNonNull(metrics);
        final var typePrefix = requireNonNull(throttleType) == ThrottleType.FRONTEND_THROTTLE ? "HAPI" : "cons";
        this.nameTemplate = typePrefix.toLowerCase() + "%sPercentUsed";
        this.descriptionTemplate = "instantaneous %% used in " + typePrefix + " %s throttle bucket";
        this.throttlesToSampleSupplier = throttleType == ThrottleType.FRONTEND_THROTTLE
                ? StatsConfig::hapiThrottlesToSample
                : StatsConfig::consThrottlesToSample;
    }

    /**
     * Sets up all metrics for the given throttles.
     *
     * @param throttles the throttles to set up metrics for
     * @param configuration the configuration that specifies which throttles should be monitored
     */
    public void setupThrottleMetrics(
            @NonNull final List<DeterministicThrottle> throttles, @NonNull final Configuration configuration) {
        final var statsConfig = configuration.getConfigData(StatsConfig.class);
        final var throttlesToSample = throttlesToSampleSupplier.apply(statsConfig);

        liveMetricPairs = throttles.stream()
                .filter(throttle -> throttlesToSample.contains(throttle.name()))
                .map(this::setupLiveMetricPair)
                .toList();

        // If there is a configured throttle to sample, that doesn't have a corresponding
        // DeterministicThrottle live metric created (not sure when would that be the case?) we add it
        // as an "inert" metric, that never changes.
        // Unless it's a GAS or OPS_DURATION throttle - we don't include those.
        final var throttlesExcludedFromInertMetrics = Set.of(GAS_THROTTLE_ID, OPS_DURATION_ID);
        final var throttleNames =
                throttles.stream().map(DeterministicThrottle::name).collect(Collectors.toSet());
        throttlesToSample.stream()
                .filter(name -> !throttleNames.contains(name) && !throttlesExcludedFromInertMetrics.contains(name))
                .forEach(this::setupInertMetric);
    }

    /**
     * Sets up the gas throttle metric.
     *
     * @param gasThrottle the gas throttle to set up the metric for
     * @param configuration the configuration that specifies which throttles should be monitored
     */
    public void setupGasThrottleMetric(
            @NonNull final LeakyBucketDeterministicThrottle gasThrottle, @NonNull final Configuration configuration) {
        final var statsConfig = configuration.getConfigData(StatsConfig.class);
        final var throttlesToSample = throttlesToSampleSupplier.apply(statsConfig);

        gasThrottleMetricPair = throttlesToSample.contains(GAS_THROTTLE_ID) ? setupLiveMetricPair(gasThrottle) : null;
    }

    /**
     * Sets up the bytes throttle metric.
     *
     * @param bytesThrottle the bytes throttle to set up the metric for
     * @param configuration the configuration that specifies which throttles should be monitored
     */
    public void setupBytesThrottleMetric(
            @NonNull final LeakyBucketDeterministicThrottle bytesThrottle, @NonNull final Configuration configuration) {
        final var statsConfig = configuration.getConfigData(StatsConfig.class);
        final var throttlesToSample = throttlesToSampleSupplier.apply(statsConfig);

        gasThrottleMetricPair =
                throttlesToSample.contains(BYTES_THROTTLE_ID) ? setupLiveMetricPair(bytesThrottle) : null;
    }

    public void setupOpsDurationMetric(
            @NonNull final OpsDurationDeterministicThrottle opsDurationThrottle,
            @NonNull final Configuration configuration) {
        final var statsConfig = configuration.getConfigData(StatsConfig.class);
        final var throttlesToSample = throttlesToSampleSupplier.apply(statsConfig);
        opsDurationThrottleMetricPair =
                throttlesToSample.contains(OPS_DURATION_ID) ? setupLiveMetricPair(opsDurationThrottle) : null;
    }

    /**
     * Updates all metrics for the given throttles.
     */
    public void updateAllMetrics() {
        for (final var metricPair : liveMetricPairs) {
            metricPair.gauge().set(metricPair.throttle().instantaneousPercentUsed());
        }
        if (gasThrottleMetricPair != null) {
            gasThrottleMetricPair.gauge().set(gasThrottleMetricPair.throttle().instantaneousPercentUsed());
        }
        if (opsDurationThrottleMetricPair != null) {
            opsDurationThrottleMetricPair
                    .gauge()
                    .set(opsDurationThrottleMetricPair.throttle().instantaneousPercentUsed());
        }
    }

    private MetricPair setupLiveMetricPair(@NonNull final CongestibleThrottle throttle) {
        final var gauge = setupMetric(throttle.name(), "LIVE");
        return new MetricPair(throttle, gauge);
    }

    private void setupInertMetric(@NonNull final String throttleName) {
        setupMetric(throttleName, "INERT");
    }

    private DoubleGauge setupMetric(@NonNull final String throttleName, @NonNull final String status) {
        final var name = String.format(nameTemplate, throttleName);
        final var description = String.format(descriptionTemplate, throttleName);
        final var config = new Config("app", name).withDescription(description).withFormat("%,13.2f");
        final var gauge = metrics.getOrCreate(config);
        log.info("Registered {} gauge for '{}' under name '{}'", status, description, name);
        return gauge;
    }

    private record MetricPair(CongestibleThrottle throttle, DoubleGauge gauge) {}
}
