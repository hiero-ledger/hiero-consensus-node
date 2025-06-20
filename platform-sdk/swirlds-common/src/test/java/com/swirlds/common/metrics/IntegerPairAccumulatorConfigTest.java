// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics;

import static com.swirlds.common.metrics.IntegerPairAccumulator.AVERAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.function.BiFunction;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntegerPairAccumulatorConfigTest {

    private static final String DEFAULT_FORMAT = "%s";

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String DESCRIPTION = "DeScRiPtIoN";
    private static final String UNIT = "UnIt";
    private static final String FORMAT = "FoRmAt";

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Constructor should store values")
    void testConstructor() {
        // when
        final BiFunction<Integer, Integer, Integer> resultFunction = mock(BiFunction.class);
        final IntegerPairAccumulator.Config<Integer> config =
                new IntegerPairAccumulator.Config<>(CATEGORY, NAME, Integer.class, resultFunction);

        // then
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getName()).isEqualTo(NAME);
        assertThat(config.getDescription()).isEqualTo(NAME);
        assertThat(config.getUnit()).isEmpty();
        assertThat(config.getFormat()).isEqualTo(DEFAULT_FORMAT);
        assertThat(config.getType()).isEqualTo(Integer.class);
        assertThat(config.getLeftAccumulator().applyAsInt(2, 3)).isEqualTo(2 + 3);
        assertThat(config.getRightAccumulator().applyAsInt(5, 7)).isEqualTo(5 + 7);
        assertThat(config.getResultFunction()).isEqualTo(resultFunction);
    }

    @Test
    @DisplayName("Constructor should throw IAE when passing illegal parameters")
    void testConstructorWithIllegalParameter() {
        assertThatThrownBy(() -> new IntegerPairAccumulator.Config<>(null, NAME, Double.class, AVERAGE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IntegerPairAccumulator.Config<>("", NAME, Double.class, AVERAGE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegerPairAccumulator.Config<>(" \t\n", NAME, Double.class, AVERAGE))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new IntegerPairAccumulator.Config<>(CATEGORY, null, Double.class, AVERAGE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IntegerPairAccumulator.Config<>(CATEGORY, "", Double.class, AVERAGE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegerPairAccumulator.Config<>(CATEGORY, " \t\n", Double.class, AVERAGE))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new IntegerPairAccumulator.Config<>(CATEGORY, CATEGORY, null, AVERAGE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IntegerPairAccumulator.Config<>(CATEGORY, CATEGORY, Double.class, null))
                .isInstanceOf(NullPointerException.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void testSetters() {
        // given
        final IntBinaryOperator leftAccumulator = mock(IntBinaryOperator.class);
        final IntBinaryOperator rightAccumulator = mock(IntBinaryOperator.class);
        final BiFunction<Integer, Integer, Integer> resultFunction = mock(BiFunction.class);
        final IntegerPairAccumulator.Config<Integer> config =
                new IntegerPairAccumulator.Config<>(CATEGORY, NAME, Integer.class, resultFunction);

        // when
        final IntegerPairAccumulator.Config<Integer> result = config.withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withLeftAccumulator(leftAccumulator)
                .withRightAccumulator(rightAccumulator);

        // then
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getName()).isEqualTo(NAME);
        assertThat(config.getDescription()).isEqualTo(NAME);
        assertThat(config.getUnit()).isEmpty();
        assertThat(config.getFormat()).isEqualTo(DEFAULT_FORMAT);
        assertThat(config.getType()).isEqualTo(Integer.class);
        assertThat(config.getLeftAccumulator().applyAsInt(2, 3)).isEqualTo(2 + 3);
        assertThat(config.getRightAccumulator().applyAsInt(5, 7)).isEqualTo(5 + 7);
        assertThat(config.getResultFunction()).isEqualTo(resultFunction);

        assertThat(result.getCategory()).isEqualTo(CATEGORY);
        assertThat(result.getName()).isEqualTo(NAME);
        assertThat(result.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(result.getUnit()).isEqualTo(UNIT);
        assertThat(result.getFormat()).isEqualTo(FORMAT);
        assertThat(result.getType()).isEqualTo(Integer.class);
        assertThat(result.getLeftAccumulator()).isEqualTo(leftAccumulator);
        assertThat(result.getRightAccumulator()).isEqualTo(rightAccumulator);
        assertThat(result.getResultFunction()).isEqualTo(resultFunction);
    }

    @SuppressWarnings("unchecked")
    @Test
    void testSettersWithIllegalParameters() {
        // given
        final BiFunction<Integer, Integer, Integer> resultFunction = mock(BiFunction.class);
        final IntegerPairAccumulator.Config<Integer> config =
                new IntegerPairAccumulator.Config<>(CATEGORY, NAME, Integer.class, resultFunction);
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

        assertThatThrownBy(() -> config.withLeftAccumulator(null)).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> config.withRightAccumulator(null)).isInstanceOf(NullPointerException.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void testToString() {
        // given
        final BiFunction<Integer, Integer, Integer> resultFunction = mock(BiFunction.class);
        final IntegerPairAccumulator.Config<Integer> config = new IntegerPairAccumulator.Config<>(
                        CATEGORY, NAME, Integer.class, resultFunction)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT);

        // then
        assertThat(config.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, "java.lang.Integer");
    }
}
