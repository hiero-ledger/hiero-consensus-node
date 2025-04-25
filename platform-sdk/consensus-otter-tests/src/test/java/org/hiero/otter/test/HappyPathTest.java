// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import com.swirlds.logging.legacy.LogMarker;
import java.time.Duration;
import org.apache.logging.log4j.Level;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.Validator.LogFilter;
import org.hiero.otter.fixtures.Validator.Profile;
import org.junit.jupiter.api.Disabled;

public class HappyPathTest {

    @Disabled
    @OtterTest
    void testHappyPath(TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);
        network.start(Duration.ofMinutes(1L));
        env.generator().start();

        // Wait for two minutes
        timeManager.waitFor(Duration.ofMinutes(2L));

        // Validations
        env.validator()
                .assertLogs(
                        LogFilter.maxLogLevel(Level.INFO),
                        LogFilter.ignoreMarkers(LogMarker.STARTUP),
                        LogFilter.ignoreNodes(network.getNodes().getFirst()))
                .validateRemaining(Profile.DEFAULT);
    }
}
