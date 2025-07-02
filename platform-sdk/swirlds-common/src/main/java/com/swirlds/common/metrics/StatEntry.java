// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.metrics.statistics.StatsBuffered;
import com.swirlds.metrics.api.FloatFormats;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricType;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A very flexible implementation of Metric which behavior is mostly passed to it via lambdas.
 *
 * @deprecated This class will be removed. Use one of the specialized {@link Metric}-implementations instead.
 */
@Deprecated(forRemoval = true)
public interface StatEntry extends Metric {

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    default MetricType getMetricType() {
        return MetricType.STAT_ENTRY;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    default Set<ValueType> getValueTypes() {
        return getBuffered() == null ? SINGLE_VALUE_TYPE_SET : ALL_VALUE_TYPES_SET;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    default Object get(@NonNull final ValueType valueType) {
        Objects.requireNonNull(valueType, "valueType must not be null");
        if (getBuffered() == null) {
            if (valueType == VALUE) {
                return getStatsStringSupplier().get();
            }
            throw new IllegalArgumentException("Unsupported ValueType: " + valueType);
        } else {
            return switch (valueType) {
                case VALUE -> getStatsStringSupplier().get();
                case MAX -> getBuffered().getMax();
                case MIN -> getBuffered().getMin();
                case STD_DEV -> getBuffered().getStdDev();
                default -> throw new IllegalArgumentException("Unsupported ValueType: " + valueType);
            };
        }
    }

    /**
     * Getter for {@code buffered}
     *
     * @return the {@link StatsBuffered}, if available, otherwise {@code null}
     */
    @Nullable
    StatsBuffered getBuffered();

    /**
     * Getter for {@code reset}, a lambda that resets the metric, using the given half life
     *
     * @return the reset-lambda, if available, otherwise {@code null}
     */
    @Nullable
    Consumer<Double> getReset();

    /**
     * Getter for {@code statsStringSupplier}, a lambda that returns the metric value
     *
     * @return the lambda
     */
    @NonNull
    Supplier<Object> getStatsStringSupplier();

    /**
     * Getter for {@code resetStatsStringSupplier}, a lambda that returns the statistic string
     * and resets it at the same time
     *
     * @return the lambda
     */
    @NonNull
    Supplier<Object> getResetStatsStringSupplier();

    /**
     * Configuration of a {@link StatEntry}.
     *
     * @param <T> the type of the value that will be contained in the {@code StatEntry}
     */
    final class Config<T> extends PlatformMetricConfig<StatEntry, Config<T>> {

        private final @NonNull Class<T> type;
        private final @NonNull Supplier<T> statsStringSupplier;

        private @Nullable StatsBuffered buffered;
        private @Nullable Function<Double, StatsBuffered> init;
        private @Nullable Consumer<Double> reset;
        private @Nullable Supplier<T> resetStatsStringSupplier;
        private double halfLife;

        /**
         * stores all the parameters, which can be accessed directly
         *
         * @param category
         * 		the kind of metric (metrics are grouped or filtered by this)
         * @param name
         * 		a short name for the metric
         * @param type
         * 		the type of the values this {@code StatEntry} returns
         * @param statsStringSupplier
         * 		a lambda that returns the metric string
         * @throws IllegalArgumentException
         * 		if one of the parameters is {@code null} or consists only of whitespaces
         */
        public Config(
                @NonNull final String category,
                @NonNull final String name,
                @NonNull final Class<T> type,
                @NonNull final Supplier<T> statsStringSupplier) {

            super(category, name);
            withFormat(FloatFormats.FORMAT_11_3);
            this.type = Objects.requireNonNull(type, "type must not be null");
            this.statsStringSupplier =
                    Objects.requireNonNull(statsStringSupplier, "statsStringSupplier must not be null");
            this.resetStatsStringSupplier = statsStringSupplier;
            this.halfLife = -1;
        }

        /**
         * Getter of the type of the returned values
         *
         * @return the type of the returned values
         */
        @NonNull
        public Class<T> getType() {
            return type;
        }

        /**
         * Getter of {@code buffered}
         *
         * @return {@code buffered}
         */
        @Nullable
        public StatsBuffered getBuffered() {
            return buffered;
        }

        /**
         * Fluent-style setter of {@code buffered}.
         *
         * @param buffered
         * 		the {@link StatsBuffered}
         * @return a reference to {@code this}
         */
        @NonNull
        public StatEntry.Config<T> withBuffered(@NonNull final StatsBuffered buffered) {
            this.buffered = Objects.requireNonNull(buffered, "buffer must not be null");
            return this;
        }

        /**
         * Getter of {@code init}
         *
         * @return {@code init}
         */
        @Nullable
        public Function<Double, StatsBuffered> getInit() {
            return init;
        }

        /**
         * Fluent-style setter of {@code init}.
         *
         * @param init
         * 		the init-function
         * @return a reference to {@code this}
         */
        @NonNull
        public StatEntry.Config<T> withInit(@Nullable final Function<Double, StatsBuffered> init) {
            this.init = init;
            return this;
        }

        /**
         * Getter of {@code reset}
         *
         * @return {@code reset}
         */
        @Nullable
        public Consumer<Double> getReset() {
            return reset;
        }

        /**
         * Fluent-style setter of {@code reset}.
         *
         * @param reset
         * 		the reset-function
         * @return a reference to {@code this}
         */
        @NonNull
        public StatEntry.Config<T> withReset(@Nullable final Consumer<Double> reset) {
            this.reset = reset;
            return this;
        }

        /**
         * Getter of {@code statsStringSupplier}
         *
         * @return {@code statsStringSupplier}
         */
        @NonNull
        public Supplier<T> getStatsStringSupplier() {
            return statsStringSupplier;
        }

        /**
         * Getter of {@code resetStatsStringSupplier}
         *
         * @return {@code resetStatsStringSupplier}
         */
        @NonNull
        public Supplier<T> getResetStatsStringSupplier() {
            return resetStatsStringSupplier;
        }

        /**
         * Fluent-style setter of {@code resetStatsStringSupplier}.
         *
         * @param resetStatsStringSupplier
         * 		the reset-supplier
         * @return a reference to {@code this}
         */
        @NonNull
        public StatEntry.Config<T> withResetStatsStringSupplier(@NonNull final Supplier<T> resetStatsStringSupplier) {
            this.resetStatsStringSupplier =
                    Objects.requireNonNull(resetStatsStringSupplier, "resetStatsStringSupplier must not be null");
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public Class<StatEntry> getResultClass() {
            return StatEntry.class;
        }

        /**
         * Fluent-style setter of {@code halfLife}.
         *
         * @param halfLife
         * 		value of the half-life
         * @return a reference to {@code this}
         */
        @NonNull
        public StatEntry.Config<T> withHalfLife(final double halfLife) {
            this.halfLife = halfLife;
            return this;
        }

        /**
         * Getter of the {@code halfLife}.
         *
         * @return the {@code halfLife}
         */
        public double getHalfLife() {
            return halfLife;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public StatEntry create(@NonNull final PlatformMetricsFactory factory) {
            return factory.createStatEntry(this);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected Config<T> self() {
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected ToStringBuilder selfToString() {
            return super.selfToString().append("type", type.getName());
        }
    }
}
