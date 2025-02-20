// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.config;

import com.swirlds.config.api.ConfigurationBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReconnectConfigTest {

    @Test
    public void testDefaultValuesValid() {
        // given
        final ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(ReconnectConfig.class);

        // then
        Assertions.assertDoesNotThrow(() -> builder.build(), "All default values of ReconnectConfig should be valid");
    }
}
