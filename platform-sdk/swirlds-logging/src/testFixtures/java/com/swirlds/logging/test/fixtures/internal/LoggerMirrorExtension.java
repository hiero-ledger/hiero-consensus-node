// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.test.fixtures.internal;

import com.swirlds.base.test.fixtures.util.TestInjector;
import com.swirlds.logging.api.internal.DefaultLoggingSystem;
import com.swirlds.logging.test.fixtures.LoggingMirror;
import java.lang.reflect.Method;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

public class LoggerMirrorExtension implements InvocationInterceptor, ParameterResolver {

    @Override
    public void interceptTestMethod(
            Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext)
            throws Throwable {
        try (final LoggingMirrorImpl loggingMirror = new LoggingMirrorImpl()) {
            try {
                DefaultLoggingSystem.getInstance().addHandler(loggingMirror);
                TestInjector.injectInTest(LoggingMirror.class, () -> loggingMirror, extensionContext);
                TestInjector.injectInTest(LoggingMirrorImpl.class, () -> loggingMirror, extensionContext);
                invocation.proceed();
            } finally {
                DefaultLoggingSystem.getInstance().removeHandler(loggingMirror);
            }
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
        return TestInjector.supportsParameter(parameterContext, LoggingMirror.class);
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
        return TestInjector.resolveParameter(LoggingMirror.class, parameterContext, () -> new LoggingMirrorImpl());
    }
}
