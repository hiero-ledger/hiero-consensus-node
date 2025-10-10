// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static com.swirlds.logging.legacy.LogMarker.METRICS;
import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;
import static com.swirlds.logging.legacy.LogMarker.STATE_HASH;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.tools.GenerateStateTool.SEED;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.apache.logging.log4j.Level;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterSpecs;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.turtle.TurtleSpecs;

public class StartFromStateTest {

    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    @TurtleSpecs(randomSeed = SEED)
    void startFromPreviousVersionState(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);
        network.savedStateDirectory("previous-version-state");
        network.start();

        // Wait for two minutes
        timeManager.waitFor(Duration.ofMinutes(2L));

        // Validations
        assertThat(network.newLogResults()
                        .suppressingLogMarker(STATE_HASH)
                        .suppressingLogMarker(SOCKET_EXCEPTIONS)
                        .suppressingLogMarker(METRICS))
                .haveNoMessagesWithLevelHigherThan(Level.INFO);
    }
}
