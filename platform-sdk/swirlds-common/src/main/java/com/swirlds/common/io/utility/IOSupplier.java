// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.utility;

import java.io.IOException;

/**
 * Similar to a {@link java.util.function.Supplier} except that an {@link java.io.IOException} can be thrown.
 *
 * @param <T>
 * 		the type of the object that is returned
 */
@FunctionalInterface
public interface IOSupplier<T> {

    /**
     * Get an object.
     *
     * @return an object
     */
    T get() throws IOException;
}
