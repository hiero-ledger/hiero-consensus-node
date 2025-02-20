// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.config;

import com.swirlds.config.api.ConfigurationBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class StateCommonConfigTest {

    @Test
    public void testDefaultValuesValid() {
        // given
        final ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(StateCommonConfig.class);

        // then
        Assertions.assertDoesNotThrow(builder::build, "All default values of StateConfig should be valid");
    }
}
