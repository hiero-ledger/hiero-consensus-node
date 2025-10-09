// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.Supplier;
import org.hiero.metrics.api.GenericGauge;
import org.hiero.metrics.api.datapoint.GaugeDataPoint;
import org.hiero.metrics.internal.core.AbstractStatefulSingleValueMetric;

public final class DefaultGenericGauge<T> extends AbstractStatefulSingleValueMetric<Supplier<T>, GaugeDataPoint<T>>
        implements GenericGauge<T> {

    public DefaultGenericGauge(GenericGauge.Builder<T> builder) {
        super(builder);
    }

    @Override
    protected void reset(GaugeDataPoint<T> dataPoint) {
        dataPoint.reset();
    }
}
