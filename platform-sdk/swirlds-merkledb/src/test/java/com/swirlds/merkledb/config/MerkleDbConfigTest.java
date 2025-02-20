// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.config;

import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.validation.ConfigViolationException;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MerkleDbConfigTest {

    @Test
    public void testDefaultValuesValid() {
        // given
        final ConfigurationBuilder configurationBuilder =
                ConfigurationBuilder.create().withConfigDataTypes(MerkleDbConfig.class);
        // then
        Assertions.assertDoesNotThrow(() -> configurationBuilder.build(), "All default values should be valid");
    }

    @Test
    public void testMinNumberOfFilesInCompactionViolation() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withConfigDataTypes(MerkleDbConfig.class)
                .withSources(new SimpleConfigSource("merkleDb.minNumberOfFilesInCompaction", 1));

        // when
        final ConfigViolationException configViolationException = Assertions.assertThrows(
                ConfigViolationException.class,
                () -> configurationBuilder.build(),
                "A violation should cancel the initialization");

        // then
        Assertions.assertEquals(1, configViolationException.getViolations().size());
    }
}
