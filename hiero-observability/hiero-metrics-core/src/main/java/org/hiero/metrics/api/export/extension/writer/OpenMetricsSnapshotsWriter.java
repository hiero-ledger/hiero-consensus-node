// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension.writer;

import static org.hiero.metrics.api.stat.StatUtils.ONE;
import static org.hiero.metrics.api.stat.StatUtils.ZERO;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.MetricMetadata;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.export.snapshot.DataPointSnapshot;
import org.hiero.metrics.api.export.snapshot.GenericMultiValueDataPointSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricsSnapshot;
import org.hiero.metrics.api.export.snapshot.SingleValueDataPointSnapshot;
import org.hiero.metrics.api.export.snapshot.StateSetDataPointSnapshot;

/**
 * A {@link MetricsSnapshotsWriter} implementation that writes metrics in the OpenMetrics text format.
 *
 * <p>See <a href="https://github.com/prometheus/OpenMetrics/blob/main/specification/OpenMetrics.md">OpenMetrics</a> for details.
 */
public class OpenMetricsSnapshotsWriter
        extends AbstractCachingMetricsSnapshotsWriter<OpenMetricsSnapshotsWriter.MetricExportData> {

    public static final OpenMetricsSnapshotsWriter DEFAULT =
            OpenMetricsSnapshotsWriter.builder().build();

    private static final EnumMap<MetricType, String> METRIC_TYPES = new EnumMap<>(MetricType.class);

    static {
        METRIC_TYPES.put(MetricType.UNKNOWN, "unknown");
        METRIC_TYPES.put(MetricType.GAUGE, "gauge");
        METRIC_TYPES.put(MetricType.COUNTER, "counter");
        METRIC_TYPES.put(MetricType.STATE_SET, "stateset");
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
    private static final byte[] DOUBLE_POSITIVE_INF = "+Inf".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DOUBLE_NEGATIVE_INF = "-Inf".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DOUBLE_NAN = "NaN".getBytes(StandardCharsets.UTF_8);

    private final boolean writeTimestamp;

    private OpenMetricsSnapshotsWriter(Builder builder) {
        super(builder);
        this.writeTimestamp = builder.writeTimestamp;
    }

    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    private static String getMetricTypeName(MetricType metricType) {
        String typeName = METRIC_TYPES.get(metricType);
        if (typeName == null) {
            throw new IllegalArgumentException("Unsupported metric type: " + metricType);
        }
        return typeName;
    }

    @Override
    protected void writeDataPoint(
            @NonNull Instant timestamp,
            @NonNull DataPointSnapshot dataPointSnapshot,
            @NonNull ByteArrayTemplate template,
            @NonNull OutputStream output)
            throws IOException {

        byte[][] variables = new byte[3][]; // max 3 variables: value type, value, timestamp

        switch (dataPointSnapshot) {
            case SingleValueDataPointSnapshot snapshot -> {
                int varIdx =
                        addValueAndTimestampVariables(timestamp, variables, convertValue(snapshot.getAsDouble()), 0);
                writeDataLine(template, varIdx, variables, output);
            }
            case GenericMultiValueDataPointSnapshot snapshot -> {
                for (int i = 0; i < snapshot.valuesCount(); i++) {
                    variables[0] = escape(snapshot.valueTypeAt(i)).getBytes(StandardCharsets.UTF_8);
                    int varIdx =
                            addValueAndTimestampVariables(timestamp, variables, convertValue(snapshot.valueAt(i)), 1);
                    writeDataLine(template, varIdx, variables, output);
                }
            }
            case StateSetDataPointSnapshot<?> snapshot -> {
                Enum<?>[] states = snapshot.states();
                for (int i = 0; i < states.length; i++) {
                    variables[0] = escape(states[i].toString()).getBytes(StandardCharsets.UTF_8);
                    int varIdx = addValueAndTimestampVariables(
                            timestamp, variables, convertValue(snapshot.state(i) ? ONE : ZERO), 1);
                    writeDataLine(template, varIdx, variables, output);
                }
            }
            default ->
                throw new IllegalArgumentException(
                        "Unsupported data point snapshot type: " + dataPointSnapshot.getClass());
        }
    }

    private int addValueAndTimestampVariables(Instant timestamp, byte[][] variables, byte[] valueBytes, int varIdx) {
        variables[varIdx++] = valueBytes;
        if (writeTimestamp) {
            variables[varIdx++] = convertTimestamp(timestamp.toEpochMilli());
        }
        return varIdx;
    }

    private void writeDataLine(ByteArrayTemplate template, int varCount, byte[][] variables, OutputStream output)
            throws IOException {
        Iterator<byte[]> iterator = template.iterator(varCount, variables);
        while (iterator.hasNext()) {
            output.write(iterator.next());
        }
        output.write(NEW_LINE);
    }

    @Override
    protected void beforeMetricWrite(@NonNull MetricExportData metricExportData, @NonNull OutputStream output)
            throws IOException {
        output.write(metricExportData.metricMetadataLines);
    }

    @Override
    protected void afterSnapshotsWrite(@NonNull MetricsSnapshot snapshots, @NonNull OutputStream output)
            throws IOException {
        output.write(END);
        super.afterSnapshotsWrite(snapshots, output);
    }

    @Override
    protected MetricExportData buildMetricExportData(MetricSnapshot metricSnapshot) {
        return new MetricExportData(metricSnapshot);
    }

    private byte[] convertValue(double value) {
        if (value == Double.POSITIVE_INFINITY) {
            return DOUBLE_POSITIVE_INF;
        } else if (value == Double.NEGATIVE_INFINITY) {
            return DOUBLE_NEGATIVE_INF;
        } else if (Double.isNaN(value)) {
            return DOUBLE_NAN;
        } else {
            return format(value).getBytes(StandardCharsets.UTF_8);
        }
    }

    private byte[] convertTimestamp(long timestampMs) {
        String result = timestampMs / 1000L + ".";
        long ms = timestampMs % 1000;
        if (ms < 100) {
            result += "0";
        }
        if (ms < 10) {
            result += "0";
        }
        result += Long.toString(ms);
        return result.getBytes(StandardCharsets.UTF_8);
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

    /**
     * Class for storing serialized data for a single metric, including metadata lines and
     * pre-built data point export data templates.
     */
    public class MetricExportData extends BaseMetricExportData {

        private final byte[] metricNameBytes;
        private final byte[] metricMetadataLines;

        protected MetricExportData(MetricSnapshot metricSnapshot) {
            super(metricSnapshot);

            final MetricMetadata metadata = metricSnapshot.metadata();
            String metricName = metadata.name();
            String metricUnit = metadata.unit();

            if (!metricUnit.isEmpty() && metadata.metricType() != MetricType.STATE_SET) {
                metricName += '_' + metricUnit;
            }

            this.metricNameBytes = metricName.getBytes(StandardCharsets.UTF_8);

            final UnsynchronizedByteArrayOutputStream metadataLine = new UnsynchronizedByteArrayOutputStream(128);
            metadataLine.write(TYPE);
            metadataLine.write(metricNameBytes);
            metadataLine.write(SPACE);
            metadataLine.writeUtf8(getMetricTypeName(metadata.metricType()));
            metadataLine.write(NEW_LINE);

            if (!metricUnit.isEmpty() && metadata.metricType() != MetricType.STATE_SET) {
                metadataLine.write(UNIT);
                metadataLine.write(metricNameBytes);
                metadataLine.write(SPACE);
                metadataLine.writeUtf8(metricUnit);
                metadataLine.write(NEW_LINE);
            }
            if (!metadata.description().isEmpty()) {
                metadataLine.write(HELP);
                metadataLine.writeUtf8(metricName);
                metadataLine.write(SPACE);
                metadataLine.writeUtf8(escape(metadata.description()));
                metadataLine.write(NEW_LINE);
            }

            metricMetadataLines = metadataLine.toByteArray();
        }

        @Override
        protected ByteArrayTemplate buildDataPointExportTemplate(DataPointSnapshot dataPointSnapshot) {
            return switch (dataPointSnapshot) {
                case SingleValueDataPointSnapshot snapshot -> buildSingleValueTemplate(snapshot);
                case GenericMultiValueDataPointSnapshot snapshot -> buildGenericMultiValueTemplate(snapshot);
                case StateSetDataPointSnapshot<?> snapshot -> buildStateSetTemplate(snapshot);
                default ->
                    throw new IllegalArgumentException(
                            "Unsupported data point snapshot type: " + dataPointSnapshot.getClass());
            };
        }

        private ByteArrayTemplate buildSingleValueTemplate(SingleValueDataPointSnapshot dataPointSnapshot) {
            ByteArrayTemplate.Builder builder = ByteArrayTemplate.builder();

            builder.append(metricNameBytes);
            if (metricSnapshot().metadata().metricType() == MetricType.COUNTER) {
                builder.append(COUNTER_SUFFIX);
            }

            if (!metricSnapshot().constantLabels().isEmpty()
                    || !metricSnapshot().dynamicLabelNames().isEmpty()) {
                builder.append(OPEN_BRACKET);
                appendLabels(dataPointSnapshot, builder);
                builder.append(CLOSE_BRACKET);
            }

            appendValueAndTimestamp(builder);

            return builder.build();
        }

        private ByteArrayTemplate buildGenericMultiValueTemplate(GenericMultiValueDataPointSnapshot dataPointSnapshot) {
            ByteArrayTemplate.Builder builder =
                    ByteArrayTemplate.builder().append(metricNameBytes).append(OPEN_BRACKET);

            boolean firstLabel = appendLabels(dataPointSnapshot, builder);
            if (!firstLabel) {
                builder.append(COMMA);
            }
            builder.appendUtf8(dataPointSnapshot.valueClassifier())
                    .append(EQUALS_QUOTE)
                    .addPlaceholder() // Placeholder for value type
                    .append(QUOTE)
                    .append(CLOSE_BRACKET);

            appendValueAndTimestamp(builder);

            return builder.build();
        }

        private ByteArrayTemplate buildStateSetTemplate(StateSetDataPointSnapshot<?> dataPointSnapshot) {
            ByteArrayTemplate.Builder builder = ByteArrayTemplate.builder().append(metricNameBytes);

            // state set requires an additional label with name equal to metric name and value equal to state name
            builder.append(OPEN_BRACKET);
            boolean firstLabel = appendLabels(dataPointSnapshot, builder);
            if (!firstLabel) {
                builder.append(COMMA);
            }
            builder.append(metricNameBytes)
                    .append(EQUALS_QUOTE)
                    .addPlaceholder() // Placeholder for state name
                    .append(QUOTE)
                    .append(CLOSE_BRACKET);

            appendValueAndTimestamp(builder);

            return builder.build();
        }

        private void appendValueAndTimestamp(ByteArrayTemplate.Builder builder) {
            builder.append(SPACE).addPlaceholder(); // Placeholder for value

            if (writeTimestamp) {
                builder.append(SPACE).addPlaceholder(); // Placeholder for timestamp
            }
        }

        private boolean appendLabels(DataPointSnapshot dataPointSnapshot, ByteArrayTemplate.Builder builder) {
            boolean firstLabel = appendConstantLabels(builder);
            return appendDynamicLabels(dataPointSnapshot, builder, firstLabel);
        }

        private boolean appendConstantLabels(ByteArrayTemplate.Builder builder) {
            boolean first = true;
            for (Label label : metricSnapshot().constantLabels()) {
                if (!first) {
                    builder.append(COMMA);
                }
                first = false;
                builder.appendUtf8(label.name())
                        .append(EQUALS_QUOTE)
                        .appendUtf8(escape(label.value()))
                        .append(QUOTE);
            }
            return first;
        }

        private boolean appendDynamicLabels(
                DataPointSnapshot dataPointSnapshot, ByteArrayTemplate.Builder builder, boolean firstLabel) {
            List<String> labelNames = metricSnapshot().dynamicLabelNames();
            for (int i = 0; i < labelNames.size(); i++) {
                String labelValue = dataPointSnapshot.labelValue(i);
                if (!firstLabel) {
                    builder.append(COMMA);
                }
                firstLabel = false;
                builder.appendUtf8(labelNames.get(i))
                        .append(EQUALS_QUOTE)
                        .appendUtf8(escape(labelValue))
                        .append(QUOTE);
            }
            return firstLabel;
        }
    }

    public static class Builder extends AbstractMetricsSnapshotsWriter.Builder<Builder, OpenMetricsSnapshotsWriter> {

        private boolean writeTimestamp = false;

        public Builder writeTimestamp() {
            this.writeTimestamp = true;
            return this;
        }

        @Override
        public OpenMetricsSnapshotsWriter build() {
            return new OpenMetricsSnapshotsWriter(this);
        }

        @NonNull
        @Override
        protected Builder self() {
            return this;
        }
    }
}
