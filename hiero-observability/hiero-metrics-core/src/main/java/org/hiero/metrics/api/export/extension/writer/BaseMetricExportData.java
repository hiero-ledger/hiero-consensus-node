// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension.writer;

import java.util.HashMap;
import java.util.Map;
import org.hiero.metrics.api.export.snapshot.DataPointSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;

/**
 * Base class for metric export data used in caching by {@link AbstractCachingMetricsSnapshotsWriter}.
 * Holds a reference to the associated {@link MetricSnapshot} and a cache of export templates
 * for individual {@link DataPointSnapshot}s.
 *
 * @see ByteArrayTemplate
 */
public abstract class BaseMetricExportData {

    private final MetricSnapshot metricSnapshot;
    private final Map<DataPointSnapshot, ByteArrayTemplate> dataPointCache = new HashMap<>();

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
     * Clears the cache of data point export templates.
     */
    public final void clearCache() {
        dataPointCache.clear();
    }

    /**
     * Retrieves the cached export template for the given data point snapshot, or creates and caches
     * a new one if it does not exist.
     *
     * @param dataPointSnapshot the data point snapshot
     * @return the cached or newly created data point export template
     */
    public final ByteArrayTemplate getOrCreateDatapointExportTemplate(DataPointSnapshot dataPointSnapshot) {
        return dataPointCache.computeIfAbsent(dataPointSnapshot, this::buildDataPointExportTemplate);
    }

    /**
     * Builds a new export template for the given data point snapshot.
     * Subclasses must implement this method to define how the template is constructed.
     *
     * @param dataPointSnapshot the data point snapshot
     * @return the newly created data point export template
     */
    protected abstract ByteArrayTemplate buildDataPointExportTemplate(DataPointSnapshot dataPointSnapshot);
}
