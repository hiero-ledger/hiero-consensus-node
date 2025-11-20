// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.chaos;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;

/**
 * A test that runs chaos experiments on a network of nodes.
 */
class ChaosTest {

    @OtterTest(requires = Capability.RECONNECT)
    void chaosTest(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        network.addNodes(4);
        network.start();

        env.createChaosBot().runChaos(Duration.ofMinutes(5L));
    }
}
