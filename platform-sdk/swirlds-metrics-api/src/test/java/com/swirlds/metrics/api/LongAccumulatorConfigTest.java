// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.function.LongBinaryOperator;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LongAccumulatorConfigTest extends BaseConfigTest<LongAccumulator.Config> {

    @Override
    protected LongAccumulator.Config create(String category, String name) {
        return new LongAccumulator.Config(category, name);
    }

    @Test
    @DisplayName("Constructor initializes custom fields")
    void testConstructorCustomFields() {
        // when
        final LongAccumulator.Config config = createBase();

        // then
        assertThat(config.getAccumulator().applyAsLong(2L, 3L)).isEqualTo(Long.max(2L, 3L));
        assertThat(config.getInitializer().getAsLong()).isZero();
    }

    @Test
    @DisplayName("Custom setters store values")
    void testCustomSetters() {
        // given
        final LongBinaryOperator accumulator = mock(LongBinaryOperator.class);
        final LongSupplier initializer = mock(LongSupplier.class);

        // when
        final LongAccumulator.Config config1 =
                createBase().withAccumulator(accumulator).withInitialValue(42L);
        final LongAccumulator.Config config2 =
                createBase().withAccumulator(accumulator).withInitializer(initializer);

        assertThat(config1.getAccumulator()).isEqualTo(accumulator);
        assertThat(config1.getInitializer().getAsLong()).isEqualTo(42L);

        assertThat(config2.getAccumulator()).isEqualTo(accumulator);
        assertThat(config2.getInitializer()).isEqualTo(initializer);
    }

    @Test
    @DisplayName("Custom setters fail with illegal parameters")
    void testCustomSettersWithIllegalParameters() {
        // given
        final LongAccumulator.Config config = createBase();

        // then
        assertThatThrownBy(() -> config.withAccumulator(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> config.withInitializer(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("toString contains custom values")
    void testToStringCustom() {
        // given
        final LongAccumulator.Config config1 = createFull().withInitialValue(42L);
        final LongAccumulator.Config config2 = createFull().withInitializer(() -> 3L);
        // then
        assertThat(config1.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, "42");
        assertThat(config2.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, "3");
    }
}
