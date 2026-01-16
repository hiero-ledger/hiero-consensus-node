// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An abstract class for snapshot of a single {@link Metric} measurement for specific dynamic label values at specific point in time.
 */
public abstract class MeasurementSnapshot {

    private final LabelValues dynamicLabelValues;

    protected MeasurementSnapshot(@NonNull LabelValues dynamicLabelValues) {
        this.dynamicLabelValues = dynamicLabelValues;
    }

    /**
     * @return the dynamic label values associated with this measurement snapshot.
     */
    @NonNull
    public LabelValues getDynamicLabelValues() {
        return dynamicLabelValues;
    }

    /**
     * Update the snapshot with the latest measurement value.
     * <p>
     * This method is package private, because it is called by the metric system internally each time collecting metrics snapshots.
     */
    abstract void update();

    @Override
    public String toString() {
        return "labelValues=" + dynamicLabelValues;
    }
}
