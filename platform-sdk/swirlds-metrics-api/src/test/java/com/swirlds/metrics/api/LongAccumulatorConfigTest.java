// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.function.LongBinaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LongAccumulatorConfigTest {

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
        final LongAccumulator.Config config = new LongAccumulator.Config(CATEGORY, NAME);

        // then
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getName()).isEqualTo(NAME);
        assertThat(config.getDescription()).isEqualTo(NAME);
        assertThat(config.getUnit()).isEmpty();
        assertThat(config.getFormat()).isEqualTo(DEFAULT_FORMAT);
        assertThat(config.getAccumulator().applyAsLong(2L, 3L)).isEqualTo(Long.max(2L, 3L));
        assertThat(config.getInitialValue()).isZero();
    }

    @Test
    @DisplayName("Constructor should throw IAE when passing illegal parameters")
    void testConstructorWithIllegalParameter() {
        assertThatThrownBy(() -> new LongAccumulator.Config(null, NAME)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LongAccumulator.Config("", NAME)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LongAccumulator.Config(" \t\n", NAME))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new LongAccumulator.Config(CATEGORY, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LongAccumulator.Config(CATEGORY, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LongAccumulator.Config(CATEGORY, " \t\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testSetters() {
        // given
        final LongBinaryOperator accumulator = mock(LongBinaryOperator.class);
        final LongAccumulator.Config config = new LongAccumulator.Config(CATEGORY, NAME);

        // when
        final LongAccumulator.Config result = config.withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withAccumulator(accumulator)
                .withInitialValue(42L);

        // then
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getName()).isEqualTo(NAME);
        assertThat(config.getDescription()).isEqualTo(NAME);
        assertThat(config.getUnit()).isEmpty();
        assertThat(config.getFormat()).isEqualTo(DEFAULT_FORMAT);
        assertThat(config.getAccumulator().applyAsLong(2L, 3L)).isEqualTo(Long.max(2L, 3L));
        assertThat(config.getInitialValue()).isZero();

        assertThat(result.getCategory()).isEqualTo(CATEGORY);
        assertThat(result.getName()).isEqualTo(NAME);
        assertThat(result.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(result.getUnit()).isEqualTo(UNIT);
        assertThat(result.getFormat()).isEqualTo(FORMAT);
        assertThat(result.getAccumulator()).isEqualTo(accumulator);
        assertThat(result.getInitialValue()).isEqualTo(42L);
    }

    @Test
    void testSettersWithIllegalParameters() {
        // given
        final LongAccumulator.Config config = new LongAccumulator.Config(CATEGORY, NAME);
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
        final LongAccumulator.Config config = new LongAccumulator.Config(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withInitialValue(42L);

        // then
        assertThat(config.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, "42");
    }
}
