// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.merkledb.config.MerkleDbConfig_;
import java.nio.file.Path;
import org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle;
import org.hiero.otter.fixtures.internal.OverrideProperties;
import org.junit.jupiter.api.Test;

/**
 * Tests that values set by the user override the defaults of a Turtle node.
 */
class TurtleNodeConfigurationTest {

    private static final Path OUTPUT_DIRECTORY = Path.of("build", "turtle-node-configuration-test");

    @Test
    void testDefaultIsUsedIfNoValueIsSet() {
        final TurtleNodeConfiguration configuration =
                new TurtleNodeConfiguration(() -> LifeCycle.INIT, new OverrideProperties(), OUTPUT_DIRECTORY);
        assertThat(configuration.current().getValue(MerkleDbConfig_.MAX_NUM_OF_KEYS, Long.class))
                .isEqualTo(100_000L);
    }

    @Test
    void testNetworkValueSetBeforeNodeCreationOverridesDefault() {
        final OverrideProperties networkProperties =
                new OverrideProperties().withConfigValue(MerkleDbConfig_.MAX_NUM_OF_KEYS, 200_000L);
        final TurtleNodeConfiguration configuration =
                new TurtleNodeConfiguration(() -> LifeCycle.INIT, networkProperties, OUTPUT_DIRECTORY);
        assertThat(configuration.current().getValue(MerkleDbConfig_.MAX_NUM_OF_KEYS, Long.class))
                .isEqualTo(200_000L);
    }
}
