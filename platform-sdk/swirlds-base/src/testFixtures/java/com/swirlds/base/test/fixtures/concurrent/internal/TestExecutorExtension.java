// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.concurrent.internal;

import com.swirlds.base.test.fixtures.concurrent.TestExecutor;
import com.swirlds.base.test.fixtures.util.TestInjector;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Method;
import java.util.Objects;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/**
 * The TestExecutorExtension is a JUnit 5 extension designed to facilitate the integration of a {@link TestExecutor}
 * into your test classes. It serves as both an {@link InvocationInterceptor} and a {@link ParameterResolver}, enabling
 * seamless injection of a {@code TestExecutor} instance and supporting its use within your test methods.
 * <p>
 * When applied, this extension injects a {@code TestExecutor} instance into the test class, allowing you to execute
 * tests in a controlled manner, such as concurrently or with customized behaviors.
 *
 * @see TestExecutor
 * @see InvocationInterceptor
 * @see ParameterResolver
 */
public class TestExecutorExtension implements InvocationInterceptor, ParameterResolver {

    /**
     * Intercept the execution of a test method and inject a TestExecutor instance into the current test context.
     *
     * @param invocation        The invocation of the test method.
     * @param invocationContext The context of the method invocation.
     * @param extensionContext  The context of the test extension.
     * @throws Throwable if an error occurs during method interception.
     */
    @Override
    public void interceptTestMethod(
            @NonNull final Invocation<Void> invocation,
            @NonNull final ReflectiveInvocationContext<Method> invocationContext,
            @NonNull final ExtensionContext extensionContext)
            throws Throwable {
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(extensionContext, "extensionContext must not be null");

        try (ConcurrentTestSupport concurrentTestSupport = new ConcurrentTestSupport()) {
            TestInjector.injectInTest(TestExecutor.class, () -> concurrentTestSupport, extensionContext);
            invocation.proceed();
        }
    }

    /**
     * Check if this extension supports parameter resolution for the given parameter context.
     *
     * @param parameterContext The context of the parameter to be resolved.
     * @param extensionContext The context of the test extension.
     * @return true if parameter resolution is supported, false otherwise.
     * @throws ParameterResolutionException if an error occurs during parameter resolution.
     */
    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return TestInjector.supportsParameter(parameterContext, TestExecutor.class);
    }

    /**
     * Resolve the parameter of a test method, providing a TestExecutor instance when needed.
     *
     * @param parameterContext The context of the parameter to be resolved.
     * @param extensionContext The context of the test extension.
     * @return The resolved parameter value.
     * @throws ParameterResolutionException if an error occurs during parameter resolution.
     */
    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return TestInjector.resolveParameter(TestExecutor.class, parameterContext, ConcurrentTestSupport::new);
    }
}
