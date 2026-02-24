// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import static java.lang.System.Logger.Level.WARNING;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.EnumMap;
import java.util.List;
import org.hiero.metrics.core.DoubleMeasurementSnapshot;
import org.hiero.metrics.core.Label;
import org.hiero.metrics.core.LabelValues;
import org.hiero.metrics.core.LongMeasurementSnapshot;
import org.hiero.metrics.core.MeasurementSnapshot;
import org.hiero.metrics.core.MetricRegistrySnapshot;
import org.hiero.metrics.core.MetricSnapshot;
import org.hiero.metrics.core.MetricType;

/**
 * A writer that writes metrics in the OpenMetrics text format.
 * <p>
 * This class in not thread-safe, due to the use of {@link DecimalFormat}.
 *
 * <p>See <a href="https://github.com/prometheus/OpenMetrics/blob/main/specification/OpenMetrics.md">OpenMetrics</a> for details.
 */
class OpenMetricsWriter {

    private static final System.Logger logger = System.getLogger(OpenMetricsWriter.class.getName());

    private static final EnumMap<MetricType, byte[]> METRIC_TYPES = new EnumMap<>(MetricType.class);
    private static final byte[] UNKNOWN_TYPE = "unknown".getBytes(StandardCharsets.UTF_8);

    static {
        METRIC_TYPES.put(MetricType.GAUGE, "gauge".getBytes(StandardCharsets.UTF_8));
        METRIC_TYPES.put(MetricType.COUNTER, "counter".getBytes(StandardCharsets.UTF_8));
    }

    private static final byte COMMA = ',';
    private static final byte QUOTE = '"';
    private static final byte SPACE = ' ';
    private static final byte NEW_LINE = '\n';
    private static final byte OPEN_BRACKET = '{';
    private static final byte CLOSE_BRACKET = '}';
    private static final byte[] EQUALS_QUOTE = "=\"".getBytes(StandardCharsets.UTF_8);

    private static final byte[] COUNTER_SUFFIX = "_total".getBytes(StandardCharsets.UTF_8);

    private static final byte[] TYPE = "# TYPE ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNIT = "# UNIT ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HELP = "# HELP ".getBytes(StandardCharsets.UTF_8);

    private static final byte[] END = "# EOF\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] POSITIVE_INF = "+Inf".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEGATIVE_INF = "-Inf".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NAN = "NaN".getBytes(StandardCharsets.UTF_8);

    private final DecimalFormat formatter;

    public OpenMetricsWriter(String decimalFormat) {
        formatter = new DecimalFormat(decimalFormat);
    }

    public final void write(MetricRegistrySnapshot registrySnapshot, OutputStream output) throws IOException {
        for (MetricSnapshot metricSnapshot : registrySnapshot) {
            writeMetric(metricSnapshot, output);
        }

        output.write(END);
        output.flush();
    }

    private void writeMetric(MetricSnapshot metricSnapshot, OutputStream output) throws IOException {
        byte[] metricNameBytes = writeMetricMetadata(metricSnapshot, output);

        for (MeasurementSnapshot measurementSnapshot : metricSnapshot) {
            if (measurementSnapshot instanceof LongMeasurementSnapshot longSnapshot) {
                writeSingleValueMeasurementMetadata(metricNameBytes, metricSnapshot, measurementSnapshot, output);
                output.write(convertValue(longSnapshot.get()));
                output.write(NEW_LINE);
            } else if (measurementSnapshot instanceof DoubleMeasurementSnapshot doubleSnapshot) {
                writeSingleValueMeasurementMetadata(metricNameBytes, metricSnapshot, measurementSnapshot, output);
                output.write(convertValue(doubleSnapshot.get()));
                output.write(NEW_LINE);
            } else {
                logger.log(
                        WARNING,
                        "Skipping unsupported measurement snapshot type: {0}",
                        measurementSnapshot.getClass().getName());
            }
        }
    }

    private byte[] writeMetricMetadata(MetricSnapshot metricSnapshot, OutputStream output) throws IOException {
        String metricName = metricSnapshot.name();
        final String metricUnit = metricSnapshot.unit();
        final boolean metricUnitAvailable = metricUnit != null && !metricUnit.isBlank();

        if (metricUnitAvailable) {
            metricName += '_' + metricUnit;
        }

        byte[] metricNameBytes = metricName.getBytes(StandardCharsets.UTF_8);

        output.write(TYPE);
        output.write(metricNameBytes);
        output.write(SPACE);
        output.write(METRIC_TYPES.getOrDefault(metricSnapshot.type(), UNKNOWN_TYPE));
        output.write(NEW_LINE);

        if (metricUnitAvailable) {
            output.write(UNIT);
            output.write(metricNameBytes);
            output.write(SPACE);
            output.write(metricUnit.getBytes(StandardCharsets.UTF_8));
            output.write(NEW_LINE);
        }

        String description = metricSnapshot.description();
        if (description != null && !description.isBlank()) {
            output.write(HELP);
            output.write(metricNameBytes);
            output.write(SPACE);
            output.write(escape(description).getBytes(StandardCharsets.UTF_8));
            output.write(NEW_LINE);
        }

        return metricNameBytes;
    }

    private void writeSingleValueMeasurementMetadata(
            byte[] metricNameBytes,
            MetricSnapshot metricSnapshot,
            MeasurementSnapshot measurementSnapshot,
            OutputStream output)
            throws IOException {
        output.write(metricNameBytes);
        if (metricSnapshot.type() == MetricType.COUNTER) {
            output.write(COUNTER_SUFFIX);
        }

        if (!metricSnapshot.staticLabels().isEmpty()
                || !metricSnapshot.dynamicLabelNames().isEmpty()) {
            output.write(OPEN_BRACKET);
            boolean firstLabel = appendStaticLabels(metricSnapshot, output);
            appendDynamicLabels(metricSnapshot, measurementSnapshot, output, firstLabel);
            output.write(CLOSE_BRACKET);
        }

        output.write(SPACE);
    }

    private byte[] convertValue(long value) {
        if (value == Long.MAX_VALUE) {
            return POSITIVE_INF;
        } else if (value == Long.MIN_VALUE) {
            return NEGATIVE_INF;
        } else return formatter.format(value).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] convertValue(double value) {
        if (value == Double.POSITIVE_INFINITY) {
            return POSITIVE_INF;
        } else if (value == Double.NEGATIVE_INFINITY) {
            return NEGATIVE_INF;
        } else if (Double.isNaN(value)) {
            return NAN;
        } else {
            return formatter.format(value).getBytes(StandardCharsets.UTF_8);
        }
    }

    private boolean appendStaticLabels(MetricSnapshot metricSnapshot, OutputStream output) throws IOException {
        boolean first = true;
        for (Label label : metricSnapshot.staticLabels()) {
            if (!first) {
                output.write(COMMA);
            }
            first = false;
            output.write(label.name().getBytes(StandardCharsets.UTF_8));
            output.write(EQUALS_QUOTE);
            output.write(escape(label.value()).getBytes(StandardCharsets.UTF_8));
            output.write(QUOTE);
        }
        return first;
    }

    private void appendDynamicLabels(
            MetricSnapshot metricSnapshot,
            MeasurementSnapshot measurementSnapshot,
            OutputStream output,
            boolean firstLabel)
            throws IOException {
        List<String> labelNames = metricSnapshot.dynamicLabelNames();
        LabelValues dynamicLabelValues = measurementSnapshot.getDynamicLabelValues();

        for (int i = 0; i < labelNames.size(); i++) {
            String labelValue = dynamicLabelValues.get(i);
            if (!firstLabel) {
                output.write(COMMA);
            }
            firstLabel = false;
            output.write(labelNames.get(i).getBytes(StandardCharsets.UTF_8));
            output.write(EQUALS_QUOTE);
            output.write(escape(labelValue).getBytes(StandardCharsets.UTF_8));
            output.write(QUOTE);
        }
    }

    /**
     * Escape newline {@code \n}, double quote {@code "} and backslash {@code \} characters in string values.
     *
     * @param value the string value to escape
     * @return the escaped string
     */
    private static String escape(final String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
