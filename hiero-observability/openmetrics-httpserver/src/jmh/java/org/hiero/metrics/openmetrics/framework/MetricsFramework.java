// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics.framework;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;

/**
 * Abstract metrics framework exposing an HTTP endpoint for OpenMetrics scraping.
 */
public abstract class MetricsFramework implements Closeable {

    public static final String[] EMPTY_LABELS = new String[0];

    private final String name;
    private final int port;
    private final URI endpointUri;

    protected MetricsFramework(String name) {
        this.name = name;
        this.port = findPort();
        endpointUri = URI.create("http://" + hostname() + ":" + port + getPath());
    }

    /** Resolve metrics framework by name. */
    public static MetricsFramework resolve(String name) {
        return switch (name.toLowerCase()) {
            case "hiero" -> new HieroMetricsFramework();
            case "prometheus" -> new PrometheusFramework();
            default -> throw new IllegalArgumentException("Unknown metrics framework: " + name);
        };
    }

    private int findPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Unable to find port", e);
        }
    }

    /** Endpoint URI where metrics can be scraped. */
    public final URI endpointUri() {
        return endpointUri;
    }

    /** Name of the metrics framework. */
    public final String name() {
        return name;
    }

    /** Hostname where the HTTP endpoint is listening. */
    public String hostname() {
        return "localhost";
    }

    /** Port where the HTTP endpoint is listening. */
    public final int port() {
        return port;
    }

    /** Path where the HTTP endpoint is exposed. */
    protected String getPath() {
        return "/metrics";
    }

    /** Initialize label values array for the given label names. */
    public String[] initLabelValues(String... labelNames) {
        if (labelNames.length == 0) {
            return EMPTY_LABELS;
        }
        return new String[labelNames.length];
    }

    /** Set label value at the given index in the label values array. */
    public void setLabelValue(String[] labelValues, int labelIdx, String value) {
        labelValues[labelIdx] = value;
    }

    /** Register a counter metric. */
    public abstract CounterAdapter registerCounter(String name, String unit, String description, String... labelNames);

    /** Register a gauge metric. */
    public abstract GaugeAdapter registerGauge(String name, String unit, String description, String... labelNames);

    /** Base class for metric adapters. */
    public abstract static class MetricAdapter {

        protected final String[] labelNames;

        protected MetricAdapter(String... labelNames) {
            this.labelNames = labelNames;
        }

        public int labelsCount() {
            return labelNames.length;
        }

        public final String[] labelNames() {
            return labelNames;
        }
    }

    public abstract static class CounterAdapter extends MetricAdapter {

        protected CounterAdapter(String... labelNames) {
            super(labelNames);
        }

        public void increment() {
            increment(EMPTY_LABELS);
        }

        public abstract void increment(String... labelValues);
    }

    public abstract static class GaugeAdapter extends MetricAdapter {

        protected GaugeAdapter(String... labelNames) {
            super(labelNames);
        }

        public void set(long value) {
            set(value, EMPTY_LABELS);
        }

        public abstract void set(long value, String... labelValues);
    }
}
