// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid;

public interface WritableEntityIdStore extends ReadableEntityIdStore, WritableEntityCounters {
    /**
     * Increments the current entity number in state and returns the new value.
     *
     * @return the next new entity number
     */
    long incrementAndGet();

    /**
     * Increments the current highest node id in state and returns the new value.
     * If no node id has been allocated yet, initializes to 0.
     */
    long incrementHighestNodeIdAndGet();
}
