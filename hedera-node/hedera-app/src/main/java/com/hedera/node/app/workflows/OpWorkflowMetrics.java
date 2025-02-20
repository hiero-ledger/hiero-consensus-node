// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.StatsConfig;
import com.swirlds.common.metrics.IntegerPairAccumulator;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.RunningAverageMetric.Config;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.IntegerAccumulator;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.BinaryOperator;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A class to handle the metrics for all operations (transactions and queries)
 */
@Singleton
public class OpWorkflowMetrics {

    private static final BinaryOperator<Integer> AVERAGE = (sum, count) -> count == 0 ? 0 : sum / count;

    private static final Config GAS_PER_CONS_SEC_CONFIG = new Config("app", "gasPerConsSec")
            .withDescription("average EVM gas used per second of consensus time")
            .withFormat("%,13.6f");

    private final Map<HederaFunctionality, TransactionMetric> transactionDurationMetrics =
            new EnumMap<>(HederaFunctionality.class);

    private final Map<HederaFunctionality, Counter> transactionThrottleMetrics =
            new EnumMap<>(HederaFunctionality.class);

    private final RunningAverageMetric gasPerConsSec;

    private long gasUsedThisConsensusSecond = 0L;

    /**
     * Constructor for the OpWorkflowMetrics
     *
     * @param metrics the {@link Metrics} object where all metrics will be registered
     */
    @Inject
    public OpWorkflowMetrics(@NonNull final Metrics metrics, @NonNull final ConfigProvider configProvider) {
        requireNonNull(metrics, "metrics must not be null");
        requireNonNull(configProvider, "configProvider must not be null");

        for (final var functionality : HederaFunctionality.values()) {
            if (functionality == HederaFunctionality.NONE) {
                continue;
            }
            final var protoName = functionality.protoName();
            final var name = protoName.substring(0, 1).toLowerCase() + protoName.substring(1);

            // initialize the transaction duration metrics
            final var maxConfig = new IntegerAccumulator.Config("app", name + "DurationMax")
                    .withDescription("The maximum duration of a " + name + " transaction in nanoseconds")
                    .withUnit("ns");
            final var maxMetric = metrics.getOrCreate(maxConfig);
            final var avgConfig = new IntegerPairAccumulator.Config<>(
                            "app", name + "DurationAvg", Integer.class, AVERAGE)
                    .withDescription("The average duration of a " + name + " transaction in nanoseconds")
                    .withUnit("ns");
            final var avgMetric = metrics.getOrCreate(avgConfig);
            transactionDurationMetrics.put(functionality, new TransactionMetric(maxMetric, avgMetric));

            // initialize the transaction throttle metrics
            final var throttledConfig = new Counter.Config("app", name + "ThrottledTxns")
                    .withDescription(
                            "The number of " + name + " transactions that were rejected due to throttle limits");
            transactionThrottleMetrics.put(functionality, metrics.getOrCreate(throttledConfig));
        }

        final StatsConfig statsConfig = configProvider.getConfiguration().getConfigData(StatsConfig.class);
        gasPerConsSec = metrics.getOrCreate(GAS_PER_CONS_SEC_CONFIG.withHalfLife(statsConfig.runningAvgHalfLifeSecs()));
    }

    /**
     * Update the transaction duration metrics for the given functionality
     *
     * @param functionality the {@link HederaFunctionality} for which the metrics will be updated
     * @param duration the duration of the operation in {@code ns}
     */
    public void updateDuration(@NonNull final HederaFunctionality functionality, final int duration) {
        requireNonNull(functionality, "functionality must not be null");
        if (functionality == HederaFunctionality.NONE) {
            return;
        }
        final var metric = transactionDurationMetrics.get(functionality);
        if (metric != null) {
            // We do not synchronize the update of the metrics. This may lead to a situation where the max value is
            // is stored in one reporting interval and the average in another. This is acceptable as synchronizing
            // the updates would introduce a severe performance penalty.
            metric.max.update(duration);
            metric.avg.update(duration, 1);
        }
    }

    /**
     * Increment the throttled metrics for the given functionality, to track the number of transactions per second that
     * failed due to throttling
     *
     * @param functionality the {@link HederaFunctionality} for which the throttled metrics will be updated
     */
    public void incrementThrottled(@NonNull final HederaFunctionality functionality) {
        requireNonNull(functionality, "functionality must not be null");
        if (functionality == HederaFunctionality.NONE) {
            return;
        }
        final var metric = transactionThrottleMetrics.get(functionality);
        if (metric != null) {
            metric.increment();
        }
    }

    public void switchConsensusSecond() {
        gasPerConsSec.update(gasUsedThisConsensusSecond);
        gasUsedThisConsensusSecond = 0L;
    }

    public void addGasUsed(final long gasUsed) {
        gasUsedThisConsensusSecond += gasUsed;
    }

    private record TransactionMetric(IntegerAccumulator max, IntegerPairAccumulator<Integer> avg) {}
}
