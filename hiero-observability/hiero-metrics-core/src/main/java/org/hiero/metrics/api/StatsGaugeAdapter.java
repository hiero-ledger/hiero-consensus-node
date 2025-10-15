// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import static org.hiero.metrics.api.stat.StatUtils.DEFAULT_STAT_LABEL;
import static org.hiero.metrics.api.stat.StatUtils.NO_DEFAULT_INITIALIZER;

import com.swirlds.base.ArgumentUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.StatefulMetric;
import org.hiero.metrics.api.core.ToLongOrDoubleFunction;
import org.hiero.metrics.api.stat.StatUtils;
import org.hiero.metrics.api.utils.MetricUtils;
import org.hiero.metrics.internal.StatsGaugeAdapterImpl;

/**
 * A stateful metric of type {@link MetricType#GAUGE} similar to {@link GaugeAdapter} but holding multiple
 * custom data points (provided by the client code) per label set.
 * <p>
 * This metric can be used for cases when some custom logic or aggregation is required to handle observed values,
 * and hold multiple values per label set.<br>
 * <p>
 * On export each value will have additional label to classify the value type - see {@link Builder#getStatLabel()}.
 * <p>
 * It is responsibility of the client to ensure that external data point is thread safe and provides atomic updates,
 * if needed.
 *
 * @param <I> the type of the initializer used to create the data point
 * @param <D> the type of the data point held by the metric
 */
public interface StatsGaugeAdapter<I, D> extends StatefulMetric<I, D> {

    /**
     * Create a metric key for a {@link StatsGaugeAdapter} with the given name. <br>
     * See {@link MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name the name of the metric
     * @param <I>  the type of the initializer used to create the data point
     * @param <D>  the type of the data point
     * @return the metric key
     */
    @NonNull
    static <I, D> MetricKey<StatsGaugeAdapter<I, D>> key(@NonNull String name) {
        return MetricKey.of(name, StatsGaugeAdapter.class);
    }

    /**
     * Create a builder for a {@link StatsGaugeAdapter} with the given metric key.
     *
     * @param key                the metric key
     * @param defaultInitializer the default initializer used to create the data point
     * @param dataPointFactory   the factory function to create the data point using the initializer
     * @param <I>                the type of the initializer used to create the data point
     * @param <D>                the type of the data point
     * @return the builder
     */
    @NonNull
    static <I, D> Builder<I, D> builder(
            @NonNull MetricKey<StatsGaugeAdapter<I, D>> key,
            @NonNull I defaultInitializer,
            @NonNull Function<I, D> dataPointFactory) {
        return new Builder<>(key, defaultInitializer, dataPointFactory);
    }

    /**
     * Create a builder for a {@link StatsGaugeAdapter} with the given metric key.
     * The data point is created using the provided factory without any initializer.
     *
     * @param key              the metric key
     * @param dataPointFactory the factory function to create the data point
     * @param <D>              the type of the data point
     * @return the builder
     */
    @NonNull
    static <D> Builder<Object, D> builder(
            @NonNull MetricKey<StatsGaugeAdapter<Object, D>> key, @NonNull Supplier<D> dataPointFactory) {
        return new Builder<>(key, NO_DEFAULT_INITIALIZER, init -> dataPointFactory.get());
    }

    /**
     * Builder for a {@link StatsGaugeAdapter}.
     * <p>
     * Default additional label for export is {@value StatUtils#DEFAULT_STAT_LABEL}, and can be changed via
     * {@link #withStatLabel(String)}.
     *
     * @param <I> the type of the initializer used to create the data point
     * @param <D> the type of the data point held by the metric
     */
    final class Builder<I, D> extends StatefulMetric.Builder<I, D, Builder<I, D>, StatsGaugeAdapter<I, D>> {

        private String statLabel = DEFAULT_STAT_LABEL;
        private final List<String> statNames = new ArrayList<>();
        private final List<ToLongOrDoubleFunction<D>> statExportGetters = new ArrayList<>();
        private Consumer<D> reset;

        private Builder(
                @NonNull MetricKey<StatsGaugeAdapter<I, D>> key,
                @NonNull I defaultInitializer,
                @NonNull Function<I, D> dataPointFactory) {
            super(MetricType.GAUGE, key, defaultInitializer, dataPointFactory);
        }

        /**
         * @return the label name used to identify the stat type in the exported metric
         */
        @NonNull
        public String getStatLabel() {
            return statLabel;
        }

        /**
         * @return the list of stat names defined for this composite gauge adapter
         */
        @NonNull
        public List<String> getStatNames() {
            return statNames;
        }

        /**
         * @return the list of functions to get the stat values from the data point
         */
        @NonNull
        public List<ToLongOrDoubleFunction<D>> getStatExportGetters() {
            return statExportGetters;
        }

        /**
         * Get the optional reset function to reset the data point.
         *
         * @return the reset function, or {@code null} if not set
         */
        @Nullable
        public Consumer<D> getReset() {
            return reset;
        }

        /**
         * Set the optional reset function to reset the data points.
         * If set, this function will be called to reset the data points when needed.
         *
         * @param reset the reset function, must not be {@code null}
         * @return this builder
         */
        @NonNull
        public Builder<I, D> withReset(@NonNull Consumer<D> reset) {
            this.reset = Objects.requireNonNull(reset, "Container stats reset must not be null");
            return this;
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
        public Builder<I, D> withStatLabel(@NonNull String statLabel) {
            this.statLabel = MetricUtils.validateNameCharacters(statLabel);
            return this;
        }

        /**
         * Add a stat to be exported from the data point using the given function.
         * The stat name must be unique and not blank.
         *
         * @param statName     the name of the stat
         * @param exportGetter the function to get the {@code double} from the data point, must not be {@code null}
         * @return this builder
         * @throws IllegalArgumentException if the stat name is blank or if the export getter is {@code null}
         */
        @NonNull
        public Builder<I, D> withDoubleStat(@NonNull String statName, @NonNull ToDoubleFunction<D> exportGetter) {
            return withStat(statName, new ToLongOrDoubleFunction<>(exportGetter));
        }

        /**
         * Add a stat to be exported from the data point using the given function.
         * The stat name must be unique and not blank.
         *
         * @param statName     the name of the stat
         * @param exportGetter the function to get the {@code long} from the data point, must not be {@code null}
         * @return this builder
         * @throws IllegalArgumentException if the stat name is blank or if the export getter is {@code null}
         */
        @NonNull
        public Builder<I, D> withLongStat(@NonNull String statName, @NonNull ToLongFunction<D> exportGetter) {
            return withStat(statName, new ToLongOrDoubleFunction<>(exportGetter));
        }

        @NonNull
        private Builder<I, D> withStat(@NonNull String statName, @NonNull ToLongOrDoubleFunction<D> exportGetter) {
            statNames.add(ArgumentUtils.throwArgBlank(statName, "stat name"));
            statExportGetters.add(Objects.requireNonNull(exportGetter, "Export getter must not be null"));
            return this;
        }

        /**
         * Build the {@link StatsGaugeAdapter} instance.
         *
         * @return the built metric
         */
        @NonNull
        @Override
        protected StatsGaugeAdapter<I, D> buildMetric() {
            if (statExportGetters.isEmpty()) {
                throw new IllegalStateException("At least one stat must be defined");
            }
            if (new HashSet<>(statNames).size() != statExportGetters.size()) {
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

            return new StatsGaugeAdapterImpl<>(this);
        }

        /**
         * @return this builder
         */
        @NonNull
        @Override
        protected Builder<I, D> self() {
            return this;
        }
    }
}
