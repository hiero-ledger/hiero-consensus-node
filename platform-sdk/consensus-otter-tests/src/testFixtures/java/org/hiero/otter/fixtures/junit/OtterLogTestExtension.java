// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.reflect.Parameter;
import java.util.Objects;
import java.util.Optional;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestWatcher;

public class OtterLogTestExtension implements InvocationInterceptor, ParameterResolver, TestWatcher {

    /**
     * The namespace of the extension.
     */
    private static final Namespace EXTENSION_NAMESPACE = Namespace.create(OtterLogTestExtension.class);

    /**
     * The key to store the environment in the extension context.
     */
    private static final String ENVIRONMENT_KEY = "environment";

    /**
     * Checks if this extension supports parameter resolution for the given parameter context.
     *
     * @param parameterContext the context of the parameter to be resolved
     * @param ignored the extension context of the test (ignored)
     *
     * @return true if parameter resolution is supported, false otherwise
     *
     * @throws ParameterResolutionException if an error occurs during parameter resolution
     */
    @Override
    public boolean supportsParameter(
            @NonNull final ParameterContext parameterContext, @Nullable final ExtensionContext ignored)
            throws ParameterResolutionException {
        Objects.requireNonNull(parameterContext, "parameterContext must not be null");

        return Optional.of(parameterContext)
                .map(ParameterContext::getParameter)
                .map(Parameter::getType)
                .filter(TestEnvironment.class::equals)
                .isPresent();
    }

    /**
     * Resolves the parameter of a test method, providing a {@link TestEnvironment} instance when needed.
     *
     * @param parameterContext the context of the parameter to be resolved
     * @param extensionContext the extension context of the test
     *
     * @return the resolved parameter value
     *
     * @throws ParameterResolutionException if an error occurs during parameter resolution
     */
    @Override
    public Object resolveParameter(
            @NonNull final ParameterContext parameterContext, @NonNull final ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Objects.requireNonNull(parameterContext, "parameterContext must not be null");
        Objects.requireNonNull(extensionContext, "extensionContext must not be null");

        return Optional.of(parameterContext)
                .map(ParameterContext::getParameter)
                .map(Parameter::getType)
                .filter(t -> t.equals(TestEnvironment.class))
                .map(t -> createTurtleTestEnvironment(extensionContext))
                .orElseThrow(() -> new ParameterResolutionException("Could not resolve parameter"));
    }

    /**
     * Creates a new {@link TurtleTestEnvironment} instance which gets stored in the extension context.
     *
     * @param extensionContext the extension context of the test
     *
     * @return a new {@link TurtleTestEnvironment} instance
     */
    private TestEnvironment createTurtleTestEnvironment(final ExtensionContext extensionContext) {
        final TurtleTestEnvironment turtleTestEnvironment = new TurtleTestEnvironment();

        extensionContext.getStore(EXTENSION_NAMESPACE).put(ENVIRONMENT_KEY, turtleTestEnvironment);

        return turtleTestEnvironment;
    }

    /**
     * Invoked after a test has been executed and failed.
     *
     * @param extensionContext the current extension context; never {@code null}
     * @param cause the throwable that caused test failure; may be {@code null}
     */
    @Override
    public void testFailed(@NonNull final ExtensionContext extensionContext, @Nullable final Throwable cause) {
        Objects.requireNonNull(extensionContext, "extensionContext must not be null");
        clear(extensionContext);
    }

    /**
     * Invoked after a test has been executed and succeeded.
     *
     * @param extensionContext the current extension context; never {@code null}
     */
    @Override
    public void testSuccessful(@NonNull final ExtensionContext extensionContext) {
        Objects.requireNonNull(extensionContext, "extensionContext must not be null");
        clear(extensionContext);
    }

    /**
     * Removes the seed from the extension context.
     *
     * @param extensionContext the current extension context; never {@code null}
     */
    private static void clear(@NonNull final ExtensionContext extensionContext) {
        final TestEnvironment testEnvironment =
                (TestEnvironment) extensionContext.getStore(EXTENSION_NAMESPACE).remove(ENVIRONMENT_KEY);
        if (testEnvironment != null) {
            try {
                testEnvironment.destroy();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
