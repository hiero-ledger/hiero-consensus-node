// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.api.export.snapshot.DataPointSnapshot;
import org.hiero.metrics.internal.core.LabelValues;

/**
 * Base implementation of {@link DataPointSnapshot} that handles dynamic label values storage and access.
 */
public abstract class BaseDataPointSnapshot implements DataPointSnapshot {

    private final LabelValues dynamicLabelValues;

    protected BaseDataPointSnapshot(@NonNull LabelValues dynamicLabelValues) {
        this.dynamicLabelValues = dynamicLabelValues;
    }

    @NonNull
    @Override
    public final String labelValue(int idx) {
        return dynamicLabelValues.get(idx);
    }
}
