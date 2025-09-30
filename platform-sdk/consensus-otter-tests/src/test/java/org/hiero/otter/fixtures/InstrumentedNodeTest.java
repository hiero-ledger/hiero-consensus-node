// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import static org.hiero.otter.fixtures.OtterAssertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.stream.Stream;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class InstrumentedNodeTest {

    public static final long RANDOM_SEED = 0L;

    /**
     * Provides a stream of test environments for the parameterized tests.
     *
     * @return a stream of {@link TestEnvironment} instances
     */
    public static Stream<TestEnvironment> environments() {
        return Stream.of(new TurtleTestEnvironment(RANDOM_SEED), new ContainerTestEnvironment());
    }

    @ParameterizedTest
    @MethodSource("environments")
    void testInstrumentation(@NonNull final TestEnvironment env) {
        try {
            final TimeManager timeManager = env.timeManager();
            final Network network = env.network();

            network.addNodes(3);
            final InstrumentedNode instrumentedNode = network.addInstrumentedNode();

            network.start();
            timeManager.waitFor(Duration.ofSeconds(5));

            instrumentedNode.ping("Hello Hiero!");

            timeManager.waitFor(Duration.ofSeconds(5));

            assertThat(instrumentedNode.newLogResult())
                    .hasMessageContaining("InstrumentedEventCreator created")
                    .hasMessageContaining("Ping message received: Hello Hiero!");
            assertThat(network.newLogResults().suppressingNode(instrumentedNode))
                    .haveNoMessagesContaining("InstrumentedEventCreator created")
                    .haveNoMessagesContaining("Ping message received: Hello Hiero!");
        } finally {
            env.destroy();
        }
    }
}
