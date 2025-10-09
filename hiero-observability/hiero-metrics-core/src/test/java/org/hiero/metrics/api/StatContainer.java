// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.hiero.metrics.api.stat.StatUtils;

public class StatContainer {

    private final AtomicInteger counter;
    private final AtomicInteger sum;

    public StatContainer(IntSupplier initializer) {
        int initialValue = initializer.getAsInt();
        counter = new AtomicInteger(initialValue);
        sum = new AtomicInteger(initialValue);
    }

    public StatContainer() {
        this(StatUtils.INT_INIT);
    }

    public void update(int value) {
        counter.incrementAndGet();
        sum.accumulateAndGet(value, Integer::sum);
    }

    public int getCounter() {
        return counter.get();
    }

    public int getSum() {
        return sum.get();
    }

    public double getAverage() {
        int count = counter.get();
        return count == 0 ? 0.0 : (double) getSum() / count;
    }

    public void reset() {
        counter.set(0);
        sum.set(0);
    }
}
