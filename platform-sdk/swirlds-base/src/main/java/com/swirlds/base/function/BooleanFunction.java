// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.function;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A function that accepts and object and returns a primitive boolean. Side effects are allowed.
 *
 * @param <T> the type accepted by the function
 */
@FunctionalInterface
public interface BooleanFunction<T> {

    /**
     * A function that accepts and object and returns a primitive boolean. Side effects are allowed.
     *
     * @param object the object to apply
     * @return true if success, false otherwise
     */
    boolean apply(@Nullable T object);
}
