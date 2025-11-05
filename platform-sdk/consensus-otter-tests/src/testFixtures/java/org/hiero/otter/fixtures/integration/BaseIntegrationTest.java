// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

/**
 * Base class for integration tests providing common setup functionality.
 */
public abstract class BaseIntegrationTest {
    private static final Logger log = LogManager.getLogger(BaseIntegrationTest.class);

    protected Path testOutputDirectory;

    @BeforeEach
    void setUpOutputDirectory(TestInfo testInfo) throws Exception {
        String className = testInfo.getTestClass().map(Class::getSimpleName).orElse("Unknown");
        String testName =
                testInfo.getDisplayName().replaceAll("[^a-zA-Z0-9-_\\-]", "_") + "_" + System.currentTimeMillis();

        String baseDir = System.getProperty("integration.output.dir", "build/aggregateTestIntegration");
        testOutputDirectory = Path.of(baseDir, className, testName);

        if (Files.exists(testOutputDirectory)) {
            com.swirlds.common.io.utility.FileUtils.deleteDirectory(testOutputDirectory);
        }
        Files.createDirectories(testOutputDirectory);

        log.info("Created test output directory: " + testOutputDirectory);
    }

    /**
     * Creates a TurtleTestEnvironment configured to use this test's output directory.
     */
    protected TurtleTestEnvironment createTurtleEnvironment() {
        Path turtleOutputDir = Path.of(testOutputDirectory.toString(), "turtle");
        return new TurtleTestEnvironment(0L, true, turtleOutputDir);
    }

    /**
     * Creates a ContainerTestEnvironment configured to use this test's output directory.
     */
    protected ContainerTestEnvironment createContainerEnvironment() {
        Path containerOutputDir = Path.of(testOutputDirectory.toString(), "container");
        return new ContainerTestEnvironment(true, containerOutputDir);
    }
}
