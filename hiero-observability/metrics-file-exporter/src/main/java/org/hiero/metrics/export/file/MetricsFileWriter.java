// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.export.file;

import static java.lang.System.Logger.Level.WARNING;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import org.hiero.metrics.core.DoubleMeasurementSnapshot;
import org.hiero.metrics.core.LongMeasurementSnapshot;
import org.hiero.metrics.core.MeasurementSnapshot;
import org.hiero.metrics.core.MetricRegistrySnapshot;
import org.hiero.metrics.core.MetricSnapshot;
import org.hiero.metrics.core.MetricType;
import org.hiero.metrics.export.OpenMetricsTextUtils;

/**
 * A writer that writes metrics in mixture of Prometheus/OpenMetrics text format.
 * <p>
 * This class in not thread-safe, due to the use of {@link DecimalFormat}.
 */
public class MetricsFileWriter {

    private static final System.Logger logger = System.getLogger(MetricsFileWriter.class.getName());

    private static final byte SPACE = ' ';

    private final DecimalFormat formatter;

    private int lastMetricId = -1;

    public MetricsFileWriter(@NonNull String decimalFormat) {
        // Force a locale-independent decimal separator ('.') and disable grouping so the output is
        // always valid Prometheus text format, regardless of the JVM's default locale.
        formatter = new DecimalFormat(decimalFormat, DecimalFormatSymbols.getInstance(Locale.ROOT));
        formatter.setGroupingUsed(false);
    }

    public final void write(MetricRegistrySnapshot registrySnapshot, OutputStream output) throws IOException {
        byte[] timestampAndNewLine = spaceTimestampAndNewLineBytes(registrySnapshot.timestamp());
        int metricId = 0;

        for (MetricSnapshot metricSnapshot : registrySnapshot) {
            metricId++;
            writeMetric(metricId, metricSnapshot, timestampAndNewLine, output);
        }
        lastMetricId = metricId;

        output.flush();
    }

    private void writeMetric(
            int metricId, MetricSnapshot metricSnapshot, byte[] timestampAndNewLine, OutputStream output)
            throws IOException {
        byte[] metricNameBytes = writeMetricMetadata(metricId, metricSnapshot, output);

        for (MeasurementSnapshot measurementSnapshot : metricSnapshot) {
            if (measurementSnapshot instanceof LongMeasurementSnapshot longSnapshot) {
                writeSingleValueMeasurementMetadata(metricNameBytes, metricSnapshot, measurementSnapshot, output);
                output.write(OpenMetricsTextUtils.convertValue(formatter, longSnapshot.get()));
                output.write(timestampAndNewLine);
            } else if (measurementSnapshot instanceof DoubleMeasurementSnapshot doubleSnapshot) {
                writeSingleValueMeasurementMetadata(metricNameBytes, metricSnapshot, measurementSnapshot, output);
                output.write(OpenMetricsTextUtils.convertValue(formatter, doubleSnapshot.get()));
                output.write(timestampAndNewLine);
            } else {
                logger.log(
                        WARNING,
                        "Skipping unsupported measurement snapshot type: {0}",
                        measurementSnapshot.getClass().getName());
            }
        }
    }

    private byte[] writeMetricMetadata(int metricId, MetricSnapshot metricSnapshot, OutputStream output)
            throws IOException {
        String metricName = metricSnapshot.name();
        final String metricUnit = metricSnapshot.unit();

        if (metricUnit != null && !metricUnit.isBlank()) {
            metricName += '_' + metricUnit;
        }
        if (metricSnapshot.type() == MetricType.COUNTER) {
            metricName += "_total";
        }

        byte[] metricNameBytes = metricName.getBytes(StandardCharsets.UTF_8);

        if (metricId > lastMetricId) {
            // write metric metadata only once on first occurrence of the metric name
            OpenMetricsTextUtils.writeTypeLine(output, metricNameBytes, metricSnapshot.type());
            OpenMetricsTextUtils.writeHelpLine(output, metricNameBytes, metricSnapshot.description());
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

        OpenMetricsTextUtils.writeLabels(output, metricSnapshot, measurementSnapshot);

        output.write(SPACE);
    }

    private static byte[] spaceTimestampAndNewLineBytes(long timestampMillis) {
        final byte[] msBytes = Long.toString(timestampMillis).getBytes(StandardCharsets.UTF_8);
        final byte[] buf = new byte[2 + msBytes.length];
        buf[0] = ' ';
        System.arraycopy(msBytes, 0, buf, 1, msBytes.length);
        buf[buf.length - 1] = '\n';
        return buf;
    }
}
