// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.extensions;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.hiero.consensus.metrics.FunctionGauge;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.extensions.internal.ConcurrentLatencyRecorder;
import org.hiero.consensus.metrics.extensions.internal.HistogramLayout;
import org.hiero.consensus.metrics.extensions.internal.SlidingLatencyWindow;

/**
 * Records latencies and registers gauges that report percentiles and the maximum of them over a sliding window.
 *
 * <p>An instance named {@code foo} has one gauge per percentile, such as {@code foo_p50} or {@code foo_p99_9}, and
 * {@code foo_max}. The values are upper bounds of buckets, at most {@code 2^-p} above the exact value, where {@code p}
 * is the number of precision bits. See {@link Config} for the parameters and their defaults.
 *
 * <p>All gauges of an instance report on one sliding window, which advances on the first read of the metrics snapshot
 * round that ends a step; see {@link Config#withSnapshots(int)}.
 */
public final class LatencyGauges {

    /** The format of the reported values. */
    private static final String FORMAT = "%,10.3f";

    /** The recorder the gauges report on. */
    private final ConcurrentLatencyRecorder recorder;

    /** The window over the recorded values. */
    private final SlidingLatencyWindow slidingWindow;

    /** The number of nanoseconds in the unit of the reported values. */
    private final double nanosPerUnit;

    private LatencyGauges(@NonNull final Time time, @NonNull final Duration interval, @NonNull final Config config) {
        // the percentiles are only used when the gauges are read, so check them now
        for (final double percentile : config.getPercentiles()) {
            HistogramLayout.ppmOf(percentile);
        }
        this.recorder =
                new ConcurrentLatencyRecorder(new HistogramLayout(config.getPrecisionBits(), config.getRangeBits()));
        this.slidingWindow =
                new SlidingLatencyWindow(recorder, time, interval, config.getWindow(), config.getSnapshots());
        this.nanosPerUnit = config.getUnit().getDuration().toNanos();
    }

    /**
     * Creates an instance and registers its gauges.
     *
     * @param metrics       the metrics system to register the gauges with
     * @param configuration the configuration, holding the metrics snapshot interval of the {@link MetricsConfig}
     * @param time          the clock that drives the metrics snapshots
     * @param config        the parameters of the instance and its gauges
     * @return the instance to record the latencies with
     * @throws IllegalArgumentException if a parameter is out of its range, or the window is shorter than the metrics
     *                                  snapshot interval
     */
    @NonNull
    public static LatencyGauges register(
            @NonNull final Metrics metrics,
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            @NonNull final Config config) {
        final Duration interval =
                configuration.getConfigData(MetricsConfig.class).getMetricsSnapshotDuration();
        final LatencyGauges gauges = new LatencyGauges(time, interval, config);

        final String category = config.getCategory();
        final String name = config.getName();
        final String description =
                config.getDescription() + " over the last " + config.getWindow().toSeconds() + " s";
        for (final double percentile : config.getPercentiles()) {
            final String label =
                    "p" + BigDecimal.valueOf(percentile).stripTrailingZeros().toPlainString();
            // the Prometheus endpoint turns a point into a colon
            final String gaugeName = name + "_" + label.replace('.', '_');
            registerGauge(
                    metrics,
                    category,
                    gaugeName,
                    label + " of " + description,
                    () -> gauges.valueAtPercentile(percentile));
        }
        registerGauge(metrics, category, name + "_max", "max of " + description, gauges::max);
        return gauges;
    }

    private static void registerGauge(
            @NonNull final Metrics metrics,
            @NonNull final String category,
            @NonNull final String name,
            @NonNull final String description,
            @NonNull final Supplier<Double> supplier) {
        metrics.getOrCreate(new FunctionGauge.Config<>(category, name, Double.class, supplier)
                .withDescription(description)
                .withFormat(FORMAT));
    }

    /**
     * Records one latency. A negative latency, which a clock adjustment can produce, counts as 0. A latency above the
     * tracked range counts in the top bucket. Any number of threads can record at the same time, without locking.
     *
     * @param nanos the latency in nanoseconds
     */
    public void record(final long nanos) {
        recorder.record(nanos);
    }

    /**
     * Returns the value at a percentile of the current window.
     *
     * @param percentile the percentile, above 0 and below 100
     * @return the value at the percentile in the unit of the gauges
     */
    private synchronized double valueAtPercentile(final double percentile) {
        return slidingWindow.current().valueAtPercentile(percentile) / nanosPerUnit;
    }

    /**
     * Returns the maximum of the current window.
     *
     * @return the maximum in the unit of the gauges
     */
    private synchronized double max() {
        return slidingWindow.current().max() / nanosPerUnit;
    }

    /**
     * The parameters of the latency gauges. Only the category and the name are required, every other
     * parameter has a default.
     *
     * <p>This class is immutable, changing a parameter creates a new instance.
     */
    public static final class Config {

        private static final int DEFAULT_PRECISION_BITS = 7;
        private static final int DEFAULT_RANGE_BITS = 36;
        private static final Duration DEFAULT_WINDOW = Duration.ofSeconds(60);
        private static final int DEFAULT_SNAPSHOTS = 20;
        private static final List<Double> DEFAULT_PERCENTILES = List.of(50.0, 90.0, 99.0, 99.9);
        private static final ChronoUnit DEFAULT_UNIT = ChronoUnit.MICROS;

        private final String category;
        private final String name;
        private final String description;
        private final int precisionBits;
        private final int rangeBits;
        private final Duration window;
        private final int snapshots;
        private final List<Double> percentiles;
        private final ChronoUnit unit;

        /**
         * Creates the parameters with the defaults.
         *
         * @param category the category of the gauges
         * @param name     the base name of the gauges
         */
        public Config(@NonNull final String category, @NonNull final String name) {
            this(
                    category,
                    name,
                    name,
                    DEFAULT_PRECISION_BITS,
                    DEFAULT_RANGE_BITS,
                    DEFAULT_WINDOW,
                    DEFAULT_SNAPSHOTS,
                    DEFAULT_PERCENTILES,
                    DEFAULT_UNIT);
        }

        private Config(
                @NonNull final String category,
                @NonNull final String name,
                @NonNull final String description,
                final int precisionBits,
                final int rangeBits,
                @NonNull final Duration window,
                final int snapshots,
                @NonNull final List<Double> percentiles,
                @NonNull final ChronoUnit unit) {
            this.category = requireNonNull(category);
            this.name = requireNonNull(name);
            this.description = requireNonNull(description);
            this.precisionBits = precisionBits;
            this.rangeBits = rangeBits;
            this.window = requireNonNull(window);
            this.snapshots = snapshots;
            this.percentiles = List.copyOf(percentiles);
            this.unit = requireNonNull(unit);
        }

        /**
         * Sets the description of the recorded latency, which the gauges extend with the percentile and the window.
         * Defaults to the name.
         *
         * @param description the description of the recorded latency
         * @return a new instance with the updated description
         */
        @NonNull
        public Config withDescription(@NonNull final String description) {
            return new Config(
                    category, name, description, precisionBits, rangeBits, window, snapshots, percentiles, unit);
        }

        /**
         * Sets the number of precision bits {@code p}, which bounds the width of a bucket to {@code 2^-p} of its
         * values. Defaults to 7.
         *
         * @param precisionBits the number of precision bits, between 1 and 16
         * @return a new instance with the updated number of precision bits
         */
        @NonNull
        public Config withPrecisionBits(final int precisionBits) {
            return new Config(
                    category, name, description, precisionBits, rangeBits, window, snapshots, percentiles, unit);
        }

        /**
         * Sets the number of range bits {@code E}, which makes {@code 2^E - 1} ns the highest tracked latency; higher
         * latencies share the top bucket. Defaults to 36, about 68.7 s.
         *
         * @param rangeBits the number of range bits, above the number of precision bits and at most 62
         * @return a new instance with the updated number of range bits
         */
        @NonNull
        public Config withRangeBits(final int rangeBits) {
            return new Config(
                    category, name, description, precisionBits, rangeBits, window, snapshots, percentiles, unit);
        }

        /**
         * Sets the time the gauges report on, which is rounded up to whole steps; see {@link #withSnapshots(int)}.
         * Defaults to 60 s.
         *
         * @param window the time the gauges report on, at least the metrics snapshot interval
         * @return a new instance with the updated window
         */
        @NonNull
        public Config withWindow(@NonNull final Duration window) {
            return new Config(
                    category, name, description, precisionBits, rangeBits, window, snapshots, percentiles, unit);
        }

        /**
         * Sets the most snapshots the window takes per window length. The window advances in steps of whole metrics
         * snapshot intervals, the shortest that need no more snapshots; the gauges change only when a step ends. Each
         * snapshot is a copy of the counts, so fewer snapshots save memory but make the gauges follow changes later.
         * Defaults to 20: one per interval for a 60 s window and the default interval of 3 s.
         *
         * @param snapshots the most snapshots per window length, at least 1
         * @return a new instance with the updated number of snapshots
         */
        @NonNull
        public Config withSnapshots(final int snapshots) {
            return new Config(
                    category, name, description, precisionBits, rangeBits, window, snapshots, percentiles, unit);
        }

        /**
         * Sets the reported percentiles. Defaults to 50, 90, 99, and 99.9.
         *
         * @param percentiles the reported percentiles, each above 0 and below 100; the maximum is always reported
         * @return a new instance with the updated percentiles
         */
        @NonNull
        public Config withPercentiles(@NonNull final double... percentiles) {
            return withPercentiles(Arrays.stream(percentiles).boxed().toList());
        }

        /**
         * Sets the reported percentiles, such as a list read from the configuration. Defaults to 50, 90, 99, and 99.9.
         *
         * @param percentiles the reported percentiles, each above 0 and below 100; the maximum is always reported
         * @return a new instance with the updated percentiles
         */
        @NonNull
        public Config withPercentiles(@NonNull final List<Double> percentiles) {
            return new Config(
                    category, name, description, precisionBits, rangeBits, window, snapshots, percentiles, unit);
        }

        /**
         * Sets the unit of the reported values. Defaults to microseconds.
         *
         * @param unit the unit of the reported values
         * @return a new instance with the updated unit
         */
        @NonNull
        public Config withUnit(@NonNull final ChronoUnit unit) {
            return new Config(
                    category, name, description, precisionBits, rangeBits, window, snapshots, percentiles, unit);
        }

        /**
         * @return the category of the gauges
         */
        @NonNull
        public String getCategory() {
            return category;
        }

        /**
         * @return the base name of the gauges
         */
        @NonNull
        public String getName() {
            return name;
        }

        /**
         * @return the description of the recorded latency
         */
        @NonNull
        public String getDescription() {
            return description;
        }

        /**
         * @return the number of precision bits
         */
        public int getPrecisionBits() {
            return precisionBits;
        }

        /**
         * @return the number of range bits
         */
        public int getRangeBits() {
            return rangeBits;
        }

        /**
         * @return the time the gauges report on
         */
        @NonNull
        public Duration getWindow() {
            return window;
        }

        /**
         * @return the most snapshots per window length
         */
        public int getSnapshots() {
            return snapshots;
        }

        /**
         * @return the reported percentiles
         */
        @NonNull
        public List<Double> getPercentiles() {
            return percentiles;
        }

        /**
         * @return the unit of the reported values
         */
        @NonNull
        public ChronoUnit getUnit() {
            return unit;
        }
    }
}
