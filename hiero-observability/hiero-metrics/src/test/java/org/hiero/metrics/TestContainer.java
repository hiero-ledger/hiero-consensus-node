// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

public class TestContainer implements LongSupplier, DoubleSupplier {

    private long longValue;
    private double doubleValue;

    private long longInitValue;
    private double doubleInitValue;

    public TestContainer() {}

    public TestContainer(long initValue) {
        this.longValue = initValue;
        this.longInitValue = initValue;
    }

    public TestContainer(double initValue) {
        this.doubleValue = initValue;
        this.doubleInitValue = initValue;
    }

    public void set(long longValue) {
        this.longValue = longValue;
    }

    public void set(double doubleValue) {
        this.doubleValue = doubleValue;
    }

    @Override
    public double getAsDouble() {
        return doubleValue;
    }

    @Override
    public long getAsLong() {
        return longValue;
    }

    public void reset() {
        this.longValue = longInitValue;
        this.doubleValue = doubleInitValue;
    }
}
