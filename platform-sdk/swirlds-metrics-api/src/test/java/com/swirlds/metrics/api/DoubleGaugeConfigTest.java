// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DoubleGaugeConfigTest extends BaseConfigTest<DoubleGauge.Config> {

    private static final double EPSILON = 1e-6;

    @Override
    protected DoubleGauge.Config create(String category, String name) {
        return new DoubleGauge.Config(category, name);
    }

    @Test
    @DisplayName("Constructor initializes custom fields")
    void testConstructorCustomFields() {
        // when
        final DoubleGauge.Config config = createBase();

        // then
        assertThat(config.getFormat()).isEqualTo(FloatFormats.FORMAT_11_3);
        assertThat(config.getInitialValue()).isEqualTo(0.0, within(EPSILON));
    }

    @Test
    @DisplayName("Custom setters store values")
    void testCustomSetters() {
        // given
        final DoubleGauge.Config config = createBase().withInitialValue(Math.PI);

        // then
        assertThat(config.getInitialValue()).isEqualTo(Math.PI, within(EPSILON));
    }

    @Test
    @DisplayName("toString contains custom values")
    void testToStringCustom() {
        // given
        final DoubleGauge.Config config = createFull().withInitialValue(Math.PI);

        // then
        assertThat(config.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, "3.1415");
    }
}
