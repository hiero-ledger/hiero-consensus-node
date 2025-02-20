// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.io.internal;

import com.swirlds.base.test.fixtures.io.SystemOutProvider;
import com.swirlds.base.test.fixtures.util.TestInjector;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.Objects;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/**
 * This class is used to inject a {@link SystemOutProvider} instance into a test and run the test in isolation.
 */
public class SystemOutExtension implements InvocationInterceptor {

    @Override
    public void interceptTestMethod(
            @NonNull final Invocation<Void> invocation,
            @NonNull final ReflectiveInvocationContext<Method> invocationContext,
            @NonNull final ExtensionContext extensionContext)
            throws Throwable {
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(extensionContext, "extensionContext must not be null");
        final PrintStream originalSystemOutPrintStream = System.out;
        try (final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            final SystemIoProvider provider = new SystemIoProvider(byteArrayOutputStream);
            System.setOut(new PrintStream(byteArrayOutputStream));
            TestInjector.injectInTest(SystemOutProvider.class, () -> provider, extensionContext);
            invocation.proceed();
        } finally {
            System.setOut(originalSystemOutPrintStream);
        }
    }
}
