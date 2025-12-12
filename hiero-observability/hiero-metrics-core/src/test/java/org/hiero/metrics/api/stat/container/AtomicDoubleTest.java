// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.stat.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import org.assertj.core.data.Percentage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class AtomicDoubleTest {

    @Test
    void testNullInitializerThrows() {
        assertThatThrownBy(() -> new AtomicDouble(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("initializer must not be null");
    }

    @Test
    void testConvertBinaryOperatorWithNull() {
        assertThatThrownBy(() -> AtomicDouble.convertBinaryOperator(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("operator must not be null");
    }

    @Test
    void testDefaultConstructor() {
        AtomicDouble atomicDouble = new AtomicDouble();

        assertThat(atomicDouble.getAsDouble()).isEqualTo(0.0);
        assertThat(atomicDouble.getInitValue()).isEqualTo(0.0);
    }

    @ParameterizedTest
    @ValueSource(
            doubles = {
                0.0,
                -1.5,
                1.5,
                Double.MIN_VALUE,
                Double.MAX_VALUE,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY
            })
    void testConstructorWithInitialValue(double initialValue) {
        AtomicDouble atomicDouble = new AtomicDouble(initialValue);

        assertThat(atomicDouble.getAsDouble()).isEqualTo(initialValue);
        assertThat(atomicDouble.getInitValue()).isEqualTo(initialValue);
    }

    @ParameterizedTest
    @ValueSource(
            doubles = {
                0.0,
                -1.5,
                1.5,
                Double.MIN_VALUE,
                Double.MAX_VALUE,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY
            })
    void testConstructorWithSupplier(double initialValue) {
        AtomicDouble atomicDouble = new AtomicDouble(() -> initialValue);

        assertThat(atomicDouble.getAsDouble()).isEqualTo(initialValue);
        assertThat(atomicDouble.getInitValue()).isEqualTo(initialValue);
    }

    @ParameterizedTest
    @ValueSource(
            doubles = {
                0.0,
                -1.5,
                1.5,
                Double.MIN_VALUE,
                Double.MAX_VALUE,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY
            })
    void testSet(double value) {
        AtomicDouble atomicDouble = new AtomicDouble();

        atomicDouble.set(value);
        assertThat(atomicDouble.getAsDouble()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(
            doubles = {
                0.0,
                -1.5,
                1.5,
                Double.MIN_VALUE,
                Double.MAX_VALUE,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY
            })
    void testGetAndSet(double value) {
        AtomicDouble atomicDouble = new AtomicDouble(100.0);
        double previous = atomicDouble.getAndSet(value);

        assertThat(previous).isEqualTo(100.0);
        assertThat(atomicDouble.getAsDouble()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(
            doubles = {
                0.0,
                -1.5,
                1.5,
                Double.MIN_VALUE,
                Double.MAX_VALUE,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY
            })
    void testReset(double initValue) {
        AtomicDouble atomicDouble = new AtomicDouble(initValue);

        atomicDouble.set(100.0);
        atomicDouble.reset();

        assertThat(atomicDouble.getAsDouble()).isEqualTo(initValue);
    }

    @ParameterizedTest
    @ValueSource(
            doubles = {
                0.0,
                -1.5,
                1.5,
                Double.MIN_VALUE,
                Double.MAX_VALUE,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY
            })
    void testGetAndReset(double initValue) {
        AtomicDouble atomicDouble = new AtomicDouble(initValue);

        atomicDouble.set(100.0);
        double previous = atomicDouble.getAndReset();

        assertThat(previous).isEqualTo(100.0);
        assertThat(atomicDouble.getAsDouble()).isEqualTo(initValue);
    }

    @ParameterizedTest
    @ValueSource(
            doubles = {
                0.0,
                -1.5,
                1.5,
                Double.MIN_VALUE,
                Double.MAX_VALUE,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY
            })
    void testCompareAndSet(double initValue) {
        AtomicDouble atomicDouble = new AtomicDouble(initValue);

        assertThat(atomicDouble.compareAndSet(initValue, 100.0)).isTrue();
        assertThat(atomicDouble.getAsDouble()).isEqualTo(100.0);

        assertThat(atomicDouble.compareAndSet(initValue, 200.0)).isFalse();
        assertThat(atomicDouble.getAsDouble()).isEqualTo(100.0);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -1.5, 1.5, -1000.2, 1000.2})
    void testAddAndGet(double delta) {
        AtomicDouble atomicDouble = new AtomicDouble(10.0);

        double result = atomicDouble.addAndGet(delta);

        assertThat(result).isEqualTo(10.0 + delta);
        assertThat(atomicDouble.getAsDouble()).isEqualTo(10.0 + delta);
    }

    @Nested
    class ConcurrentTests {

        @Test
        void testConcurrentIncrementsExact() throws InterruptedException {
            AtomicDouble atomicDouble = new AtomicDouble(0.0);
            int threadCount = 10;
            int incrementsPerThread = 1000;
            double delta = 1.0;

            runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
                for (int i = 0; i < incrementsPerThread; i++) {
                    atomicDouble.addAndGet(delta);
                }
            });

            assertThat(atomicDouble.getAsDouble()).isEqualTo(threadCount * incrementsPerThread);
        }

        @Test
        void testConcurrentIncrementsApprox() throws InterruptedException {
            AtomicDouble atomicDouble = new AtomicDouble(0.0);
            int threadCount = 10;
            int incrementsPerThread = 1000;
            double delta = 1.2;

            runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
                for (int i = 0; i < incrementsPerThread; i++) {
                    atomicDouble.addAndGet(delta);
                }
            });

            assertThat(atomicDouble.getAsDouble())
                    .isCloseTo(threadCount * incrementsPerThread * delta, Percentage.withPercentage(0.0001));
        }
    }
}
