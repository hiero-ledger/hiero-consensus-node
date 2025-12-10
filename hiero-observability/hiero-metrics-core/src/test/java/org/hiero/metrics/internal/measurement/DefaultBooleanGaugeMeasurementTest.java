// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class DefaultBooleanGaugeMeasurementTest {

    @Test
    void testWithDefaultInitializer() {
        DefaultBooleanGaugeMeasurement gauge = new DefaultBooleanGaugeMeasurement();

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
        DefaultBooleanGaugeMeasurement gauge = new DefaultBooleanGaugeMeasurement(() -> true);

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

    @Test
    void testAsLong() {
        DefaultBooleanGaugeMeasurement gauge = new DefaultBooleanGaugeMeasurement();

        assertThat(gauge.getAsLong()).isEqualTo(0L);

        gauge.set(true);
        assertThat(gauge.getAsLong()).isEqualTo(1L);

        gauge.set(false);
        assertThat(gauge.getAsLong()).isEqualTo(0L);
    }
}
