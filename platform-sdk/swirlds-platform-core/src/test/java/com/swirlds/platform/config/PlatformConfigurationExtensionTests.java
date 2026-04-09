// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.config.extensions.test.fixtures.ConfigUtils;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlatformConfigurationExtensionTests {

    @Test
    void testIfAllConfigDataTypesAreRegistered() {
        // given
        final var allRecordsFound = ConfigUtils.loadAllConfigDataRecords(Set.of("com.swirlds"));
        final var extension = new PlatformConfigurationExtension();

        // when
        final var allConfigDataTypes = extension.getConfigDataTypes();

        // then
        assertThat(allConfigDataTypes).containsExactlyInAnyOrderElementsOf(allRecordsFound);
    }
}
