// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.component.internal;

import com.swirlds.component.framework.component.ComponentWiring;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * This dynamic proxy is used by the {@link ComponentWiring} to capture the most
 * recently invoked method.
 */
public class WiringComponentProxy implements InvocationHandler {

    private Method mostRecentlyInvokedMethod = null;

    /**
     * {@inheritDoc}
     */
    @Override
    public Object invoke(@NonNull final Object proxy, @NonNull final Method method, @NonNull final Object[] args)
            throws Throwable {

        if (method.getName().equals("toString")) {
            // Handle this specially, the debugger likes to call toString()
            // on the proxy which disrupts normal behavior when debugging.
            return "WiringComponentProxy";
        }

        mostRecentlyInvokedMethod = Objects.requireNonNull(method);
        return null;
    }

    /**
     * Get the most recently invoked method. Calling this method resets the most recently invoked method to null
     * as a safety measure.
     *
     * @return the most recently invoked method
     */
    @NonNull
    public Method getMostRecentlyInvokedMethod() {
        if (mostRecentlyInvokedMethod == null) {
            throw new IllegalArgumentException("Provided lambda is not a method on the component interface.");
        }
        try {
            return mostRecentlyInvokedMethod;
        } finally {
            mostRecentlyInvokedMethod = null;
        }
    }
}
