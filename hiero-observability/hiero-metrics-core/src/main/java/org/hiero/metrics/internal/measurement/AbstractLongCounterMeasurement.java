// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import org.hiero.metrics.api.measurement.LongCounterMeasurement;

public abstract class AbstractLongCounterMeasurement implements LongCounterMeasurement {

    @Override
    public void increment(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Increment value must be non-negative, but was: " + value);
        }
        if (value != 0) {
            safeIncrement(value);
        }
    }

    @Override
    public void increment() {
        safeIncrement(1);
    }

    protected abstract void safeIncrement(long value);
}
