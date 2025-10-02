package org.hiero.otter.test;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

public class IssTest {

    @OtterTest
    void testSelfIss(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);

        network.start();

        network.triggerSingleNodeIss(network.nodes().getFirst());
    }
}
