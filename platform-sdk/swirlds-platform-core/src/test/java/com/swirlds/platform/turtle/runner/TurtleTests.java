// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.turtle.runner;

import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.test.fixtures.consensus.framework.validation.ConsensusRoundValidator;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TurtleTests {

    @TempDir
    Path outputDirectory;

    /**
     * Simulate a turtle network for 5 minutes.
     * <p>
     * This test needs to remain disabled until the following things are resolved:
     * <ul>
     *     <li>We need validation. No point in running a test if you can't validate if it is a pass/fail.</li>
     *     <li>We need to work out the proper place for these tests in the CI lifecycle. TURTLE tests are long for a
     *         unit test, so we need to be careful where we run them.</li>
     *     <li>We need to ensure that all resources used by TURTLE are properly freed up after the test is run.
     *         Currently turtle leaves a bunch of junk on the file system.</li>
     *     <li>We need safeguards/detection against test deadlock. These tests are sufficiently complex as to make
     *         deadlocks a real possibility, and so it would be good to make the framework handle deadlocks.</li>
     * </ul>
     */
    @Disabled
    @Test
    void turtleTest() {
        final Randotron randotron = Randotron.create();

        final Turtle turtle = TurtleBuilder.create(randotron)
                .withNodeCount(4)
                .withSimulationGranularity(Duration.ofMillis(10))
                .withTimeReportingEnabled(true)
                .withOutputDirectory(outputDirectory)
                .withConsensusRoundValidator(new ConsensusRoundValidator())
                .build();

        turtle.start();
        turtle.simulateTimeAndValidate(Duration.ofMinutes(5L));
    }
}
