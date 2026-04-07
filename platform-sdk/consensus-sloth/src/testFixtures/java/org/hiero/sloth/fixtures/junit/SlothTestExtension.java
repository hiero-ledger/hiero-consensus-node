// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.junit;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.hiero.sloth.fixtures.Benchmark;
import org.hiero.sloth.fixtures.Capability;
import org.hiero.sloth.fixtures.TestEnvironment;
import org.hiero.sloth.fixtures.container.ContainerTestEnvironment;
import org.hiero.sloth.fixtures.remote.RemoteTestEnvironment;
import org.hiero.sloth.fixtures.specs.ContainerSpecs;
import org.hiero.sloth.fixtures.specs.RemoteSpecs;
import org.hiero.sloth.fixtures.specs.SlothSpecs;
import org.hiero.sloth.fixtures.util.EnvironmentUtils;
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
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * A JUnit 5 extension for testing with the sloth framework.
 *
 * <p>This extension supports parameter resolution for {@link TestEnvironment} and manages the lifecycle of the test
 * environment. The type of the {@link TestEnvironment} is selected based on the system property {@code "sloth.env"}.
 *
 * <p>The extension checks if the test method is annotated with any standard JUnit test annotations
 * (e.g., {@link RepeatedTest} or {@link ParameterizedTest}). If none of these annotations are present, this extension
 * ensures that the method is executed like a regular test (i.e., as if annotated with {@link Test}).
 */
public class SlothTestExtension
        implements TestInstancePreDestroyCallback,
                ParameterResolver,
                TestTemplateInvocationContextProvider,
                ExecutionCondition,
                TestWatcher {

    private enum Environment {
        CONTAINER("container"),
        REMOTE("remote");

        private final String propertyValue;

        Environment(@NonNull final String propertyValue) {
            this.propertyValue = propertyValue;
        }
    }

    /**
     * The namespace of the extension.
     */
    private static final Namespace EXTENSION_NAMESPACE = Namespace.create(SlothTestExtension.class);

    /**
     * The key to store the environment in the extension context.
     */
    private static final String ENVIRONMENT_KEY = "environment";

    public static final String SYSTEM_PROPERTY_OTTER_ENV = "sloth.env";

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
                return "Benchmark";
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
        final boolean allSupported = environment == Environment.REMOTE
                ? RemoteTestEnvironment.supports(requiredCapabilities)
                : ContainerTestEnvironment.supports(requiredCapabilities);
        return allSupported
                ? ConditionEvaluationResult.enabled(
                        "Environment %s supports all required capabilities".formatted(environment))
                : ConditionEvaluationResult.disabled(
                        "Environment %s does not support all required capabilities".formatted(environment));
    }

    /**
     * Retrieves the current environment based on the system property {@code "sloth.env"}.
     *
     * @return the current {@link Environment}
     */
    @Nullable
    private SlothTestExtension.Environment readEnvironmentFromSystemProperty() {
        final String propertyValue = System.getProperty(SYSTEM_PROPERTY_OTTER_ENV);
        if (propertyValue == null) {
            return null;
        }
        for (final Environment env : Environment.values()) {
            if (env.propertyValue.equalsIgnoreCase(propertyValue)) {
                return env;
            }
        }
        throw new IllegalArgumentException("Unknown sloth environment: " + propertyValue);
    }

    /**
     * Retrieves the required capabilities for a test method by evaluating {@link Benchmark#requires()}.
     *
     * @param extensionContext the extension context of the test
     * @return a list of required capabilities
     */
    private List<Capability> getRequiredCapabilitiesFromTest(@NonNull final ExtensionContext extensionContext) {
        final Benchmark benchmark =
                findAnnotation(extensionContext, Benchmark.class).orElseThrow();
        return List.of(benchmark.requires());
    }

    /**
     * Creates a new {@link TestEnvironment} instance based on the current system property {@code "sloth.env"}.
     *
     * @param extensionContext the extension context of the test
     *
     * @return a new {@link TestEnvironment} instance
     */
    @NonNull
    private TestEnvironment createTestEnvironment(@NonNull final ExtensionContext extensionContext) {
        final Environment environment = readEnvironmentFromSystemProperty();
        final TestEnvironment testEnvironment = environment == Environment.REMOTE
                ? createRemoteTestEnvironment(extensionContext)
                : createContainerTestEnvironment(extensionContext);
        extensionContext.getStore(EXTENSION_NAMESPACE).put(ENVIRONMENT_KEY, testEnvironment);
        return testEnvironment;
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

        final Optional<SlothSpecs> otterSpecs = findAnnotation(extensionContext, SlothSpecs.class);
        final boolean randomNodeIds = otterSpecs.map(SlothSpecs::randomNodeIds).orElse(true);

        final Optional<ContainerSpecs> containerSpecs = findAnnotation(extensionContext, ContainerSpecs.class);
        final boolean gcLoggingEnabled =
                containerSpecs.map(ContainerSpecs::gcLogging).orElse(false);
        final List<String> jvmArgs =
                containerSpecs.map(specs -> List.of(specs.jvmArgs())).orElse(List.of());

        final Path outputDirectory = EnvironmentUtils.getDefaultOutputDirectory("container", extensionContext);
        return new ContainerTestEnvironment(randomNodeIds, outputDirectory, gcLoggingEnabled, jvmArgs);
    }

    /**
     * Creates a new {@link RemoteTestEnvironment} instance.
     *
     * @param extensionContext the extension context of the test
     * @return a new {@link TestEnvironment} instance for remote tests
     */
    @NonNull
    private TestEnvironment createRemoteTestEnvironment(@NonNull final ExtensionContext extensionContext) {

        final Optional<SlothSpecs> slothSpecs = findAnnotation(extensionContext, SlothSpecs.class);
        final boolean randomNodeIds = slothSpecs.map(SlothSpecs::randomNodeIds).orElse(true);

        final RemoteSpecs remoteSpecs = findAnnotation(extensionContext, RemoteSpecs.class)
                .orElseThrow(() ->
                        new IllegalStateException("Remote environment requires @RemoteSpecs annotation with hosts"));

        final List<String> hosts = List.of(remoteSpecs.hosts().split(","));
        final String remoteWorkDir = remoteSpecs.remoteWorkDir();
        final boolean cleanupOnDestroy = remoteSpecs.cleanupOnDestroy();
        final String remoteJavaPath = remoteSpecs.remoteJavaPath();

        final Path outputDirectory = EnvironmentUtils.getDefaultOutputDirectory("remote", extensionContext);
        return new RemoteTestEnvironment(
                randomNodeIds, outputDirectory, hosts, remoteWorkDir, cleanupOnDestroy, remoteJavaPath);
    }

    /**
     * Finds an annotation on the test method first, falling back to the test class if not found on the method.
     *
     * @param extensionContext the extension context of the test
     * @param annotationType the annotation type to search for
     * @param <A> the annotation type
     * @return an optional containing the annotation if found
     */
    @NonNull
    private <A extends Annotation> Optional<A> findAnnotation(
            @NonNull final ExtensionContext extensionContext, @NonNull final Class<A> annotationType) {
        return AnnotationSupport.findAnnotation(extensionContext.getElement(), annotationType)
                .or(() -> AnnotationSupport.findAnnotation(extensionContext.getTestClass(), annotationType));
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
