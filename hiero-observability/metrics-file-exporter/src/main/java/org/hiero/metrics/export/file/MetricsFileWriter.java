// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.export.file;

import static java.lang.System.Logger.Level.WARNING;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
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

    // Metrics with metadata already written, by identity: a metric removed and registered again under the same name
    // is a new snapshot and gets its metadata again. Swapped with metrics seen by the last write to forget removed
    // ones.
    private Set<MetricSnapshot> describedMetrics = newIdentitySet();
    private Set<MetricSnapshot> writtenMetrics = newIdentitySet();

    public MetricsFileWriter(@NonNull String decimalFormat) {
        // Force a locale-independent decimal separator ('.') and disable grouping so the output is
        // always valid Prometheus text format, regardless of the JVM's default locale.
        formatter = new DecimalFormat(decimalFormat, DecimalFormatSymbols.getInstance(Locale.ROOT));
        formatter.setGroupingUsed(false);
    }

    public final void write(MetricRegistrySnapshot registrySnapshot, OutputStream output) throws IOException {
        byte[] timestampAndNewLine = spaceTimestampAndNewLineBytes(registrySnapshot.timestamp());
        writtenMetrics.clear();

        for (MetricSnapshot metricSnapshot : registrySnapshot) {
            writeMetric(metricSnapshot, timestampAndNewLine, output);
            writtenMetrics.add(metricSnapshot);
        }
        // The metrics of this write are the described ones of the next write. The previous set is reused for the
        // next write and must be a different object than describedMetrics, because the next write clears it first.
        final Set<MetricSnapshot> previouslyDescribed = describedMetrics;
        describedMetrics = writtenMetrics;
        writtenMetrics = previouslyDescribed;

        output.flush();
    }

    private void writeMetric(MetricSnapshot metricSnapshot, byte[] timestampAndNewLine, OutputStream output)
            throws IOException {
        byte[] metricNameBytes = writeMetricMetadata(metricSnapshot, output);

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

    private byte[] writeMetricMetadata(MetricSnapshot metricSnapshot, OutputStream output) throws IOException {
        String metricName = metricSnapshot.name();
        final String metricUnit = metricSnapshot.unit();

        if (metricUnit != null && !metricUnit.isBlank()) {
            metricName += '_' + metricUnit;
        }
        if (metricSnapshot.type() == MetricType.COUNTER) {
            metricName += "_total";
        }

        byte[] metricNameBytes = metricName.getBytes(StandardCharsets.UTF_8);

        if (!describedMetrics.contains(metricSnapshot)) {
            // write metric metadata only once on first occurrence of the metric
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

    private static Set<MetricSnapshot> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static byte[] spaceTimestampAndNewLineBytes(long timestampMillis) {
        return (" " + timestampMillis + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
