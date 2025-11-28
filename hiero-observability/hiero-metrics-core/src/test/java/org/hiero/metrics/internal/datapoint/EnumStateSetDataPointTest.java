// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class EnumStateSetDataPointTest {

    private enum TestEnum {
        A,
        B,
        C
    }

    @Test
    void testNullInitialStatesThrows() {
        assertThatThrownBy(() -> new EnumStateSetDataPoint<>(null, TestEnum.class))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("initial states list must not be null");
    }

    @Test
    void testNullEnumClassThrows() {
        assertThatThrownBy(() -> new EnumStateSetDataPoint<TestEnum>(Set.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("enum class must not be null");
    }

    @Test
    void testDataPointNoInitialStates() {
        EnumStateSetDataPoint<TestEnum> dataPoint = new EnumStateSetDataPoint<>(Set.of(), TestEnum.class);

        assertThat(dataPoint.getStates()).containsExactlyInAnyOrder(TestEnum.A, TestEnum.B, TestEnum.C);

        verifyStates(dataPoint, false, false, false);

        dataPoint.setTrue(TestEnum.B);
        dataPoint.set(TestEnum.C, true);
        verifyStates(dataPoint, false, true, true);

        dataPoint.set(TestEnum.C, false);
        dataPoint.setFalse(TestEnum.B);
        dataPoint.set(TestEnum.A, true);
        verifyStates(dataPoint, true, false, false);

        dataPoint.reset();
        verifyStates(dataPoint, false, false, false);
    }

    @Test
    void testDataPointWithInitialStates() {
        EnumStateSetDataPoint<TestEnum> dataPoint = new EnumStateSetDataPoint<>(Set.of(TestEnum.B), TestEnum.class);

        assertThat(dataPoint.getStates()).containsExactlyInAnyOrder(TestEnum.A, TestEnum.B, TestEnum.C);

        verifyStates(dataPoint, false, true, false);

        dataPoint.setTrue(TestEnum.B);
        dataPoint.set(TestEnum.C, true);
        verifyStates(dataPoint, false, true, true);

        dataPoint.set(TestEnum.C, false);
        dataPoint.setFalse(TestEnum.B);
        dataPoint.set(TestEnum.A, true);
        verifyStates(dataPoint, true, false, false);

        dataPoint.reset();
        verifyStates(dataPoint, false, true, false);
    }

    @Test
    void testConcurrentUpdates() throws InterruptedException {
        int threadCount = 10;
        int incrementsPerThread = 10000;
        EnumStateSetDataPoint<TestEnum> dataPoint = new EnumStateSetDataPoint<>(Set.of(), TestEnum.class);

        runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
            for (int i = 0; i < incrementsPerThread; i++) {
                dataPoint.set(TestEnum.A, true);
                dataPoint.set(TestEnum.B, false);
                dataPoint.set(TestEnum.C, true);
            }
        });

        verifyStates(dataPoint, true, false, true);
    }

    private void verifyStates(EnumStateSetDataPoint<TestEnum> dataPoint, boolean... values) {
        assertThat(dataPoint.getState(TestEnum.A)).isEqualTo(values[0]);
        assertThat(dataPoint.getState(TestEnum.B)).isEqualTo(values[1]);
        assertThat(dataPoint.getState(TestEnum.C)).isEqualTo(values[2]);
    }
}
