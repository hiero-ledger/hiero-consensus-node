// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static org.hiero.otter.fixtures.OtterAssertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.stream.Stream;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
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
        return Stream.of(new TurtleTestEnvironment(RANDOM_SEED) /*, new ContainerTestEnvironment() */);
    }

    @ParameterizedTest
    @MethodSource("environments")
    void testInitialization(@NonNull final TestEnvironment env) {
        final TimeManager timeManager = env.timeManager();
        final Network network = env.network();

        network.addNodes(3);
        final InstrumentedNode instrumentedNode = network.addInstrumentedNode();

        network.start();
        timeManager.waitFor(Duration.ofSeconds(5));

        assertThat(instrumentedNode.newLogResult()).hasMessageContaining("InstrumentedEventCreator created");
        assertThat(network.newLogResults().suppressingNode(instrumentedNode))
                .haveNoMessagesContaining("InstrumentedEventCreator created");
    }
}
