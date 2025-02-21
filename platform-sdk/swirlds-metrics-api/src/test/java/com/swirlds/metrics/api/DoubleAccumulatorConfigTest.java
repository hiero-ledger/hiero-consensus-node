// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.function.DoubleBinaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DoubleAccumulatorConfigTest {

    private static final String DEFAULT_FORMAT = FloatFormats.FORMAT_11_3;

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String DESCRIPTION = "DeScRiPtIoN";
    private static final String UNIT = "UnIt";
    private static final String FORMAT = "FoRmAt";
    private static final double EPSILON = 1e-6;

    @Test
    @DisplayName("Constructor should store values")
    void testConstructor() {
        // when
        final DoubleAccumulator.Config config = new DoubleAccumulator.Config(CATEGORY, NAME);

        // then
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getName()).isEqualTo(NAME);
        assertThat(config.getDescription()).isEqualTo(NAME);
        assertThat(config.getUnit()).isEmpty();
        assertThat(config.getFormat()).isEqualTo(DEFAULT_FORMAT);
        assertThat(config.getAccumulator().applyAsDouble(2.0, 3.0)).isEqualTo(Double.max(2.0, 3.0), within(EPSILON));
        assertThat(config.getInitialValue()).isEqualTo(0.0, within(EPSILON));
    }

    @Test
    @DisplayName("Constructor should throw IAE when passing illegal parameters")
    void testConstructorWithIllegalParameter() {
        assertThatThrownBy(() -> new DoubleAccumulator.Config(null, NAME)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DoubleAccumulator.Config("", NAME)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DoubleAccumulator.Config(" \t\n", NAME))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new DoubleAccumulator.Config(CATEGORY, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DoubleAccumulator.Config(CATEGORY, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DoubleAccumulator.Config(CATEGORY, " \t\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testSetters() {
        // given
        final DoubleBinaryOperator accumulator = mock(DoubleBinaryOperator.class);
        final DoubleAccumulator.Config config = new DoubleAccumulator.Config(CATEGORY, NAME);

        // when
        final DoubleAccumulator.Config result = config.withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withAccumulator(accumulator)
                .withInitialValue(Math.PI);

        // then
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getName()).isEqualTo(NAME);
        assertThat(config.getDescription()).isEqualTo(NAME);
        assertThat(config.getUnit()).isEmpty();
        assertThat(config.getFormat()).isEqualTo(DEFAULT_FORMAT);
        assertThat(config.getAccumulator().applyAsDouble(2.0, 3.0)).isEqualTo(Double.max(2.0, 3.0), within(EPSILON));
        assertThat(config.getInitialValue()).isEqualTo(0.0, within(EPSILON));

        assertThat(result.getCategory()).isEqualTo(CATEGORY);
        assertThat(result.getName()).isEqualTo(NAME);
        assertThat(result.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(result.getUnit()).isEqualTo(UNIT);
        assertThat(result.getFormat()).isEqualTo(FORMAT);
        assertThat(result.getAccumulator()).isEqualTo(accumulator);
        assertThat(result.getInitialValue()).isEqualTo(Math.PI, within(EPSILON));
    }

    @Test
    void testSettersWithIllegalParameters() {
        // given
        final DoubleAccumulator.Config config = new DoubleAccumulator.Config(CATEGORY, NAME);
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
    }

    @Test
    void testToString() {
        // given
        final DoubleAccumulator.Config config = new DoubleAccumulator.Config(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withInitialValue(Math.PI);

        // then
        assertThat(config.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, "3.1415");
    }
}
