// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestInstancePreDestroyCallback;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.jupiter.params.ParameterizedTest;

public class OtterTestExtension
        implements TestInstancePreDestroyCallback, ParameterResolver, TestTemplateInvocationContextProvider {

    /**
     * The namespace of the extension.
     */
    private static final Namespace EXTENSION_NAMESPACE = Namespace.create(OtterTestExtension.class);

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
     * Removes the {@code TestEnvironment} from the {@code extensionContext}
     *
     * @param extensionContext the current extension context; never {@code null}
     */
    @Override
    public void preDestroyTestInstance(@NonNull final ExtensionContext extensionContext) throws Exception {
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

    /**
     * Provides a single {@link TestTemplateInvocationContext} for executing the test method as a basic test.
     * This is used to simulate the behavior of a regular {@code @Test} method when using {@code @OtterTest} alone.
     *
     * @param context the current extension context; never {@code null}
     * @return a stream containing a single {@link TestTemplateInvocationContext}
     */
    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(final ExtensionContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return Stream.of(new TestTemplateInvocationContext() {
            @Override
            public String getDisplayName(int invocationIndex) {
                return "OtterTest";
            }

            @Override
            public List<Extension> getAdditionalExtensions() {
                return List.of();
            }
        });
    }
    /**
     * Determines whether the current test method should be treated as a template invocation.
     * This method returns {@code true} only if the method is not annotated with any standard JUnit test annotations.
     *
     * @param context the current extension context; never {@code null}
     * @return {@code true} if the method has no other test-related annotations and should be treated as an OtterTest
     */
    @Override
    public boolean supportsTestTemplate(@NonNull final ExtensionContext context) {
        Objects.requireNonNull(context, "context must not be null");
        final Method testMethod = context.getRequiredTestMethod();
        // Only act if no other test annotation is present
        return !isTestAnnotated(testMethod);
    }

    /**
     * Creates a new {@link TurtleTestEnvironment} instance which gets stored in the extension context.
     *
     * @param extensionContext the extension context of the test
     *
     * @return a new {@link TurtleTestEnvironment} instance
     */
    private TestEnvironment createTurtleTestEnvironment(@NonNull final ExtensionContext extensionContext) {
        final TurtleTestEnvironment turtleTestEnvironment = new TurtleTestEnvironment();

        extensionContext.getStore(EXTENSION_NAMESPACE).put(ENVIRONMENT_KEY, turtleTestEnvironment);

        return turtleTestEnvironment;
    }

    /**
     * Checks whether the given method is annotated with any standard JUnit 5 test-related annotations.
     *
     * @param method the method to inspect; must not be {@code null}
     * @return {@code true} if the method has any of the JUnit test annotations; {@code false} otherwise
     */
    private boolean isTestAnnotated(@NonNull final Method method) {
        Objects.requireNonNull(method, "method must not be null");
        return method.isAnnotationPresent(Test.class)
                || method.isAnnotationPresent(RepeatedTest.class)
                || method.isAnnotationPresent(ParameterizedTest.class)
                || method.isAnnotationPresent(TestFactory.class)
                || method.isAnnotationPresent(TestTemplate.class);
    }
}
