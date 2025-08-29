// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntegerGaugeConfigTest extends BaseConfigTest<IntegerGauge.Config> {

    @Override
    protected IntegerGauge.Config create(String category, String name) {
        return new IntegerGauge.Config(category, name);
    }

    @Test
    @DisplayName("Constructor initializes custom fields")
    void testConstructorCustomFields() {
        // when
        final IntegerGauge.Config config = createBase();

        // then
        assertThat(config.getFormat()).isEqualTo(MetricConfig.NUMBER_FORMAT);
        assertThat(config.getInitialValue()).isZero();
    }

    @Test
    @DisplayName("Custom setters store values")
    void testCustomSetters() {
        // given
        final IntegerGauge.Config config = createBase().withInitialValue(42);

        assertThat(config.getInitialValue()).isEqualTo(42);
    }

    @Test
    @DisplayName("toString contains custom values")
    void testToStringCustom() {
        // given
        final IntegerGauge.Config config = createFull().withInitialValue(42);

        // then
        assertThat(config.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, "42");
    }
}
