// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import static java.lang.System.Logger.Level.WARNING;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import org.hiero.metrics.core.DoubleMeasurementSnapshot;
import org.hiero.metrics.core.LongMeasurementSnapshot;
import org.hiero.metrics.core.MeasurementSnapshot;
import org.hiero.metrics.core.MetricRegistrySnapshot;
import org.hiero.metrics.core.MetricSnapshot;
import org.hiero.metrics.core.MetricType;
import org.hiero.metrics.export.OpenMetricsTextUtils;

/**
 * A writer that writes metrics in the OpenMetrics text format.
 * <p>
 * This class in not thread-safe, due to the use of {@link DecimalFormat}.
 *
 * <p>See <a href="https://github.com/prometheus/OpenMetrics/blob/main/specification/OpenMetrics.md">OpenMetrics</a> for details.
 */
class OpenMetricsWriter {

    private static final System.Logger logger = System.getLogger(OpenMetricsWriter.class.getName());

    private static final byte SPACE = ' ';
    private static final byte NEW_LINE = '\n';

    private static final byte[] COUNTER_SUFFIX = "_total".getBytes(StandardCharsets.UTF_8);

    private final DecimalFormat formatter;

    public OpenMetricsWriter(String decimalFormat) {
        formatter = new DecimalFormat(decimalFormat);
    }

    public final void write(MetricRegistrySnapshot registrySnapshot, OutputStream output) throws IOException {
        for (MetricSnapshot metricSnapshot : registrySnapshot) {
            writeMetric(metricSnapshot, output);
        }

        OpenMetricsTextUtils.writeEndLine(output);
        output.flush();
    }

    private void writeMetric(MetricSnapshot metricSnapshot, OutputStream output) throws IOException {
        byte[] metricNameBytes = writeMetricMetadata(metricSnapshot, output);

        for (MeasurementSnapshot measurementSnapshot : metricSnapshot) {
            if (measurementSnapshot instanceof LongMeasurementSnapshot longSnapshot) {
                writeSingleValueMeasurementMetadata(metricNameBytes, metricSnapshot, measurementSnapshot, output);
                output.write(OpenMetricsTextUtils.convertValue(formatter, longSnapshot.get()));
                output.write(NEW_LINE);
            } else if (measurementSnapshot instanceof DoubleMeasurementSnapshot doubleSnapshot) {
                writeSingleValueMeasurementMetadata(metricNameBytes, metricSnapshot, measurementSnapshot, output);
                output.write(OpenMetricsTextUtils.convertValue(formatter, doubleSnapshot.get()));
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

        if (metricUnit != null && !metricUnit.isBlank()) {
            metricName += '_' + metricUnit;
        }

        byte[] metricNameBytes = metricName.getBytes(StandardCharsets.UTF_8);

        OpenMetricsTextUtils.writeTypeLine(output, metricNameBytes, metricSnapshot.type());
        OpenMetricsTextUtils.writeUnitLine(output, metricNameBytes, metricSnapshot.unit());
        OpenMetricsTextUtils.writeHelpLine(output, metricNameBytes, metricSnapshot.description());

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

        OpenMetricsTextUtils.writeLabels(output, metricSnapshot, measurementSnapshot);
        output.write(SPACE);
    }
}
