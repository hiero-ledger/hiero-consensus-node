// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class EnumStateSetMeasurementTest {

    private enum TestEnum {
        A,
        B,
        C
    }

    @Test
    void testNullInitialStatesThrows() {
        assertThatThrownBy(() -> new EnumStateSetMeasurement<>(null, TestEnum.class))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("initial states list must not be null");
    }

    @Test
    void testNullEnumClassThrows() {
        assertThatThrownBy(() -> new EnumStateSetMeasurement<TestEnum>(Set.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("enum class must not be null");
    }

    @Test
    void testMeasurementNoInitialStates() {
        EnumStateSetMeasurement<TestEnum> measurement = new EnumStateSetMeasurement<>(Set.of(), TestEnum.class);

        assertThat(measurement.getStates()).containsExactlyInAnyOrder(TestEnum.A, TestEnum.B, TestEnum.C);

        verifyStates(measurement, false, false, false);

        measurement.setTrue(TestEnum.B);
        measurement.set(TestEnum.C, true);
        verifyStates(measurement, false, true, true);

        measurement.set(TestEnum.C, false);
        measurement.setFalse(TestEnum.B);
        measurement.set(TestEnum.A, true);
        verifyStates(measurement, true, false, false);

        measurement.reset();
        verifyStates(measurement, false, false, false);
    }

    @Test
    void testMeasurementWithInitialStates() {
        EnumStateSetMeasurement<TestEnum> measurement =
                new EnumStateSetMeasurement<>(Set.of(TestEnum.B), TestEnum.class);

        assertThat(measurement.getStates()).containsExactlyInAnyOrder(TestEnum.A, TestEnum.B, TestEnum.C);

        verifyStates(measurement, false, true, false);

        measurement.setTrue(TestEnum.B);
        measurement.set(TestEnum.C, true);
        verifyStates(measurement, false, true, true);

        measurement.set(TestEnum.C, false);
        measurement.setFalse(TestEnum.B);
        measurement.set(TestEnum.A, true);
        verifyStates(measurement, true, false, false);

        measurement.reset();
        verifyStates(measurement, false, true, false);
    }

    @Test
    void testConcurrentUpdates() throws InterruptedException {
        final int threadCount = 10;
        final int incrementsPerThread = 10000;
        final EnumStateSetMeasurement<TestEnum> measurement = new EnumStateSetMeasurement<>(Set.of(), TestEnum.class);

        runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
            for (int i = 0; i < incrementsPerThread; i++) {
                measurement.set(TestEnum.A, true);
                measurement.set(TestEnum.B, false);
                measurement.set(TestEnum.C, true);
            }
        });

        verifyStates(measurement, true, false, true);
    }

    private void verifyStates(EnumStateSetMeasurement<TestEnum> measurement, boolean... values) {
        assertThat(measurement.getState(TestEnum.A)).isEqualTo(values[0]);
        assertThat(measurement.getState(TestEnum.B)).isEqualTo(values[1]);
        assertThat(measurement.getState(TestEnum.C)).isEqualTo(values[2]);
    }
}
