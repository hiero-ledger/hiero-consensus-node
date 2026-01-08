// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension.writer;

import java.util.HashMap;
import java.util.Map;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;

/**
 * Base class for metric export data used in caching by {@link AbstractCachingMetricsSnapshotsWriter}.
 * Holds a reference to the associated {@link MetricSnapshot} and a cache of export templates
 * for individual {@link MeasurementSnapshot}s.
 *
 * @see ByteArrayTemplate
 */
public abstract class BaseMetricExportData {

    private final MetricSnapshot metricSnapshot;
    private final Map<MeasurementSnapshot, ByteArrayTemplate> measurementCache = new HashMap<>();

    protected BaseMetricExportData(MetricSnapshot metricSnapshot) {
        this.metricSnapshot = metricSnapshot;
    }

    /**
     * @return the associated metric snapshot
     */
    public final MetricSnapshot metricSnapshot() {
        return metricSnapshot;
    }

    /**
     * Clears the cache of measurement export templates.
     */
    public final void clearCache() {
        measurementCache.clear();
    }

    /**
     * Retrieves the cached export template for the given measurement snapshot, or creates and caches
     * a new one if it does not exist.
     *
     * @param measurementSnapshot the measurement snapshot
     * @return the cached or newly created measurement export template
     */
    public final ByteArrayTemplate getOrCreateMeasurementExportTemplate(MeasurementSnapshot measurementSnapshot) {
        return measurementCache.computeIfAbsent(measurementSnapshot, this::buildMeasurementExportTemplate);
    }

    /**
     * Builds a new export template for the given measurement snapshot.
     * Subclasses must implement this method to define how the template is constructed.
     *
     * @param measurementSnapshot the measurement snapshot
     * @return the newly created measurement export template
     */
    protected abstract ByteArrayTemplate buildMeasurementExportTemplate(MeasurementSnapshot measurementSnapshot);
}
