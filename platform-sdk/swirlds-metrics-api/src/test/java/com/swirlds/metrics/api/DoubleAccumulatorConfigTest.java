// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DoubleAccumulatorConfigTest extends BaseConfigTest<DoubleAccumulator.Config> {

    private static final double EPSILON = 1e-6;

    @Override
    protected DoubleAccumulator.Config create(String category, String name) {
        return new DoubleAccumulator.Config(category, name);
    }

    @Test
    @DisplayName("Constructor initializes custom fields")
    void testConstructorCustomFields() {
        // when
        final DoubleAccumulator.Config config = createBase();

        // then
        assertThat(config.getFormat()).isEqualTo(FloatFormats.FORMAT_11_3);
        assertThat(config.getAccumulator().applyAsDouble(2.0, 3.0)).isEqualTo(Double.max(2.0, 3.0), within(EPSILON));
        assertThat(config.getInitializer().getAsDouble()).isEqualTo(0.0, within(EPSILON));
    }

    @Test
    @DisplayName("Custom setters store values")
    void testCustomSetters() {
        // given
        final DoubleBinaryOperator accumulator = mock(DoubleBinaryOperator.class);
        final DoubleSupplier initializer = mock(DoubleSupplier.class);

        // when
        final DoubleAccumulator.Config config1 =
                createBase().withAccumulator(accumulator).withInitialValue(Math.PI);
        final DoubleAccumulator.Config config2 =
                createBase().withAccumulator(accumulator).withInitializer(initializer);

        // then
        assertThat(config1.getAccumulator()).isEqualTo(accumulator);
        assertThat(config1.getInitializer().getAsDouble()).isEqualTo(Math.PI, within(EPSILON));

        assertThat(config2.getAccumulator()).isEqualTo(accumulator);
        assertThat(config2.getInitializer()).isEqualTo(initializer);
    }

    @Test
    @DisplayName("Custom setters fail with illegal parameters")
    void testCustomSettersWithIllegalParameters() {
        // given
        final DoubleAccumulator.Config config = createBase();

        // then
        assertThatThrownBy(() -> config.withAccumulator(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> config.withInitializer(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("toString contains custom values")
    void testToStringCustom() {
        // given
        final DoubleAccumulator.Config config1 = createFull().withInitialValue(Math.PI);
        final DoubleAccumulator.Config config2 = createFull().withInitializer(() -> 42);
        // then
        assertThat(config1.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, "3.1415");
        assertThat(config2.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, "42");
    }
}
