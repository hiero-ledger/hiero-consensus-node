// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.OtterSpecs;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.hiero.otter.fixtures.turtle.TurtleSpecs;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
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
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * A JUnit 5 extension for testing with the Otter framework.
 *
 * <p>This extension supports parameter resolution for {@link TestEnvironment} and manages the lifecycle of the test
 * environment. The type of the {@link TestEnvironment} is selected based on the system property {@code "otter.env"}.
 *
 * <p>The extension checks if the test method is annotated with any standard JUnit test annotations
 * (e.g., {@link RepeatedTest} or {@link ParameterizedTest}). If none of these annotations are present, this extension
 * ensures that the method is executed like a regular test (i.e., as if annotated with {@link Test}).
 */
public class OtterTestExtension
        implements TestInstancePreDestroyCallback,
                ParameterResolver,
                TestTemplateInvocationContextProvider,
                ExecutionCondition {

    private enum Environment {
        TURTLE("turtle"),
        CONTAINER("container");

        private final String propertyValue;

        Environment(@NonNull final String propertyValue) {
            this.propertyValue = propertyValue;
        }
    }

    /**
     * The namespace of the extension.
     */
    private static final Namespace EXTENSION_NAMESPACE = Namespace.create(OtterTestExtension.class);

    /**
     * The key to store the environment in the extension context.
     */
    private static final String ENVIRONMENT_KEY = "environment";

    public static final String SYSTEM_PROPERTY_OTTER_ENV = "otter.env";

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
        requireNonNull(parameterContext, "parameterContext must not be null");

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
        requireNonNull(parameterContext, "parameterContext must not be null");
        requireNonNull(extensionContext, "extensionContext must not be null");

        return Optional.of(parameterContext)
                .map(ParameterContext::getParameter)
                .map(Parameter::getType)
                .filter(t -> t.equals(TestEnvironment.class))
                .map(t -> createTestEnvironment(extensionContext))
                .orElseThrow(() -> new ParameterResolutionException("Could not resolve parameter"));
    }

    /**
     * Removes the {@code TestEnvironment} from the {@code extensionContext}
     *
     * @param extensionContext the current extension context; never {@code null}
     */
    @Override
    public void preDestroyTestInstance(@NonNull final ExtensionContext extensionContext) {
        final TestEnvironment testEnvironment =
                (TestEnvironment) extensionContext.getStore(EXTENSION_NAMESPACE).remove(ENVIRONMENT_KEY);
        if (testEnvironment != null) {
            testEnvironment.destroy();
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
        requireNonNull(context, "context must not be null");
        return Stream.of(new TestTemplateInvocationContext() {
            @Override
            public String getDisplayName(final int invocationIndex) {
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
        requireNonNull(context, "context must not be null");
        final Method testMethod = context.getRequiredTestMethod();
        // Only act if no other test annotation is present
        return !isTestAnnotated(testMethod);
    }

    /**
     * Checks if the test requires additional capabilities to run and whether the current environment supports them.
     *
     * @param extensionContext the current extension context; never {@code null}
     * @return {@code disabled} if the test requires capabilities that are not met by the current environment, {@code enabled otherwise}
     */
    @Override
    @NonNull
    public ConditionEvaluationResult evaluateExecutionCondition(@NonNull final ExtensionContext extensionContext) {
        final Environment environment = readEnvironmentFromSystemProperty();
        if (environment == null) {
            return ConditionEvaluationResult.enabled("No environment set, a matching one will be selected");
        }

        final List<Capability> requiredCapabilities = getRequiredCapabilitiesFromTest(extensionContext);
        final boolean allSupported =
                switch (environment) {
                    case TURTLE -> TurtleTestEnvironment.supports(requiredCapabilities);
                    case CONTAINER -> ContainerTestEnvironment.supports(requiredCapabilities);
                };
        return allSupported
                ? ConditionEvaluationResult.enabled(
                        "Environment %s supports all required capabilities".formatted(environment))
                : ConditionEvaluationResult.disabled(
                        "Environment %s does not support all required capabilities".formatted(environment));
    }

    /**
     * Retrieves the current environment based on the system property {@code "otter.env"}.
     *
     * @return the current {@link Environment}
     */
    @Nullable
    private OtterTestExtension.Environment readEnvironmentFromSystemProperty() {
        final String propertyValue = System.getProperty(SYSTEM_PROPERTY_OTTER_ENV);
        if (propertyValue == null) {
            return null;
        }
        for (final Environment env : Environment.values()) {
            if (env.propertyValue.equalsIgnoreCase(propertyValue)) {
                return env;
            }
        }
        throw new IllegalArgumentException("Unknown otter environment: " + propertyValue);
    }

    /**
     * Retrieves the required capabilities for a test method by evaluating {@link OtterTest#requires()}.
     *
     * @param extensionContext the extension context of the test
     * @return a list of required capabilities
     */
    private List<Capability> getRequiredCapabilitiesFromTest(@NonNull final ExtensionContext extensionContext) {
        final OtterTest otterTest = AnnotationSupport.findAnnotation(extensionContext.getElement(), OtterTest.class)
                .orElseThrow();
        return List.of(otterTest.requires());
    }

    /**
     * Creates a new {@link TestEnvironment} instance based on the current system property {@code "otter.env"}.
     *
     * @param extensionContext the extension context of the test
     *
     * @return a new {@link TestEnvironment} instance
     */
    @NonNull
    private TestEnvironment createTestEnvironment(@NonNull final ExtensionContext extensionContext) {
        Environment environment = readEnvironmentFromSystemProperty();
        if (environment == null) {
            final List<Capability> requiredCapabilities = getRequiredCapabilitiesFromTest(extensionContext);
            environment =
                    TurtleTestEnvironment.supports(requiredCapabilities) ? Environment.TURTLE : Environment.CONTAINER;
        }
        final TestEnvironment testEnvironment = environment == Environment.CONTAINER
                ? createContainerTestEnvironment(extensionContext)
                : createTurtleTestEnvironment(extensionContext);
        extensionContext.getStore(EXTENSION_NAMESPACE).put(ENVIRONMENT_KEY, testEnvironment);
        return testEnvironment;
    }

    /**
     * Creates a new {@link TurtleTestEnvironment} instance.
     *
     * @param extensionContext the extension context of the test
     *
     * @return a new {@link TurtleTestEnvironment} instance
     */
    @NonNull
    private TestEnvironment createTurtleTestEnvironment(@NonNull final ExtensionContext extensionContext) {
        final Optional<OtterSpecs> otterSpecs =
                AnnotationSupport.findAnnotation(extensionContext.getElement(), OtterSpecs.class);
        final boolean randomNodeIds = otterSpecs.map(OtterSpecs::randomNodeIds).orElse(true);

        final Optional<TurtleSpecs> turtleSpecs =
                AnnotationSupport.findAnnotation(extensionContext.getElement(), TurtleSpecs.class);
        final long randomSeed = turtleSpecs.map(TurtleSpecs::randomSeed).orElse(0L);

        return new TurtleTestEnvironment(randomSeed, randomNodeIds);
    }

    /**
     * Creates a new {@link ContainerTestEnvironment} instance.
     *
     * @param extensionContext the extension context of the test
     *
     * @return a new {@link TestEnvironment} instance for container tests
     */
    @NonNull
    private TestEnvironment createContainerTestEnvironment(@NonNull final ExtensionContext extensionContext) {
        final Optional<OtterSpecs> otterSpecs =
                AnnotationSupport.findAnnotation(extensionContext.getElement(), OtterSpecs.class);
        final boolean randomNodeIds = otterSpecs.map(OtterSpecs::randomNodeIds).orElse(true);

        return new ContainerTestEnvironment(randomNodeIds);
    }

    /**
     * Checks whether the given method is annotated with any standard JUnit 5 test-related annotations.
     *
     * @param method the method to inspect; must not be {@code null}
     * @return {@code true} if the method has any of the JUnit test annotations; {@code false} otherwise
     */
    private boolean isTestAnnotated(@NonNull final Method method) {
        requireNonNull(method, "method must not be null");
        return method.isAnnotationPresent(Test.class)
                || method.isAnnotationPresent(RepeatedTest.class)
                || method.isAnnotationPresent(ParameterizedTest.class)
                || method.isAnnotationPresent(TestFactory.class)
                || method.isAnnotationPresent(TestTemplate.class);
    }
}
