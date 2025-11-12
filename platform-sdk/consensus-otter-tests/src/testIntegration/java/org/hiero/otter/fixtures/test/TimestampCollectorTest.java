// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.test;

import java.time.Duration;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.container.ContainerNetwork;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.junit.jupiter.api.Test;

/**
 * A simple test to collect timestamps during normal operation.
 */
public class TimestampCollectorTest {

    /**
     * Simple test that runs a network with a light-weight OtterApp and no transactions.
     */
    @Test
    void collectTimestamps() {
        final TestEnvironment env = new ContainerTestEnvironment(false);
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup simulation
            network.addNodes(4);
            network.withConfigValue("otter.app.services", ""); // disable optional services
            network.start();
            env.transactionGenerator().stop();

            // Wait for some time
            timeManager.waitForRealTime(Duration.ofMinutes(5L));

            ((ContainerNetwork) network).dumpTimestampsFile();

            // wait a bit before shutting down to allow writing timestamps file
            timeManager.waitForRealTime(Duration.ofSeconds(10L));
        } finally {
            env.destroy();
        }
    }
}
