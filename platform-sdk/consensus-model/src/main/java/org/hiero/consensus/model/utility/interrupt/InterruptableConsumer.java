// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.utility.interrupt;

import java.util.function.Consumer;

/**
 * A variant of a {@link Consumer} that can be interrupted.
 */
@FunctionalInterface
public interface InterruptableConsumer<T> {

    /**
     * Handle an item from the queue.
     *
     * @param item
     * 		an item from the queue. Will never be null.
     * @throws InterruptedException
     * 		if the thread is interrupted while work is being done
     */
    void accept(T item) throws InterruptedException;
}
