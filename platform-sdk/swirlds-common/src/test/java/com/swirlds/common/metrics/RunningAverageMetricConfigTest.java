// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.swirlds.metrics.api.FloatFormats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunningAverageMetricConfigTest {

    private static final String DEFAULT_FORMAT = FloatFormats.FORMAT_11_3;
    private static final float DEFAULT_HALF_LIFE = -1;

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String DESCRIPTION = "DeScRiPtIoN";
    private static final String UNIT = "UnIt";
    private static final String FORMAT = "FoRmAt";

    private static final double EPSILON = 1e-6;

    @Test
    void testConstructor() {
        // when
        final RunningAverageMetric.Config config = new RunningAverageMetric.Config(CATEGORY, NAME);

        // then
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getName()).isEqualTo(NAME);
        assertThat(config.getDescription()).isEqualTo(NAME);
        assertThat(config.getUnit()).isEmpty();
        assertThat(config.getFormat()).isEqualTo(DEFAULT_FORMAT);
        assertThat(config.getHalfLife()).isEqualTo(DEFAULT_HALF_LIFE);
        assertThat(config.isUseDefaultHalfLife()).isTrue();
    }

    @Test
    @DisplayName("Constructor should throw IAE when passing illegal parameters")
    void testConstructorWithIllegalParameter() {
        assertThatThrownBy(() -> new RunningAverageMetric.Config(null, NAME)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RunningAverageMetric.Config("", NAME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunningAverageMetric.Config(" \t\n", NAME))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RunningAverageMetric.Config(CATEGORY, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RunningAverageMetric.Config(CATEGORY, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunningAverageMetric.Config(CATEGORY, " \t\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testSetters() {
        // given
        final RunningAverageMetric.Config config = new RunningAverageMetric.Config(CATEGORY, NAME);

        // when
        final RunningAverageMetric.Config result = config.withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withHalfLife(Math.PI);

        // then
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getName()).isEqualTo(NAME);
        assertThat(config.getDescription()).isEqualTo(NAME);
        assertThat(config.getUnit()).isEmpty();
        assertThat(config.getFormat()).isEqualTo(DEFAULT_FORMAT);
        assertThat(config.getHalfLife()).isEqualTo(DEFAULT_HALF_LIFE);
        assertThat(config.isUseDefaultHalfLife()).isTrue();

        assertThat(result.getCategory()).isEqualTo(CATEGORY);
        assertThat(result.getName()).isEqualTo(NAME);
        assertThat(result.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(result.getUnit()).isEqualTo(UNIT);
        assertThat(result.getFormat()).isEqualTo(FORMAT);
        assertThat(result.getHalfLife()).isEqualTo(Math.PI, within(EPSILON));
        assertThat(result.isUseDefaultHalfLife()).isFalse();
    }

    @Test
    void testSettersWithIllegalParameters() {
        // given
        final RunningAverageMetric.Config config = new RunningAverageMetric.Config(CATEGORY, NAME);
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
    }

    @Test
    void testToString() {
        // given
        final RunningAverageMetric.Config config = new RunningAverageMetric.Config(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withHalfLife(Math.PI);

        // then
        assertThat(config.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, "3.1415");
    }
}
