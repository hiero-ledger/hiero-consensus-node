// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework;

import java.util.List;
import java.util.concurrent.BlockingQueue;

/**
 * Implements a thread pool that continuously takes elements from a queue and handles them.
 *
 * @param <T>
 * 		the type of the item in the queue
 */
public interface QueueThreadPool<T> extends BlockingQueue<T>, Stoppable {

    /**
     * Build a list of seeds that can be used to instantiate this thread pool on a pre-existing collection of threads.
     *
     * @return a list of thread seeds
     */
    List<ThreadSeed> buildSeeds();
}
