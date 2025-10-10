// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.BooleanSupplier;
import org.hiero.metrics.api.BooleanGauge;
import org.hiero.metrics.api.datapoint.BooleanGaugeDataPoint;
import org.hiero.metrics.internal.core.AbstractStatefulSingleValueMetric;

public final class DefaultBooleanGauge extends AbstractStatefulSingleValueMetric<BooleanSupplier, BooleanGaugeDataPoint>
        implements BooleanGauge {

    public DefaultBooleanGauge(BooleanGauge.Builder builder) {
        super(builder);
    }

    @Override
    protected void reset(BooleanGaugeDataPoint dataPoint) {
        dataPoint.reset();
    }
}
