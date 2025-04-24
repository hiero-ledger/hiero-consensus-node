// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.otter.fixtures.turtle.TurtleNodeConfiguration.SOFTWARE_VERSION;

import java.time.Duration;
import org.hiero.consensus.config.EventConfig_;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.junit.jupiter.api.Disabled;

class BirthRoundMigrationTest {

    private static final Duration THIRTY_SECONDS = Duration.ofSeconds(30L);
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1L);

    private static final String OLD_VERSION = "1.0.0";
    private static final String NEW_VERSION = "1.0.1";

    @OtterTest
    @Disabled
    void testBirthRoundMigration(TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);
        for (final Node node : network.getNodes()) {
            node.getConfiguration().set(SOFTWARE_VERSION, OLD_VERSION);
        }
        network.start(ONE_MINUTE);
        env.generator().start();

        // Wait for 30 seconds
        timeManager.waitFor(THIRTY_SECONDS);

        // Initiate the migration
        env.generator().stop();
        network.prepareUpgrade(ONE_MINUTE);

        // update the configuration
        for (final Node node : network.getNodes()) {
            node.getConfiguration()
                    .set(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, true)
                    .set(SOFTWARE_VERSION, NEW_VERSION);
        }

        // restart the network
        network.resume(ONE_MINUTE);
        env.generator().start();

        // Wait for 30 seconds
        timeManager.waitFor(THIRTY_SECONDS);

        // Validations
        env.validator().assertPlatformStatus().assertLogErrors().assertMetrics();
    }
}
