// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension.writer;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;

/**
 * A base class for {@link MetricsSnapshotsWriter} implementations that cache metric export data
 * between writes to optimize performance.
 * <p>
 * Subclasses must implement the abstract methods to define
 * how individual measurements are written and how metric export data is constructed.
 *
 * @param <M> the type of metric export data used for caching
 */
public abstract class AbstractCachingMetricsSnapshotsWriter<M extends BaseMetricExportData>
        extends AbstractMetricsSnapshotsWriter {

    private final Map<MetricSnapshot, M> metricCache = new HashMap<>();

    protected AbstractCachingMetricsSnapshotsWriter(Builder<?, ?> builder) {
        super(builder);
    }

    /**
     * Clears the cache of metrics export data.
     */
    public void clearCache() {
        metricCache.clear();
    }

    @Override
    protected final void writeMetricSnapshot(Instant timestamp, MetricSnapshot metricSnapshot, OutputStream output)
            throws IOException {
        M metricExportData = metricCache.computeIfAbsent(metricSnapshot, this::buildMetricExportData);

        beforeMetricWrite(metricExportData, output);
        int size = metricSnapshot.size();
        for (int i = 0; i < size; i++) {
            MeasurementSnapshot measurementSnapshot = metricSnapshot.get(i);
            ByteArrayTemplate measurementExportTemplate =
                    metricExportData.getOrCreateMeasurementExportTemplate(measurementSnapshot);
            writeMeasurement(timestamp, measurementSnapshot, measurementExportTemplate, output);
        }
        afterMetricWrite(metricExportData, output);
    }

    /**
     * Called before writing each metric and its measurements.
     *
     * @param metricExportData the metric export data
     * @param output the output stream to write to
     * @throws IOException if an I/O error occurs
     */
    protected void beforeMetricWrite(@NonNull M metricExportData, @NonNull OutputStream output) throws IOException {
        // nothing by default
    }

    /**
     * Called after writing each metric and its measurements.
     *
     * @param metricExportData the metric export data
     * @param output the output stream to write to
     * @throws IOException if an I/O error occurs
     */
    protected void afterMetricWrite(@NonNull M metricExportData, @NonNull OutputStream output) throws IOException {
        // nothing by default
    }

    /**
     * Writes a single measurement to the output stream using the provided export template. <br>
     * Usually implementation defines variables that measurement snapshot can provide and uses them to
     * fill the template.
     *
     * @param timestamp the timestamp of the measurement
     * @param measurementSnapshot the measurement snapshot to write
     * @param measurementExportTemplate the export template for the measurement
     * @param output the output stream to write to
     * @throws IOException if an I/O error occurs
     */
    protected abstract void writeMeasurement(
            @NonNull Instant timestamp,
            @NonNull MeasurementSnapshot measurementSnapshot,
            @NonNull ByteArrayTemplate measurementExportTemplate,
            @NonNull OutputStream output)
            throws IOException;

    /**
     * Builds the metric export data for the given metric snapshot.
     * Subclasses must implement this method to define how the metric export data is constructed.
     *
     * @param metricSnapshot the metric snapshot
     * @return the constructed metric export data
     */
    protected abstract M buildMetricExportData(MetricSnapshot metricSnapshot);
}
