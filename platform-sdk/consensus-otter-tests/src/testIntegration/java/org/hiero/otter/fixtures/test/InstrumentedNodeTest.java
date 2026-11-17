// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.test;

import static org.hiero.otter.fixtures.OtterAssertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.Test;

/**
 * Tests that an {@link InstrumentedNode} runs the instrumented modules and that they receive messages from the test.
 */
final class InstrumentedNodeTest {

    private static final String PING_MESSAGE = "Hello Hiero!";

    /**
     * Test the instrumentation in the Turtle environment.
     */
    @Test
    void testInstrumentationTurtle() {
        testInstrumentation(new TurtleTestEnvironment());
    }

    /**
     * Test the instrumentation in the Container environment.
     */
    @Test
    void testInstrumentationContainer() {
        testInstrumentation(new ContainerTestEnvironment());
    }

    private void testInstrumentation(@NonNull final TestEnvironment env) {
        try {
            final TimeManager timeManager = env.timeManager();
            final Network network = env.network();

            network.addNodes(3);
            final InstrumentedNode instrumentedNode = network.addInstrumentedNode();

            network.start();
            timeManager.waitFor(Duration.ofSeconds(5));

            instrumentedNode.ping(PING_MESSAGE);

            timeManager.waitFor(Duration.ofSeconds(5));

            assertThat(instrumentedNode.newLogResult())
                    .hasMessageContaining("InstrumentedEventCreatorModule created")
                    .hasMessageContaining("Ping message received: " + PING_MESSAGE);
            assertThat(network.newLogResults().suppressingNode(instrumentedNode))
                    .haveNoMessageContaining("InstrumentedEventCreatorModule created")
                    .haveNoMessageContaining("Ping message received: " + PING_MESSAGE);
        } finally {
            env.destroy();
        }
    }
}
