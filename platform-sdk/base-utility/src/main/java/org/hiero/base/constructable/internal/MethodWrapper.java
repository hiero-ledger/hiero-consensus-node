// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.constructable.internal;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;

/**
 * Used as a wrapper for a {@link Method} to compare if the methods have the same signature
 */
public record MethodWrapper(Method method) {
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MethodWrapper that)) {
            return false;
        }
        return Objects.equals(method.getName(), that.method.getName())
                && Arrays.equals(method.getParameterTypes(), that.method.getParameterTypes());
    }

    @Override
    public int hashCode() {
        return Objects.hash(method.getName(), Arrays.hashCode(method.getParameterTypes()));
    }
}
