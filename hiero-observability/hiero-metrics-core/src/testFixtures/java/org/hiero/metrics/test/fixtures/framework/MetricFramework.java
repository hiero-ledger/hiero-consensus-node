// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.test.fixtures.framework;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * An adapter for a metrics framework for creating and managing different types of metrics for testing purposes.
 * <p>
 * Creating metrics is <b>not thread-safe</b> and should be done in a single thread.
 */
public abstract class MetricFramework {

    protected static final String[] EMPTY_LABELS = new String[0];

    private static final long LONG_BOUND = 100L;
    private static final double DOUBLE_BOUND = 100.0;

    private final Random random = new Random();

    private final Map<MetricType, MetricAdapter<?, ?>> metricAdapters = new EnumMap<>(MetricType.class);
    private final List<MetricContext> metrics = new ArrayList<>();

    /**
     * Get common supported metric types across multiple frameworks.
     *
     * @param frameworks the metric frameworks
     * @return the common supported metric types
     */
    public static MetricType[] getCommonSupportedTypes(MetricFramework... frameworks) {
        if (frameworks.length == 0) {
            return new MetricType[0];
        }
        Set<MetricType> commonTypes = new HashSet<>(frameworks[0].supportedMetricTypes());
        for (int i = 1; i < frameworks.length; i++) {
            commonTypes.retainAll(frameworks[i].supportedMetricTypes());
        }
        return commonTypes.toArray(new MetricType[0]);
    }

    /**
     * Register a metric adapter for a specific metric type.
     *
     * @param metricType the metric type
     * @param adapter    the metric adapter
     * @param <M>        the metric type
     * @param <D>        the data point type
     */
    protected <M, D> void registerAdapter(MetricType metricType, MetricAdapter<M, D> adapter) {
        metricAdapters.put(metricType, adapter);
    }

    /**
     * @return the generated random boolean value
     */
    protected boolean randomBoolean() {
        return random.nextBoolean();
    }

    /**
     * @return the generated random long value for gauge in the range [-{@value LONG_BOUND}, {@value LONG_BOUND})
     */
    protected long randomLongForGauge() {
        return random.nextLong(-LONG_BOUND, LONG_BOUND);
    }

    /**
     * @return the generated random double value for gauge in the range [-{@value DOUBLE_BOUND}, {@value DOUBLE_BOUND})
     */
    protected double randomDoubleForGauge() {
        return random.nextDouble(-DOUBLE_BOUND, DOUBLE_BOUND);
    }

    /**
     * Initialize label values template for the given label names.
     *
     * @param labelNames the label names
     * @return the initialized label values template
     */
    protected String[] initLabelValuesTemplate(String[] labelNames) {
        if (labelNames.length == 0) {
            return EMPTY_LABELS;
        }
        return new String[labelNames.length];
    }

    /**
     * Update a specific label value in the label values array.
     *
     * @param labelValues the label values array
     * @param labelIdx    the label index to update
     * @param value       the new label value
     */
    protected void updateLabelValue(String[] labelValues, int labelIdx, String value) {
        labelValues[labelIdx] = value;
    }

    /**
     * @return the supported metric types in this framework
     */
    public Set<MetricType> supportedMetricTypes() {
        return metricAdapters.keySet();
    }

    /**
     * Get unit for a specific index.
     *
     * @param index the index
     * @return the unit
     */
    protected String getUnit(int index) {
        return switch (index % 3) {
            case 0 -> "seconds";
            case 1 -> "bytes";
            default -> "";
        };
    }

    /**
     * Generate label names of provided size.
     *
     * @param labelsCount the number of labels
     * @return the generated label names
     */
    protected String[] generateLabels(int labelsCount) {
        String[] labels = new String[labelsCount];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = "label_" + i;
        }
        return labels;
    }

    private MetricAdapter<?, ?> getMetricAdapter(MetricType metricType) {
        MetricAdapter<?, ?> metricAdapter = metricAdapters.get(metricType);
        if (metricAdapter == null) {
            throw new IllegalArgumentException("Unsupported metric type: " + metricType);
        }
        return metricAdapter;
    }

    /**
     * Create a metric with or without labels.
     * This method is not thread-safe and should be called from a single thread.
     *
     * @param type            the metric type
     * @param dataPointsCount the number of data points for this metric
     * @param initDataPoints  whether to initialize the data points
     * @param labelsCount     number of labels to generate (could be 0, meaning no labels and 1 datapoint)
     * @return the metric id
     */
    public int createMetricWithLabels(MetricType type, int dataPointsCount, boolean initDataPoints, int labelsCount) {
        if (labelsCount < 0) {
            throw new IllegalArgumentException("labelsCount must be non-negative");
        }
        if (dataPointsCount <= 0) {
            throw new IllegalArgumentException("dataPointsCount must be positive");
        }

        MetricAdapter<?, ?> metricAdapter = getMetricAdapter(type);
        int metricId = metrics.size();
        if (labelsCount == 0) {
            metrics.add(new MetricContext(metricAdapter, metricId, initDataPoints));
        } else {
            String[] labelNames = generateLabels(labelsCount);
            metrics.add(new MetricContext(metricAdapter, metricId, labelNames, dataPointsCount, initDataPoints));
        }
        return metricId;
    }

    /**
     * Generate metrics of all supported types deterministically.
     *
     * @param dataPointsCount the total number of data points to generate
     * @param labelsBound     the maximum number of labels per metric
     * @param initDataPoints  whether to initialize the data points
     */
    public void generateAllMetricsTypesDeterministic(int dataPointsCount, int labelsBound, boolean initDataPoints) {
        generateAllMetricsTypesDeterministic(
                dataPointsCount,
                labelsBound,
                initDataPoints,
                metricAdapters.keySet().toArray(new MetricType[0]));
    }

    /**
     * Generate metrics of specific types deterministically.
     *
     * @param dataPointsCount the total number of data points to generate
     * @param labelsBound     the maximum number of labels per metric
     * @param initDataPoints  whether to initialize the data points
     * @param metricTypes     the metric types to generate
     */
    public void generateAllMetricsTypesDeterministic(
            int dataPointsCount, int labelsBound, boolean initDataPoints, MetricType... metricTypes) {
        if (dataPointsCount <= 0) {
            throw new IllegalArgumentException("dataPointsCount must be positive");
        }
        if (labelsBound <= 0) {
            throw new IllegalArgumentException("labelsBound must be positive");
        }
        if (metricTypes.length == 0) {
            throw new IllegalArgumentException("At least one metric type must be provided");
        }

        int dataPointsPerMetricType = dataPointsCount / metricTypes.length;
        int dataPointsPerLabelsPerType = 0;
        int noLabelDataPointsPerType = dataPointsPerMetricType;

        if (labelsBound > 1) {
            dataPointsPerLabelsPerType = dataPointsPerMetricType / labelsBound;
            noLabelDataPointsPerType = dataPointsPerLabelsPerType + (dataPointsPerMetricType % labelsBound);
        }

        for (MetricType metricType : metricTypes) {
            for (int i = 0; i < noLabelDataPointsPerType; i++) {
                createMetricWithLabels(metricType, 1, initDataPoints, 0);
            }

            if (dataPointsPerLabelsPerType > 0) {
                for (int i = 1; i < labelsBound; i++) {
                    createMetricWithLabels(metricType, dataPointsPerLabelsPerType, initDataPoints, i);
                }
            }
        }
    }

    /**
     * @return number of metrics created in this framework
     */
    public int getMetricsCount() {
        return metrics.size();
    }

    /**
     * Update all data points of random metrics a specific number of times.
     *
     * @param metricsCount the number of metrics to update all datapoints
     */
    public void updateRandomMetricsAllDataPoints(int metricsCount) {
        for (int i = 0; i < metricsCount; i++) {
            getRandomMetric().updateAllDataPoints();
        }
    }

    /**
     * Update random data points of random metrics a specific number of times.
     *
     * @param updatesCount the number of data points to update
     */
    public void updateRandomDataPoints(int updatesCount) {
        for (int i = 0; i < updatesCount; i++) {
            MetricFramework.MetricContext<?, ?> metric = getRandomMetric();
            int dataPointId = random.nextInt(metric.getDataPointsCount());
            metric.updateDataPoint(dataPointId);
        }
    }

    private <M, D> MetricFramework.MetricContext<M, D> getRandomMetric() {
        int metricId = random.nextInt(getMetricsCount());
        return getMetricContext(metricId);
    }

    /**
     * Get the metric context for a specific metric id.
     *
     * @param metricId the metric id
     * @return the metric context
     * @throws IllegalArgumentException if the metric id is invalid
     */
    @SuppressWarnings("unchecked")
    public <M, D> MetricContext<M, D> getMetricContext(int metricId) {
        if (metricId < 0 || metricId >= metrics.size()) {
            throw new IllegalArgumentException(
                    "Invalid metricId: " + metricId + ", must be between 0 and " + (metrics.size() - 1));
        }
        return metrics.get(metricId);
    }

    /**
     * A context for a specific metric, including label names amd its data points fixed size.
     * Knowing datapoint id,
     */
    public class MetricContext<M, D> {
        private final M metric;
        private final String[] labelNames;
        private final MetricAdapter<M, D> metricAdapter;
        private final int dataPointsCount;

        private MetricContext(MetricAdapter<M, D> metricAdapter, int metricId, boolean initDataPoint) {
            this.metricAdapter = metricAdapter;
            this.metric = metricAdapter.createMetric(metricId, EMPTY_LABELS);
            labelNames = EMPTY_LABELS;

            dataPointsCount = 1;
            if (initDataPoint) {
                updateDataPoint(0);
            }
        }

        private MetricContext(
                MetricAdapter<M, D> metricAdapter,
                int metricId,
                String[] labelNames,
                int dataPointsCount,
                boolean initDataPoints) {
            if (dataPointsCount <= 0) {
                throw new IllegalArgumentException("dataPointsCount must be positive");
            }

            this.metricAdapter = metricAdapter;
            this.metric = metricAdapter.createMetric(metricId, labelNames);
            this.labelNames = labelNames;
            this.dataPointsCount = dataPointsCount;

            if (initDataPoints) {
                for (int i = 0; i < dataPointsCount; i++) {
                    updateDataPoint(i);
                }
            }
        }

        /**
         * Generate label values for a specific data point id.
         * This operation is idempotent and for same data point id will generate same label values.
         *
         * @param dataPointId the data point id
         * @return the generated label values
         */
        protected String[] generateLabelValues(int dataPointId) {
            if (labelNames.length == 0) {
                return EMPTY_LABELS;
            }
            String[] labelValues = initLabelValuesTemplate(labelNames);
            for (int j = 0; j < labelNames.length; j++) {
                updateLabelValue(labelValues, j, "value_" + dataPointId + "_" + j);
            }
            return labelValues;
        }

        /**
         * Update all data points of this metric.
         */
        public void updateAllDataPoints() {
            for (int i = 0; i < getDataPointsCount(); i++) {
                updateDataPoint(i);
            }
        }

        /**
         * @return number of data points for this metric
         */
        public int getDataPointsCount() {
            return dataPointsCount;
        }

        /**
         * Update a specific data point of this metric.
         *
         * @param dataPointId the data point id
         */
        public void updateDataPoint(int dataPointId) {
            if (dataPointId < 0 || dataPointId >= dataPointsCount) {
                throw new IllegalArgumentException(
                        "Invalid dataPointId: " + dataPointId + ", must be between 0 and " + (dataPointsCount - 1));
            }
            String[] dataPointLabelValue = generateLabelValues(dataPointId);
            metricAdapter.updateDataPoint(metric, dataPointLabelValue, dataPointId);
        }
    }
}
