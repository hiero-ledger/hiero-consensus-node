// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.impl.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.swirlds.metrics.impl.AtomicDouble;
import org.junit.jupiter.api.Test;

class AtomicDoubleTest {

    private static final double EPSILON = 1e-6;

    @Test
    void testConstructors() {
        // when
        final AtomicDouble atomic1 = new AtomicDouble(Math.PI);
        final AtomicDouble atomic2 = new AtomicDouble(Double.NaN);
        final AtomicDouble atomic3 = new AtomicDouble(Double.NEGATIVE_INFINITY);
        final AtomicDouble atomic4 = new AtomicDouble(Double.POSITIVE_INFINITY);
        final AtomicDouble atomic5 = new AtomicDouble();

        // then
        assertThat(atomic1.get()).isEqualTo(Math.PI, within(EPSILON));
        assertThat(atomic2.get()).isNaN();
        assertThat(atomic3.get()).isNegative().isInfinite();
        assertThat(atomic4.get()).isPositive().isInfinite();
        assertThat(atomic5.get()).isEqualTo(0.0, within(EPSILON));
    }

    @Test
    void testSet() {
        // given
        final AtomicDouble atomic1 = new AtomicDouble();
        final AtomicDouble atomic2 = new AtomicDouble();
        final AtomicDouble atomic3 = new AtomicDouble();
        final AtomicDouble atomic4 = new AtomicDouble();

        // when
        atomic1.set(Math.PI);
        atomic2.set(Double.NaN);
        atomic3.set(Double.NEGATIVE_INFINITY);
        atomic4.set(Double.POSITIVE_INFINITY);

        // then
        assertThat(atomic1.get()).isEqualTo(Math.PI, within(EPSILON));
        assertThat(atomic2.get()).isNaN();
        assertThat(atomic3.get()).isNegative().isInfinite();
        assertThat(atomic4.get()).isPositive().isInfinite();
    }

    @Test
    void testGetAndSet() {
        // given
        final AtomicDouble atomic = new AtomicDouble();
        double oldValue;

        // when
        oldValue = atomic.getAndSet(Math.PI);

        // then
        assertThat(oldValue).isEqualTo(0.0, within(EPSILON));
        assertThat(atomic.get()).isEqualTo(Math.PI, within(EPSILON));

        // when
        oldValue = atomic.getAndSet(Double.NaN);

        // then
        assertThat(oldValue).isEqualTo(Math.PI, within(EPSILON));
        assertThat(atomic.get()).isNaN();

        // when
        oldValue = atomic.getAndSet(Double.NEGATIVE_INFINITY);

        // then
        assertThat(oldValue).isNaN();
        assertThat(atomic.get()).isNegative().isInfinite();

        // when
        oldValue = atomic.getAndSet(Double.POSITIVE_INFINITY);

        // then
        assertThat(oldValue).isNegative().isInfinite();
        assertThat(atomic.get()).isPositive().isInfinite();

        // when
        oldValue = atomic.getAndSet(Math.E);

        // then
        assertThat(oldValue).isPositive().isInfinite();
        assertThat(atomic.get()).isEqualTo(Math.E, within(EPSILON));
    }

    @Test
    void testCompareAndSet() {
        // given
        final AtomicDouble atomic = new AtomicDouble();
        boolean result;

        // when
        result = atomic.compareAndSet(Math.PI, Math.PI);

        // then
        assertThat(result).isFalse();
        assertThat(atomic.get()).isEqualTo(0.0, within(EPSILON));

        // when
        result = atomic.compareAndSet(0.0, Math.PI);

        // then
        assertThat(result).isTrue();
        assertThat(atomic.get()).isEqualTo(Math.PI, within(EPSILON));

        // when
        result = atomic.compareAndSet(Double.NaN, Double.NaN);

        // then
        assertThat(result).isFalse();
        assertThat(atomic.get()).isEqualTo(Math.PI, within(EPSILON));

        // when
        result = atomic.compareAndSet(Math.PI, Double.NaN);

        // then
        assertThat(result).isTrue();
        assertThat(atomic.get()).isNaN();

        // when
        result = atomic.compareAndSet(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);

        // then
        assertThat(result).isFalse();
        assertThat(atomic.get()).isNaN();

        // when
        result = atomic.compareAndSet(Double.NaN, Double.NEGATIVE_INFINITY);

        // then
        assertThat(result).isTrue();
        assertThat(atomic.get()).isNegative().isInfinite();

        // when
        result = atomic.compareAndSet(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);

        // then
        assertThat(result).isFalse();
        assertThat(atomic.get()).isNegative().isInfinite();

        // when
        result = atomic.compareAndSet(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);

        // then
        assertThat(result).isTrue();
        assertThat(atomic.get()).isPositive().isInfinite();

        // when
        result = atomic.compareAndSet(Math.E, Math.E);

        // then
        assertThat(result).isFalse();
        assertThat(atomic.get()).isPositive().isInfinite();

        // when
        result = atomic.compareAndSet(Double.POSITIVE_INFINITY, Math.E);

        // then
        assertThat(result).isTrue();
        assertThat(atomic.get()).isEqualTo(Math.E, within(EPSILON));
    }
}
