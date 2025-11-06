// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.stat.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import com.swirlds.base.utility.Pair;
import java.time.Duration;
import java.util.stream.Stream;
import org.hiero.metrics.api.stat.StatUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class AtomicIntPairTest {

    @Test
    void testNullLeftOperatorThrows() {
        assertThatThrownBy(() -> new AtomicIntPair(null, StatUtils.INT_SUM))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("leftOperator must not be null");
    }

    @Test
    void testNullRightOperatorThrows() {
        assertThatThrownBy(() -> new AtomicIntPair(StatUtils.INT_SUM, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("rightOperator must not be null");
    }

    @Test
    void testComputeDouble() {
        AtomicIntPair pair = AtomicIntPair.createAccumulatingSum();

        pair.accumulate(2, 3);
        double result = pair.computeDouble(Math::subtractExact);

        assertThat(result).isEqualTo(2 - 3);
        assertThat(pair.getLeft()).isEqualTo(2);
        assertThat(pair.getRight()).isEqualTo(3);
    }

    @Test
    void testComputeDoubleAndReset() {
        AtomicIntPair pair = AtomicIntPair.createAccumulatingSum();

        pair.accumulate(2, 3);
        double result = pair.computeDoubleAndReset(Math::subtractExact);

        assertThat(result).isEqualTo(2 - 3);
        assertThat(pair.getLeft()).isZero();
        assertThat(pair.getRight()).isZero();
    }

    @Test
    void testComputeDoubleAndSet() {
        AtomicIntPair pair = AtomicIntPair.createAccumulatingSum();

        pair.accumulate(2, 3);
        double result = pair.computeDoubleAndSet(Math::subtractExact, 10, 20);

        assertThat(result).isEqualTo(2 - 3);
        assertThat(pair.getLeft()).isEqualTo(10);
        assertThat(pair.getRight()).isEqualTo(20);
    }

    @Test
    void testCompute() {
        AtomicIntPair pair = AtomicIntPair.createAccumulatingSum();

        pair.accumulate(2, 3);
        Pair<Integer, Integer> result = pair.compute(Pair::of);

        assertThat(result).isEqualTo(Pair.of(2, 3));
        assertThat(pair.getLeft()).isEqualTo(2);
        assertThat(pair.getRight()).isEqualTo(3);
    }

    @Test
    void testComputeAndReset() {
        AtomicIntPair pair = AtomicIntPair.createAccumulatingSum();

        pair.accumulate(2, 3);
        Pair<Integer, Integer> result = pair.computeAndReset(Pair::of);

        assertThat(result).isEqualTo(Pair.of(2, 3));
        assertThat(pair.getLeft()).isZero();
        assertThat(pair.getRight()).isZero();
    }

    @Test
    void testComputeAndSet() {
        AtomicIntPair pair = AtomicIntPair.createAccumulatingSum();

        pair.accumulate(2, 3);
        Pair<Integer, Integer> result = pair.computeAndSet(Pair::of, 10, 20);

        assertThat(result).isEqualTo(Pair.of(2, 3));
        assertThat(pair.getLeft()).isEqualTo(10);
        assertThat(pair.getRight()).isEqualTo(20);
    }

    @Test
    void testSet() {
        AtomicIntPair pair = AtomicIntPair.createAccumulatingSum();

        pair.accumulate(5, 10);
        pair.set(20, 30);

        assertThat(pair.getLeft()).isEqualTo(20);
        assertThat(pair.getRight()).isEqualTo(30);
    }

    @Test
    void testReset() {
        AtomicIntPair pair = AtomicIntPair.createAccumulatingSum();

        pair.accumulate(5, 10);
        pair.reset();

        assertThat(pair.getLeft()).isZero();
        assertThat(pair.getRight()).isZero();
    }

    @ParameterizedTest
    @MethodSource("sumArguments")
    void testSummingPair(int left1, int right1, int left2, int right2) {
        AtomicIntPair pair = AtomicIntPair.createAccumulatingSum();
        pair.accumulate(left1, right1);
        pair.accumulate(left2, right2);

        assertThat(pair.getLeft()).isEqualTo(left1 + left2);
        assertThat(pair.getRight()).isEqualTo(right1 + right2);
    }

    private static Stream<Arguments> sumArguments() {
        return Stream.of(
                Arguments.of(0, 0, 0, 0), // stays zero
                Arguments.of(-1, -10000, -2, -20000), // negative sums
                Arguments.of(1, 10000, 2, 20000), // positive sums
                Arguments.of(3, 10000, -3, -10000), // result to zero
                Arguments.of(Integer.MAX_VALUE, 0, 1, 1), // overflow left max
                Arguments.of(0, Integer.MAX_VALUE, 1, 1), // overflow right max
                Arguments.of(Integer.MIN_VALUE, 0, -1, -1), // overflow left min
                Arguments.of(0, Integer.MIN_VALUE, -1, -1) // overflow right min
                );
    }

    @Test
    void concurrentSum() throws InterruptedException {
        AtomicIntPair pair = AtomicIntPair.createAccumulatingSum();
        int threadCount = 10;
        int incrementsPerThread = 1000;

        runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
            for (int i = 0; i < incrementsPerThread; i++) {
                pair.accumulate(1, 2);
            }
        });

        assertThat(pair.getLeft()).isEqualTo(threadCount * incrementsPerThread);
        assertThat(pair.getRight()).isEqualTo(2 * threadCount * incrementsPerThread);
    }
}
