// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.junit.jupiter.api.Test;

class ProfilerTest {

    /**
     * Test that starting an already started node throws an exception.
     */
    @Test
    void testCoreProfilingFunctionality() {
        final TestEnvironment env = new ContainerTestEnvironment();
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);
            final Node node = nodes.getFirst();
            network.start();
            timeManager.waitFor(Duration.ofSeconds(5));

            // Profile for 20 seconds
            node.startProfiling("profile.jfr");
            timeManager.waitFor(Duration.ofSeconds(60));
            node.stopProfiling();

            // Verify the profiling file was created and is not empty
            final Path profilePath = Path.of("build/container/node-0/profile.jfr");
            assertThat(profilePath).isNotEmptyFile();
        } finally {
            env.destroy();
        }
    }
}
