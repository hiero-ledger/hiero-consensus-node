// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

/**
 * Base class for integration tests providing common setup functionality.
 */
public abstract class BaseIntegrationTest {
    protected Path testOutputDirectory;

    @BeforeEach
    void setUpOutputDirectory(TestInfo testInfo) throws Exception {
        final String className =
                testInfo.getTestClass().map(Class::getSimpleName).orElse("Unknown");
        final String testName =
                testInfo.getDisplayName().replaceAll("[^a-zA-Z0-9-_\\-]", "_") + "_" + System.currentTimeMillis();

        final String baseDir = System.getProperty("integration.output.dir", "build/aggregateTestIntegration");
        testOutputDirectory = Path.of(baseDir, className, testName);

        if (Files.exists(testOutputDirectory)) {
            com.swirlds.common.io.utility.FileUtils.deleteDirectory(testOutputDirectory);
        }
        Files.createDirectories(testOutputDirectory);
    }

    /**
     * Creates a TurtleTestEnvironment configured to use this test's output directory.
     */
    protected TurtleTestEnvironment createTurtleEnvironment() {
        final Path turtleOutputDir = Path.of(testOutputDirectory.toString(), "turtle");
        return new TurtleTestEnvironment(0L, true, turtleOutputDir);
    }

    /**
     * Creates a ContainerTestEnvironment configured to use this test's output directory.
     */
    protected ContainerTestEnvironment createContainerEnvironment() {
        final Path containerOutputDir = Path.of(testOutputDirectory.toString(), "container");
        return new ContainerTestEnvironment(true, containerOutputDir);
    }
}
