// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.config;

import com.swirlds.config.api.ConfigurationBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CryptoConfigTest {

    @Test
    public void testDefaultValuesValid() {
        // given
        final ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(CryptoConfig.class);

        // then
        Assertions.assertDoesNotThrow(() -> builder.build(), "All default values of CryptoConfig should be valid");
    }
}
