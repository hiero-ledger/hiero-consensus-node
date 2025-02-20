// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform.prometheus;

import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.metrics.api.Metric;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.prometheus.client.SimpleCollector;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public abstract class AbstractMetricAdapter implements MetricAdapter {
    private static final Logger logger = LogManager.getLogger(AbstractMetricAdapter.class);
    protected final PrometheusEndpoint.AdapterType adapterType;
    private final @NonNull AdaptedMetricCommonValues values;

    private final AtomicInteger referenceCount = new AtomicInteger();

    /**
     * @param metric the metric being adapted
     * @throws NullPointerException in case {@code adapterType} parameter is {@code null}
     * @throws NullPointerException in case {@code metric} parameter is {@code null}
     */
    protected AbstractMetricAdapter(
            @NonNull final PrometheusEndpoint.AdapterType adapterType, @NonNull final Metric metric) {
        this(adapterType, metric, true);
    }

    /**
     * @param metric       the metric being adapted
     * @param supportsUnit if the {@code metric} being adapted is not a dimensionless metric. Prometheus will fail in
     *                     case of setting a unit to an invalid metric type.
     * @throws NullPointerException in case {@code adapterType} parameter is {@code null}
     * @throws NullPointerException in case {@code metric} parameter is {@code null}
     */
    protected AbstractMetricAdapter(
            @NonNull final PrometheusEndpoint.AdapterType adapterType,
            @NonNull final Metric metric,
            final boolean supportsUnit) {
        this.adapterType = Objects.requireNonNull(adapterType, "adapterType must not be null");
        this.values = new AdaptedMetricCommonValues(metric, supportsUnit);
    }

    /**
     * Depending on the configuration for this adapter, sets adapted values from {@code values} to
     * {@code collectorBuilder}
     *
     * @param collectorBuilder builder to set the values to
     * @return same instance of {@code collectorBuilder} with values set.
     */
    protected final <C extends SimpleCollector<?>, T extends SimpleCollector.Builder<T, C>> @NonNull
            T assignCommonValues(final @NonNull SimpleCollector.Builder<T, C> collectorBuilder) {
        return values.assignTo(collectorBuilder);
    }

    @Override
    public int incAndGetReferenceCount() {
        return referenceCount.incrementAndGet();
    }

    @Override
    public int decAndGetReferenceCount() {
        return referenceCount.decrementAndGet();
    }

    /**
     * A holder for common metric values supporting automatic naming rules conversions. Instances of this class are
     * created based on a {@code Metric}, and naming rules conversions will be applied to the category, name, and unit
     * fields according to {@link NameConverter}.
     * The class facilitates the creation of Prometheus-compatible metric values by storing the fixed and converted
     * category, name, unit, and help fields.
     */
    private static class AdaptedMetricCommonValues {
        private final boolean supportsUnit;

        @NonNull
        private final String subSystem;

        @NonNull
        private final String name;

        @NonNull
        private final String unit;

        @NonNull
        private final String help;

        /**
         * @param metric       the metric being adapted
         * @param supportsUnit if the {@code metric} being adapted is not a dimensionless metric. Prometheus will fail
         *                     in case of setting a unit to an invalid metric type.
         * @throws NullPointerException in case {@code metric} parameter is {@code null}
         */
        private AdaptedMetricCommonValues(final @NonNull Metric metric, final boolean supportsUnit) {
            Objects.requireNonNull(metric, "metric must not be null");
            this.supportsUnit = supportsUnit;
            this.subSystem = NameConverter.fix(metric.getCategory());
            this.name = NameConverter.fix(metric.getName());
            this.unit = NameConverter.fix(metric.getUnit());
            this.help = metric.getDescription();
            verifyMetricNamingComponents(metric);
        }

        <C extends SimpleCollector<?>, T extends SimpleCollector.Builder<T, C>> @NonNull T assignTo(
                final @NonNull SimpleCollector.Builder<T, C> collectorBuilder) {
            final T builder = collectorBuilder.subsystem(subSystem).name(name).help(help);
            return supportsUnit ? builder.unit(unit) : builder;
        }

        /**
         * Identifies changes in the metrics name components (category, name, and unit). If a change is detected, error
         * log statements with the purpose of failing JRS are generated to inform developers that adjustments to the
         * metric name may be required.
         * <p>
         * It is not throwing exceptions in order to minimize the possibility for runtime errors produced by
         * non-critical misconfigurations
         *
         * @param metric the metric to check against
         */
        private void verifyMetricNamingComponents(@NonNull final Metric metric) {
            if (!Objects.equals(this.subSystem, metric.getCategory())) {
                logger.error(
                        LogMarker.EXCEPTION.getMarker(),
                        "category field changed for metric:{} from:{} to:{}",
                        metric,
                        metric.getCategory(),
                        this.subSystem);
            }
            if (!Objects.equals(this.name, metric.getName())) {
                logger.error(
                        LogMarker.EXCEPTION.getMarker(),
                        "name field changed for metric:{} from:{} to:{}",
                        metric,
                        metric.getName(),
                        this.name);
            }
            if (supportsUnit && !Objects.equals(this.unit, metric.getUnit())) {
                logger.error(
                        LogMarker.EXCEPTION.getMarker(),
                        "unit field changed for metric:{} from:{} to:{}",
                        metric,
                        metric.getUnit(),
                        this.unit);
            }
        }
    }
}
