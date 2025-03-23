// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.utility.interrupt;

/**
 * Similar to {@link java.util.function.Supplier} but can be interrupted.
 *
 * @param <T>
 * 		the type of the value that is supplied.
 */
@FunctionalInterface
public interface InterruptableSupplier<T> {

    /**
     * Gets a result.
     *
     * @return a result
     * @throws InterruptedException
     * 		if the thread is interrupted
     */
    T get() throws InterruptedException;
}
