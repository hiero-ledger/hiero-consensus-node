// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Parameter;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;
import org.hiero.otter.fixtures.TestEnvironment;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Utility class for environment-related operations.
 */
public class EnvironmentUtils {

    /**
     * Gets the default output directory for test environment logs.
     * If the test method has parameters beyond just TestEnvironment (e.g., parameterized with
     * @ValueSource or @MethodSource), appends a timestamp to ensure unique directories for each
     * parameter combination.
     *
     * @param envName the name of the environment (e.g., "Turtle", "Container")
     * @param extensionContext the JUnit 5 extension context containing test information
     * @return the default output directory path
     */
    @NonNull
    public static Path getDefaultOutputDirectory(
            @NonNull final String envName, @NonNull final ExtensionContext extensionContext) {
        final String testClassName = extensionContext.getRequiredTestClass().getSimpleName();
        final String testMethodName = extensionContext.getRequiredTestMethod().getName();

        // Check if there are extra parameters beyond TestEnvironment
        String effectiveMethodName = testMethodName;
        if (hasExtraParameters(extensionContext)) {
            // Append timestamp in millis to create unique directories for each parameterized invocation
            effectiveMethodName = testMethodName + "_" + System.currentTimeMillis();
        }

        return Path.of("build", envName, testClassName, effectiveMethodName);
    }

    /**
     * Checks if the test method has parameters beyond just TestEnvironment.
     * If the test is parameterized with additional values (@ValueSource, @MethodSource, etc.),
     * this returns true.
     *
     * @param extensionContext the JUnit 5 extension context
     * @return true if there are extra parameters, false if only TestEnvironment parameter exists
     */
    private static boolean hasExtraParameters(@NonNull final ExtensionContext extensionContext) {
        final Parameter[] parameters = extensionContext.getRequiredTestMethod().getParameters();
        return Stream.of(parameters).map(Parameter::getType).anyMatch(type -> !type.equals(TestEnvironment.class));
    }

    /**
     * Gets the default output directory for test environment logs using stack trace inspection.
     * This method attempts to extract test information from the call stack when no extension stack is available.
     *
     * @param envName the name of the environment (e.g., "Turtle", "Container")
     * @return the default output directory path
     */
    @NonNull
    public static Path getDefaultOutputDirectory(@NonNull final String envName) {
        String testMethodName = null;
        String testClassName = null;

        // Extract test method name from stack trace
        final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (final StackTraceElement element : stackTrace) {
            if (element.getClassName().contains("org.hiero.otter.fixtures.test")
                    || element.getClassName().contains("org.hiero.otter.test")) {
                testMethodName = element.getMethodName();
                final String className = element.getClassName();
                testClassName = className.substring(className.lastIndexOf('.') + 1);
                break;
            }
        }

        // If a test method name found, use it as sub directory; otherwise use default
        return Path.of(
                "build",
                envName,
                Objects.requireNonNullElse(testClassName, "unknownClass"),
                Objects.requireNonNullElse(testMethodName, "unknownTest"));
    }
}
