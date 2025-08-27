// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent;

import java.util.concurrent.Callable;

/**
 * Same as {@link Runnable} but can throw an {@link Exception}. Also extends {@link Callable} for convenience.
 */
@FunctionalInterface
public interface ThrowingRunnable extends Callable<Void> {
    /**
     * Execute this runnable
     *
     * @throws Exception
     * 		if any issue occurs
     */
    void run() throws Exception;

    /**
     * {@inheritDoc}
     */
    @Override
    default Void call() throws Exception {
        run();
        return null;
    }
}
