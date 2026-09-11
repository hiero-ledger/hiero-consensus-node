// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.test.fixtures.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.ServerSocket;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * A JUnit 5 extension that provides a free TCP port for test methods. Use the {@link FreePort} annotation
 * on a test method parameter of type int to inject a free port.
 *
 * <p>Example:
 * <pre>
 * {@code
 * @ExtendWith(FreePortExtension.class)
 * class MyTest {
 *     @Test
 *     void testSomething(@FreePort int port) { ... }
 * }
 * }
 * </pre>
 */
public class FreePortExtension implements ParameterResolver {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface FreePort {}

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportsParameter(@NonNull final ParameterContext pc, @NonNull final ExtensionContext ec) {
        return pc.isAnnotated(FreePort.class) && pc.getParameter().getType() == int.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Object resolveParameter(@NonNull final ParameterContext pc, @NonNull final ExtensionContext ec) {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new ParameterResolutionException("No free port available", e);
        }
    }
}
