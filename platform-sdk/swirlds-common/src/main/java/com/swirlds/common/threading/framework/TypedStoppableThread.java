// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework;

import org.hiero.consensus.model.utility.interrupt.InterruptableRunnable;

/**
 * A {@link StoppableThread} that is aware of the type of object being used to do work.
 *
 * @param <T>
 * 		the type of object used to do work
 */
public interface TypedStoppableThread<T extends InterruptableRunnable> extends StoppableThread {

    /**
     * Get the object used to do work.
     *
     * @return the object used to do work
     */
    T getWork();
}
