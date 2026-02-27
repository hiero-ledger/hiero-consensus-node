// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics.framework;

import com.swirlds.config.api.ConfigurationBuilder;
import java.io.IOException;
import org.hiero.metrics.LongCounter;
import org.hiero.metrics.LongGauge;
import org.hiero.metrics.core.MetricRegistry;

/**
 * {@link MetricsFramework} implementation using the Hiero metrics library.
 * This serves as the baseline for benchmark comparisons.
 */
public final class HieroMetricsFramework extends MetricsFramework {

    private final MetricRegistry registry;

    public HieroMetricsFramework() {
        super("hiero");

        registry = MetricRegistry.builder()
                .discoverMetricsExporter(ConfigurationBuilder.create()
                        .autoDiscoverExtensions()
                        .withValue("metrics.exporter.openmetrics.http.port", String.valueOf(port()))
                        .withValue("metrics.exporter.openmetrics.http.hostname", hostname())
                        .withValue("metrics.exporter.openmetrics.http.path", getPath())
                        .build())
                .build();
    }

    @Override
    public String[] initLabelValues(String... labelNames) {
        if (labelNames.length == 0) {
            return EMPTY_LABELS;
        }
        String[] namesAndValues = new String[labelNames.length * 2];
        for (int i = 0; i < labelNames.length; i++) {
            namesAndValues[i * 2] = labelNames[i];
        }
        return namesAndValues;
    }

    @Override
    public void setLabelValue(String[] namesAndValues, int labelIdx, String value) {
        namesAndValues[2 * labelIdx + 1] = value;
    }

    @Override
    public CounterAdapter registerCounter(String name, String unit, String description, String... labelNames) {
        return new HieroCounter(
                LongCounter.builder(name)
                        .setUnit(unit)
                        .setDescription(description)
                        .addDynamicLabelNames(labelNames)
                        .register(registry),
                labelNames);
    }

    @Override
    public GaugeAdapter registerGauge(String name, String unit, String description, String... labelNames) {
        return new HieroGauge(
                LongGauge.builder(name)
                        .setUnit(unit)
                        .setDescription(description)
                        .addDynamicLabelNames(labelNames)
                        .register(registry),
                labelNames);
    }

    @Override
    public void close() throws IOException {
        registry.close();
    }

    private static final class HieroCounter extends CounterAdapter {
        private final LongCounter counter;

        private HieroCounter(LongCounter counter, String... labelNames) {
            super(labelNames);
            this.counter = counter;
        }

        @Override
        public void increment(String... labelNamesAndValues) {
            if (labelNamesAndValues.length == 0) {
                counter.getOrCreateNotLabeled().increment();
            } else {
                counter.getOrCreateLabeled(labelNamesAndValues).increment();
            }
        }
    }

    private static final class HieroGauge extends GaugeAdapter {
        private final LongGauge gauge;

        private HieroGauge(LongGauge gauge, String... labelNames) {
            super(labelNames);
            this.gauge = gauge;
        }

        @Override
        public void set(long value, String... labelNamesAndValues) {
            if (labelNamesAndValues.length == 0) {
                gauge.getOrCreateNotLabeled().set(value);
            } else {
                gauge.getOrCreateLabeled(labelNamesAndValues).set(value);
            }
        }
    }
}
