// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.utility;

import java.io.IOException;

/**
 * Similar to a {@link java.util.function.Consumer} except that the method may throw an {@link IOException}.
 *
 * @param <T>
 * 		the type that the consumer accepts
 */
@FunctionalInterface
public interface IOConsumer<T> {

    /**
     * Accept a value
     *
     * @param t
     * 		the value
     * @throws IOException
     * 		if an IO exception occurs during the handling of this method
     */
    void accept(T t) throws IOException;
}
