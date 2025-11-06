// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class AtomicBooleanGaugeDataPointTest {

    @Test
    void testWithDefaultInitializer() {
        AtomicBooleanGaugeDataPoint gauge = new AtomicBooleanGaugeDataPoint();

        assertThat(gauge.getAsBoolean()).isFalse();

        gauge.set(true);
        assertThat(gauge.getAsBoolean()).isTrue();

        gauge.setFalse();
        assertThat(gauge.getAsBoolean()).isFalse();

        gauge.setTrue();
        assertThat(gauge.getAsBoolean()).isTrue();

        gauge.reset();
        assertThat(gauge.getAsBoolean()).isFalse();
    }

    @Test
    void testWithCustomInitializer() {
        AtomicBooleanGaugeDataPoint gauge = new AtomicBooleanGaugeDataPoint(() -> true);

        assertThat(gauge.getAsBoolean()).isTrue();

        gauge.set(false);
        assertThat(gauge.getAsBoolean()).isFalse();

        gauge.setTrue();
        assertThat(gauge.getAsBoolean()).isTrue();

        gauge.setFalse();
        assertThat(gauge.getAsBoolean()).isFalse();

        gauge.reset();
        assertThat(gauge.getAsBoolean()).isTrue();
    }
}
