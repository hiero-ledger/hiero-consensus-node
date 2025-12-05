// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension.writer;

import static org.hiero.metrics.api.stat.StatUtils.ONE;
import static org.hiero.metrics.api.stat.StatUtils.ZERO;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.export.snapshot.DataPointSnapshot;
import org.hiero.metrics.api.export.snapshot.DoubleValueDataPointSnapshot;
import org.hiero.metrics.api.export.snapshot.LongValueDataPointSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;
import org.hiero.metrics.api.export.snapshot.MultiValueDataPointSnapshot;
import org.hiero.metrics.api.export.snapshot.StateSetDataPointSnapshot;

/**
 * A {@link MetricsSnapshotsWriter} implementation that writes metrics in CSV format.
 *
 * <p>CSV Format:
 *
 * <pre>
 * timestamp,metric,unit,value,labels
 * 2024-10-01T12:00:00Z,cpu_usage,percentage,75.5,"host=server1;region=us-west"
 * 2024-10-01T12:00:00Z,memory_usage,MB,2048,"host=server1;region=us-west"
 * </pre>
 *
 * <p>Labels are enclosed in quotes and separated by semicolons to handle commas in label values.
 */
public class CsvMetricsSnapshotsWriter
        extends AbstractCachingMetricsSnapshotsWriter<CsvMetricsSnapshotsWriter.MetricExportData> {

    public static final CsvMetricsSnapshotsWriter DEFAULT = builder().build();

    private static final byte COMMA = ',';
    private static final byte QUOTE = '"';
    private static final byte EQUALS = '=';
    private static final byte NEW_LINE = '\n';

    private CsvMetricsSnapshotsWriter(Builder builder) {
        super(builder);
    }

    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    public void writeHeaders(OutputStream outputStream) throws IOException {
        outputStream.write("timestamp,metric,unit,value,labels\n".getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void writeDataPoint(
            @NonNull Instant timestamp,
            @NonNull DataPointSnapshot dataPointSnapshot,
            @NonNull ByteArrayTemplate template,
            @NonNull OutputStream output)
            throws IOException {
        byte[][] variables = new byte[3][]; // max 3 variables: timestamp, value, value type
        variables[0] = timestamp.toString().getBytes(StandardCharsets.UTF_8);

        switch (dataPointSnapshot) {
            case LongValueDataPointSnapshot snapshot -> {
                variables[1] = format(snapshot.getAsLong()).getBytes(StandardCharsets.UTF_8);
                writeDataLine(template, 2, variables, output);
            }
            case DoubleValueDataPointSnapshot snapshot -> {
                variables[1] = format(snapshot.getAsDouble()).getBytes(StandardCharsets.UTF_8);
                writeDataLine(template, 2, variables, output);
            }
            case MultiValueDataPointSnapshot snapshot -> {
                for (int i = 0; i < snapshot.valuesCount(); i++) {
                    if (snapshot.isFloatingPointAt(i)) {
                        variables[1] = format(snapshot.doubleValueAt(i)).getBytes(StandardCharsets.UTF_8);
                    } else {
                        variables[1] = format(snapshot.longValueAt(i)).getBytes(StandardCharsets.UTF_8);
                    }
                    variables[2] = snapshot.valueTypeAt(i).getBytes(StandardCharsets.UTF_8);
                    writeDataLine(template, 3, variables, output);
                }
            }
            case StateSetDataPointSnapshot<?> snapshot -> {
                Enum<?>[] states = snapshot.states();
                for (int i = 0; i < states.length; i++) {
                    variables[1] = format(snapshot.state(i) ? ONE : ZERO).getBytes(StandardCharsets.UTF_8);
                    variables[2] = states[i].toString().getBytes(StandardCharsets.UTF_8);
                    writeDataLine(template, 3, variables, output);
                }
            }
            default ->
                throw new IllegalArgumentException(
                        "Unsupported data point snapshot type: " + dataPointSnapshot.getClass());
        }
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
    protected MetricExportData buildMetricExportData(MetricSnapshot metricSnapshot) {
        return new MetricExportData(metricSnapshot);
    }

    public static class MetricExportData extends BaseMetricExportData {

        public MetricExportData(MetricSnapshot metricSnapshot) {
            super(metricSnapshot);
        }

        @Override
        protected ByteArrayTemplate buildDataPointExportTemplate(DataPointSnapshot dataPointSnapshot) {
            ByteArrayTemplate.Builder builder = ByteArrayTemplate.builder()
                    .addPlaceholder() // timestamp
                    .append(COMMA)
                    .appendUtf8(metricSnapshot().metadata().name())
                    .append(COMMA)
                    .appendUtf8(metricSnapshot().metadata().unit())
                    .append(COMMA)
                    .addPlaceholder() // value
                    .append(COMMA)
                    .append(QUOTE);

            boolean firstLabel = appendLabels(dataPointSnapshot, builder);
            if (dataPointSnapshot instanceof MultiValueDataPointSnapshot snapshot) {
                appendGenericMultiValueLabels(snapshot, builder, firstLabel);
            } else if (dataPointSnapshot instanceof StateSetDataPointSnapshot) {
                appendStateSetLabels(builder, firstLabel);
            }
            return builder.append(QUOTE).build();
        }

        private void appendStateSetLabels(ByteArrayTemplate.Builder builder, boolean firstLabel) {
            if (!firstLabel) {
                builder.append(COMMA);
            }
            builder.appendUtf8(metricSnapshot().metadata().name())
                    .append(EQUALS)
                    .addPlaceholder(); // Placeholder for state name
        }

        private void appendGenericMultiValueLabels(
                MultiValueDataPointSnapshot snapshot, ByteArrayTemplate.Builder builder, boolean firstLabel) {
            if (!firstLabel) {
                builder.append(COMMA);
            }
            builder.appendUtf8(snapshot.valueClassifier())
                    .append(EQUALS)
                    .addPlaceholder(); // Placeholder for value type
        }

        private boolean appendLabels(DataPointSnapshot dataPointSnapshot, ByteArrayTemplate.Builder builder) {
            boolean firstLabel = appendStaticLabels(builder);
            return appendDynamicLabels(dataPointSnapshot, builder, firstLabel);
        }

        private boolean appendStaticLabels(ByteArrayTemplate.Builder builder) {
            boolean first = true;
            for (Label label : metricSnapshot().staticLabels()) {
                if (!first) {
                    builder.append(COMMA);
                }
                first = false;
                builder.appendUtf8(label.name()).append(EQUALS).appendUtf8(label.value());
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
                builder.appendUtf8(labelNames.get(i)).append(EQUALS).appendUtf8(labelValue);
            }
            return firstLabel;
        }
    }

    public static class Builder extends AbstractMetricsSnapshotsWriter.Builder<Builder, CsvMetricsSnapshotsWriter> {

        @NonNull
        @Override
        public CsvMetricsSnapshotsWriter build() {
            return new CsvMetricsSnapshotsWriter(this);
        }

        @NonNull
        @Override
        protected Builder self() {
            return this;
        }
    }
}
