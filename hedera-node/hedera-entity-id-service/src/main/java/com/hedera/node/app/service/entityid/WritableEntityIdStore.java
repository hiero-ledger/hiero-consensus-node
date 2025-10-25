// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid;

public interface WritableEntityIdStore extends ReadableEntityIdStore, WritableEntityCounters {
    /**
     * Returns the next entity number that will be used.
     *
     * @return the next entity number that will be used
     */
    long peekAtNextNumber();

    /**
     * Increments the current entity number in state and returns the new value.
     *
     * @return the next new entity number
     */
    long incrementAndGet();

    /**
     * Returns the next node id that will be used, without updating the state.
     * If no node id has been allocated yet, returns 0.
     */
    long peekAtNextNodeId();

    /**
     * Increments the current highest node id in state and returns the new value.
     * If no node id has been allocated yet, initializes to 0.
     */
    long incrementHighestNodeIdAndGet();
}
