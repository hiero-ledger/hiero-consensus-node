// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.hiero.consensus.config.EventConfig_;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.apache.logging.log4j.Level.WARN;
import static org.assertj.core.data.Percentage.withPercentage;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.turtle.TurtleNodeConfiguration.SOFTWARE_VERSION;
import static org.hiero.otter.test.BirthRoundFreezeTestUtils.assertBirthRoundsBeforeAndAfterFreeze;

/**
 * Test class for verifying the behavior of birth round migration when loading a freeze state from disk that did not use
 * birth round ancient mode.
 */
public class BirthRoundFreezeFromStateTest {

    private static final String OTTER_RUN_DIR = "/Users/kellygreco/repos/hiero-consensus-node/platform-sdk/consensus-otter-tests/build/turtle/";

    private static final Duration THIRTY_SECONDS = Duration.ofSeconds(30L);
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1L);

    private static final String OLD_VERSION = "1.0.0";
    private static final String NEW_VERSION = "1.0.1";

    /**
     * Test steps:
     * <pre>
     * 1. Run the network with birth round mode enabled, starting from a state with birth round mode disabled.
     * 4. Verify proper birth rounds in events created before and after the upgrade.
     * </pre>
     *
     * @param env the test environment for this test
     * @throws InterruptedException if an operation times out
     */
    @OtterTest
    void testBirthRoundMigrationFromFreezeState(final TestEnvironment env) throws InterruptedException {

        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);
        for (final Node node : network.getNodes()) {
            node.getConfiguration().set(SOFTWARE_VERSION, NEW_VERSION);
            copyInitialState(node);
        }

        // Start the network. Load the freeze state from disk that did not use birth round ancient mode.
        network.start(ONE_MINUTE);
        env.generator().start();

        // Wait for 30 seconds
        timeManager.waitFor(THIRTY_SECONDS);

        // Validations
        assertThat(network.getLogResults()).noMessageWithLevelHigherThan(WARN);

        assertThat(network.getConsensusResults())
                .hasAdvancedSince(51)
                .hasEqualRoundsIgnoringLast(withPercentage(5));

    }

    private void copyInitialState(final Node node) {
        final long nodeId = node.getSelfId().id();
        try {
            copyFolder(getOtterSourcePath(nodeId), getOtterDestinationPath(nodeId));
            copyFolder(getPcesSourcePath(nodeId), getPcesDestinationPath(nodeId));
            copyFolder(getSavedSourcePath(nodeId), getSavedDestinationPath(nodeId));
        } catch (final IOException e) {
            System.out.println(e);
        }
    }

    private static void copyFolder(final Path src, final Path dest) throws IOException {
        try (final Stream<Path> stream = Files.walk(src)) {
            stream.forEach(source -> copy(source, dest.resolve(src.relativize(source))));
        }
    }

    private static void copy(final Path source, final Path dest) {
        try {
            Files.copy(source, dest, REPLACE_EXISTING);
        } catch (final Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private Path getSavedDestinationPath(final long nodeId) {
        return Path.of(OTTER_RUN_DIR, "/node-" + nodeId + "/saved");
    }

    private Path getSavedSourcePath(final long nodeId) {
        return Path.of("/Users/kellygreco/otterState/node-" + nodeId, "saved");
    }

    private Path getPcesDestinationPath(final long nodeId) {
        return Path.of(OTTER_RUN_DIR, "/node-" + nodeId + "/preconsensus-events");
    }

    private Path getPcesSourcePath(final long nodeId) {
        return Path.of("/Users/kellygreco/otterState/node-" + nodeId, "preconsensus-events");
    }

    private static Path getOtterSourcePath(final long nodeId) {
        return Path.of("/Users/kellygreco/otterState/node-" + nodeId, "otter");
    }

    private static Path getOtterDestinationPath(final long nodeId) {
        return Path.of(OTTER_RUN_DIR, "/node-" + nodeId + "/otter");
    }
}
