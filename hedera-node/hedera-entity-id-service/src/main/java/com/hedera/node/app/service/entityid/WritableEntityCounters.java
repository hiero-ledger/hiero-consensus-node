// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid;

import com.hedera.node.app.hapi.utils.EntityType;

/**
 * Provides a way to update entity number counts.
 */
public interface WritableEntityCounters extends ReadableEntityCounters {
    /**
     * Decrements the entity type counter for the given entity type.
     * Since entity counters are used to determine the size of the state, when an entity is removed,
     * the counter must be decremented.
     * This method is called when a remove operation is performed on a store.
     *
     * @param entityType the type of entity for which to decrement the number
     */
    void decrementEntityTypeCounter(EntityType entityType);

    /**
     * Increments the entity type counter for the given entity type.
     * This is called when a new entity is created.
     *
     * @param entityType the type of entity for which to increment the number
     */
    void incrementEntityTypeCount(final EntityType entityType);

    /**
     * Adjusts the entity count for the given entity type by the given delta.
     * @param entityType the type of entity for which to adjust the count
     * @param delta the delta to adjust the count by
     */
    void adjustEntityCount(final EntityType entityType, final long delta);
}
