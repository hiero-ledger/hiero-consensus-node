// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This payload is logged when the queue of an FCHashMapGarbageCollector grows too large.
 */
public class GarbageCollectionQueuePayload extends AbstractLogPayload {

    private int queueSize;

    public GarbageCollectionQueuePayload(final int queueSize) {
        super("FCHashMap garbage collection queue size exceeds threshold");
        this.queueSize = queueSize;
    }

    /**
     * Get the size of the queue.
     */
    public int getQueueSize() {
        return queueSize;
    }

    /**
     * Set the size of the queue.
     */
    public void setQueueSize(final int queueSize) {
        this.queueSize = queueSize;
    }
}
