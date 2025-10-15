// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import static org.hiero.otter.fixtures.OtterAssertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class QuiescenceTest {

    /**
     * Provides a stream of test environments for the parameterized tests.
     *
     * @return a stream of {@link TestEnvironment} instances
     */
    public static Stream<TestEnvironment> environments() {
        return Stream.of(new TurtleTestEnvironment(), new ContainerTestEnvironment());
    }

    /**
     * This is just a temporary test until quiescence is implemented and we can actually
     * do something.
     *
     * @param env the test environment
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testIsolateAndRejoinSingleNode(@NonNull final TestEnvironment env) {
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
