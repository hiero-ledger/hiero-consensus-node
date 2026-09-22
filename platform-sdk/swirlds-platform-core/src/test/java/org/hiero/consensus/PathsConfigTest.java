// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import com.swirlds.config.api.ConfigurationBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PathsConfigTest {

    @Test
    public void testDefaultValuesValid() {
        // given
        final ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(PathsConfig.class);

        // then
        Assertions.assertDoesNotThrow(builder::build, "All default values of StateConfig should be valid");
    }
}
