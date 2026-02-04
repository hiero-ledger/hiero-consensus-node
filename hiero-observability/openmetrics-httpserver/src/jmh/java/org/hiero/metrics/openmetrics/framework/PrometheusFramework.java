// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics.framework;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.Unit;
import java.io.IOException;

/**
 * {@link MetricsFramework} implementation using the newer Prometheus Java client library
 * (io.prometheus:prometheus-metrics-core).
 */
public final class PrometheusFramework extends MetricsFramework {

    private final PrometheusRegistry registry;
    private final HTTPServer httpServer;

    public PrometheusFramework() {
        super("prometheus");
        this.registry = new PrometheusRegistry();

        try {
            httpServer = HTTPServer.builder()
                    .hostname(hostname())
                    .port(port())
                    .registry(registry)
                    .buildAndStart();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Unit createUnit(String unit) {
        if (unit == null || unit.isBlank()) return null;

        if ("bytes".equals(unit)) {
            return Unit.BYTES;
        } else if ("seconds".equals(unit)) {
            return Unit.SECONDS;
        }
        return new Unit(unit);
    }

    @Override
    public CounterAdapter registerCounter(String name, String unit, String description, String... labelNames) {
        return new PrometheusCounter(
                Counter.builder()
                        .name(name)
                        .unit(createUnit(unit))
                        .help(description)
                        .labelNames(labelNames)
                        .register(registry),
                labelNames);
    }

    @Override
    public GaugeAdapter registerGauge(String name, String unit, String description, String... labelNames) {
        return new PrometheusGauge(
                Gauge.builder()
                        .name(name)
                        .unit(createUnit(unit))
                        .help(description)
                        .labelNames(labelNames)
                        .register(registry),
                labelNames);
    }

    @Override
    public void close() {
        httpServer.close();
    }

    private static final class PrometheusCounter extends CounterAdapter {
        private final Counter counter;

        private PrometheusCounter(Counter counter, String... labelNames) {
            super(labelNames);
            this.counter = counter;
        }

        @Override
        public void increment(String... labelValues) {
            if (labelValues.length == 0) {
                counter.inc();
            } else {
                counter.labelValues(labelValues).inc();
            }
        }
    }

    private static final class PrometheusGauge extends GaugeAdapter {
        private final Gauge gauge;

        private PrometheusGauge(Gauge gauge, String... labelNames) {
            super(labelNames);
            this.gauge = gauge;
        }

        @Override
        public void set(long value, String... labelValues) {
            if (labelValues.length == 0) {
                gauge.set(value);
            } else {
                gauge.labelValues(labelValues).set(value);
            }
        }
    }
}
