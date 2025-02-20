// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.function.IntBinaryOperator;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntegerAccumulatorConfigTest {

    private static final String DEFAULT_FORMAT = "%d";

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String DESCRIPTION = "DeScRiPtIoN";
    private static final String UNIT = "UnIt";
    private static final String FORMAT = "FoRmAt";

    @Test
    @DisplayName("Constructor should store values")
    void testConstructor() {
        // when
        final IntegerAccumulator.Config config = new IntegerAccumulator.Config(CATEGORY, NAME);

        // then
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getName()).isEqualTo(NAME);
        assertThat(config.getDescription()).isEqualTo(NAME);
        assertThat(config.getUnit()).isEmpty();
        assertThat(config.getFormat()).isEqualTo(DEFAULT_FORMAT);
        assertThat(config.getAccumulator().applyAsInt(2, 3)).isEqualTo(Integer.max(2, 3));
        assertThat(config.getInitializer()).isNull();
        assertThat(config.getInitialValue()).isZero();
    }

    @Test
    @DisplayName("Constructor should throw IAE when passing illegal parameters")
    void testConstructorWithIllegalParameter() {
        assertThatThrownBy(() -> new IntegerAccumulator.Config(null, NAME)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IntegerAccumulator.Config("", NAME)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegerAccumulator.Config(" \t\n", NAME))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new IntegerAccumulator.Config(CATEGORY, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IntegerAccumulator.Config(CATEGORY, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegerAccumulator.Config(CATEGORY, " \t\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testSetters() {
        // given
        final IntBinaryOperator accumulator = mock(IntBinaryOperator.class);
        final IntegerAccumulator.Config config = new IntegerAccumulator.Config(CATEGORY, NAME);
        final IntSupplier initializer = mock(IntSupplier.class);

        // when
        final IntegerAccumulator.Config result = config.withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withAccumulator(accumulator)
                .withInitializer(initializer)
                .withInitialValue(42);

        // then
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getName()).isEqualTo(NAME);
        assertThat(config.getDescription()).isEqualTo(NAME);
        assertThat(config.getUnit()).isEmpty();
        assertThat(config.getFormat()).isEqualTo(DEFAULT_FORMAT);
        assertThat(config.getAccumulator().applyAsInt(2, 3)).isEqualTo(Integer.max(2, 3));
        assertThat(config.getInitializer()).isNull();
        assertThat(config.getInitialValue()).isZero();

        assertThat(result.getCategory()).isEqualTo(CATEGORY);
        assertThat(result.getName()).isEqualTo(NAME);
        assertThat(result.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(result.getUnit()).isEqualTo(UNIT);
        assertThat(result.getFormat()).isEqualTo(FORMAT);
        assertThat(result.getAccumulator()).isEqualTo(accumulator);
        assertThat(result.getInitializer()).isEqualTo(initializer);
        assertThat(result.getInitialValue()).isEqualTo(42);
    }

    @Test
    void testSettersWithIllegalParameters() {
        // given
        final IntegerAccumulator.Config config = new IntegerAccumulator.Config(CATEGORY, NAME);
        final String longDescription = DESCRIPTION.repeat(50);

        // then
        assertThatThrownBy(() -> config.withDescription(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> config.withDescription("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.withDescription(" \t\n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.withDescription(longDescription)).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> config.withUnit(null)).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> config.withFormat(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> config.withFormat("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.withFormat(" \t\n")).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> config.withAccumulator(null)).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> config.withInitializer(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testToString() {
        // given
        final IntegerAccumulator.Config config1 = new IntegerAccumulator.Config(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withInitialValue(42);
        final IntegerAccumulator.Config config2 = new IntegerAccumulator.Config(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withInitializer(() -> 3)
                .withInitialValue(42);

        // then
        assertThat(config1.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, "42");
        assertThat(config2.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, "3");
    }
}
