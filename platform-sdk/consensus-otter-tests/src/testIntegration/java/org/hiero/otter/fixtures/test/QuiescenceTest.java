// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.test;

import static org.hiero.otter.fixtures.OtterAssertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.Test;

class QuiescenceTest {

    /**
     * Test quiescence with Turtle environment.
     */
    @Test
    void testQuiescenceTurtle() {
        testQuiescence(new TurtleTestEnvironment());
    }

    /**
     * Test quiescence with Container environment.
     */
    @Test
    void testQuiescenceContainer() {
        testQuiescence(new ContainerTestEnvironment());
    }

    /**
     * This is just a temporary test until quiescence is implemented and we can actually
     * do something.
     *
     * @param env the test environment
     */
    private void testQuiescence(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            final List<Node> nodes = network.addNodes(4);

            network.start();
            timeManager.waitFor(Duration.ofSeconds(5));

            nodes.getFirst().sendQuiescenceCommand(QuiescenceCommand.QUIESCE);
            timeManager.waitFor(Duration.ofSeconds(5));

            nodes.getFirst().sendQuiescenceCommand(QuiescenceCommand.BREAK_QUIESCENCE);
            timeManager.waitFor(Duration.ofSeconds(5));

            nodes.getFirst().sendQuiescenceCommand(QuiescenceCommand.DONT_QUIESCE);
            timeManager.waitFor(Duration.ofSeconds(5));

            network.sendQuiescenceCommand(QuiescenceCommand.QUIESCE);
            timeManager.waitFor(Duration.ofSeconds(5));

            network.sendQuiescenceCommand(QuiescenceCommand.BREAK_QUIESCENCE);
            timeManager.waitFor(Duration.ofSeconds(5));

            network.sendQuiescenceCommand(QuiescenceCommand.DONT_QUIESCE);
            timeManager.waitFor(Duration.ofSeconds(5));

            assertThat(network.newLogResults()).haveNoErrorLevelMessages();
        } finally {
            env.destroy();
        }
    }
}
