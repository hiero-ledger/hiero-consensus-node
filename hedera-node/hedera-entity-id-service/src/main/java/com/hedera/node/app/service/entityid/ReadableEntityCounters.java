// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid;

import com.hedera.node.app.hapi.utils.EntityType;

/**
 * Provides a way to get the entity type counter for a given entity type.
 */
public interface ReadableEntityCounters {
    /**
     * Returns the counter for the given entity type.
     * This is used to determine the size of the state.
     *
     * @param entityType the type of entity for which to get the counter
     * @return the counter for the given entity type
     */
    long getCounterFor(EntityType entityType);
}
