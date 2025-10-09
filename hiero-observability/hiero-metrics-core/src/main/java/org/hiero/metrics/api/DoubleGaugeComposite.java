// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import static org.hiero.metrics.api.stat.StatUtils.DEFAULT_STAT_LABEL;
import static org.hiero.metrics.api.stat.StatUtils.NO_DEFAULT_INITIALIZER;
import static org.hiero.metrics.api.stat.StatUtils.ZERO;

import com.swirlds.base.ArgumentUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Supplier;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.StatefulMetric;
import org.hiero.metrics.api.datapoint.DoubleGaugeCompositeDataPoint;
import org.hiero.metrics.api.datapoint.DoubleGaugeDataPoint;
import org.hiero.metrics.api.stat.StatUtils;
import org.hiero.metrics.api.utils.MetricUtils;
import org.hiero.metrics.internal.DefaultDoubleGaugeComposite;
import org.hiero.metrics.internal.datapoint.AtomicDoubleGaugeDataPoint;
import org.hiero.metrics.internal.datapoint.DoubleAccumulatorGaugeDataPoint;
import org.hiero.metrics.internal.datapoint.DoubleGaugeCompositeArrayDataPoint;

/**
 * A stateful metric of type {@link MetricType#GAUGE} that holds {@link DoubleGaugeCompositeDataPoint} per label set.
 * General use case is to track multiple related statistics (e.g. min, max, sum, count, latest) for the same metric
 * without creating multiple individual metrics. <br>
 * If custom logic is not required to handle observed values, then consider using {@link StatsGaugeAdapter}.
 * <p>
 * On export each {@link DoubleGaugeDataPoint} within {@link DoubleGaugeCompositeDataPoint} has additional label
 * to be identified - see {@link Builder} for details
 */
public interface DoubleGaugeComposite extends StatefulMetric<Object, DoubleGaugeCompositeDataPoint> {

    /**
     * Create a metric key for a {@link DoubleGaugeComposite} with the given name.<br>
     * See {@link MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    static MetricKey<DoubleGaugeComposite> key(@NonNull String name) {
        return MetricKey.of(name, DoubleGaugeComposite.class);
    }

    /**
     * Create a builder for a {@link DoubleGaugeComposite} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull MetricKey<DoubleGaugeComposite> key) {
        return new Builder(key);
    }

    /**
     * Create a builder for a {@link DoubleGaugeComposite} with the given metric name. <br>
     * See {@link MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name the metric name
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull String name) {
        return builder(key(name));
    }

    /**
     * A builder for a {@link DoubleGaugeComposite} using {@link DoubleGaugeCompositeArrayDataPoint} per label set.
     * <p>
     * Default additional label for export is {@value StatUtils#DEFAULT_STAT_LABEL}, and can be changed via
     * {@link #withStatLabel(String)}.
     */
    final class Builder
            extends StatefulMetric.Builder<Object, DoubleGaugeCompositeDataPoint, Builder, DoubleGaugeComposite> {

        private String statLabel = DEFAULT_STAT_LABEL;
        private final List<String> statNames = new ArrayList<>();
        private final List<Supplier<DoubleGaugeDataPoint>> dataPointFactories = new ArrayList<>();
        private boolean resetOnExport = false;

        /**
         * Create a builder for a {@link DoubleGaugeComposite} with the given metric key.
         *
         * @param key the metric key
         */
        private Builder(@NonNull MetricKey<DoubleGaugeComposite> key) {
            super(
                    MetricType.GAUGE,
                    key,
                    NO_DEFAULT_INITIALIZER,
                    init -> new DoubleGaugeCompositeArrayDataPoint(() -> new DoubleGaugeDataPoint[0]));
        }

        /**
         * @return {@code true} if the stats will be reset on export, {@code false} otherwise
         */
        public boolean isResetOnExport() {
            return resetOnExport;
        }

        /**
         * @return the label name used to identify the stat type in the exported metric
         */
        @NonNull
        public String getStatLabel() {
            return statLabel;
        }

        /**
         * @return the list of stat names defined for this composite gauge
         */
        @NonNull
        public List<String> getStatNames() {
            return statNames;
        }

        /**
         * Set the label name used to identify the stat type in the exported snapshots. <br>
         * Stat label name must not be blank and must only contain valid characters
         * - see {@link MetricUtils#validateNameCharacters(String)}. <br>
         * Default is {@value StatUtils#DEFAULT_STAT_LABEL}.
         *
         * @param statLabel the label name
         * @return this builder
         * @throws IllegalArgumentException if the stat label is blank
         */
        @NonNull
        public Builder withStatLabel(@NonNull String statLabel) {
            this.statLabel = MetricUtils.validateNameCharacters(statLabel);
            return this;
        }

        /**
         * Add a new stat to be tracked by this composite gauge using the given accumulation operator.
         * The initial value is {@code 0.0}.
         *
         * @param name     the name of the stat
         * @param operator the accumulation operator
         * @return this builder
         * @throws NullPointerException if the operator is null
         * @throws IllegalArgumentException if the stat name is blank
         */
        @NonNull
        public Builder withAccumulatorStat(@NonNull String name, @NonNull DoubleBinaryOperator operator) {
            return withAccumulatorStat(name, operator, ZERO);
        }

        /**
         * Add a new stat to be tracked by this composite gauge using the given accumulation operator
         * and initial value.
         *
         * @param name      the name of the stat
         * @param operator  the accumulation operator
         * @param initValue the initial value
         * @return this builder
         * @throws NullPointerException if the operator is null
         * @throws IllegalArgumentException if the stat name is blank
         */
        @NonNull
        public Builder withAccumulatorStat(
                @NonNull String name, @NonNull DoubleBinaryOperator operator, double initValue) {
            Objects.requireNonNull(operator, "operator must not be null");
            return withStatContainerFactory(name, () -> new DoubleAccumulatorGaugeDataPoint(operator, initValue));
        }

        /**
         * Add a new stat to be tracked by this composite gauge that simply holds the sum of all values set.
         * The initial value is {@code 0.0}.
         *
         * @return this builder
         */
        @NonNull
        public Builder withSumStat() {
            return withAccumulatorStat("sum", StatUtils.DOUBLE_SUM, ZERO);
        }

        /**
         * Add a new stat to be tracked by this composite gauge that simply holds the maximum of all values set.
         * The initial value is {@link Double#NEGATIVE_INFINITY}.
         *
         * @return this builder
         */
        @NonNull
        public Builder withMaxStat() {
            return withAccumulatorStat("max", StatUtils.DOUBLE_MAX, Double.NEGATIVE_INFINITY);
        }

        /**
         * Add a new stat to be tracked by this composite gauge that simply holds the minimum of all values set.
         * The initial value is {@link Double#POSITIVE_INFINITY}.
         *
         * @return this builder
         */
        @NonNull
        public Builder withMinStat() {
            return withAccumulatorStat("min", StatUtils.DOUBLE_MIN, Double.POSITIVE_INFINITY);
        }

        /**
         * Add a new stat to be tracked by this composite gauge that simply holds the latest value set.
         * The initial value is {@code 0.0}.
         *
         * @return this builder
         */
        @NonNull
        public Builder withLatestValueStat() {
            return withLatestValueStat(ZERO);
        }

        /**
         * Add a new stat to be tracked by this composite gauge that simply holds the latest value set.
         *
         * @param initValue the initial value
         * @return this builder
         */
        @NonNull
        public Builder withLatestValueStat(double initValue) {
            return withStatContainerFactory("latest", () -> new AtomicDoubleGaugeDataPoint(initValue));
        }

        /**
         * Configure this composite gauge to reset all stats to their initial values after each export.
         * Default is {@code false}. <br>
         * Due to performance reasons reset is <b>not atomic</b> across all stats.
         *
         * @return this builder
         */
        @NonNull
        public Builder withResetOnExport() {
            this.resetOnExport = true;
            return this;
        }

        /**
         * Build the {@link DoubleGaugeComposite} instance.
         *
         * @return the built metric
         * @throws IllegalStateException if no stats have been defined, or if the stat names are not unique
         *                               or stat label conflicts with a constant or dynamic label
         */
        @NonNull
        @Override
        public DoubleGaugeComposite buildMetric() {
            if (dataPointFactories.isEmpty()) {
                throw new IllegalStateException("At least one stat must be defined");
            }

            if (new HashSet<>(statNames).size() != dataPointFactories.size()) {
                throw new IllegalStateException("Stat names must be unique");
            }

            if (constantLabels.containsKey(statLabel)) {
                throw new IllegalStateException("Stat label '" + statLabel + "' conflicts with a constant label");
            }
            for (String dynamicLabelName : getDynamicLabelNames()) {
                if (dynamicLabelName.equals(statLabel)) {
                    throw new IllegalStateException("Stat label '" + statLabel + "' conflicts with a dynamic label");
                }
            }

            // copy the data point factories to ensure immutability
            final List<Supplier<DoubleGaugeDataPoint>> suppliers = List.copyOf(dataPointFactories);
            Supplier<DoubleGaugeDataPoint[]> dataPonitsSupplier =
                    () -> suppliers.stream().map(Supplier::get).toArray(DoubleGaugeDataPoint[]::new);

            withContainerFactory(init -> new DoubleGaugeCompositeArrayDataPoint(dataPonitsSupplier));
            return new DefaultDoubleGaugeComposite(this);
        }

        /**
         * @return this builder
         */
        @NonNull
        @Override
        protected Builder self() {
            return this;
        }

        /**
         * Add a new stat container factory to this composite gauge.
         *
         * @param statName             the name of the stat
         * @param statContainerFactory the factory to create the stat container
         * @return this builder
         * @throws IllegalArgumentException if the stat name is blank
         */
        @NonNull
        private Builder withStatContainerFactory(String statName, Supplier<DoubleGaugeDataPoint> statContainerFactory) {
            ArgumentUtils.throwArgBlank(statName, "stat name");

            statNames.add(statName);
            dataPointFactories.add(statContainerFactory);
            return this;
        }
    }
}
