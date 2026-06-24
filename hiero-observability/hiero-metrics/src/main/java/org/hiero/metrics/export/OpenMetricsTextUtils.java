// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.export;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.EnumMap;
import java.util.List;
import org.hiero.metrics.core.Label;
import org.hiero.metrics.core.LabelValues;
import org.hiero.metrics.core.MeasurementSnapshot;
import org.hiero.metrics.core.MetricSnapshot;
import org.hiero.metrics.core.MetricType;

/**
 * Utility class for writing metrics in OpenMetrics text format.
 */
public final class OpenMetricsTextUtils {

    private static final EnumMap<MetricType, byte[]> METRIC_TYPES = new EnumMap<>(MetricType.class);
    private static final byte[] UNKNOWN_TYPE = "unknown".getBytes(StandardCharsets.UTF_8);

    private static final byte COMMA = ',';
    private static final byte QUOTE = '"';
    private static final byte SPACE = ' ';
    private static final byte NEW_LINE = '\n';
    private static final byte OPEN_BRACKET = '{';
    private static final byte CLOSE_BRACKET = '}';
    private static final byte[] EQUALS_QUOTE = "=\"".getBytes(StandardCharsets.UTF_8);

    private static final byte[] TYPE = "# TYPE ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNIT = "# UNIT ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HELP = "# HELP ".getBytes(StandardCharsets.UTF_8);

    private static final byte[] POSITIVE_INF = "+Inf".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEGATIVE_INF = "-Inf".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NAN = "NaN".getBytes(StandardCharsets.UTF_8);

    private static final byte[] END = "# EOF\n".getBytes(StandardCharsets.UTF_8);

    static {
        METRIC_TYPES.put(MetricType.GAUGE, "gauge".getBytes(StandardCharsets.UTF_8));
        METRIC_TYPES.put(MetricType.COUNTER, "counter".getBytes(StandardCharsets.UTF_8));
    }

    private OpenMetricsTextUtils() {}

    public static byte[] convertValue(DecimalFormat formatter, long value) {
        if (value == Long.MAX_VALUE) {
            return POSITIVE_INF;
        } else if (value == Long.MIN_VALUE) {
            return NEGATIVE_INF;
        } else return formatter.format(value).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] convertValue(DecimalFormat formatter, double value) {
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

    public static void writeTypeLine(OutputStream output, byte[] metricNameBytes, MetricType metricType)
            throws IOException {
        output.write(TYPE);
        output.write(metricNameBytes);
        output.write(SPACE);
        output.write(METRIC_TYPES.getOrDefault(metricType, UNKNOWN_TYPE));
        output.write(NEW_LINE);
    }

    public static void writeUnitLine(OutputStream output, byte[] metricNameBytes, String metricUnit)
            throws IOException {
        if (metricUnit != null && !metricUnit.isBlank()) {
            output.write(UNIT);
            output.write(metricNameBytes);
            output.write(SPACE);
            output.write(metricUnit.getBytes(StandardCharsets.UTF_8));
            output.write(NEW_LINE);
        }
    }

    public static void writeHelpLine(OutputStream output, byte[] metricNameBytes, String description)
            throws IOException {
        if (description != null && !description.isBlank()) {
            output.write(HELP);
            output.write(metricNameBytes);
            output.write(SPACE);
            output.write(escape(description).getBytes(StandardCharsets.UTF_8));
            output.write(NEW_LINE);
        }
    }

    public static void writeLabels(
            OutputStream output, MetricSnapshot metricSnapshot, MeasurementSnapshot measurementSnapshot)
            throws IOException {
        if (!metricSnapshot.staticLabels().isEmpty()
                || !metricSnapshot.dynamicLabelNames().isEmpty()) {
            output.write(OPEN_BRACKET);
            boolean firstLabel = appendStaticLabels(metricSnapshot, output);
            appendDynamicLabels(metricSnapshot, measurementSnapshot, output, firstLabel);
            output.write(CLOSE_BRACKET);
        }
    }

    public static void writeEndLine(OutputStream output) throws IOException {
        output.write(END);
    }

    private static boolean appendStaticLabels(MetricSnapshot metricSnapshot, OutputStream output) throws IOException {
        boolean first = true;
        for (Label label : metricSnapshot.staticLabels()) {
            if (!first) {
                output.write(COMMA);
            }
            first = false;
            output.write(label.name().getBytes(StandardCharsets.UTF_8));
            output.write(EQUALS_QUOTE);
            output.write(OpenMetricsTextUtils.escape(label.value()).getBytes(StandardCharsets.UTF_8));
            output.write(QUOTE);
        }
        return first;
    }

    private static void appendDynamicLabels(
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
            output.write(OpenMetricsTextUtils.escape(labelValue).getBytes(StandardCharsets.UTF_8));
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
