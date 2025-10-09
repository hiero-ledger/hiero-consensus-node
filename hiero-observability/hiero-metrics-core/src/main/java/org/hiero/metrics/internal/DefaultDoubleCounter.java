// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.DoubleCounter;
import org.hiero.metrics.api.datapoint.DoubleCounterDataPoint;
import org.hiero.metrics.internal.core.AbstractStatefulSingleValueMetric;

public final class DefaultDoubleCounter
        extends AbstractStatefulSingleValueMetric<DoubleSupplier, DoubleCounterDataPoint> implements DoubleCounter {

    public DefaultDoubleCounter(DoubleCounter.Builder builder) {
        super(builder);
    }

    @Override
    protected void reset(DoubleCounterDataPoint dataPoint) {
        dataPoint.reset();
    }
}
