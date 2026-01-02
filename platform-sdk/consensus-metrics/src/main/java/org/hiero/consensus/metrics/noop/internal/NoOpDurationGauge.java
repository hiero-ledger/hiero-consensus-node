// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.noop.internal;

import com.swirlds.metrics.api.MetricConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.consensus.metrics.DurationGauge;

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
