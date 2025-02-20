// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.io.internal;

import com.swirlds.base.test.fixtures.io.SystemErrProvider;
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
 * This class is used to inject a {@link SystemErrProvider} instance into a test and run the test in isolation.
 *
 * @see com.swirlds.base.test.fixtures.io.WithSystemError
 */
public class SystemErrorExtension implements InvocationInterceptor {

    @Override
    public void interceptTestMethod(
            @NonNull final Invocation<Void> invocation,
            @NonNull final ReflectiveInvocationContext<Method> invocationContext,
            @NonNull final ExtensionContext extensionContext)
            throws Throwable {
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(extensionContext, "extensionContext must not be null");
        final PrintStream originalSystemErrorPrintStream = System.err;
        try (final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            final SystemIoProvider provider = new SystemIoProvider(byteArrayOutputStream);
            System.setErr(new PrintStream(byteArrayOutputStream));
            TestInjector.injectInTest(SystemErrProvider.class, () -> provider, extensionContext);
            invocation.proceed();
        } finally {
            System.setErr(originalSystemErrorPrintStream);
        }
    }
}
