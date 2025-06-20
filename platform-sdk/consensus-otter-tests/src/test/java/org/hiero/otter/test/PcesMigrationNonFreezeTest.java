// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.otter.fixtures.OtterAssertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.apache.logging.log4j.Level;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.junit.jupiter.api.BeforeAll;

/**
 * Test class for verifying the behavior of
 */
public class PcesMigrationNonFreezeTest {

    private static final Duration DURATION = Duration.ofMinutes(10);

    static Path stateSnapshotTmpDir;

    @BeforeAll
    static void setup() throws IOException {
        stateSnapshotTmpDir = Files.createTempDirectory("state");
    }
    /**
     * Test steps:
     * <pre>
     * 1. Run a network with birth round mode enabled.
     * 2. Freeze and upgrade the network.
     * 3. Run the network with birth round mode enabled again.
     * 4. Verify proper birth rounds in events created before and after the upgrade.
     * </pre>
     *
     * @param env the test environment for this test
     * @throws InterruptedException if an operation times out
     */
    @OtterTest
    void testA(final TestEnvironment env) throws InterruptedException {

        final Network network = env.network();
        network.withDeterministicKeyGeneration(true);
        final TimeManager timeManager = env.timeManager();
        // Setup simulation
        network.addNodes(4);
        for (final Node node : network.getNodes()) {
            node.getConfiguration().set("virtualMap.copyFlushThreshold", "100");
        }
        network.start();
        env.transactionGenerator().start();

        timeManager.waitFor(DURATION);

        // Initiate the migration
        network.shutdown();

        network.copyStateSnapshotTo(stateSnapshotTmpDir);
        System.out.println(stateSnapshotTmpDir);
    }

    @OtterTest
    void testB(final TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        network.withDeterministicKeyGeneration(true);
        // Setup simulation
        network.addNodes(6);
        for (final Node node : network.getNodes()) {
            node.getConfiguration().set("virtualMap.copyFlushThreshold", "100");
        }
        network.useInitialSnapshot(stateSnapshotTmpDir);
        network.start();
        env.timeManager().waitFor(DURATION);

        assertThat(network.getLogResults()).notMatchesLevelAndMessage(Level.ERROR, ".*Node \\d+ is branching");
        // Initiate the migration
        network.shutdown();
    }
}
