// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.LongSupplier;
import org.hiero.metrics.api.LongCounter;
import org.hiero.metrics.api.datapoint.LongCounterDataPoint;
import org.hiero.metrics.internal.core.AbstractStatefulSingleValueMetric;

public final class DefaultLongCounter extends AbstractStatefulSingleValueMetric<LongSupplier, LongCounterDataPoint>
        implements LongCounter {

    public DefaultLongCounter(LongCounter.Builder builder) {
        super(builder);
    }

    @Override
    protected void reset(LongCounterDataPoint dataPoint) {
        dataPoint.reset();
    }
}
