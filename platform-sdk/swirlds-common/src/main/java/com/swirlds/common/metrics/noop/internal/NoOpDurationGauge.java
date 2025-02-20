// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.noop.internal;

import com.swirlds.common.metrics.DurationGauge;
import com.swirlds.metrics.api.MetricConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;

/**
 * A no-op implementation of a duration gauge.
 */
public class NoOpDurationGauge extends AbstractNoOpMetric implements DurationGauge {

    public NoOpDurationGauge(final @NonNull MetricConfig<?, ?> config) {
        super(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getNanos() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void set(final Duration duration) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public double get() {
        return 0;
    }
}
