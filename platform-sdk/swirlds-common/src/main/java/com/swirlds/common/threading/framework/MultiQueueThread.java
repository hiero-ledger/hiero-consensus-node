// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework;

import org.hiero.base.utility.Clearable;

/**
 * Similar to a {@link QueueThread}, but can hold multiple types of data.
 */
public interface MultiQueueThread extends StoppableThread, Clearable {

    /**
     * Get the inserter for a particular data type.
     *
     * @param clazz
     * 		the class of the data type
     * @return the inserter for the data type
     */
    <T> BlockingQueueInserter<T> getInserter(final Class<T> clazz);

    /**
     * Wait until this queue thread has handled all enqueued work and is no longer busy. This method may continue
     * to block indefinitely if new work is continuously added to the queue.
     *
     * @throws InterruptedException if this method is interrupted during execution
     */
    void waitUntilNotBusy() throws InterruptedException;
}
