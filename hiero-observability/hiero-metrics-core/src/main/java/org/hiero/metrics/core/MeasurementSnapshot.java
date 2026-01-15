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
     * Returns the dynamic label value at the specified index.
     *
     * @param idx the index of the dynamic label value to return
     * @return the dynamic label value at the specified index
     */
    @NonNull
    public final String labelValue(int idx) {
        return dynamicLabelValues.get(idx);
    }

    /**
     * Returns the number of dynamic label values in this measurement snapshot.
     *
     * @return the number of dynamic label values
     */
    public final int getLabelValuesCount() {
        return dynamicLabelValues.size();
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
