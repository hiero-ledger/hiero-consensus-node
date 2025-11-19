// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.test.fixtures;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.hiero.metrics.api.StatsGaugeAdapter;
import org.hiero.metrics.api.stat.StatUtils;

/**
 * A simple container for statistical metrics: count, sum, and average.
 */
public class StatContainer {

    private final IntSupplier initializer;

    private final AtomicInteger counter;
    private final AtomicInteger sum;

    public StatContainer(IntSupplier initializer) {
        this.initializer = initializer;

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
        int initialValue = initializer.getAsInt();

        counter.set(initialValue);
        sum.set(initialValue);
    }

    public static StatsGaugeAdapter.Builder<IntSupplier, StatContainer> metricBuilder(String name) {
        return metricBuilder(name, StatUtils.INT_INIT);
    }

    public static StatsGaugeAdapter.Builder<IntSupplier, StatContainer> metricBuilder(
            String name, IntSupplier initializer) {
        return StatsGaugeAdapter.builder(StatsGaugeAdapter.key(name), initializer, StatContainer::new)
                .withLongStat("cnt", StatContainer::getCounter)
                .withLongStat("sum", StatContainer::getSum)
                .withDoubleStat("avg", StatContainer::getAverage)
                .withReset(StatContainer::reset);
    }
}
