// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.internal.core.LabelValues;

/**
 * Base implementation of {@link MeasurementSnapshot} that handles dynamic label values storage and access.
 */
public abstract class BaseMeasurementSnapshot implements MeasurementSnapshot {

    private final LabelValues dynamicLabelValues;

    protected BaseMeasurementSnapshot(@NonNull LabelValues dynamicLabelValues) {
        this.dynamicLabelValues = dynamicLabelValues;
    }

    @NonNull
    @Override
    public final String labelValue(int idx) {
        return dynamicLabelValues.get(idx);
    }
}
