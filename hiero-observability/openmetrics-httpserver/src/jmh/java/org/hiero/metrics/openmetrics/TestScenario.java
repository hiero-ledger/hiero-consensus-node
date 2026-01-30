// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import java.io.Closeable;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.hiero.metrics.openmetrics.framework.MetricsFramework;

/**
 * A test scenario that can generate and update metrics and call the metrics endpoint.
 * Generate metrics should be called only once or few times from a single thread (not thread safe).
 * Update methods are thread safe.
 */
public class TestScenario implements Closeable {

    private static final String[] UNITS = {"bytes", "seconds", ""};

    private static final String[][] LABEL_SETS = {
        {}, {"label1"}, {"label1", "label2"}, {"label1", "label2", "label3"},
    };

    private final ThreadLocal<Random> metricsUpdateRandom;
    private final Random metricsGenerateRandom;
    private final MetricsFramework framework;

    private final List<MetricContext> metrics = new ArrayList<>();

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public TestScenario(MetricsFramework framework, long seed) {
        this.framework = framework;
        metricsGenerateRandom = new Random(seed);
        metricsUpdateRandom = ThreadLocal.withInitial(
                () -> new Random(seed + Thread.currentThread().threadId()));
    }

    private String getUnit(int idx) {
        return UNITS[idx % UNITS.length];
    }

    // not thread safe - should be called only from a single thread once or few times
    public void generateMetrics(int totalMeasurements, int cardinalityLowBound, int cardinalityHighBound) {
        if (totalMeasurements <= 0) {
            throw new IllegalArgumentException("totalMeasurements should be > 0");
        }
        if (cardinalityHighBound < cardinalityLowBound) {
            throw new IllegalArgumentException("cardinalityHighBound should be >= cardinalityLowBound");
        }
        if (cardinalityLowBound <= 0) {
            throw new IllegalArgumentException("cardinalityLowBound should be > 0");
        }

        final int[] measurementsPerLabelsCount = {
            0, totalMeasurements * 30 / 100, totalMeasurements * 40 / 100, totalMeasurements * 20 / 100
        };

        // create no-labels metrics
        int noLabelsMeasurements = totalMeasurements
                - (measurementsPerLabelsCount[1] + measurementsPerLabelsCount[2] + measurementsPerLabelsCount[3]);
        for (int metricId = 0; metricId < noLabelsMeasurements / 2; metricId++) {
            String unit = getUnit(metricId);
            addMetric(
                    framework.registerCounter(
                            "counter_0l_" + metricId,
                            unit,
                            UUID.randomUUID().toString(),
                            MetricsFramework.EMPTY_LABELS),
                    1);

            addMetric(
                    framework.registerGauge(
                            "gauge_0l_" + metricId, unit, UUID.randomUUID().toString(), MetricsFramework.EMPTY_LABELS),
                    1);
        }

        // create labeled metrics
        for (int labelsCount = 1; labelsCount < measurementsPerLabelsCount.length; labelsCount++) {
            int measurementsInGroup = measurementsPerLabelsCount[labelsCount];
            String[] labels = LABEL_SETS[labelsCount];

            int metricId = 0;
            while (measurementsInGroup > 0) {
                int cardinality = cardinalityLowBound
                        + metricsGenerateRandom.nextInt(cardinalityHighBound - cardinalityLowBound + 1);
                // counters and gauges should cover all measurements
                cardinality = Math.min((measurementsInGroup + 1) / 2, cardinality);

                String unit = getUnit(metricId);
                addMetric(
                        framework.registerCounter(
                                "counter_" + labelsCount + "l_" + metricId,
                                unit,
                                UUID.randomUUID().toString(),
                                labels),
                        cardinality);

                addMetric(
                        framework.registerGauge(
                                "gauge_" + labelsCount + "l_" + metricId,
                                unit,
                                UUID.randomUUID().toString(),
                                labels),
                        cardinality);

                metricId++;
                measurementsInGroup -= cardinality * 2;
            }
        }
    }

    private void addMetric(MetricsFramework.MetricAdapter adapter, int cardinality) {
        metrics.add(new MetricContext(adapter, cardinality));
    }

    public void updateRandomMetricMeasurements(int measurementsCount) {
        for (int i = 0; i < measurementsCount; i++) {
            updateRandomMetricMeasurement();
        }
    }

    public void updateRandomMetricMeasurement() {
        metrics.get(metricsUpdateRandom.get().nextInt(metrics.size())).updateRandomMeasurement();
    }

    public void updateAllMetricMeasurements() {
        for (MetricContext metric : metrics) {
            metric.updateAllMeasurements();
        }
    }

    public void callEndpoint(boolean useGzip) throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(framework.endpointUri())
                .GET()
                .header("Accept", "application/openmetrics-text");
        if (useGzip) {
            requestBuilder.header("Accept-Encoding", "gzip");
        }

        HttpResponse<Void> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200) {
            throw new IOException("Unexpected response status: " + response.statusCode());
        }
    }

    @Override
    public void close() throws IOException {
        framework.close();
    }

    private final class MetricContext {
        private final MetricsFramework.MetricAdapter adapter;
        private final AtomicReferenceArray<String[]> measurementLabelValuesCache;

        private MetricContext(MetricsFramework.MetricAdapter adapter, int cardinality) {
            this.adapter = adapter;
            measurementLabelValuesCache = new AtomicReferenceArray<>(cardinality);
        }

        private String[] getCachedOrCreatedLabelValuesRandom(Random random) {
            int measurementIdx = random.nextInt(measurementLabelValuesCache.length());
            return getCachedOrCreatedLabelValues(measurementIdx);
        }

        private String[] getCachedOrCreatedLabelValues(int measurementIdx) {
            String[] labelValues = measurementLabelValuesCache.get(measurementIdx);
            if (labelValues == null) {
                labelValues = framework.initLabelValues(adapter.labelNames());
                for (int i = 0; i < adapter.labelsCount(); i++) {
                    framework.setLabelValue(labelValues, i, "value_" + measurementIdx + "_" + i);
                }

                if (!measurementLabelValuesCache.compareAndSet(measurementIdx, null, labelValues)) {
                    // Another thread won the race to initialize; use the cached value.
                    labelValues = measurementLabelValuesCache.get(measurementIdx);
                }
            }
            return labelValues;
        }

        public void updateAllMeasurements() {
            final Random random = metricsUpdateRandom.get();
            for (int i = 0; i < measurementLabelValuesCache.length(); i++) {
                String[] labelValues = getCachedOrCreatedLabelValues(i);
                if (adapter instanceof MetricsFramework.CounterAdapter counter) {
                    counter.increment(labelValues);
                } else if (adapter instanceof MetricsFramework.GaugeAdapter gauge) {
                    gauge.set(random.nextLong(100L), labelValues);
                }
            }
        }

        public void updateRandomMeasurement() {
            final Random random = metricsUpdateRandom.get();
            if (adapter instanceof MetricsFramework.CounterAdapter counter) {
                if (adapter.labelsCount() == 0) {
                    counter.increment();
                } else {
                    counter.increment(getCachedOrCreatedLabelValuesRandom(random));
                }
            } else if (adapter instanceof MetricsFramework.GaugeAdapter gauge) {
                if (adapter.labelsCount() == 0) {
                    gauge.set(random.nextLong(100L));
                } else {
                    gauge.set(random.nextLong(100L), getCachedOrCreatedLabelValuesRandom(random));
                }
            }
        }
    }
}
