// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.config;

import com.swirlds.config.api.ConfigurationBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MerkleDbConfigTest {

    @Test
    public void testDefaultValuesValid() {
        // given
        final ConfigurationBuilder configurationBuilder =
                ConfigurationBuilder.create().withConfigDataTypes(MerkleDbConfig.class);
        // then
        Assertions.assertDoesNotThrow(configurationBuilder::build, "All default values should be valid");
    }
}
