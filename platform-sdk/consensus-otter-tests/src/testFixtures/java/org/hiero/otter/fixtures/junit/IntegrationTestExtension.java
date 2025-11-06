// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.StringUtils.capitalize;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestInstancePreDestroyCallback;

public class IntegrationTestExtension implements TestInstancePreDestroyCallback, ParameterResolver, BeforeEachCallback {
    private static final Namespace EXTENSION_NAMESPACE = Namespace.create(IntegrationTestExtension.class);
    private static final String SYSTEM_PROPERTY_OTTER_ENV = "otter.env";

    @Override
    public boolean supportsParameter(
            @NonNull final ParameterContext parameterContext, final ExtensionContext extensionContext)
            throws ParameterResolutionException {
        requireNonNull(parameterContext, "parameterContext must not be null");

        return Optional.of(parameterContext)
                .map(ParameterContext::getParameter)
                .map(Parameter::getType)
                .filter(TestEnvironment.class::equals)
                .isPresent();
    }

    @Override
    public void beforeEach(@NonNull final ExtensionContext context) throws Exception {
        requireNonNull(context, "context must not be null");

        final String className =
                context.getTestClass().map(Class::getSimpleName).orElse("Unknown");
        final String testName =
                context.getDisplayName().replaceAll("[^a-zA-Z0-9_\\-\\[\\]]", "_") + "_" + System.currentTimeMillis();

        final String envProperty = System.getProperty(SYSTEM_PROPERTY_OTTER_ENV, "turtle");
        boolean isIntegrationTest = envProperty.equalsIgnoreCase("integration");

        // Build the outputDirectory path
        final Path outputDir;
        if (isIntegrationTest) {
            // For integration tests, use the new directory structure
            String baseDir = System.getProperty(
                    "integration.output.dir",
                    Path.of("build", "aggregateTestIntegration").toString());
            outputDir = Path.of(baseDir, className, testName);
        } else {
            // For container/turtle tests, use appropriate aggregate test directory.
            outputDir = Path.of("build", "aggregateTest" + capitalize(envProperty), className, testName);
        }

        // Ensure the output directory exists
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output directory: " + outputDir, e);
        }

        context.getStore(EXTENSION_NAMESPACE).put("outputDirectory", outputDir);
    }

    @Override
    public Object resolveParameter(
            @NonNull final ParameterContext parameterContext, @NonNull final ExtensionContext extensionContext)
            throws ParameterResolutionException {
        requireNonNull(parameterContext, "parameterContext must not be null");
        requireNonNull(extensionContext, "extensionContext must not be null");

        return Optional.of(parameterContext)
                .map(ParameterContext::getParameter)
                .map(Parameter::getType)
                .filter(t -> t.equals(TestEnvironment.class))
                .map(t -> {
                    final Path outputDir = (Path)
                            extensionContext.getStore(EXTENSION_NAMESPACE).get("outputDirectory");

                    if (outputDir == null) {
                        throw new ParameterResolutionException("Output directory not initialized");
                    }

                    final TestEnvironment testEnvironment = createTestEnvironment(outputDir);

                    final String className = extensionContext
                            .getTestClass()
                            .map(Class::getSimpleName)
                            .orElse("Unknown");
                    final String testName = extensionContext.getDisplayName().replaceAll("[^a-zA-Z0-9_\\-\\[\\]]", "_");
                    final String testId = Path.of(className, testName).toString();

                    final OtterLifecycle lifecycle = new OtterLifecycle(testId, testEnvironment);
                    extensionContext.getStore(EXTENSION_NAMESPACE).put("otterLifecycle", lifecycle);

                    return testEnvironment;
                })
                .orElseThrow(() -> new ParameterResolutionException("Unsupported parameter type: "
                        + parameterContext.getParameter().getType()));
    }

    @Override
    public void preDestroyTestInstance(@NonNull final ExtensionContext context) throws Exception {
        final OtterLifecycle lifecycle =
                (OtterLifecycle) context.getStore(EXTENSION_NAMESPACE).remove("otterLifecycle");

        if (lifecycle != null) {
            lifecycle.environment().destroy();
        }
    }

    /**
     * Creates a TestEnvironment based on the specified output directory.
     *
     * @param outputDir the output directory for the test environment
     * @return a new TestEnvironment instance
     */
    private TestEnvironment createTestEnvironment(@NonNull final Path outputDir) {
        requireNonNull(outputDir, "outputDir must not be null");

        final String envProperty = System.getProperty(SYSTEM_PROPERTY_OTTER_ENV, "turtle");

        return envProperty.equalsIgnoreCase("container")
                ? new ContainerTestEnvironment(true, outputDir)
                : new TurtleTestEnvironment(0L, true, outputDir);
    }
}
