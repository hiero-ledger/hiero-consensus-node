// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZE_COMPLETE;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Tests basic functionality of a single node network. Single node networks would probably never be used in production,
 * but they can be useful for testing and are officially supported.
 */
@TestMethodOrder(value = MethodOrderer.OrderAnnotation.class)
public class SingleNodeNetworkTest {

    /**
     * A basic test that a single node network can reach consensus and freeze correctly.
     */
    @OtterTest
    @Order(0)
    void testSingleNodeNetwork(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        final Node theOnlyNode = network.addNode();

        // Setup continuous assertions
        assertContinuouslyThat(theOnlyNode.newLogResult()).hasNoErrorLevelMessages();
        assertContinuouslyThat(theOnlyNode.newConsensusResult()).hasConsistentRounds();
        assertContinuouslyThat(theOnlyNode.newReconnectResult()).doesNotAttemptToReconnect();

        network.start();
        // Let the single node run for a short time
        timeManager.waitFor(Duration.ofSeconds(10));
        network.freeze();
        final long freezeRound = theOnlyNode.newConsensusResult().lastRoundNum();
        network.shutdown();

        // Verify that the single node reached freeze complete status while being active for a while
        assertThat(theOnlyNode.newPlatformStatusResult())
                .hasSteps(target(FREEZE_COMPLETE)
                        .requiringInterim(ACTIVE)
                        .optionalInterim(REPLAYING_EVENTS, OBSERVING, CHECKING, FREEZING));
        // Verify that the freeze round is reasonable, given the time we let the node run
        assertThat(freezeRound)
                .withFailMessage("10 seconds should be enough time for a single node to reach at least round 20")
                .isGreaterThan(20);
    }

    private static Path tempDir;

    static {
        try {
            tempDir = Path.of("tmp");
            if (Files.exists(tempDir)) FileUtils.deleteDirectory(tempDir);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    FileUtils.deleteDirectory(tempDir);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }));
        } catch (IOException e) {
            System.out.println(tempDir);
            throw new RuntimeException(e);
        }
    }

    @OtterTest
    @Order(2)
    void testSingleNodeNetwork2(@NonNull final TestEnvironment environment) throws IOException {
        final Network network = environment.network();
        final TimeManager timeManager = environment.timeManager();

        network.addNodes(1);
        network.start();
        timeManager.waitFor(Duration.ofSeconds(10L));
        network.freeze();

        network.shutdown();
        final Configuration configuration =
                network.nodes().getLast().configuration().current();
        final Path outputDirectory =
                configuration.getConfigData(StateCommonConfig.class).savedStateDirectory();
        System.out.println(outputDirectory);
        FileUtils.copyDirectory(outputDirectory, tempDir);
    }

    @OtterTest
    @Order(3)
    void testSingleNodeNetwork3(@NonNull final TestEnvironment env) throws IOException {
        final Network network = env.network();
        network.savedStateDirectory(tempDir);
        final TimeManager timeManager = env.timeManager();
        final Node node0 = network.addNode();
        final Node node1 = network.addNode();

        // Setup continuous assertions
        assertContinuouslyThat(node0.newLogResult()).hasNoErrorLevelMessages();
        assertContinuouslyThat(node1.newLogResult()).hasNoErrorLevelMessages();

        network.start();
        // Let the single node run for a short time
        timeManager.waitFor(Duration.ofSeconds(10));
        // Verify that the node was healthy prior to being killed
        final MultipleNodePlatformStatusResults networkStatusResults = network.newPlatformStatusResults();
        assertThat(networkStatusResults)
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
        networkStatusResults.clear();
    }
}
