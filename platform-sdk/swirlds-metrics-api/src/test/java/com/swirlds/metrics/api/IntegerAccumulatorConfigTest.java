// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.function.IntBinaryOperator;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntegerAccumulatorConfigTest extends BaseConfigTest<IntegerAccumulator.Config> {

    @Override
    protected IntegerAccumulator.Config create(String category, String name) {
        return new IntegerAccumulator.Config(category, name);
    }

    @Test
    @DisplayName("Constructor initializes custom fields")
    void testConstructorCustomFields() {
        // when
        final IntegerAccumulator.Config config = createBase();

        // then
        assertThat(config.getFormat()).isEqualTo(MetricConfig.NUMBER_FORMAT);
        assertThat(config.getAccumulator().applyAsInt(2, 3)).isEqualTo(Integer.max(2, 3));
        assertThat(config.getInitializer().getAsInt()).isZero();
    }

    @Test
    @DisplayName("Custom setters store values")
    void testCustomSetters() {
        // given
        final IntBinaryOperator accumulator = mock(IntBinaryOperator.class);
        final IntSupplier initializer = mock(IntSupplier.class);

        // when
        final IntegerAccumulator.Config config1 =
                createBase().withAccumulator(accumulator).withInitialValue(42);
        final IntegerAccumulator.Config config2 =
                createBase().withAccumulator(accumulator).withInitializer(initializer);

        // then
        assertThat(config1.getAccumulator()).isEqualTo(accumulator);
        assertThat(config1.getInitializer().getAsInt()).isEqualTo(42);

        assertThat(config2.getAccumulator()).isEqualTo(accumulator);
        assertThat(config2.getInitializer()).isEqualTo(initializer);
    }

    @Test
    @DisplayName("Custom setters fail with illegal parameters")
    void testCustomSettersWithIllegalParameters() {
        // given
        final IntegerAccumulator.Config config = createBase();

        // then
        assertThatThrownBy(() -> config.withAccumulator(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> config.withInitializer(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("toString contains custom values")
    void testToStringCustom() {
        // given
        final IntegerAccumulator.Config config1 = createFull().withInitialValue(42);
        final IntegerAccumulator.Config config2 = createFull().withInitializer(() -> 3);

        // then
        assertThat(config1.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, "42");
        assertThat(config2.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, "3");
    }
}
