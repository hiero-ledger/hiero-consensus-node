// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework;

import java.util.concurrent.BlockingQueue;
import org.hiero.base.utility.Clearable;

/**
 * A thread that continuously takes elements from a queue and handles them.
 *
 * @param <T>
 * 		the type of the item in the queue
 */
public interface QueueThread<T> extends StoppableThread, BlockingQueue<T>, Clearable {

    /**
     * Wait until this queue thread has handled all enqueued work and is no longer busy. This method may continue
     * to block indefinitely if new work is continuously added to the queue.
     *
     * @throws InterruptedException if this method is interrupted during execution
     */
    void waitUntilNotBusy() throws InterruptedException;
}
