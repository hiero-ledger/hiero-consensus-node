// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CounterConfigTest extends BaseConfigTest<Counter.Config> {

    @Override
    protected Counter.Config create(String category, String name) {
        return new Counter.Config(category, name);
    }

    @Test
    @DisplayName("Constructor initializes custom format")
    void testConstructorCustomFormat() {
        // when
        final Counter.Config config = createBase();

        assertThat(config.getFormat()).isEqualTo(MetricConfig.NUMBER_FORMAT);
    }
}
