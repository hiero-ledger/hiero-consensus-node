// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid;

public interface WritableEntityIdStore extends ReadableEntityIdStore, WritableEntityCounters {
    /**
     * Increments the current entity number in state and returns the new value.
     *
     * @return the next new entity number
     */
    long incrementEntityNumAndGet();

    /**
     * Increments the current highest node id in state and returns the new value.
     * If no node id has been allocated yet, initializes to 0.
     */
    long incrementHighestNodeIdAndGet();

    /**
     * Sets the highest node id to the given value if it is greater than the current highest.
     *
     * @param nodeId the node id to set as the highest
     */
    void updateHighestNodeIdIfLarger(long nodeId);
}
