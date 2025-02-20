// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CounterConfigTest {

    private static final String DEFAULT_FORMAT = "%d";

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String DESCRIPTION = "DeScRiPtIoN";
    private static final String UNIT = "UnIt";

    @Test
    @DisplayName("Constructor should store values")
    void testConstructor() {
        // when
        final Counter.Config config = new Counter.Config(CATEGORY, NAME);

        // then
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getName()).isEqualTo(NAME);
        assertThat(config.getDescription()).isEqualTo(NAME);
        assertThat(config.getUnit()).isEmpty();
        assertThat(config.getFormat()).isEqualTo(DEFAULT_FORMAT);
    }

    @Test
    @DisplayName("Constructor should throw IAE when passing illegal parameters")
    void testConstructorWithIllegalParameter() {
        assertThatThrownBy(() -> new Counter.Config(null, NAME)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Counter.Config("", NAME)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Counter.Config(" \t\n", NAME)).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new Counter.Config(CATEGORY, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Counter.Config(CATEGORY, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Counter.Config(CATEGORY, " \t\n")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testSetters() {
        // given
        final Counter.Config config = new Counter.Config(CATEGORY, NAME);

        // when
        final Counter.Config result = config.withDescription(DESCRIPTION).withUnit(UNIT);

        // then
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getName()).isEqualTo(NAME);
        assertThat(config.getDescription()).isEqualTo(NAME);
        assertThat(config.getUnit()).isEmpty();
        assertThat(config.getFormat()).isEqualTo(DEFAULT_FORMAT);

        assertThat(result.getCategory()).isEqualTo(CATEGORY);
        assertThat(result.getName()).isEqualTo(NAME);
        assertThat(result.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(result.getUnit()).isEqualTo(UNIT);
        assertThat(result.getFormat()).isEqualTo(DEFAULT_FORMAT);
    }

    @Test
    void testSettersWithIllegalParameters() {
        // given
        final Counter.Config config = new Counter.Config(CATEGORY, NAME);
        final String longDescription = DESCRIPTION.repeat(50);

        // then
        assertThatThrownBy(() -> config.withDescription(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> config.withDescription("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.withDescription(" \t\n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.withDescription(longDescription)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.withUnit(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testToString() {
        // given
        final Counter.Config config =
                new Counter.Config(CATEGORY, NAME).withDescription(DESCRIPTION).withUnit(UNIT);

        // then
        assertThat(config.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT);
    }
}
