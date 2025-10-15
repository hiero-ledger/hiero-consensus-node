// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.StatefulMetric;
import org.hiero.metrics.api.datapoint.StateSetDataPoint;
import org.hiero.metrics.internal.DefaultStateSet;
import org.hiero.metrics.internal.datapoint.EnumStateSetDataPoint;

/**
 * A stateful metric of type {@link MetricType#STATE_SET} that holds {@link StateSetDataPoint} per label set.
 * <p>
 * This requires enum type to ensure states are fixed size.<br>
 * This metric won't have a unit (if set, will be overridden to {@code null} during metric construction).
 *
 * @param <E> the enum type of the states in the set
 */
public interface StateSet<E extends Enum<E>> extends StatefulMetric<List<E>, StateSetDataPoint<E>> {

    /**
     * Create a metric key for a {@link StateSet} with the given name. <br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name the name of the metric
     * @param <E>  the type of enum representing states in the set
     * @return the metric key
     */
    @NonNull
    static <E extends Enum<E>> MetricKey<StateSet<E>> key(@NonNull String name) {
        return MetricKey.of(name, StateSet.class);
    }

    /**
     * Create a builder for a {@link StateSet} with the given metric key.
     *
     * @param key the metric key
     * @param <E>  the type of enum representing states in the set
     * @return the builder
     */
    @NonNull
    static <E extends Enum<E>> Builder<E> builder(@NonNull MetricKey<StateSet<E>> key, @NonNull Class<E> enumClass) {
        Objects.requireNonNull(enumClass, "enumClass must not be null");
        return new Builder<>(key, enumClass);
    }

    /**
     * Create a builder for a {@link StateSet} with the given metric name for given enum type. <br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name the metric name
     * @param enumClass the enum type representing the states in the set
     * @param <E>  the enum type of the states in the set
     * @return the builder
     */
    @NonNull
    static <E extends Enum<E>> Builder<E> builder(@NonNull String name, @NonNull Class<E> enumClass) {
        MetricKey<StateSet<E>> key = key(name);
        return builder(key, enumClass);
    }

    /**
     * Builder for {@link StateSet} using {@link StateSetDataPoint} per label set.
     * <p>
     * By default, the initial state is empty and false for each state.
     *
     * @param <E> the type of the states in the set
     */
    final class Builder<E extends Enum<E>>
            extends StatefulMetric.Builder<List<E>, StateSetDataPoint<E>, Builder<E>, StateSet<E>> {

        private final Class<E> enumClass;

        private Builder(@NonNull MetricKey<StateSet<E>> key, @NonNull Class<E> enumClass) {
            super(MetricType.STATE_SET, key, List.of(), init -> new EnumStateSetDataPoint<>(init, enumClass));
            this.enumClass = enumClass;
        }

        @NonNull
        public Class<E> getEnumClass() {
            return enumClass;
        }

        /**
         * Build the {@link StateSet} metric.
         *
         * @return the built metric
         */
        @NonNull
        @Override
        protected StateSet<E> buildMetric() {
            withoutUnit(); // StateSet does not have a unit

            // state set must not have a label as metric name
            for (String dynamicLabelName : getDynamicLabelNames()) {
                if (dynamicLabelName.equals(key().name())) {
                    throw new IllegalStateException(
                            "StateSet metric cannot have a dynamic label with the same name as the metric");
                }
            }

            return new DefaultStateSet<>(this);
        }

        /**
         * @return this builder
         */
        @NonNull
        @Override
        protected Builder<E> self() {
            return this;
        }
    }
}
